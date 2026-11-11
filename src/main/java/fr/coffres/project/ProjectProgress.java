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

    public record Line(String id, long need, long have, long elsewhere, int stack) {
        public long missing() {
            return Math.max(0, need - have);
        }

        public boolean done() {
            return have >= need;
        }

        /** What counts towards the progress (extra items beyond the need do not). */
        public long useful() {
            return Math.min(have, need);
        }
    }

    public final List<Line> lines;
    public final long need;
    public final long have;
    public final int doneKinds;
    /** Linked containers the viewer can see. */
    public final List<StoredChest> linked;
    /** Linked containers the viewer cannot see (private containers of other players), not counted. */
    public final int hiddenLinked;

    private ProjectProgress(List<Line> lines, long need, long have, int doneKinds, List<StoredChest> linked, int hiddenLinked) {
        this.lines = lines;
        this.need = need;
        this.have = have;
        this.doneKinds = doneKinds;
        this.linked = linked;
        this.hiddenLinked = hiddenLinked;
    }

    public long missing() {
        return Math.max(0, need - have);
    }

    /** 0 to 1. */
    public double ratio() {
        return need == 0 ? 1 : (double) have / need;
    }

    /** Whole percent, rounded down so that 100 % really means "everything is there". */
    public int percent() {
        return (int) Math.floor(ratio() * 100 + 1e-9);
    }

    /**
     * @param chests     every registered container
     * @param visible    which containers the viewer may see
     * @param stackSizes item id → stack size seen in game (missing ids use a sensible default)
     */
    public static ProjectProgress of(Project p, Collection<StoredChest> chests, Predicate<StoredChest> visible,
                                     Map<String, Integer> stackSizes) {
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
        long have = 0;
        int done = 0;
        for (Map.Entry<String, Long> m : p.materials.entrySet()) {
            String id = m.getKey();
            Integer seen = stackSizes == null ? null : stackSizes.get(id);
            Line l = new Line(id, m.getValue(), inLinked.getOrDefault(id, 0L), elsewhere.getOrDefault(id, 0L),
                    seen != null && seen > 0 ? seen : defaultStack(id));
            lines.add(l);
            need += l.need();
            have += l.useful();
            if (l.done()) {
                done++;
            }
        }
        return new ProjectProgress(lines, need, have, done, linked, hidden);
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
