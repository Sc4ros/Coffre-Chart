package fr.coffres.project;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a building plan and counts the items needed to build it. The format is recognized from the content,
 * not the file name:
 * <ul>
 *   <li><b>Litematica</b> (.litematic), every version, every region;</li>
 *   <li><b>Sponge / WorldEdit</b> (.schem), versions 1, 2 and 3;</li>
 *   <li><b>MCEdit / old WorldEdit</b> (.schematic, Minecraft 1.12 and before);</li>
 *   <li><b>Structure blocks / Create</b> (.nbt).</li>
 * </ul>
 * Only blocks are counted: the contents of chests, item frames and other entities in the plan are not.
 * This class does not touch the Bukkit API (it is tested and used by the demo site without Minecraft).
 */
public final class SchematicReader {

    /** What a plan needs. {@code materials} is sorted, most needed first. */
    public record Schematic(String format, String name, String author, int sizeX, int sizeY, int sizeZ,
                            long blocks, Map<String, Long> materials) {
    }

    /** Biggest plan accepted, in blocks (a 700 × 384 × 1 500 build). */
    public static final long MAX_VOLUME = 400_000_000L;

    private SchematicReader() {
    }

    /**
     * @param file             the file as sent by the player
     * @param maxUnpackedBytes refuse files bigger than this once decompressed
     */
    public static Schematic read(byte[] file, long maxUnpackedBytes) throws IOException {
        Map<String, Object> root = Nbt.read(file, maxUnpackedBytes);
        if (Nbt.compound(root, "Regions") != null) {
            return litematic(root);
        }
        Map<String, Object> wrapped = Nbt.compound(root, "Schematic");
        if (wrapped != null) {
            return sponge(wrapped);
        }
        if (Nbt.compound(root, "Blocks") != null || Nbt.compound(root, "Palette") != null) {
            return sponge(root);
        }
        if (root.get("Blocks") instanceof byte[]) {
            return legacy(root);
        }
        if (Nbt.list(root, "blocks") != null && (Nbt.list(root, "palette") != null || Nbt.list(root, "palettes") != null)) {
            return structure(root);
        }
        throw new Nbt.NbtException("Ce fichier n'est pas un schéma reconnu (.litematic, .schem, .schematic ou .nbt).");
    }

    // ------------------------------------------------------------------ Litematica

    private static Schematic litematic(Map<String, Object> root) throws IOException {
        Map<String, Object> meta = Nbt.compound(root, "Metadata");
        Map<String, Object> regions = Nbt.compound(root, "Regions");
        if (regions.size() > 4096) {
            throw new Nbt.NbtException("Ce schéma a trop de régions.");
        }
        Counter out = new Counter();
        long total = 0;
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Object value : regions.values()) {
            if (!(value instanceof Map<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> region = (Map<String, Object>) value;
            Map<String, Object> size = Nbt.compound(region, "Size");
            Map<String, Object> pos = Nbt.compound(region, "Position");
            List<Object> palette = Nbt.list(region, "BlockStatePalette");
            Object rawStates = region.get("BlockStates");
            if (size == null || palette == null || palette.isEmpty() || !(rawStates instanceof long[] states)) {
                continue;
            }
            long sx = Nbt.number(size, "x", 0);
            long sy = Nbt.number(size, "y", 0);
            long sz = Nbt.number(size, "z", 0);
            long volume = Math.abs(sx) * Math.abs(sy) * Math.abs(sz);
            if (volume == 0) {
                continue;
            }
            total += volume;
            if (total > MAX_VOLUME) {
                throw tooBig();
            }
            if (pos != null) {
                grow(box, Nbt.number(pos, "x", 0), sx, 0);
                grow(box, Nbt.number(pos, "y", 0), sy, 1);
                grow(box, Nbt.number(pos, "z", 0), sz, 2);
            }
            // Litematica packs palette indexes tightly: one entry may start in a long and end in the next.
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            if (states.length < (volume * bits + 63) / 64) {
                throw damaged();
            }
            long mask = (1L << bits) - 1;
            long[] counts = new long[palette.size()];
            for (long i = 0; i < volume; i++) {
                long start = i * bits;
                int si = (int) (start >>> 6);
                int ei = (int) (((i + 1) * bits - 1) >>> 6);
                int off = (int) (start & 63);
                long v = si == ei
                        ? (states[si] >>> off) & mask
                        : ((states[si] >>> off) | (states[ei] << (64 - off))) & mask;
                if (v < counts.length) {
                    counts[(int) v]++;
                }
            }
            addPalette(palette, counts, out);
        }
        Map<String, Object> enclosing = Nbt.compound(meta, "EnclosingSize");
        int x = enclosing != null ? (int) Math.abs(Nbt.number(enclosing, "x", 0)) : span(box, 0);
        int y = enclosing != null ? (int) Math.abs(Nbt.number(enclosing, "y", 0)) : span(box, 1);
        int z = enclosing != null ? (int) Math.abs(Nbt.number(enclosing, "z", 0)) : span(box, 2);
        return out.result("litematic", Nbt.string(meta, "Name"), Nbt.string(meta, "Author"), x, y, z);
    }

    /** A negative region size means the region extends towards negative coordinates from its position. */
    private static void grow(int[] box, long pos, long size, int axis) {
        long a = size >= 0 ? pos : pos + size + 1;
        long b = size >= 0 ? pos + size - 1 : pos;
        box[axis] = (int) Math.min(box[axis], a);
        box[axis + 3] = (int) Math.max(box[axis + 3], b);
    }

    private static int span(int[] box, int axis) {
        return box[axis] == Integer.MAX_VALUE ? 0 : box[axis + 3] - box[axis] + 1;
    }

    // ------------------------------------------------------------------ Sponge (.schem, WorldEdit 7+)

    private static Schematic sponge(Map<String, Object> s) throws IOException {
        long w = Nbt.number(s, "Width", 0);
        long h = Nbt.number(s, "Height", 0);
        long l = Nbt.number(s, "Length", 0);
        long volume = w * h * l;
        if (volume > MAX_VOLUME) {
            throw tooBig();
        }
        Map<String, Object> palette;
        Object rawData;
        Map<String, Object> blocks = Nbt.compound(s, "Blocks");
        if (blocks != null) { // version 3
            palette = Nbt.compound(blocks, "Palette");
            rawData = blocks.get("Data");
        } else { // versions 1 and 2
            palette = Nbt.compound(s, "Palette");
            rawData = s.get("BlockData");
        }
        if (palette == null || !(rawData instanceof byte[] data)) {
            throw new Nbt.NbtException("Ce schéma ne contient pas de blocs.");
        }
        int max = -1;
        for (Object v : palette.values()) {
            if (v instanceof Number n) {
                max = Math.max(max, n.intValue());
            }
        }
        if (max < 0 || max > 1_000_000) {
            throw damaged();
        }
        String[] states = new String[max + 1];
        for (Map.Entry<String, Object> e : palette.entrySet()) {
            if (e.getValue() instanceof Number n && n.intValue() >= 0) {
                states[n.intValue()] = e.getKey();
            }
        }
        // Palette indexes are stored as varints, one after the other.
        long[] counts = new long[max + 1];
        int pos = 0;
        long index = 0;
        while (index < volume && pos < data.length) {
            int value = 0;
            int shift = 0;
            int b;
            do {
                if (pos >= data.length || shift > 28) {
                    throw damaged();
                }
                b = data[pos++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            if (value >= 0 && value <= max) {
                counts[value]++;
            }
            index++;
        }
        Counter out = new Counter();
        for (int i = 0; i <= max; i++) {
            if (counts[i] > 0 && states[i] != null) {
                String[] parts = BlockItems.split(states[i]);
                BlockItems.items(parts[0], BlockItems.props(parts[1]), counts[i], out);
            }
        }
        Map<String, Object> meta = Nbt.compound(s, "Metadata");
        return out.result("schem", Nbt.string(meta, "Name"), Nbt.string(meta, "Author"), (int) w, (int) h, (int) l);
    }

    // ------------------------------------------------------------------ MCEdit (.schematic, before 1.13)

    private static Schematic legacy(Map<String, Object> s) throws IOException {
        long w = Nbt.number(s, "Width", 0);
        long h = Nbt.number(s, "Height", 0);
        long l = Nbt.number(s, "Length", 0);
        byte[] ids = (byte[]) s.get("Blocks");
        byte[] data = s.get("Data") instanceof byte[] d ? d : null;
        byte[] add = s.get("AddBlocks") instanceof byte[] a ? a : null;
        long volume = Math.min(w * h * l, ids.length);
        long[] counts = new long[4096 * 16];
        for (int i = 0; i < volume; i++) {
            int id = ids[i] & 0xFF;
            if (add != null && (i >> 1) < add.length) {
                id |= (i & 1) == 0 ? (add[i >> 1] & 0x0F) << 8 : (add[i >> 1] & 0xF0) << 4;
            }
            int meta = data != null && i < data.length ? data[i] & 0xF : 0;
            counts[(id << 4) | meta]++;
        }
        Counter out = new Counter();
        for (int k = 16; k < counts.length; k++) {
            if (counts[k] > 0) {
                LegacyBlocks.items(k >> 4, k & 0xF, counts[k], out);
            }
        }
        return out.result("schematic", null, null, (int) w, (int) h, (int) l);
    }

    // ------------------------------------------------------------------ structure blocks / Create (.nbt)

    private static Schematic structure(Map<String, Object> s) throws IOException {
        List<Object> palette = Nbt.list(s, "palette");
        if (palette == null) {
            List<Object> palettes = Nbt.list(s, "palettes");
            if (palettes == null || palettes.isEmpty() || !(palettes.getFirst() instanceof List<?>)) {
                throw new Nbt.NbtException("Ce schéma ne contient pas de blocs.");
            }
            @SuppressWarnings("unchecked")
            List<Object> first = (List<Object>) palettes.getFirst();
            palette = first;
        }
        long[] counts = new long[palette.size()];
        for (Object o : Nbt.list(s, "blocks")) {
            if (o instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                long st = Nbt.number((Map<String, Object>) o, "state", -1);
                if (st >= 0 && st < counts.length) {
                    counts[(int) st]++;
                }
            }
        }
        Counter out = new Counter();
        addPalette(palette, counts, out);
        int[] size = new int[3];
        List<Object> sz = Nbt.list(s, "size");
        for (int i = 0; sz != null && i < 3 && i < sz.size(); i++) {
            size[i] = sz.get(i) instanceof Number n ? n.intValue() : 0;
        }
        return out.result("nbt", null, Nbt.string(s, "author"), size[0], size[1], size[2]);
    }

    // ------------------------------------------------------------------ helpers

    /** Palette entries written as {Name: "minecraft:…", Properties: {…}} (Litematica, structures). */
    private static void addPalette(List<Object> palette, long[] counts, Counter out) {
        for (int i = 0; i < palette.size() && i < counts.length; i++) {
            if (counts[i] == 0 || !(palette.get(i) instanceof Map<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) palette.get(i);
            Map<String, String> props = new HashMap<>();
            Map<String, Object> raw = Nbt.compound(entry, "Properties");
            if (raw != null) {
                raw.forEach((k, v) -> props.put(k, String.valueOf(v)));
            }
            BlockItems.items(Nbt.string(entry, "Name"), props, counts[i], out);
        }
    }

    private static Nbt.NbtException damaged() {
        return new Nbt.NbtException("Le fichier est abîmé ou incomplet.");
    }

    private static Nbt.NbtException tooBig() {
        return new Nbt.NbtException("Ce schéma est trop grand (plus de " + (MAX_VOLUME / 1_000_000) + " millions de blocs).");
    }

    private static final class Counter implements BlockItems.Sink {
        private final Map<String, Long> map = new HashMap<>();

        @Override
        public void add(String item, long count) {
            if (item != null && !item.isEmpty() && count > 0) {
                map.merge(item, count, Long::sum);
            }
        }

        Schematic result(String format, String name, String author, int x, int y, int z) throws Nbt.NbtException {
            if (map.isEmpty()) {
                throw new Nbt.NbtException("Ce schéma ne contient aucun bloc à poser.");
            }
            List<Map.Entry<String, Long>> list = new ArrayList<>(map.entrySet());
            list.sort((a, b) -> b.getValue().compareTo(a.getValue()) != 0 ? b.getValue().compareTo(a.getValue()) : a.getKey().compareTo(b.getKey()));
            Map<String, Long> sorted = new LinkedHashMap<>();
            long total = 0;
            for (Map.Entry<String, Long> e : list) {
                sorted.put(e.getKey(), e.getValue());
                total += e.getValue();
            }
            return new Schematic(format, blankToNull(name), blankToNull(author), x, y, z, total, sorted);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
