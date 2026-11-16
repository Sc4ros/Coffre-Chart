package fr.coffres;

import fr.coffres.model.Placement;
import fr.coffres.model.Project;
import fr.coffres.model.SlotItem;
import fr.coffres.model.StoredChest;
import fr.coffres.project.Build;
import fr.coffres.project.Plan;
import fr.coffres.project.ProjectProgress;
import fr.coffres.project.SchematicReader;
import fr.coffres.store.PlanStore;
import fr.coffres.store.ProjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Blocks already placed in the world: where each block of a plan goes, turning it, and the progress. */
class BuildTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final long LIMIT = 64L * 1024 * 1024;

    @TempDir
    Path dir;

    private static SchematicReader.Schematic example(String name) throws Exception {
        return SchematicReader.read(Files.readAllBytes(Path.of("docs", "exemples", name)), LIMIT);
    }

    @Test
    void turningAndMirroringCanBeUndone() {
        int[] w = new int[3];
        int[] back = new int[3];
        for (int rot : new int[]{0, 90, 180, 270}) {
            for (String mirror : new String[]{"none", "lr", "fb"}) {
                Placement at = new Placement("world", 100, 64, -20, rot, mirror);
                for (int[] r : new int[][]{{0, 0, 0}, {3, 1, 7}, {-2, 5, 4}, {9, -1, -6}}) {
                    at.toWorld(r[0], r[1], r[2], w);
                    at.toPlan(w[0], w[1], w[2], back);
                    assertArrayEquals(r, back, "rotation " + rot + ", miroir " + mirror);
                }
            }
        }
        // Like Minecraft and Litematica: a quarter turn clockwise sends +x (east) to +z (south).
        Placement quarter = new Placement("world", 0, 0, 0, 90, "none");
        quarter.toWorld(1, 0, 0, w);
        assertArrayEquals(new int[]{0, 0, 1}, w);
        quarter.toWorld(0, 0, 1, w);
        assertArrayEquals(new int[]{-1, 0, 0}, w);
        Placement lr = new Placement("world", 0, 0, 0, 0, "lr");
        lr.toWorld(2, 0, 3, w);
        assertArrayEquals(new int[]{2, 0, -3}, w, "Left-Right flips z");
    }

    @Test
    void examplePlansKeepEveryBlock() throws Exception {
        for (String name : List.of("petite_maison.litematic", "petite_maison.schem")) {
            SchematicReader.Schematic s = example(name);
            assertNotNull(s.plan(), name);
            assertNull(s.planNote(), name);
            Plan plan = s.plan();
            // Counting the kept blocks gives exactly the material list.
            assertEquals(s.materials(), plan.items(plan.countByState()), name);
            assertEquals(411, plan.items(plan.countByState()).values().stream().mapToLong(Long::longValue).sum(), name);

            // Saved and read back, it is the same plan.
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            plan.write(bos);
            Plan again = Plan.read(new ByteArrayInputStream(bos.toByteArray()));
            assertEquals(plan.size(), again.size());
            assertEquals(s.materials(), again.items(again.countByState()));
            int[] a = new int[3];
            int[] b = new int[3];
            for (int k = 0; k < plan.size(); k++) {
                plan.relative(k, a);
                again.relative(k, b);
                assertArrayEquals(a, b);
                assertEquals(k, plan.find(a[0], a[1], a[2]));
            }
        }
    }

    @Test
    void bothExampleFormatsPutTheSameBlocksAtTheSamePlaces() throws Exception {
        Plan lit = example("petite_maison.litematic").plan();
        Plan schem = example("petite_maison.schem").plan();
        assertEquals(lit.size(), schem.size());
        int[] a = new int[3];
        int[] b = new int[3];
        // Both are anchored at their lowest corner here.
        Set<String> la = new HashSet<>();
        Set<String> lb = new HashSet<>();
        for (int k = 0; k < lit.size(); k++) {
            lit.relative(k, a);
            la.add((a[0] - lit.ox) + "," + (a[1] - lit.oy) + "," + (a[2] - lit.oz) + "=" + lit.types[lit.stateOf(k)]);
            schem.relative(k, b);
            lb.add((b[0] - schem.ox) + "," + (b[1] - schem.oy) + "," + (b[2] - schem.oz) + "=" + schem.types[schem.stateOf(k)]);
        }
        assertEquals(la, lb);
    }

    @Test
    void placedBlocksAreFoundAtTheirWorldPosition() throws Exception {
        Plan plan = example("petite_maison.litematic").plan();
        Build b = new Build("p1", plan, new Placement("world", 500, 70, -300, 270, "fb"), null);
        int[] p = new int[3];
        for (int k = 0; k < plan.size(); k++) {
            b.worldPos(k, p);
            assertTrue(b.contains("world", p[0], p[1], p[2]));
            assertEquals(k, b.blockAt(p[0], p[1], p[2]));
        }
        assertEquals(-1, b.blockAt(b.maxX + 1, b.minY, b.minZ));
        for (int k = 0; k < plan.size(); k += 2) {
            b.set(k, true);
        }
        assertEquals((plan.size() + 1) / 2, b.placedCount());
        long items = b.placedItems().values().stream().mapToLong(Long::longValue).sum();
        assertTrue(items > 0 && items <= 411);
        b.set(0, false);
        assertEquals((plan.size() + 1) / 2 - 1, b.placedCount());
    }

    @Test
    void placedBlocksAreNoLongerAskedInTheChests() {
        Project p = new Project();
        p.id = "p1";
        p.materials.put("oak_planks", 100L);
        p.materials.put("glass", 20L);
        p.chests.add("c1");
        StoredChest c = new StoredChest();
        c.id = "c1";
        c.pub = true;
        c.slots = new ArrayList<>(List.of(new SlotItem(0, "oak_planks", 64), new SlotItem(1, "glass", 5)));
        // 40 planks and 20 glass already built: 60 planks still to place, 64 in the chest → planks done.
        ProjectProgress pr = ProjectProgress.of(p, List.of(c), x -> true, Map.of(), Map.of("oak_planks", 40L, "glass", 25L));
        assertEquals(120, pr.need);
        assertEquals(60, pr.placed, "never more than the plan needs");
        assertEquals(60, pr.have, "the chest only counts for what is still to place");
        assertEquals(0, pr.missing());
        assertEquals(100, pr.percent());
        assertEquals(50, pr.placedPercent());
        assertEquals(50, pr.chestPercent());
        ProjectProgress.Line glass = pr.lines.get(1);
        assertEquals(0, glass.useful(), "glass is all placed: the 5 in the chest are extra");
        assertTrue(glass.done());

        // Glass taken out of the chest and carried: 3 still to find, 2 in a pocket (10 more carried are extra).
        Project q = new Project();
        q.materials.put("glass", 10L);
        ProjectProgress inv = ProjectProgress.of(q, List.of(), x -> true, Map.of(), Map.of("glass", 5L), Map.of("glass", 12L));
        assertEquals(5, inv.placed);
        assertEquals(5, inv.carried, "the inventories only count for what is still to place");
        assertEquals(0, inv.missing());
        assertEquals(50, inv.placedPercent());
        assertEquals(0, inv.chestPercent());
        assertEquals(50, inv.carriedPercent());

        ProjectProgress without = ProjectProgress.of(p, List.of(c), x -> true, Map.of());
        assertEquals(0, without.placed);
        assertEquals(69, without.have);
        assertEquals(51, without.missing());
    }

    @Test
    void planStoreKeepsWhatIsBuiltForTheSamePlacementOnly() throws Exception {
        SchematicReader.Schematic s = example("petite_maison.schem");
        ProjectStore projects = new ProjectStore(dir.resolve("projects.json"), LOG, null);
        PlanStore plans = new PlanStore(dir.resolve("plans"), LOG);
        Project p = projects.create("Maison", "u", "U", true, "petite_maison.schem", s);
        assertEquals(s.plan().size(), p.planBlocks);
        plans.savePlan(p.id, s.plan());
        assertNull(plans.activate(p), "not placed yet");

        projects.setPlacement(p.id, new Placement("world", 10, 64, 10, 180, "none"));
        Build b = plans.activate(projects.get(p.id));
        assertNotNull(b);
        b.set(3, true);
        b.set(7, true);
        plans.save();

        PlanStore reopened = new PlanStore(dir.resolve("plans"), LOG);
        Build again = reopened.activate(projects.get(p.id));
        assertEquals(2, again.placedCount(), "what was built is kept across restarts");
        assertTrue(again.isPlaced(7));

        projects.setPlacement(p.id, new Placement("world", 11, 64, 10, 180, "none"));
        assertEquals(0, reopened.activate(projects.get(p.id)).placedCount(), "moved elsewhere: checked again from scratch");

        reopened.delete(p.id);
        assertTrue(!Files.exists(dir.resolve("plans").resolve(p.id + ".plan")));
    }
}
