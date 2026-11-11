package fr.coffres.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A container registered in Coffres, with the last known contents. */
public final class StoredChest {
    public String id;
    public String world;
    /** "overworld", "nether", "end" or "custom". */
    public String dim;
    public int x;
    public int y;
    public int z;
    /** Block id, e.g. "chest", "barrel", "white_shulker_box". */
    public String type;
    /** Double chest. */
    public boolean dbl;
    public String name;
    public String zone;
    public String owner;
    public String ownerName;
    /** Visible to every player on the site. */
    public boolean pub = true;
    public long created;
    /** Last time the contents changed. */
    public long updated;
    /** Number of slots. */
    public int size = 27;
    public String lastPlayer;
    public List<SlotItem> slots = new ArrayList<>();

    private transient Map<String, Long> totals;

    public String locKey() {
        return locKey(world, x, y, z);
    }

    public static String locKey(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }

    /** Count of every item id in this container (shulker/bundle contents included when enabled). */
    public Map<String, Long> totals() {
        if (totals == null) {
            totals = computeTotals(slots, true);
        }
        return totals;
    }

    public void setSlots(List<SlotItem> newSlots, boolean countNested) {
        this.slots = newSlots;
        this.totals = computeTotals(newSlots, countNested);
    }

    public void recompute(boolean countNested) {
        this.totals = computeTotals(slots, countNested);
    }

    public long itemCount() {
        long sum = 0;
        for (long v : totals().values()) {
            sum += v;
        }
        return sum;
    }

    public static Map<String, Long> computeTotals(List<SlotItem> slots, boolean countNested) {
        Map<String, Long> map = new HashMap<>();
        if (slots == null) {
            return map;
        }
        for (SlotItem it : slots) {
            add(map, it, 1, countNested, 0);
        }
        return map;
    }

    private static void add(Map<String, Long> map, SlotItem it, long mult, boolean nested, int depth) {
        if (it == null || it.id == null || it.n <= 0) {
            return;
        }
        long count = (long) it.n * mult;
        map.merge(it.id, count, Long::sum);
        if (nested && it.in != null && depth < 3) {
            for (SlotItem inner : it.in) {
                add(map, inner, count, true, depth + 1);
            }
        }
    }
}
