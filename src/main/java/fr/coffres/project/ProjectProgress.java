package fr.coffres.project;

import fr.coffres.model.Project;
import fr.coffres.model.StoredChest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * How far a project is: for each material, how many are needed, how many are already in the linked containers
 * and how many more sit in other containers. Only containers the viewer may see are counted, so a private
 * container never leaks its contents through a shared project.
 */
public final class ProjectProgress {

    /**
     * @param placed  already placed in the world (at most {@code need})
     * @param have    in the linked containers (may be more than what is still needed)
     * @param carried in the inventories of the players near the build (may be more than what is still needed)
     */
    public record Line(String id, long need, long placed, long have, long carried, long elsewhere, int stack) {
        /** Still to find once what is placed, in the containers and in the inventories is counted. */
        public long missing() {
            return Math.max(0, need - placed - useful() - inInventories());
        }

        /** What the inventories bring, after what is placed and what is in the containers. */
        public long inInventories() {
            return Math.max(0, Math.min(carried, need - placed - useful()));
        }

        public boolean done() {
            return missing() == 0;
        }

        /** What the containers bring to the progress (items beyond what is still to place do not count). */
        public long useful() {
            return Math.max(0, Math.min(have, need - placed));
        }

        /** Still to place in the world (with or without the containers). */
        public long toPlace() {
            return Math.max(0, need - placed);
        }
    }

    public final List<Line> lines;
    public final long need;
    /** Already placed in the world. */
    public final long placed;
    /** In the linked containers, only what is still to place. */
    public final long have;
    /** In the inventories of the players near the build, only what is still needed. */
    public final long carried;
    public final int doneKinds;
    /** Linked containers the viewer can see. */
    public final List<StoredChest> linked;
    /** Linked containers the viewer cannot see (private containers of other players), not counted. */
    public final int hiddenLinked;

    private ProjectProgress(List<Line> lines, long need, long placed, long have, long carried, int doneKinds,
                            List<StoredChest> linked, int hiddenLinked) {
        this.lines = lines;
        this.need = need;
        this.placed = placed;
        this.have = have;
        this.carried = carried;
        this.doneKinds = doneKinds;
        this.linked = linked;
        this.hiddenLinked = hiddenLinked;
    }

    public long missing() {
        return Math.max(0, need - placed - have - carried);
    }

    /** Placed + in the containers + in the inventories, 0 to 1. */
    public double ratio() {
        return need == 0 ? 1 : (double) (placed + have + carried) / need;
    }

    /** Whole percent, rounded down so that 100 % really means "everything is there". */
    public int percent() {
        return pct(ratio());
    }

    public double placedRatio() {
        return need == 0 ? 0 : (double) placed / need;
    }

    public int placedPercent() {
        return pct(placedRatio());
    }

    public double chestRatio() {
        return need == 0 ? 0 : (double) have / need;
    }

    public double carriedRatio() {
        return need == 0 ? 0 : (double) carried / need;
    }

    /** What the containers bring, so that placed % + containers % + inventories % = percent. */
    public int chestPercent() {
        return Math.max(0, pct(placedRatio() + chestRatio()) - placedPercent());
    }

    public int carriedPercent() {
        return Math.max(0, percent() - placedPercent() - chestPercent());
    }

    private static int pct(double r) {
        return (int) Math.floor(r * 100 + 1e-9);
    }

    /**
     * @param chests     every registered container
     * @param visible    which containers the viewer may see
     * @param stackSizes item id → stack size seen in game (missing ids use a sensible default)
     */
    public static ProjectProgress of(Project p, Collection<StoredChest> chests, Predicate<StoredChest> visible,
                                     Map<String, Integer> stackSizes) {
        return of(p, chests, visible, stackSizes, null, null);
    }

    public static ProjectProgress of(Project p, Collection<StoredChest> chests, Predicate<StoredChest> visible,
                                     Map<String, Integer> stackSizes, Map<String, Long> placedItems) {
        return of(p, chests, visible, stackSizes, placedItems, null);
    }

    /**
     * @param placedItems  items already placed in the world (from the {@link Build}), or null when the plan is not placed
     * @param carriedItems items in the inventories of the players near the build, or null
     */
    public static ProjectProgress of(Project p, Collection<StoredChest> chests, Predicate<StoredChest> visible,
                                     Map<String, Integer> stackSizes, Map<String, Long> placedItems,
                                     Map<String, Long> carriedItems) {
        Set<String> linkedIds = new HashSet<>(p.chests);
        Map<String, Long> inLinked = new HashMap<>();
        Map<String, Long> elsewhere = new HashMap<>();
        List<StoredChest> linked = new ArrayList<>();
        int hidden = 0;
        for (StoredChest c : chests) {
            boolean isLinked = linkedIds.contains(c.id);
            if (!visible.test(c)) {
                if (isLinked) {
                    hidden++;
                }
                continue;
            }
            if (isLinked) {
                linked.add(c);
            }
            Map<String, Long> target = isLinked ? inLinked : elsewhere;
            for (Map.Entry<String, Long> e : c.totals().entrySet()) {
                if (p.materials.containsKey(e.getKey())) {
                    target.merge(e.getKey(), e.getValue(), Long::sum);
                }
            }
        }
        List<Line> lines = new ArrayList<>(p.materials.size());
        long need = 0;
        long placed = 0;
        long have = 0;
        long carried = 0;
        int done = 0;
        for (Map.Entry<String, Long> m : p.materials.entrySet()) {
            String id = m.getKey();
            Integer seen = stackSizes == null ? null : stackSizes.get(id);
            long built = placedItems == null ? 0 : Math.min(m.getValue(), placedItems.getOrDefault(id, 0L));
            long inv = carriedItems == null ? 0 : carriedItems.getOrDefault(id, 0L);
            Line l = new Line(id, m.getValue(), built, inLinked.getOrDefault(id, 0L), inv, elsewhere.getOrDefault(id, 0L),
                    seen != null && seen > 0 ? seen : defaultStack(id));
            lines.add(l);
            need += l.need();
            placed += l.placed();
            have += l.useful();
            carried += l.inInventories();
            if (l.done()) {
                done++;
            }
        }
        return new ProjectProgress(lines, need, placed, have, carried, done, linked, hidden);
    }

    /** How many items of the plan this container holds (to suggest which containers to link). */
    public static long useful(Project p, StoredChest c) {
        long n = 0;
        for (Map.Entry<String, Long> e : c.totals().entrySet()) {
            if (p.materials.containsKey(e.getKey())) {
                n += e.getValue();
            }
        }
        return n;
    }

    /** Stack size when the item was never seen in a container yet. */
    public static int defaultStack(String id) {
        if (id.endsWith("_bucket") || id.endsWith("_bed") || id.endsWith("shulker_box") || id.equals("cake")) {
            return 1;
        }
        if (id.endsWith("_sign") || id.endsWith("_banner") || id.equals("ender_pearl") || id.equals("snowball")
                || id.equals("egg") || id.equals("honey_bottle") || id.equals("armor_stand")) {
            return 16;
        }
        return 64;
    }
}
