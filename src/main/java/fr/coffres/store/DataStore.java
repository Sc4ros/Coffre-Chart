package fr.coffres.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import fr.coffres.model.LogEntry;
import fr.coffres.model.SlotItem;
import fr.coffres.model.StoredChest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * All Coffres data, kept in memory and saved as JSON files.
 * Thread-safe: every method locks on this object. Readers that need several values at once
 * (the web API) wrap their work in {@code synchronized (store)} and use the "locked" accessors.
 * This class does not touch the Bukkit API so the site can run without a Minecraft server.
 */
public final class DataStore {

    /** What remains of a removed container, so old history lines can still show its name. */
    public static final class Tomb {
        public String name;
        public String zone;
        public String owner;
        public boolean pub;
        public long removed;
    }

    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dir;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Object fileLock = new Object();

    private final LinkedHashMap<String, StoredChest> chests = new LinkedHashMap<>();
    private final HashMap<String, String> byLoc = new HashMap<>();
    private final HashMap<String, Tomb> removed = new HashMap<>();
    private final HashMap<String, Integer> stackSizes = new HashMap<>();
    private final ArrayList<LogEntry> log = new ArrayList<>();
    private final ArrayList<LogEntry> pending = new ArrayList<>();
    private final AtomicLong rev = new AtomicLong(1);

    private boolean countNested = true;
    private long retentionMillis = 60L * 86_400_000L;
    private int maxEntries = 300_000;
    private int counter;
    private boolean dirty;
    private boolean needCompaction;

    public DataStore(Path dir, Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    public synchronized void configure(boolean countNested, int retentionDays, int maxEntries) {
        boolean changed = this.countNested != countNested;
        this.countNested = countNested;
        this.retentionMillis = Math.max(1, retentionDays) * 86_400_000L;
        this.maxEntries = Math.max(1000, maxEntries);
        if (changed) {
            for (StoredChest c : chests.values()) {
                c.recompute(countNested);
            }
        }
    }

    public boolean countNested() {
        return countNested;
    }

    /** Changes every time something visible on the site changes. The site polls it. */
    public long rev() {
        return rev.get();
    }

    /** Tells the site that something else it shows changed (projects). Takes no lock. */
    public void bump() {
        rev.incrementAndGet();
    }

    // ------------------------------------------------------------------ loading / saving

    public synchronized void load() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Impossible de créer le dossier " + dir, e);
        }
        Path file = dir.resolve("chests.json");
        if (Files.exists(file)) {
            try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject doc = gson.fromJson(r, JsonObject.class);
                if (doc != null) {
                    counter = doc.has("counter") ? doc.get("counter").getAsInt() : 0;
                    List<StoredChest> list = gson.fromJson(doc.get("chests"), new TypeToken<List<StoredChest>>() {}.getType());
                    if (list != null) {
                        for (StoredChest c : list) {
                            if (c.slots == null) {
                                c.slots = new ArrayList<>();
                            }
                            c.recompute(countNested);
                            chests.put(c.id, c);
                            byLoc.put(c.locKey(), c.id);
                        }
                    }
                    Map<String, Tomb> tombs = gson.fromJson(doc.get("removed"), new TypeToken<Map<String, Tomb>>() {}.getType());
                    if (tombs != null) {
                        removed.putAll(tombs);
                    }
                    Map<String, Integer> stacks = gson.fromJson(doc.get("stacks"), new TypeToken<Map<String, Integer>>() {}.getType());
                    if (stacks != null) {
                        stackSizes.putAll(stacks);
                    }
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "chests.json illisible, une copie est gardée en chests.json.broken", e);
                try {
                    Files.copy(file, dir.resolve("chests.json.broken"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }

        Path logFile = dir.resolve("history.jsonl");
        if (Files.exists(logFile)) {
            long cutoff = System.currentTimeMillis() - retentionMillis;
            int skipped = 0;
            try (BufferedReader r = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        LogEntry e = gson.fromJson(line, LogEntry.class);
                        if (e == null || e.t < cutoff) {
                            skipped++;
                            continue;
                        }
                        log.add(e);
                    } catch (Exception bad) {
                        skipped++;
                    }
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Lecture de l'historique impossible", e);
            }
            log.sort((a, b) -> Long.compare(a.t, b.t));
            if (log.size() > maxEntries) {
                skipped += log.size() - maxEntries;
                log.subList(0, log.size() - maxEntries).clear();
            }
            needCompaction = skipped > 0;
        }
        rev.incrementAndGet();
    }

    /** Writes pending changes to disk. Safe to call from any thread. */
    public void save() {
        synchronized (fileLock) {
            String chestsJson = null;
            List<LogEntry> lines;
            boolean rewrite;
            synchronized (this) {
                pruneLocked();
                if (dirty) {
                    JsonObject doc = new JsonObject();
                    doc.addProperty("version", 1);
                    doc.addProperty("counter", counter);
                    doc.add("chests", gson.toJsonTree(new ArrayList<>(chests.values())));
                    doc.add("removed", gson.toJsonTree(removed));
                    doc.add("stacks", gson.toJsonTree(stackSizes));
                    chestsJson = gson.toJson(doc);
                    dirty = false;
                }
                rewrite = needCompaction;
                needCompaction = false;
                lines = new ArrayList<>(rewrite ? log : pending);
                pending.clear();
            }
            try {
                Files.createDirectories(dir);
                if (chestsJson != null) {
                    Path tmp = dir.resolve("chests.json.tmp");
                    Files.writeString(tmp, chestsJson, StandardCharsets.UTF_8);
                    Files.move(tmp, dir.resolve("chests.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                Path logFile = dir.resolve("history.jsonl");
                if (rewrite) {
                    Path tmp = dir.resolve("history.jsonl.tmp");
                    try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                        for (LogEntry e : lines) {
                            w.write(gson.toJson(e));
                            w.newLine();
                        }
                    }
                    Files.move(tmp, logFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } else if (!lines.isEmpty()) {
                    try (BufferedWriter w = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                        for (LogEntry e : lines) {
                            w.write(gson.toJson(e));
                            w.newLine();
                        }
                    }
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Sauvegarde Coffres impossible", e);
                synchronized (this) {
                    dirty = true;
                    if (!rewrite) {
                        pending.addAll(0, lines);
                    } else {
                        needCompaction = true;
                    }
                }
            }
        }
    }

    private void pruneLocked() {
        long cutoff = System.currentTimeMillis() - retentionMillis;
        int drop = 0;
        while (drop < log.size() && log.get(drop).t < cutoff) {
            drop++;
        }
        if (log.size() - drop > maxEntries) {
            drop = log.size() - maxEntries;
        }
        if (drop > 0) {
            log.subList(0, drop).clear();
            needCompaction = true;
        }
    }

    // ------------------------------------------------------------------ chests

    public static String newId() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public synchronized StoredChest get(String id) {
        return id == null ? null : chests.get(id);
    }

    public synchronized StoredChest atLocation(String world, int x, int y, int z) {
        String id = byLoc.get(StoredChest.locKey(world, x, y, z));
        return id == null ? null : chests.get(id);
    }

    public synchronized List<StoredChest> all() {
        return new ArrayList<>(chests.values());
    }

    public synchronized List<String> ids() {
        return new ArrayList<>(chests.keySet());
    }

    public synchronized int nextNumber() {
        dirty = true;
        return ++counter;
    }

    /** Zone of the closest registered container within {@code radius} blocks, or null. */
    public synchronized String nearestZone(String world, int x, int y, int z, int radius) {
        String best = null;
        long bestD = (long) radius * radius;
        for (StoredChest c : chests.values()) {
            if (c.zone == null || c.zone.isBlank() || !c.world.equals(world)) {
                continue;
            }
            long dx = c.x - x;
            long dy = c.y - y;
            long dz = c.z - z;
            long d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) {
                bestD = d;
                best = c.zone;
            }
        }
        return best;
    }

    public synchronized void register(StoredChest c, List<SlotItem> slots, String actorName, String actorUuid) {
        c.setSlots(slots, countNested);
        c.updated = System.currentTimeMillis();
        chests.put(c.id, c);
        byLoc.put(c.locKey(), c.id);
        removed.remove(c.id);
        long now = System.currentTimeMillis();
        List<LogEntry> entries = new ArrayList<>();
        entries.add(new LogEntry(now, c.id, actorName, actorUuid, LogEntry.LINK, null, 0));
        for (Map.Entry<String, Long> e : c.totals().entrySet()) {
            entries.add(new LogEntry(now, c.id, actorName, actorUuid, LogEntry.LINK, e.getKey(), e.getValue()));
        }
        appendLocked(entries);
        touch();
    }

    /** Removes a container. {@code kind} is {@link LogEntry#UNLINK} or {@link LogEntry#BREAK}. */
    public synchronized StoredChest unregister(String id, String kind, String actorName, String actorUuid) {
        StoredChest c = chests.remove(id);
        if (c == null) {
            return null;
        }
        byLoc.remove(c.locKey());
        Tomb t = new Tomb();
        t.name = c.name;
        t.zone = c.zone;
        t.owner = c.owner;
        t.pub = c.pub;
        t.removed = System.currentTimeMillis();
        removed.put(c.id, t);
        long now = System.currentTimeMillis();
        List<LogEntry> entries = new ArrayList<>();
        entries.add(new LogEntry(now, c.id, actorName, actorUuid, kind, null, 0));
        for (Map.Entry<String, Long> e : c.totals().entrySet()) {
            entries.add(new LogEntry(now, c.id, actorName, actorUuid, kind, e.getKey(), -e.getValue()));
        }
        appendLocked(entries);
        touch();
        return c;
    }

    /**
     * Stores new contents for a container and returns how each item count changed
     * (only non-zero changes). Returns an empty map when nothing changed.
     */
    public synchronized Map<String, Long> updateContents(String id, List<SlotItem> slots, int size, boolean dbl) {
        StoredChest c = chests.get(id);
        if (c == null) {
            return Map.of();
        }
        Map<String, Long> before = c.totals();
        Map<String, Long> after = StoredChest.computeTotals(slots, countNested);
        Map<String, Long> delta = diff(before, after);
        boolean shapeChanged = c.size != size || c.dbl != dbl;
        boolean slotsChanged = !delta.isEmpty() || !sameSlots(c.slots, slots);
        if (!slotsChanged && !shapeChanged) {
            return Map.of();
        }
        c.slots = slots;
        c.setSlots(slots, countNested);
        c.size = size;
        c.dbl = dbl;
        if (!delta.isEmpty()) {
            c.updated = System.currentTimeMillis();
        }
        touch();
        return delta;
    }

    public static Map<String, Long> diff(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> delta = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : after.entrySet()) {
            long d = e.getValue() - before.getOrDefault(e.getKey(), 0L);
            if (d != 0) {
                delta.put(e.getKey(), d);
            }
        }
        for (Map.Entry<String, Long> e : before.entrySet()) {
            if (!after.containsKey(e.getKey()) && e.getValue() != 0) {
                delta.put(e.getKey(), -e.getValue());
            }
        }
        return delta;
    }

    private static boolean sameSlots(List<SlotItem> a, List<SlotItem> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            SlotItem x = a.get(i);
            SlotItem y = b.get(i);
            if (x.s != y.s || x.n != y.n || x.e != y.e || !Objects.equals(x.id, y.id) || !Objects.equals(x.name, y.name)) {
                return false;
            }
        }
        return true;
    }

    public synchronized boolean move(String id, String world, int x, int y, int z) {
        StoredChest c = chests.get(id);
        if (c == null) {
            return false;
        }
        byLoc.remove(c.locKey());
        c.world = world;
        c.x = x;
        c.y = y;
        c.z = z;
        byLoc.put(c.locKey(), c.id);
        touch();
        return true;
    }

    /** Edits name / zone / visibility. Null arguments are left unchanged. */
    public synchronized boolean edit(String id, String name, String zone, Boolean pub) {
        StoredChest c = chests.get(id);
        if (c == null) {
            return false;
        }
        if (name != null && !name.isBlank()) {
            c.name = clean(name, 40);
        }
        if (zone != null) {
            c.zone = zone.isBlank() ? null : clean(zone, 32);
        }
        if (pub != null) {
            c.pub = pub;
        }
        touch();
        return true;
    }

    public static String clean(String s, int max) {
        String t = s.replaceAll("[\\p{Cntrl}§]", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    public synchronized void setLastPlayer(String id, String name) {
        StoredChest c = chests.get(id);
        if (c != null && !Objects.equals(c.lastPlayer, name)) {
            c.lastPlayer = name;
            dirty = true;
        }
    }

    public synchronized void noteStackSize(String itemId, int max) {
        Integer old = stackSizes.put(itemId, max);
        if (old == null || old != max) {
            dirty = true;
        }
    }

    // ------------------------------------------------------------------ history

    public synchronized void append(List<LogEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        appendLocked(entries);
        rev.incrementAndGet();
    }

    private void appendLocked(List<LogEntry> entries) {
        log.addAll(entries);
        pending.addAll(entries);
        if (log.size() > maxEntries + maxEntries / 10) {
            log.subList(0, log.size() - maxEntries).clear();
            needCompaction = true;
        }
    }

    private void touch() {
        dirty = true;
        rev.incrementAndGet();
    }

    // ------------------------------------------------------------------ locked views for the web API

    /** Call only inside {@code synchronized (store)}. */
    public Collection<StoredChest> chestsLocked() {
        return chests.values();
    }

    /** Call only inside {@code synchronized (store)}. */
    public StoredChest chestLocked(String id) {
        return chests.get(id);
    }

    /** Call only inside {@code synchronized (store)}. Sorted oldest first. */
    public List<LogEntry> logLocked() {
        return log;
    }

    /** Call only inside {@code synchronized (store)}. */
    public Tomb tombLocked(String id) {
        return removed.get(id);
    }

    /** Call only inside {@code synchronized (store)}. */
    public Map<String, Integer> stackSizesLocked() {
        return stackSizes;
    }

    /** Test helper: inserts data as if it had been loaded from disk. */
    public synchronized void putForTest(StoredChest c, List<LogEntry> history) {
        c.recompute(countNested);
        chests.put(c.id, c);
        byLoc.put(c.locKey(), c.id);
        if (history != null) {
            log.addAll(history);
            log.sort((a, b) -> Long.compare(a.t, b.t));
        }
        rev.incrementAndGet();
    }
}
