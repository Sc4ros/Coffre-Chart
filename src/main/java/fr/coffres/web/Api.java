package fr.coffres.web;

import fr.coffres.model.LogEntry;
import fr.coffres.model.Project;
import fr.coffres.model.SlotItem;
import fr.coffres.model.StoredChest;
import fr.coffres.project.ProjectProgress;
import fr.coffres.store.DataStore;
import fr.coffres.store.ProjectStore;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Builds the JSON answers of the site from the {@link DataStore}.
 * Every method takes the store lock for its whole duration so the numbers are consistent.
 * Visibility: public containers are visible to everyone, private ones only to their owner and admins.
 */
public final class Api {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;

    private final DataStore store;
    private final ProjectStore projects;
    private final ZoneId zone = ZoneId.systemDefault();

    public Api(DataStore store, ProjectStore projects) {
        this.store = store;
        this.projects = projects;
    }

    // ------------------------------------------------------------------ visibility

    /**
     * What the player sees in the part of the site they are looking at:
     * "Mes coffres" = only the containers they added, "Communs" = shared containers, otherwise everything allowed.
     */
    static boolean visible(StoredChest c, AuthManager.Session s) {
        if (AuthManager.Session.MINE.equals(s.view)) {
            return Objects.equals(c.owner, s.uuid);
        }
        if (AuthManager.Session.COMMON.equals(s.view)) {
            return c.pub;
        }
        return visibleAll(c, s);
    }

    /** Everything the player is allowed to see, whatever the part of the site (used for projects): shared, theirs, or all for admins. */
    static boolean visibleAll(StoredChest c, AuthManager.Session s) {
        return c.pub || s.admin || Objects.equals(c.owner, s.uuid);
    }

    private Predicate<String> visibleIds(AuthManager.Session s) {
        Map<String, Boolean> cache = new HashMap<>();
        return id -> cache.computeIfAbsent(id, k -> {
            StoredChest c = findChest(k);
            if (c != null) {
                return visible(c, s);
            }
            DataStore.Tomb t = store.tombLocked(k);
            if (AuthManager.Session.MINE.equals(s.view)) {
                return t != null && Objects.equals(t.owner, s.uuid);
            }
            if (AuthManager.Session.COMMON.equals(s.view)) {
                return t != null && t.pub;
            }
            return t == null ? s.admin : t.pub || s.admin || Objects.equals(t.owner, s.uuid);
        });
    }

    private StoredChest findChest(String id) {
        return store.chestLocked(id);
    }

    private List<StoredChest> visibleChests(AuthManager.Session s) {
        List<StoredChest> out = new ArrayList<>();
        for (StoredChest c : store.chestsLocked()) {
            if (visible(c, s)) {
                out.add(c);
            }
        }
        return out;
    }

    /** How many containers are in each part of the site for this player: theirs, shared, everything they may see. */
    public Map<String, Integer> counts(AuthManager.Session s) {
        synchronized (store) {
            int mine = 0;
            int common = 0;
            int all = 0;
            for (StoredChest c : store.chestsLocked()) {
                if (Objects.equals(c.owner, s.uuid)) {
                    mine++;
                }
                if (c.pub) {
                    common++;
                }
                if (visibleAll(c, s)) {
                    all++;
                }
            }
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("mine", mine);
            m.put("common", common);
            m.put("all", all);
            return m;
        }
    }

    private static Map<String, Long> aggregate(List<StoredChest> chests) {
        Map<String, Long> totals = new HashMap<>();
        for (StoredChest c : chests) {
            c.totals().forEach((k, v) -> totals.merge(k, v, Long::sum));
        }
        return totals;
    }

    // ------------------------------------------------------------------ endpoints

    public Map<String, Object> overview(AuthManager.Session s, int days) {
        synchronized (store) {
            long now = System.currentTimeMillis();
            List<StoredChest> chests = visibleChests(s);
            Map<String, Long> totals = aggregate(chests);
            long items = totals.values().stream().mapToLong(Long::longValue).sum();
            int slotsUsed = 0;
            int slotsTotal = 0;
            for (StoredChest c : chests) {
                slotsUsed += c.slots.size();
                slotsTotal += c.size;
            }

            Predicate<String> vis = visibleIds(s);
            long in24 = 0;
            long out24 = 0;
            Set<String> players7 = new HashSet<>();
            List<LogEntry> log = store.logLocked();
            for (int i = log.size() - 1; i >= 0; i--) {
                LogEntry e = log.get(i);
                if (e.t < now - 7 * DAY) {
                    break;
                }
                if (!isPlayerMove(e) || !vis.test(e.c)) {
                    continue;
                }
                if (e.u != null) {
                    players7.add(e.u);
                }
                if (e.t >= now - DAY) {
                    if (e.d > 0) {
                        in24 += e.d;
                    } else {
                        out24 -= e.d;
                    }
                }
            }

            Map<String, Object> kpi = new LinkedHashMap<>();
            kpi.put("items", items);
            kpi.put("distinct", totals.size());
            kpi.put("chests", chests.size());
            kpi.put("slotsUsed", slotsUsed);
            kpi.put("slotsTotal", slotsTotal);
            kpi.put("in24", in24);
            kpi.put("out24", out24);
            kpi.put("players7", players7.size());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kpi", kpi);
            out.put("wheel", wheel(totals, 8));
            out.put("trend", trend(s, null, totals, days));
            out.put("daily", daily(s, null, Math.max(7, Math.min(days, 30))));
            out.put("players", playerStats(s, null, days, 6));
            out.put("zones", zones(chests));
            out.put("recent", events(s, null, null, null, null, Long.MAX_VALUE, 8));
            out.put("projects", projectList(s, 4));
            return out;
        }
    }

    public List<Map<String, Object>> items(AuthManager.Session s) {
        synchronized (store) {
            List<StoredChest> chests = visibleChests(s);
            Map<String, long[]> agg = new HashMap<>();
            Map<String, List<Map.Entry<String, Long>>> where = new HashMap<>();
            for (StoredChest c : chests) {
                for (Map.Entry<String, Long> e : c.totals().entrySet()) {
                    long[] a = agg.computeIfAbsent(e.getKey(), k -> new long[2]);
                    a[0] += e.getValue();
                    a[1]++;
                    where.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(Map.entry(c.id, e.getValue()));
                }
            }
            Map<String, Integer> stacks = store.stackSizesLocked();
            List<Map<String, Object>> out = new ArrayList<>();
            agg.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                    .forEach(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", e.getKey());
                        m.put("n", e.getValue()[0]);
                        m.put("c", e.getValue()[1]);
                        m.put("st", stacks.getOrDefault(e.getKey(), 64));
                        // the containers holding it, most first (used by the search page to show the closest one)
                        m.put("w", where.get(e.getKey()).stream()
                                .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                                .limit(12).map(Map.Entry::getKey).toList());
                        out.add(m);
                    });
            return out;
        }
    }

    public Map<String, Object> item(AuthManager.Session s, String id, int days) {
        synchronized (store) {
            List<StoredChest> chests = visibleChests(s);
            List<Map<String, Object>> where = new ArrayList<>();
            long total = 0;
            for (StoredChest c : chests) {
                Long n = c.totals().get(id);
                if (n == null || n == 0) {
                    continue;
                }
                total += n;
                Map<String, Object> m = chestSummary(c, s);
                m.put("count", n);
                m.put("loose", looseCount(c, id));
                where.add(m);
            }
            where.sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("total", total);
            out.put("stack", store.stackSizesLocked().getOrDefault(id, 64));
            out.put("chests", where);
            out.put("trend", trend(s, id, Map.of(id, total), days));
            out.put("players", playerStats(s, id, days, 8));
            out.put("recent", events(s, null, id, null, null, Long.MAX_VALUE, 20));
            out.put("projects", projectsUsing(s, id));
            return out;
        }
    }

    /** How many of this item are directly in the container (not inside shulkers). */
    private static long looseCount(StoredChest c, String id) {
        long n = 0;
        for (SlotItem it : c.slots) {
            if (id.equals(it.id)) {
                n += it.n;
            }
        }
        return n;
    }

    public List<Map<String, Object>> chests(AuthManager.Session s) {
        synchronized (store) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (StoredChest c : visibleChests(s)) {
                out.add(chestSummary(c, s));
            }
            return out;
        }
    }

    public Map<String, Object> chest(AuthManager.Session s, String id) {
        synchronized (store) {
            StoredChest c = findChest(id);
            if (c == null || !visible(c, s)) {
                return null;
            }
            Map<String, Object> out = chestSummary(c, s);
            out.put("slots", c.slots);
            List<Map<String, Object>> contents = new ArrayList<>();
            c.totals().entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(e -> contents.add(Map.of("id", e.getKey(), "n", e.getValue())));
            out.put("contents", contents);
            out.put("recent", events(s, id, null, null, null, Long.MAX_VALUE, 30));
            return out;
        }
    }

    private Map<String, Object> chestSummary(StoredChest c, AuthManager.Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("name", c.name);
        m.put("zone", c.zone);
        m.put("world", c.world);
        m.put("dim", c.dim);
        m.put("x", c.x);
        m.put("y", c.y);
        m.put("z", c.z);
        m.put("type", c.type);
        m.put("dbl", c.dbl);
        m.put("size", c.size);
        m.put("used", c.slots.size());
        m.put("items", c.itemCount());
        m.put("distinct", c.totals().size());
        m.put("owner", c.ownerName);
        m.put("pub", c.pub);
        m.put("updated", c.updated);
        m.put("created", c.created);
        m.put("lastPlayer", c.lastPlayer);
        m.put("canEdit", s.admin || Objects.equals(c.owner, s.uuid));
        m.put("mine", Objects.equals(c.owner, s.uuid));
        List<Map<String, Object>> top = new ArrayList<>();
        c.totals().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(4)
                .forEach(e -> top.add(Map.of("id", e.getKey(), "n", e.getValue())));
        m.put("top", top);
        return m;
    }

    public Map<String, Object> activity(AuthManager.Session s, String chest, String item, String player, String kind, long before, int limit) {
        synchronized (store) {
            List<Map<String, Object>> ev = events(s, chest, item, player, kind, before, Math.min(Math.max(limit, 1), 200));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("events", ev);
            out.put("next", ev.isEmpty() ? null : ev.get(ev.size() - 1).get("t"));
            return out;
        }
    }

    public Map<String, Object> players(AuthManager.Session s, int days) {
        synchronized (store) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("players", playerStats(s, null, days, 100));
            out.put("daily", daily(s, null, Math.max(7, Math.min(days, 30))));
            return out;
        }
    }

    // ------------------------------------------------------------------ building blocks

    private static boolean isPlayerMove(LogEntry e) {
        return e.m != null && e.u != null && (LogEntry.PLAYER.equals(e.k) || LogEntry.BREAK.equals(e.k));
    }

    private static List<Map<String, Object>> wheel(Map<String, Long> totals, int top) {
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(totals.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<Map<String, Object>> out = new ArrayList<>();
        long other = 0;
        int otherKinds = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (i < top) {
                out.add(Map.of("id", sorted.get(i).getKey(), "n", sorted.get(i).getValue()));
            } else {
                other += sorted.get(i).getValue();
                otherKinds++;
            }
        }
        if (other > 0) {
            out.add(Map.of("id", "_other", "n", other, "kinds", otherKinds));
        }
        return out;
    }

    /**
     * Total count over time, rebuilt backwards from the current totals and the history.
     * {@code item} null = every item.
     */
    private Map<String, Object> trend(AuthManager.Session s, String item, Map<String, Long> current, int days) {
        long now = System.currentTimeMillis();
        long step;
        int count;
        if (days <= 1) {
            step = HOUR;
            count = 24;
        } else if (days <= 7) {
            step = 4 * HOUR;
            count = days * 6;
        } else {
            step = DAY;
            count = days;
        }
        long cur = item == null ? current.values().stream().mapToLong(Long::longValue).sum() : current.getOrDefault(item, 0L);
        long[] pts = new long[count + 1];
        long[] times = new long[count + 1];
        for (int i = 0; i <= count; i++) {
            times[i] = now - (long) (count - i) * step;
        }
        Predicate<String> vis = visibleIds(s);
        List<LogEntry> log = store.logLocked();
        int j = log.size() - 1;
        long acc = cur;
        for (int i = count; i >= 0; i--) {
            long t = times[i];
            while (j >= 0 && log.get(j).t > t) {
                LogEntry e = log.get(j);
                if (e.m != null && (item == null || item.equals(e.m)) && vis.test(e.c)) {
                    acc -= e.d;
                }
                j--;
            }
            pts[i] = Math.max(0, acc);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", times[0]);
        out.put("step", step);
        List<Long> list = new ArrayList<>(pts.length);
        for (long p : pts) {
            list.add(p);
        }
        out.put("points", list);
        return out;
    }

    /** Items put in / taken out by players, per local day. */
    private List<Map<String, Object>> daily(AuthManager.Session s, String item, int days) {
        LocalDate today = LocalDate.now(zone);
        LocalDate first = today.minusDays(days - 1L);
        long start = first.atStartOfDay(zone).toInstant().toEpochMilli();
        long[] in = new long[days];
        long[] out = new long[days];
        Predicate<String> vis = visibleIds(s);
        List<LogEntry> log = store.logLocked();
        for (int i = log.size() - 1; i >= 0; i--) {
            LogEntry e = log.get(i);
            if (e.t < start) {
                break;
            }
            if (!isPlayerMove(e) || (item != null && !item.equals(e.m)) || !vis.test(e.c)) {
                continue;
            }
            LocalDate d = Instant.ofEpochMilli(e.t).atZone(zone).toLocalDate();
            int idx = (int) (d.toEpochDay() - first.toEpochDay());
            if (idx < 0 || idx >= days) {
                continue;
            }
            if (e.d > 0) {
                in[idx] += e.d;
            } else {
                out[idx] -= e.d;
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", first.plusDays(i).atStartOfDay(zone).toInstant().toEpochMilli());
            m.put("in", in[i]);
            m.put("out", out[i]);
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> playerStats(AuthManager.Session s, String item, int days, int limit) {
        long since = System.currentTimeMillis() - Math.max(1, days) * DAY;
        Predicate<String> vis = visibleIds(s);
        Map<String, Map<String, Object>> byUuid = new LinkedHashMap<>();
        Map<String, Map<String, long[]>> perItem = new HashMap<>();
        Map<String, Set<Long>> actions = new HashMap<>();
        List<LogEntry> log = store.logLocked();
        for (int i = log.size() - 1; i >= 0; i--) {
            LogEntry e = log.get(i);
            if (e.t < since) {
                break;
            }
            if (!isPlayerMove(e) || (item != null && !item.equals(e.m)) || !vis.test(e.c)) {
                continue;
            }
            Map<String, Object> p = byUuid.computeIfAbsent(e.u, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("uuid", e.u);
                m.put("name", e.p);
                m.put("in", 0L);
                m.put("out", 0L);
                m.put("last", e.t);
                return m;
            });
            if (e.d > 0) {
                p.put("in", (Long) p.get("in") + e.d);
            } else {
                p.put("out", (Long) p.get("out") - e.d);
            }
            actions.computeIfAbsent(e.u, k -> new HashSet<>()).add(e.t);
            long[] io = perItem.computeIfAbsent(e.u, k -> new HashMap<>()).computeIfAbsent(e.m, k -> new long[2]);
            if (e.d > 0) {
                io[0] += e.d;
            } else {
                io[1] -= e.d;
            }
        }
        List<Map<String, Object>> list = new ArrayList<>(byUuid.values());
        for (Map<String, Object> p : list) {
            String u = (String) p.get("uuid");
            p.put("moves", actions.getOrDefault(u, Set.of()).size());
            Map<String, long[]> items = perItem.getOrDefault(u, Map.of());
            p.put("topIn", topOf(items, 0));
            p.put("topOut", topOf(items, 1));
        }
        list.sort(Comparator.comparingLong((Map<String, Object> p) -> (Long) p.get("in") + (Long) p.get("out")).reversed());
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    private static List<Map<String, Object>> topOf(Map<String, long[]> items, int idx) {
        List<Map<String, Object>> out = new ArrayList<>();
        items.entrySet().stream()
                .filter(e -> e.getValue()[idx] > 0)
                .sorted((a, b) -> Long.compare(b.getValue()[idx], a.getValue()[idx]))
                .limit(3)
                .forEach(e -> out.add(Map.of("id", e.getKey(), "n", e.getValue()[idx])));
        return out;
    }

    private static List<Map<String, Object>> zones(List<StoredChest> chests) {
        Map<String, long[]> z = new LinkedHashMap<>();
        for (StoredChest c : chests) {
            String key = c.zone == null || c.zone.isBlank() ? "" : c.zone;
            long[] a = z.computeIfAbsent(key, k -> new long[2]);
            a[0] += c.itemCount();
            a[1]++;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        z.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> out.add(Map.of("zone", e.getKey(), "items", e.getValue()[0], "chests", e.getValue()[1])));
        return out;
    }

    /**
     * History grouped into events (all lines written by one action share time, chest, player and kind).
     * Filters may be null. {@code kind}: "in", "out", "auto", "chest" or null.
     */
    private List<Map<String, Object>> events(AuthManager.Session s, String chest, String item, String player, String kind,
                                             long before, int limit) {
        Predicate<String> vis = visibleIds(s);
        List<LogEntry> log = store.logLocked();
        List<Map<String, Object>> out = new ArrayList<>();
        int i = log.size() - 1;
        while (i >= 0 && log.get(i).t >= before) {
            i--;
        }
        while (i >= 0 && out.size() < limit) {
            LogEntry head = log.get(i);
            // collect the group
            List<LogEntry> group = new ArrayList<>();
            int j = i;
            while (j >= 0) {
                LogEntry e = log.get(j);
                if (e.t != head.t || !Objects.equals(e.c, head.c) || !Objects.equals(e.u, head.u)
                        || !Objects.equals(e.p, head.p) || !Objects.equals(e.k, head.k)) {
                    break;
                }
                group.add(e);
                j--;
            }
            i = j;
            if (!vis.test(head.c)) {
                continue;
            }
            if (chest != null && !chest.equals(head.c)) {
                continue;
            }
            if (player != null && !player.equalsIgnoreCase(head.p == null ? "" : head.p)) {
                continue;
            }
            List<Map<String, Object>> deltas = new ArrayList<>();
            long plus = 0;
            long minus = 0;
            for (LogEntry e : group) {
                if (e.m == null || e.d == 0 || (item != null && !item.equals(e.m))) {
                    continue;
                }
                deltas.add(Map.of("id", e.m, "d", e.d));
                if (e.d > 0) {
                    plus += e.d;
                } else {
                    minus -= e.d;
                }
            }
            boolean chestEvent = LogEntry.LINK.equals(head.k) || LogEntry.UNLINK.equals(head.k) || LogEntry.BREAK.equals(head.k);
            if (item != null && deltas.isEmpty()) {
                continue;
            }
            if (!chestEvent && deltas.isEmpty()) {
                continue;
            }
            if (kind != null) {
                boolean keep = switch (kind) {
                    case "in" -> LogEntry.PLAYER.equals(head.k) && plus > 0;
                    case "out" -> (LogEntry.PLAYER.equals(head.k) || LogEntry.BREAK.equals(head.k)) && minus > 0 && head.u != null;
                    case "auto" -> LogEntry.AUTO.equals(head.k);
                    case "chest" -> chestEvent;
                    default -> true;
                };
                if (!keep) {
                    continue;
                }
            }
            deltas.sort((a, b) -> Long.compare(Math.abs((Long) b.get("d")), Math.abs((Long) a.get("d"))));
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("t", head.t);
            ev.put("kind", head.k);
            ev.put("player", head.p);
            ev.put("uuid", head.u);
            ev.put("chest", chestRef(head.c));
            ev.put("plus", plus);
            ev.put("minus", minus);
            ev.put("items", deltas.size() > 12 ? deltas.subList(0, 12) : deltas);
            ev.put("more", Math.max(0, deltas.size() - 12));
            out.add(ev);
        }
        return out;
    }

    private Map<String, Object> chestRef(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        StoredChest c = findChest(id);
        if (c != null) {
            m.put("name", c.name);
            m.put("zone", c.zone);
            m.put("type", c.type);
            m.put("exists", true);
        } else {
            DataStore.Tomb t = store.tombLocked(id);
            m.put("name", t == null ? "Coffre supprimé" : t.name);
            m.put("zone", t == null ? null : t.zone);
            m.put("exists", false);
        }
        return m;
    }

    /** Ids of visible containers matching these ids, used before asking the game to highlight them. */
    public List<String> filterVisible(AuthManager.Session s, List<String> ids) {
        synchronized (store) {
            List<String> out = new ArrayList<>();
            for (String id : ids) {
                StoredChest c = findChest(id);
                if (c != null && visible(c, s)) {
                    out.add(id);
                }
            }
            return out;
        }
    }

    public boolean canEdit(AuthManager.Session s, String id) {
        synchronized (store) {
            StoredChest c = findChest(id);
            return c != null && (s.admin || Objects.equals(c.owner, s.uuid));
        }
    }

    /** Only the player who added a container may make it shared (admins included: they may only make it private). */
    public boolean isOwner(AuthManager.Session s, String id) {
        synchronized (store) {
            StoredChest c = findChest(id);
            return c != null && Objects.equals(c.owner, s.uuid);
        }
    }

    // ------------------------------------------------------------------ projects
    // Lock order: store first, then projects (the in-game code does the same).

    /** Shared projects are visible to everyone, solo projects only to their owner and admins. */
    public static boolean canSee(Project p, AuthManager.Session s) {
        return p.shared || s.admin || Objects.equals(p.owner, s.uuid);
    }

    /** Rename, share, replace the file, delete: owner and admins. */
    public static boolean canEdit(Project p, AuthManager.Session s) {
        return s.admin || Objects.equals(p.owner, s.uuid);
    }

    /** Link containers: owner and admins, and everyone on a shared project. */
    public static boolean canLink(Project p, AuthManager.Session s) {
        return p.shared || canEdit(p, s);
    }

    private ProjectProgress progress(Project p, AuthManager.Session s) {
        return ProjectProgress.of(p, store.chestsLocked(), c -> visibleAll(c, s), store.stackSizesLocked());
    }

    private Map<String, Object> projectSummary(Project p, AuthManager.Session s, ProjectProgress pr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("name", p.name);
        m.put("shared", p.shared);
        m.put("owner", p.ownerName);
        m.put("mine", Objects.equals(p.owner, s.uuid));
        m.put("canEdit", canEdit(p, s));
        m.put("canLink", canLink(p, s));
        m.put("file", p.file);
        m.put("format", p.format);
        m.put("size", List.of(p.sx, p.sy, p.sz));
        m.put("need", pr.need);
        m.put("have", pr.have);
        m.put("missing", pr.missing());
        m.put("ratio", pr.ratio());
        m.put("percent", pr.percent());
        m.put("kinds", pr.lines.size());
        m.put("doneKinds", pr.doneKinds);
        m.put("chests", pr.linked.size());
        m.put("created", p.created);
        m.put("updated", p.updated);
        List<Map<String, Object>> top = new ArrayList<>();
        pr.lines.stream()
                .filter(l -> l.missing() > 0)
                .sorted((a, b) -> Long.compare(b.missing(), a.missing()))
                .limit(5)
                .forEach(l -> top.add(Map.of("id", l.id(), "n", l.missing())));
        m.put("top", top);
        return m;
    }

    /** Projects the player can see: unfinished first, then the most recently changed. */
    private List<Map<String, Object>> projectList(AuthManager.Session s, int limit) {
        synchronized (projects) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Project p : projects.projectsLocked()) {
                if (canSee(p, s)) {
                    out.add(projectSummary(p, s, progress(p, s)));
                }
            }
            out.sort(Comparator.comparing((Map<String, Object> m) -> (Integer) m.get("percent") >= 100)
                    .thenComparing(m -> -(Long) m.get("updated")));
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        }
    }

    public List<Map<String, Object>> projects(AuthManager.Session s) {
        synchronized (store) {
            return projectList(s, Integer.MAX_VALUE);
        }
    }

    public Map<String, Object> project(AuthManager.Session s, String id) {
        synchronized (store) {
            synchronized (projects) {
                Project p = projects.get(id);
                if (p == null || !canSee(p, s)) {
                    return null;
                }
                ProjectProgress pr = progress(p, s);
                Map<String, Object> out = projectSummary(p, s, pr);
                out.put("schemName", p.schemName);
                out.put("author", p.author);
                out.put("blocks", p.blocks);
                List<Map<String, Object>> mats = new ArrayList<>(pr.lines.size());
                for (ProjectProgress.Line l : pr.lines) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", l.id());
                    m.put("need", l.need());
                    m.put("have", l.have());
                    m.put("elsewhere", l.elsewhere());
                    m.put("st", l.stack());
                    mats.add(m);
                }
                out.put("materials", mats);
                List<Map<String, Object>> linked = new ArrayList<>();
                for (StoredChest c : pr.linked) {
                    Map<String, Object> m = chestSummary(c, s);
                    m.put("useful", ProjectProgress.useful(p, c));
                    linked.add(m);
                }
                linked.sort((a, b) -> Long.compare((Long) b.get("useful"), (Long) a.get("useful")));
                out.put("linked", linked);
                out.put("hiddenChests", pr.hiddenLinked);
                // Every container the player can see, to choose which ones to link.
                java.util.Set<String> linkedIds = new HashSet<>(p.chests);
                List<Map<String, Object>> candidates = new ArrayList<>();
                for (StoredChest c : store.chestsLocked()) {
                    if (!visibleAll(c, s)) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.id);
                    m.put("name", c.name);
                    m.put("zone", c.zone);
                    m.put("type", c.type);
                    m.put("world", c.world);
                    m.put("dim", c.dim);
                    m.put("x", c.x);
                    m.put("y", c.y);
                    m.put("z", c.z);
                    m.put("pub", c.pub);
                    m.put("items", c.itemCount());
                    m.put("useful", ProjectProgress.useful(p, c));
                    m.put("linked", linkedIds.contains(c.id));
                    candidates.add(m);
                }
                out.put("candidates", candidates);
                return out;
            }
        }
    }

    /** Ids of the containers that stay linked after a player sets the list: the ones they cannot see are kept. */
    public List<String> mergeLinks(AuthManager.Session s, String projectId, List<String> wanted) {
        synchronized (store) {
            synchronized (projects) {
                Project p = projects.get(projectId);
                if (p == null) {
                    return List.of();
                }
                java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
                for (String id : p.chests) {
                    StoredChest c = findChest(id);
                    if (c != null && !visibleAll(c, s)) {
                        out.add(id);
                    }
                }
                for (String id : wanted) {
                    StoredChest c = findChest(id);
                    if (c != null && visibleAll(c, s)) {
                        out.add(id);
                    }
                }
                return new ArrayList<>(out);
            }
        }
    }

    /** Visible projects that need this item (item page). */
    private List<Map<String, Object>> projectsUsing(AuthManager.Session s, String itemId) {
        synchronized (projects) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map.Entry<Project, Long> e : projects.usingLocked(itemId).entrySet()) {
                Project p = e.getKey();
                if (!canSee(p, s)) {
                    continue;
                }
                long have = 0;
                for (String cid : p.chests) {
                    StoredChest c = findChest(cid);
                    if (c != null && visibleAll(c, s)) {
                        have += c.totals().getOrDefault(itemId, 0L);
                    }
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.id);
                m.put("name", p.name);
                m.put("need", e.getValue());
                m.put("have", have);
                out.add(m);
            }
            return out;
        }
    }
}
