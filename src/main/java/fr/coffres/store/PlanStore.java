package fr.coffres.store;

import fr.coffres.model.Placement;
import fr.coffres.model.Project;
import fr.coffres.project.Build;
import fr.coffres.project.Plan;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The full plans of the projects (where each block goes), in {@code plans/<id>.plan}, and for the projects placed in
 * the world, which blocks are already built ({@code plans/<id>.placed}). Only placed projects are kept in memory.
 * Thread-safe. Does not touch the Bukkit API.
 */
public final class PlanStore {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final int BITS_MAGIC = 0x43504C42; // "CPLB"

    private final Path dir;
    private final Logger logger;
    private final Map<String, Build> builds = new ConcurrentHashMap<>();
    private final Map<String, Carried> carried = new ConcurrentHashMap<>();

    /** Items of a project in the inventories of its members online, and who carries them. */
    public record Carried(Map<String, Long> items, List<Build.Carrier> who) {
        public static final Carried NONE = new Carried(Map.of(), List.of());
    }

    public PlanStore(Path dir, Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    private Path planFile(String id) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id");
        }
        return dir.resolve(id + ".plan");
    }

    private Path bitsFile(String id) {
        return dir.resolve(id + ".placed");
    }

    // ------------------------------------------------------------------ inventories (refreshed every second by the game)

    /** What the members of the project online carry (placed in the world or not). */
    public Carried carried(String projectId) {
        return carried.getOrDefault(projectId, Carried.NONE);
    }

    /** Returns true when it changed. */
    public boolean setCarried(String projectId, Map<String, Long> items, List<Build.Carrier> who) {
        Carried now = items.isEmpty() ? Carried.NONE : new Carried(Map.copyOf(items), List.copyOf(who));
        Carried old = now == Carried.NONE ? carried.remove(projectId) : carried.put(projectId, now);
        return !(old == null ? Carried.NONE : old).equals(now);
    }

    /** Forgets the projects that no longer exist. Returns true when something was removed. */
    public boolean keepCarried(java.util.Set<String> projectIds) {
        return carried.keySet().retainAll(projectIds);
    }

    // ------------------------------------------------------------------ plans

    /** Saves the plan of a project (or forgets it when {@code plan} is null). What was built is forgotten. */
    public void savePlan(String id, Plan plan) throws IOException {
        Files.createDirectories(dir);
        Path file = planFile(id);
        Files.deleteIfExists(bitsFile(id));
        if (plan == null) {
            Files.deleteIfExists(file);
            return;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            plan.write(out);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public boolean hasPlan(String id) {
        return Files.isRegularFile(planFile(id));
    }

    public Plan loadPlan(String id) throws IOException {
        try (InputStream in = Files.newInputStream(planFile(id))) {
            return Plan.read(in);
        }
    }

    /** Forgets everything about a deleted project. */
    public void delete(String id) {
        builds.remove(id);
        try {
            Files.deleteIfExists(planFile(id));
            Files.deleteIfExists(bitsFile(id));
        } catch (IOException e) {
            logger.log(Level.WARNING, "Impossible de supprimer le plan du projet " + id, e);
        }
    }

    // ------------------------------------------------------------------ builds (projects placed in the world)

    public Build build(String id) {
        return id == null ? null : builds.get(id);
    }

    public List<Build> builds() {
        return new ArrayList<>(builds.values());
    }

    /**
     * Loads the plan of a placed project and what was already built (when it was saved for this same placement).
     * Returns null when the project is not placed or has no plan.
     */
    public Build activate(Project p) {
        Placement at = p.placement;
        if (at == null || at.world == null || !hasPlan(p.id)) {
            builds.remove(p.id);
            return null;
        }
        try {
            Plan plan = loadPlan(p.id);
            BitSet saved = loadBits(p.id, signature(at), plan.size());
            Build b = new Build(p.id, plan, at, saved);
            builds.put(p.id, b);
            return b;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Plan du projet « " + p.name + " » illisible : la vérification des blocs posés est désactivée pour lui.", e);
            builds.remove(p.id);
            return null;
        }
    }

    /** The project is no longer placed in the world. */
    public void deactivate(String id) {
        builds.remove(id);
        try {
            Files.deleteIfExists(bitsFile(id));
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Writes what was built for the placed projects that changed. Safe to call from any thread. */
    public synchronized void save() {
        for (Build b : builds()) {
            BitSet bits = b.takeDirty();
            if (bits == null) {
                continue;
            }
            try {
                Files.createDirectories(dir);
                Path file = bitsFile(b.projectId);
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
                    out.writeInt(BITS_MAGIC);
                    out.writeUTF(signature(b.at));
                    out.writeInt(b.size());
                    long[] words = bits.toLongArray();
                    out.writeInt(words.length);
                    for (long w : words) {
                        out.writeLong(w);
                    }
                }
                if (builds.get(b.projectId) != b) {
                    Files.deleteIfExists(tmp); // replaced or removed meanwhile
                    continue;
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                b.markDirty();
                logger.log(Level.WARNING, "Sauvegarde des blocs posés impossible pour le projet " + b.projectId, e);
            }
        }
    }

    private BitSet loadBits(String id, String signature, int size) {
        Path file = bitsFile(id);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != BITS_MAGIC || !in.readUTF().equals(signature) || in.readInt() != size) {
                return null;
            }
            int n = in.readInt();
            if (n < 0 || n > (size + 63) / 64) {
                return null;
            }
            long[] words = new long[n];
            for (int i = 0; i < n; i++) {
                words[i] = in.readLong();
            }
            return BitSet.valueOf(words);
        } catch (IOException e) {
            return null;
        }
    }

    private static String signature(Placement at) {
        return at.world + "|" + at.x + "|" + at.y + "|" + at.z + "|" + at.rot + "|" + at.mirror;
    }
}
