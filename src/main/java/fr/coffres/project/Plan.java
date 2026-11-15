package fr.coffres.project;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Where each block of a plan goes, to check in the world which ones are already placed.
 * Only blocks that need an item are kept (no air, no upper half of a door…), sorted by position.
 * Positions are in a box of {@code sx × sy × sz} whose lowest corner is at {@code (ox, oy, oz)} from the plan's origin.
 * Immutable once built. Does not touch the Bukkit API.
 */
public final class Plan {

    private static final int MAGIC = 0x43504C31; // "CPL1"

    /** Default maximum number of blocks kept for the check (about 60 MB of memory). */
    public static final int DEFAULT_MAX_BLOCKS = 10_000_000;

    public final int sx;
    public final int sy;
    public final int sz;
    public final int ox;
    public final int oy;
    public final int oz;
    /** Full block states, e.g. "minecraft:oak_stairs[facing=north,half=bottom]". */
    public final String[] states;
    /** Block type of each state, without namespace or properties, e.g. "oak_stairs" (what is compared in the world). */
    public final String[] types;
    /** Linear index in the box, (y × sz + z) × sx + x, sorted. */
    final int[] pos;
    /** Index in {@link #states} of each position. */
    final char[] state;

    Plan(int sx, int sy, int sz, int ox, int oy, int oz, String[] states, int[] pos, char[] state) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.states = states;
        this.types = new String[states.length];
        for (int i = 0; i < states.length; i++) {
            types[i] = BlockItems.path(BlockItems.split(states[i])[0]);
        }
        this.pos = pos;
        this.state = state;
    }

    /** Number of blocks to check. */
    public int size() {
        return pos.length;
    }

    public int stateOf(int k) {
        return state[k];
    }

    /** Position of block {@code k} relative to the plan's origin, written into {@code out}. */
    public void relative(int k, int[] out) {
        int i = pos[k];
        out[0] = i % sx + ox;
        out[2] = (i / sx) % sz + oz;
        out[1] = i / (sx * sz) + oy;
    }

    /** The block at this position relative to the origin, or -1 when nothing has to be placed there. */
    public int find(int rx, int ry, int rz) {
        int x = rx - ox;
        int y = ry - oy;
        int z = rz - oz;
        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
            return -1;
        }
        int k = Arrays.binarySearch(pos, (y * sz + z) * sx + x);
        return k >= 0 ? k : -1;
    }

    /** How many blocks of each state there are. */
    public long[] countByState() {
        long[] n = new long[states.length];
        for (char c : state) {
            n[c]++;
        }
        return n;
    }

    /** Items needed for these counts of each state (same rules as the material list). */
    public Map<String, Long> items(long[] countByState) {
        Map<String, Long> out = new HashMap<>();
        for (int i = 0; i < states.length && i < countByState.length; i++) {
            if (countByState[i] > 0) {
                String[] parts = BlockItems.split(states[i]);
                BlockItems.items(parts[0], BlockItems.props(parts[1]), countByState[i], (item, n) -> out.merge(item, n, Long::sum));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ file

    public void write(OutputStream raw) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(raw, 1 << 16)))) {
            out.writeInt(MAGIC);
            out.writeInt(sx);
            out.writeInt(sy);
            out.writeInt(sz);
            out.writeInt(ox);
            out.writeInt(oy);
            out.writeInt(oz);
            out.writeInt(states.length);
            for (String s : states) {
                out.writeUTF(s);
            }
            out.writeInt(pos.length);
            int prev = 0;
            for (int p : pos) {
                varint(out, p - prev);
                prev = p;
            }
            for (char c : state) {
                out.writeChar(c);
            }
        }
    }

    public static Plan read(InputStream raw) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw, 1 << 16)))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Fichier de plan inconnu");
            }
            int sx = in.readInt();
            int sy = in.readInt();
            int sz = in.readInt();
            int ox = in.readInt();
            int oy = in.readInt();
            int oz = in.readInt();
            int ns = in.readInt();
            if (ns < 0 || ns > 65535 || sx <= 0 || sy <= 0 || sz <= 0) {
                throw new IOException("Plan abîmé");
            }
            String[] states = new String[ns];
            for (int i = 0; i < ns; i++) {
                states[i] = in.readUTF();
            }
            int n = in.readInt();
            if (n < 0 || n > 200_000_000) {
                throw new IOException("Plan abîmé");
            }
            int[] pos = new int[n];
            int prev = 0;
            for (int i = 0; i < n; i++) {
                prev += readVarint(in);
                pos[i] = prev;
            }
            char[] state = new char[n];
            for (int i = 0; i < n; i++) {
                state[i] = in.readChar();
                if (state[i] >= ns) {
                    throw new IOException("Plan abîmé");
                }
            }
            return new Plan(sx, sy, sz, ox, oy, oz, states, pos, state);
        }
    }

    private static void varint(DataOutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    private static int readVarint(DataInputStream in) throws IOException {
        int v = 0;
        int shift = 0;
        int b;
        do {
            if (shift > 28) {
                throw new IOException("Plan abîmé");
            }
            b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return v;
    }

    // ------------------------------------------------------------------ building while a schematic is read

    /**
     * Collects the blocks of a schematic as it is read. Coordinates are relative to the plan's origin and must
     * stay inside the box given at creation. When there are too many blocks, it gives up (the counts still work).
     */
    static final class Builder {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sx;
        private final int sy;
        private final int sz;
        private final int max;
        private final List<String> states = new ArrayList<>();
        private final Map<String, Integer> stateIds = new HashMap<>();
        private long[] packed = new long[4096];
        private int n;
        private String failure;

        Builder(int minX, int minY, int minZ, int sx, int sy, int sz, int maxBlocks) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sx = Math.max(1, sx);
            this.sy = Math.max(1, sy);
            this.sz = Math.max(1, sz);
            this.max = maxBlocks;
            if ((long) this.sx * this.sy * this.sz > Integer.MAX_VALUE - 1) {
                fail("Ce plan est trop grand pour vérifier les blocs posés.");
            }
        }

        boolean failed() {
            return failure != null;
        }

        String failure() {
            return failure;
        }

        void fail(String why) {
            if (failure == null) {
                failure = why;
                packed = null;
            }
        }

        /**
         * Id of a block state, or -1 when it does not need an item (air, upper half…) and is not kept.
         * @param state full state, e.g. "minecraft:oak_door[half=lower]"
         */
        int stateId(String state) {
            Integer id = stateIds.get(state);
            if (id != null) {
                return id;
            }
            String[] parts = BlockItems.split(state);
            boolean[] any = {false};
            BlockItems.items(parts[0], BlockItems.props(parts[1]), 1, (item, c) -> any[0] = true);
            int v = -1;
            if (any[0]) {
                if (states.size() >= 65535) {
                    fail("Ce plan a trop de sortes de blocs pour vérifier les blocs posés.");
                } else {
                    v = states.size();
                    states.add(state);
                }
            }
            stateIds.put(state, v);
            return v;
        }

        void add(int rx, int ry, int rz, int stateId) {
            if (stateId < 0 || failure != null) {
                return;
            }
            int x = rx - minX;
            int y = ry - minY;
            int z = rz - minZ;
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
                return;
            }
            if (n >= max) {
                fail("Ce plan a plus de " + (max >= 1_000_000 ? (max / 1_000_000) + " millions de" : String.valueOf(max))
                        + " blocs : la vérification des blocs posés est désactivée pour lui.");
                return;
            }
            if (n == packed.length) {
                packed = Arrays.copyOf(packed, (int) Math.min(Integer.MAX_VALUE - 16, packed.length * 2L));
            }
            long index = ((long) y * sz + z) * sx + x;
            packed[n++] = (index << 16) | stateId;
        }

        Plan build() {
            if (failure != null) {
                return null;
            }
            long[] p = Arrays.copyOf(packed, n);
            packed = null;
            Arrays.sort(p);
            int[] pos = new int[n];
            char[] st = new char[n];
            int m = 0;
            for (long v : p) {
                int index = (int) (v >>> 16);
                if (m > 0 && pos[m - 1] == index) {
                    continue; // two regions on the same block: keep one
                }
                pos[m] = index;
                st[m] = (char) (v & 0xFFFF);
                m++;
            }
            if (m == 0) {
                return null;
            }
            return new Plan(sx, sy, sz, minX, minY, minZ, states.toArray(String[]::new),
                    Arrays.copyOf(pos, m), Arrays.copyOf(st, m));
        }
    }
}
