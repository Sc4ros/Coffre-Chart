package fr.coffres.model;

/**
 * Where a project's plan is built in the world, the same way Litematica places a schematic:
 * the plan's origin goes to (x, y, z), it is mirrored first, then turned clockwise around that origin.
 * For Litematica the origin is the one shown in the placement menu; for the other formats it is the plan's
 * lowest corner (smallest x, y and z).
 */
public final class Placement {
    public String world;
    public int x;
    public int y;
    public int z;
    /** Clockwise turn in degrees: 0, 90, 180 or 270. */
    public int rot;
    /** "none", "lr" (Litematica "Left-Right": flips z) or "fb" (Litematica "Front-Back": flips x). */
    public String mirror = "none";
    /** Who placed it and when (for the site). */
    public String by;
    public long at;

    public Placement() {
    }

    public Placement(String world, int x, int y, int z, int rot, String mirror) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.rot = normRot(rot);
        this.mirror = normMirror(mirror);
    }

    public static int normRot(int r) {
        int v = ((r % 360) + 360) % 360;
        return v == 90 || v == 180 || v == 270 ? v : 0;
    }

    public static String normMirror(String m) {
        return "lr".equals(m) || "fb".equals(m) ? m : "none";
    }

    /** Plan coordinates (relative to the origin) → world coordinates, written into {@code out}. */
    public void toWorld(int rx, int ry, int rz, int[] out) {
        int a = rx;
        int c = rz;
        if ("lr".equals(mirror)) {
            c = -c;
        } else if ("fb".equals(mirror)) {
            a = -a;
        }
        int wx;
        int wz;
        switch (rot) {
            case 90 -> { wx = -c; wz = a; }
            case 180 -> { wx = -a; wz = -c; }
            case 270 -> { wx = c; wz = -a; }
            default -> { wx = a; wz = c; }
        }
        out[0] = x + wx;
        out[1] = y + ry;
        out[2] = z + wz;
    }

    /** World coordinates → plan coordinates relative to the origin (the exact inverse of {@link #toWorld}). */
    public void toPlan(int wx, int wy, int wz, int[] out) {
        int dx = wx - x;
        int dz = wz - z;
        int a;
        int c;
        switch (rot) {
            case 90 -> { a = dz; c = -dx; }
            case 180 -> { a = -dx; c = -dz; }
            case 270 -> { a = -dz; c = dx; }
            default -> { a = dx; c = dz; }
        }
        if ("lr".equals(mirror)) {
            c = -c;
        } else if ("fb".equals(mirror)) {
            a = -a;
        }
        out[0] = a;
        out[1] = wy - y;
        out[2] = c;
    }
}
