package fr.coffres.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import fr.coffres.model.Project;
import fr.coffres.project.SchematicReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Building projects, kept in memory and saved in {@code projects.json}.
 * Thread-safe: every method locks on this object. When a caller also needs the {@link DataStore},
 * it must lock the DataStore first, then this store (never the other way round).
 * Does not touch the Bukkit API.
 */
public final class ProjectStore {

    private final Path file;
    private final Logger logger;
    private final Runnable onChange;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Object fileLock = new Object();
    private final LinkedHashMap<String, Project> projects = new LinkedHashMap<>();
    private boolean dirty;

    /**
     * @param onChange called after every change (the site uses it to refresh), without any lock held by this store's callers
     */
    public ProjectStore(Path file, Logger logger, Runnable onChange) {
        this.file = file;
        this.logger = logger;
        this.onChange = onChange == null ? () -> { } : onChange;
    }

    // ------------------------------------------------------------------ loading / saving

    public synchronized void load() {
        projects.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject doc = gson.fromJson(r, JsonObject.class);
            if (doc == null || !doc.has("projects")) {
                return;
            }
            List<Project> list = gson.fromJson(doc.get("projects"), new TypeToken<List<Project>>() {}.getType());
            if (list != null) {
                for (Project p : list) {
                    if (p == null || p.id == null) {
                        continue;
                    }
                    if (p.materials == null) {
                        p.materials = new LinkedHashMap<>();
                    }
                    if (p.chests == null) {
                        p.chests = new ArrayList<>();
                    }
                    projects.put(p.id, p);
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "projects.json illisible, une copie est gardée en projects.json.broken", e);
            try {
                Files.copy(file, file.resolveSibling("projects.json.broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /** Writes the projects to disk if something changed. Safe to call from any thread. */
    public void save() {
        synchronized (fileLock) {
            String json;
            synchronized (this) {
                if (!dirty) {
                    return;
                }
                JsonObject doc = new JsonObject();
                doc.addProperty("version", 1);
                doc.add("projects", gson.toJsonTree(new ArrayList<>(projects.values())));
                json = gson.toJson(doc);
                dirty = false;
            }
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Sauvegarde des projets impossible", e);
                synchronized (this) {
                    dirty = true;
                }
            }
        }
    }

    // ------------------------------------------------------------------ reading

    public synchronized Project get(String id) {
        return id == null ? null : projects.get(id);
    }

    public synchronized List<Project> all() {
        return new ArrayList<>(projects.values());
    }

    /** Call only inside {@code synchronized (projectStore)}. */
    public Collection<Project> projectsLocked() {
        return projects.values();
    }

    public synchronized int countOwnedBy(String uuid) {
        int n = 0;
        for (Project p : projects.values()) {
            if (Objects.equals(p.owner, uuid)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ changes

    /** Creates a project from a plan that was just read. */
    public Project create(String name, String ownerUuid, String ownerName, boolean shared, String fileName, SchematicReader.Schematic s) {
        Project p = new Project();
        synchronized (this) {
            do {
                p.id = DataStore.newId();
            } while (projects.containsKey(p.id));
            p.name = DataStore.clean(name, 40);
            p.owner = ownerUuid;
            p.ownerName = ownerName;
            p.shared = shared;
            p.created = System.currentTimeMillis();
            fill(p, fileName, s);
            projects.put(p.id, p);
            dirty = true;
        }
        onChange.run();
        return p;
    }

    /** Test / demo helper: inserts a ready-made project. */
    public void put(Project p) {
        synchronized (this) {
            projects.put(p.id, p);
            dirty = true;
        }
        onChange.run();
    }

    /** Replaces the plan of a project (the linked containers stay). */
    public boolean replaceFile(String id, String fileName, SchematicReader.Schematic s) {
        synchronized (this) {
            Project p = projects.get(id);
            if (p == null) {
                return false;
            }
            fill(p, fileName, s);
            dirty = true;
        }
        onChange.run();
        return true;
    }

    private static void fill(Project p, String fileName, SchematicReader.Schematic s) {
        p.file = DataStore.clean(fileName == null ? "plan" : fileName, 80);
        p.format = s.format();
        p.schemName = s.name() == null ? null : DataStore.clean(s.name(), 60);
        p.author = s.author() == null ? null : DataStore.clean(s.author(), 40);
        p.sx = s.sizeX();
        p.sy = s.sizeY();
        p.sz = s.sizeZ();
        p.blocks = s.blocks();
        p.materials = new LinkedHashMap<>(s.materials());
        p.updated = System.currentTimeMillis();
    }

    /** Edits name / sharing. Null arguments are left unchanged. */
    public boolean edit(String id, String name, Boolean shared) {
        synchronized (this) {
            Project p = projects.get(id);
            if (p == null) {
                return false;
            }
            if (name != null && !name.isBlank()) {
                p.name = DataStore.clean(name, 40);
            }
            if (shared != null) {
                p.shared = shared;
            }
            p.updated = System.currentTimeMillis();
            dirty = true;
        }
        onChange.run();
        return true;
    }

    /** Replaces the linked containers. */
    public boolean setChests(String id, Collection<String> chestIds) {
        synchronized (this) {
            Project p = projects.get(id);
            if (p == null) {
                return false;
            }
            p.chests = new ArrayList<>(new LinkedHashSet<>(chestIds));
            p.updated = System.currentTimeMillis();
            dirty = true;
        }
        onChange.run();
        return true;
    }

    /** Links the container if it is not linked yet, unlinks it otherwise. Returns true when it is now linked, null if no such project. */
    public Boolean toggleChest(String id, String chestId) {
        boolean linked;
        synchronized (this) {
            Project p = projects.get(id);
            if (p == null) {
                return null;
            }
            List<String> list = new ArrayList<>(p.chests);
            linked = !list.remove(chestId);
            if (linked) {
                list.add(chestId);
            }
            p.chests = list;
            p.updated = System.currentTimeMillis();
            dirty = true;
        }
        onChange.run();
        return linked;
    }

    public Project delete(String id) {
        Project p;
        synchronized (this) {
            p = projects.remove(id);
            if (p == null) {
                return null;
            }
            dirty = true;
        }
        onChange.run();
        return p;
    }

    /** Forgets links to containers that are no longer followed. */
    public void pruneChests(Set<String> existing) {
        boolean changed = false;
        synchronized (this) {
            for (Project p : projects.values()) {
                List<String> kept = new ArrayList<>();
                for (String c : p.chests) {
                    if (existing.contains(c)) {
                        kept.add(c);
                    }
                }
                if (kept.size() != p.chests.size()) {
                    p.chests = kept;
                    changed = true;
                }
            }
            if (changed) {
                dirty = true;
            }
        }
        if (changed) {
            onChange.run();
        }
    }

    /** Projects that use this item, with how many they need (for the item page). Call only inside {@code synchronized (projectStore)}. */
    public Map<Project, Long> usingLocked(String itemId) {
        Map<Project, Long> out = new LinkedHashMap<>();
        for (Project p : projects.values()) {
            Long n = p.materials.get(itemId);
            if (n != null && n > 0) {
                out.put(p, n);
            }
        }
        return out;
    }
}
