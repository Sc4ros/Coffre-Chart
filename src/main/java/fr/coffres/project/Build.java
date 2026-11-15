package fr.coffres.project;

import fr.coffres.model.Placement;

import java.util.BitSet;
import java.util.Map;

/**
 * A plan placed in the world: which of its blocks are already there. The game fills it (main thread), the site reads it
 * (web threads); every method is safe from any thread. Does not touch the Bukkit API.
 */
public final class Build {

    public final String projectId;
    public final Plan plan;
    public final Placement at;
    /** Box around the build in the world (inclusive), to quickly ignore blocks far from it. */
    public final int minX;
    public final int minY;
    public final int minZ;
    public final int maxX;
    public final int maxY;
    public final int maxZ;

    private final BitSet placed;
    private final long[] placedByState;
    private int placedCount;
    private boolean dirty;
    private long version;

    // Status of the last full check, for the site.
    private volatile boolean scanning;
    private volatile double scanProgress;
    private volatile long lastScan;
    private volatile int unloaded;

    public Build(String projectId, Plan plan, Placement at, BitSet saved) {
        this.projectId = projectId;
        this.plan = plan;
        this.at = at;
        this.placedByState = new long[plan.states.length];
        this.placed = new BitSet(plan.size());
        if (saved != null) {
            for (int k = saved.nextSetBit(0); k >= 0 && k < plan.size(); k = saved.nextSetBit(k + 1)) {
                placed.set(k);
                placedByState[plan.stateOf(k)]++;
                placedCount++;
            }
        }
        int[] a = new int[3];
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int cx = 0; cx < 2; cx++) {
            for (int cy = 0; cy < 2; cy++) {
                for (int cz = 0; cz < 2; cz++) {
                    at.toWorld(plan.ox + cx * (plan.sx - 1), plan.oy + cy * (plan.sy - 1), plan.oz + cz * (plan.sz - 1), a);
                    for (int i = 0; i < 3; i++) {
                        lo[i] = Math.min(lo[i], a[i]);
                        hi[i] = Math.max(hi[i], a[i]);
                    }
                }
            }
        }
        minX = lo[0];
        minY = lo[1];
        minZ = lo[2];
        maxX = hi[0];
        maxY = hi[1];
        maxZ = hi[2];
    }

    public int size() {
        return plan.size();
    }

    public boolean contains(String world, int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ && world.equals(at.world);
    }

    /** World position of block {@code k}. */
    public void worldPos(int k, int[] out) {
        plan.relative(k, out);
        at.toWorld(out[0], out[1], out[2], out);
    }

    /** The plan's block at this world position, or -1. */
    public int blockAt(int x, int y, int z) {
        int[] r = new int[3];
        at.toPlan(x, y, z, r);
        return plan.find(r[0], r[1], r[2]);
    }

    /** Records whether block {@code k} is placed. Returns true when it changed. */
    public synchronized boolean set(int k, boolean on) {
        if (placed.get(k) == on) {
            return false;
        }
        placed.set(k, on);
        int st = plan.stateOf(k);
        placedByState[st] += on ? 1 : -1;
        placedCount += on ? 1 : -1;
        dirty = true;
        version++;
        return true;
    }

    public synchronized boolean isPlaced(int k) {
        return placed.get(k);
    }

    /** Blocks of the plan already in place. */
    public synchronized int placedCount() {
        return placedCount;
    }

    /** Changes every time a block changes (to know when the site needs a refresh). */
    public synchronized long version() {
        return version;
    }

    /** Items already placed, counted like the material list (a double slab gives 2 slabs…). */
    public Map<String, Long> placedItems() {
        long[] copy;
        synchronized (this) {
            copy = placedByState.clone();
        }
        return plan.items(copy);
    }

    /** A copy of the placed blocks to save, or null when nothing changed since the last call. */
    public synchronized BitSet takeDirty() {
        if (!dirty) {
            return null;
        }
        dirty = false;
        return (BitSet) placed.clone();
    }

    public synchronized void markDirty() {
        dirty = true;
    }

    // ------------------------------------------------------------------ check status

    public boolean scanning() {
        return scanning;
    }

    public double scanProgress() {
        return scanProgress;
    }

    public long lastScan() {
        return lastScan;
    }

    /** Blocks that could not be checked during the last full check because their area of the world was not loaded. */
    public int unloaded() {
        return unloaded;
    }

    public void scanStarted() {
        scanning = true;
        scanProgress = 0;
    }

    public void scanProgress(double v) {
        scanProgress = v;
    }

    public void scanFinished(int unloadedBlocks) {
        unloaded = unloadedBlocks;
        lastScan = System.currentTimeMillis();
        scanProgress = 1;
        scanning = false;
    }
}
