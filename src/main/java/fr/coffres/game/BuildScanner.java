package fr.coffres.game;

import fr.coffres.CoffresPlugin;
import fr.coffres.Msg;
import fr.coffres.model.Project;
import fr.coffres.project.Build;
import fr.coffres.project.ProjectProgress;
import fr.coffres.store.PlanStore;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Checks in the world which blocks of the placed projects are already built. Everything runs on the main thread:
 * <ul>
 *   <li>a full check goes through every block of the plan, a few thousand per tick, when the project is placed,
 *       when someone asks for it, and every few minutes;</li>
 *   <li>between two full checks, blocks placed, broken, burnt, blown up, pushed… near a project are checked again
 *       one tick later.</li>
 * </ul>
 * Areas of the world that are not loaded are never loaded on purpose: their blocks keep their last known state.
 */
public final class BuildScanner implements Listener {

    private final CoffresPlugin plugin;
    private final PlanStore plans;
    /** Block types of each plan state, per project (main thread only). */
    private final Map<String, Material[]> materials = new HashMap<>();
    private final Map<String, Scan> scans = new LinkedHashMap<>();
    private final ArrayDeque<long[]> dirty = new ArrayDeque<>();
    private List<Build> active = List.of();
    private int blocksPerTick = 20_000;
    private long rescanMillis = 10 * 60_000L;
    private boolean countInventories = true;
    private int invTicks;
    private long nextRescan;
    private boolean changed;
    private int ticks;

    private static final class Scan {
        final Build build;
        final Set<UUID> notify = new HashSet<>();
        int cursor;
        int unloaded;

        Scan(Build build) {
            this.build = build;
        }
    }

    public BuildScanner(CoffresPlugin plugin, PlanStore plans) {
        this.plugin = plugin;
        this.plans = plans;
    }

    public void configure(int blocksPerTick, int rescanMinutes, boolean countInventories) {
        this.countInventories = countInventories;
        this.blocksPerTick = Math.max(500, blocksPerTick);
        this.rescanMillis = rescanMinutes <= 0 ? Long.MAX_VALUE : rescanMinutes * 60_000L;
        this.nextRescan = System.currentTimeMillis() + Math.min(rescanMillis, 60_000L);
    }

    /** Starts (or restarts) a full check of a project. Main thread. */
    public void rescan(String projectId, UUID who) {
        Build b = plans.build(projectId);
        if (b == null) {
            return;
        }
        Scan s = scans.get(projectId);
        if (s == null || s.build != b) {
            Scan old = s;
            s = new Scan(b);
            if (old != null) {
                s.notify.addAll(old.notify);
            }
            scans.put(projectId, s);
            b.scanStarted();
        }
        if (who != null) {
            s.notify.add(who);
        }
        plugin.store().bump();
    }

    /** Called every tick on the main thread. */
    public void tick() {
        if (++invTicks >= 20) {
            invTicks = 0;
            inventories();
        }
        active = plans.builds();
        if (active.isEmpty()) {
            dirty.clear();
            scans.clear();
            return;
        }
        int budget = blocksPerTick;
        budget -= processDirty(Math.min(budget / 2, 5_000));
        if (System.currentTimeMillis() >= nextRescan) {
            nextRescan = System.currentTimeMillis() + rescanMillis;
            for (Build b : active) {
                if (!scans.containsKey(b.projectId)) {
                    rescan(b.projectId, null);
                }
            }
        }
        Iterator<Scan> it = scans.values().iterator();
        while (it.hasNext() && budget > 0) {
            Scan s = it.next();
            if (plans.build(s.build.projectId) != s.build) {
                it.remove(); // removed or placed elsewhere meanwhile
                continue;
            }
            budget -= step(s, budget);
            if (s.cursor >= s.build.size()) {
                it.remove();
                s.build.scanFinished(s.unloaded);
                changed = true;
                finished(s);
            }
        }
        if (++ticks >= 20) {
            ticks = 0;
            if (changed) {
                changed = false;
                plugin.store().bump();
            }
        }
    }

    private int step(Scan s, int budget) {
        Build b = s.build;
        World w = Bukkit.getWorld(b.at.world);
        int n = b.size();
        if (w == null) {
            s.unloaded = n;
            s.cursor = n;
            return 1;
        }
        Material[] mats = materials(b);
        int[] p = new int[3];
        int done = 0;
        int lastCx = Integer.MIN_VALUE;
        int lastCz = Integer.MIN_VALUE;
        boolean loaded = false;
        while (s.cursor < n && done < budget) {
            int k = s.cursor++;
            done++;
            b.worldPos(k, p);
            int cx = p[0] >> 4;
            int cz = p[2] >> 4;
            if (cx != lastCx || cz != lastCz) {
                lastCx = cx;
                lastCz = cz;
                loaded = w.isChunkLoaded(cx, cz);
            }
            if (!loaded) {
                s.unloaded++;
                continue;
            }
            if (check(b, w, k, p, mats)) {
                changed = true;
            }
        }
        b.scanProgress((double) s.cursor / Math.max(1, n));
        return done;
    }

    private boolean check(Build b, World w, int k, int[] p, Material[] mats) {
        if (p[1] < w.getMinHeight() || p[1] >= w.getMaxHeight()) {
            return b.set(k, false);
        }
        Material want = mats[b.plan.stateOf(k)];
        return b.set(k, want != null && w.getBlockAt(p[0], p[1], p[2]).getType() == want);
    }

    private Material[] materials(Build b) {
        Material[] m = materials.get(b.projectId);
        if (m == null || m.length != b.plan.types.length) {
            m = new Material[b.plan.types.length];
            for (int i = 0; i < m.length; i++) {
                m[i] = Material.matchMaterial(b.plan.types[i]);
            }
            materials.put(b.projectId, m);
        }
        return m;
    }

    private void finished(Scan s) {
        if (s.notify.isEmpty()) {
            return;
        }
        Project pr = plugin.projects().get(s.build.projectId);
        if (pr == null) {
            return;
        }
        for (UUID u : s.notify) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) {
                continue;
            }
            ProjectProgress progress = plugin.projectsGame().progress(p, pr);
            Msg.send(p, "<gray>Vérification de</gray> <light_purple><project></light_purple> <gray>terminée :</gray> "
                            + "<green><placed> % posé</green> <dark_gray>·</dark_gray> <aqua><chests> % coffres</aqua> "
                            + "<dark_gray>·</dark_gray> <gold><inv> % inventaires</gold> <dark_gray>·</dark_gray> <white><pct> % au total</white>",
                    Placeholder.unparsed("project", pr.name),
                    Placeholder.unparsed("placed", String.valueOf(progress.placedPercent())),
                    Placeholder.unparsed("chests", String.valueOf(progress.chestPercent())),
                    Placeholder.unparsed("inv", String.valueOf(progress.carriedPercent())),
                    Placeholder.unparsed("pct", String.valueOf(progress.percent())));
            if (s.unloaded > 0) {
                Msg.raw(p, "  <dark_gray>" + Msg.number(s.unloaded) + " bloc(s) dans des zones non chargées n'ont pas pu être vérifiés : "
                        + "approche-toi de la construction puis relance</dark_gray> <white>/coffres projet verifier <name></white>",
                        Placeholder.unparsed("name", pr.name));
            }
        }
    }

    // ------------------------------------------------------------------ inventories of the members

    /**
     * Every second: the items of each project carried by its members online, wherever they are and whether the
     * project is placed in the world or not. The whole inventory is read, with the off hand and what is inside the
     * shulker boxes and bundles it holds.
     */
    private void inventories() {
        boolean any = false;
        // Each player is read once, then counted for every project they are a member of.
        Map<String, Map<String, Long>> carriedBy = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        if (countInventories) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                    continue;
                }
                org.bukkit.inventory.PlayerInventory inv = p.getInventory();
                org.bukkit.inventory.ItemStack[] storage = inv.getStorageContents();
                org.bukkit.inventory.ItemStack[] all = java.util.Arrays.copyOf(storage, storage.length + 1);
                all[storage.length] = inv.getItemInOffHand();
                Map<String, Long> totals = fr.coffres.model.StoredChest.computeTotals(
                        fr.coffres.track.Snapshot.read(all, true, null), true);
                if (!totals.isEmpty()) {
                    String uuid = p.getUniqueId().toString();
                    carriedBy.put(uuid, totals);
                    names.put(uuid, p.getName());
                }
            }
        }
        Set<String> ids = new HashSet<>();
        for (Project pr : plugin.projects().all()) {
            ids.add(pr.id);
            Map<String, Long> sum = new HashMap<>();
            List<Build.Carrier> who = new ArrayList<>();
            // Shared project: everyone. Group project: the group. Solo: the creator.
            Set<String> people = carriedBy.isEmpty() ? Set.of() : plugin.projectsGame().people(pr);
            for (Map.Entry<String, Map<String, Long>> player : carriedBy.entrySet()) {
                if (people != null && !people.contains(player.getKey())) {
                    continue;
                }
                long mine = 0;
                for (Map.Entry<String, Long> e : player.getValue().entrySet()) {
                    if (pr.materials.containsKey(e.getKey())) {
                        sum.merge(e.getKey(), e.getValue(), Long::sum);
                        mine += e.getValue();
                    }
                }
                if (mine > 0) {
                    who.add(new Build.Carrier(names.get(player.getKey()), mine));
                }
            }
            who.sort((a, c) -> Long.compare(c.items(), a.items()));
            any |= plans.setCarried(pr.id, sum, who);
        }
        any |= plans.keepCarried(ids);
        if (any) {
            plugin.store().bump();
        }
    }

    // ------------------------------------------------------------------ live changes

    /** Blocks changed this tick are checked on the next one, once the change is done. */
    private int processDirty(int max) {
        int done = 0;
        int[] p = new int[3];
        Map<String, World> worlds = new HashMap<>();
        while (!dirty.isEmpty() && done < max) {
            long[] d = dirty.poll();
            done++;
            String worldName = dirtyWorld(d[3]);
            if (worldName == null) {
                continue;
            }
            int x = (int) d[0];
            int y = (int) d[1];
            int z = (int) d[2];
            for (Build b : active) {
                if (!b.contains(worldName, x, y, z)) {
                    continue;
                }
                int k = b.blockAt(x, y, z);
                if (k < 0) {
                    continue;
                }
                World w = worlds.computeIfAbsent(worldName, Bukkit::getWorld);
                if (w == null) {
                    continue;
                }
                p[0] = x;
                p[1] = y;
                p[2] = z;
                if (check(b, w, k, p, materials(b))) {
                    changed = true;
                }
            }
        }
        if (dirty.isEmpty()) {
            worldIds.clear();
        }
        return done;
    }

    private final List<String> worldIds = new ArrayList<>();

    private long worldId(String name) {
        int i = worldIds.indexOf(name);
        if (i < 0) {
            worldIds.add(name);
            i = worldIds.size() - 1;
        }
        return i;
    }

    private String dirtyWorld(long id) {
        return id >= 0 && id < worldIds.size() ? worldIds.get((int) id) : null;
    }

    private void touch(Block block) {
        if (active.isEmpty() || dirty.size() > 200_000) {
            return;
        }
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        for (Build b : active) {
            // one block around, for doors, beds and other two-block things whose other half changes too
            if (x >= b.minX - 1 && x <= b.maxX + 1 && y >= b.minY - 1 && y <= b.maxY + 1 && z >= b.minZ - 1 && z <= b.maxZ + 1
                    && world.equals(b.at.world)) {
                long wid = worldId(world);
                for (int dy = -1; dy <= 1; dy++) {
                    dirty.add(new long[]{x, y + dy, z, wid});
                }
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        touch(e.getBlockPlaced());
        if (e instanceof BlockMultiPlaceEvent m) {
            for (BlockState s : m.getReplacedBlockStates()) {
                touch(s.getBlock());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        touch(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        touch(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        touch(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        touch(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        touch(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        touch(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        touch(e.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        touch(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        touch(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) {
            touch(b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) {
            touch(b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent e) {
        piston(e.getBlock(), e.getBlocks(), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent e) {
        piston(e.getBlock(), e.getBlocks(), e.getDirection());
    }

    private void piston(Block piston, List<Block> moved, BlockFace dir) {
        touch(piston);
        touch(piston.getRelative(dir));
        for (Block b : moved) {
            touch(b);
            touch(b.getRelative(dir));
        }
    }

    /** Short status for /coffres projet: "en cours (42 %)" or null. */
    public String status(String projectId) {
        Scan s = scans.get(projectId);
        if (s == null) {
            return null;
        }
        return "en cours (" + (int) (100.0 * s.cursor / Math.max(1, s.build.size())) + " %)";
    }
}
