package fr.coffres.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import fr.coffres.model.Group;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Groups of friends and the players already seen on the server (to add them to a group by name), saved in
 * {@code groups.json}. Thread-safe: every method locks on this object, and never calls another store while locked
 * (so it can be used inside the DataStore and ProjectStore locks). Does not touch the Bukkit API.
 */
public final class GroupStore {

    /** Groups a player can create. */
    public static final int MAX_OWNED = 20;
    /** Members of one group (the creator not counted). */
    public static final int MAX_MEMBERS = 50;

    private final Path file;
    private final Logger logger;
    private final Runnable onChange;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Object fileLock = new Object();
    private final LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
    /** uuid → last known name of every player seen on the server. */
    private final LinkedHashMap<String, String> known = new LinkedHashMap<>();
    private boolean dirty;

    public GroupStore(Path file, Logger logger, Runnable onChange) {
        this.file = file;
        this.logger = logger;
        this.onChange = onChange == null ? () -> { } : onChange;
    }

    // ------------------------------------------------------------------ loading / saving

    public synchronized void load() {
        groups.clear();
        known.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject doc = gson.fromJson(r, JsonObject.class);
            if (doc == null) {
                return;
            }
            if (doc.has("groups")) {
                List<Group> list = gson.fromJson(doc.get("groups"), new TypeToken<List<Group>>() {}.getType());
                for (Group g : list == null ? List.<Group>of() : list) {
                    if (g != null && g.id != null && g.owner != null) {
                        if (g.members == null) {
                            g.members = new ArrayList<>();
                        }
                        groups.put(g.id, g);
                    }
                }
            }
            if (doc.has("players")) {
                Map<String, String> map = gson.fromJson(doc.get("players"), new TypeToken<Map<String, String>>() {}.getType());
                if (map != null) {
                    known.putAll(map);
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "groups.json illisible, une copie est gardée en groups.json.broken", e);
            try {
                Files.copy(file, file.resolveSibling("groups.json.broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    public void save() {
        synchronized (fileLock) {
            String json;
            synchronized (this) {
                if (!dirty) {
                    return;
                }
                JsonObject doc = new JsonObject();
                doc.addProperty("version", 1);
                doc.add("groups", gson.toJsonTree(new ArrayList<>(groups.values())));
                doc.add("players", gson.toJsonTree(known));
                json = gson.toJson(doc);
                dirty = false;
            }
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Sauvegarde des groupes impossible", e);
                synchronized (this) {
                    dirty = true;
                }
            }
        }
    }

    // ------------------------------------------------------------------ players seen on the server

    /** A player joined (or was found in an older file): remember their name, and keep it up to date in the groups. */
    public void seen(String uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) {
            return;
        }
        boolean changed = false;
        synchronized (this) {
            if (!name.equals(known.get(uuid))) {
                known.put(uuid, name);
                dirty = true;
            }
            for (Group g : groups.values()) {
                if (uuid.equals(g.owner) && !name.equals(g.ownerName)) {
                    g.ownerName = name;
                    changed = true;
                }
                for (Group.Member m : g.members) {
                    if (uuid.equals(m.uuid) && !name.equals(m.name)) {
                        m.name = name;
                        changed = true;
                    }
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

    /** The uuid of a player already seen on the server, by name (case ignored), or null. */
    public synchronized String findPlayer(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : known.entrySet()) {
            if (e.getValue().toLowerCase(Locale.ROOT).equals(n)) {
                return e.getKey();
            }
        }
        return null;
    }

    public synchronized String nameOf(String uuid) {
        return known.get(uuid);
    }

    /** Names of every player already seen on the server, sorted. */
    public synchronized List<String> knownNames() {
        List<String> out = new ArrayList<>(new HashSet<>(known.values()));
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    // ------------------------------------------------------------------ reading

    public synchronized Group get(String id) {
        return id == null ? null : groups.get(id);
    }

    /** Creator or member of this group. */
    public synchronized boolean isMember(String groupId, String uuid) {
        Group g = get(groupId);
        return g != null && g.has(uuid);
    }

    /** The creator and the members of a group. */
    public synchronized Set<String> people(String groupId) {
        Set<String> out = new HashSet<>();
        Group g = get(groupId);
        if (g != null) {
            out.add(g.owner);
            for (Group.Member m : g.members) {
                out.add(m.uuid);
            }
        }
        return out;
    }

    /** Groups this player created or belongs to (all of them for admins when {@code all}). */
    public synchronized List<Group> groupsOf(String uuid, boolean all) {
        List<Group> out = new ArrayList<>();
        for (Group g : groups.values()) {
            if (all || g.has(uuid)) {
                out.add(g);
            }
        }
        return out;
    }

    public synchronized int countOwnedBy(String uuid) {
        int n = 0;
        for (Group g : groups.values()) {
            if (Objects.equals(g.owner, uuid)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ changes

    public Group create(String name, String ownerUuid, String ownerName) {
        Group g = new Group();
        synchronized (this) {
            do {
                g.id = DataStore.newId();
            } while (groups.containsKey(g.id));
            g.name = DataStore.clean(name, 32);
            g.owner = ownerUuid;
            g.ownerName = ownerName;
            g.created = System.currentTimeMillis();
            groups.put(g.id, g);
            dirty = true;
        }
        onChange.run();
        return g;
    }

    public boolean rename(String id, String name) {
        synchronized (this) {
            Group g = groups.get(id);
            if (g == null || name == null || name.isBlank()) {
                return false;
            }
            g.name = DataStore.clean(name, 32);
            dirty = true;
        }
        onChange.run();
        return true;
    }

    /** Adds a player already seen on the server. Returns false when they are already in the group. */
    public boolean add(String id, String uuid, String name) {
        synchronized (this) {
            Group g = groups.get(id);
            if (g == null || g.has(uuid)) {
                return false;
            }
            List<Group.Member> list = new ArrayList<>(g.members);
            list.add(new Group.Member(uuid, name));
            g.members = list;
            dirty = true;
        }
        onChange.run();
        return true;
    }

    /** Removes a member (the creator cannot be removed: they delete the group instead). */
    public boolean remove(String id, String uuid) {
        synchronized (this) {
            Group g = groups.get(id);
            if (g == null) {
                return false;
            }
            List<Group.Member> list = new ArrayList<>(g.members);
            if (!list.removeIf(m -> m.uuid.equals(uuid))) {
                return false;
            }
            g.members = list;
            dirty = true;
        }
        onChange.run();
        return true;
    }

    public Group delete(String id) {
        Group g;
        synchronized (this) {
            g = groups.remove(id);
            if (g == null) {
                return null;
            }
            dirty = true;
        }
        onChange.run();
        return g;
    }

    /** Test / demo helper. */
    public void put(Group g) {
        synchronized (this) {
            groups.put(g.id, g);
            dirty = true;
        }
        onChange.run();
    }
}
