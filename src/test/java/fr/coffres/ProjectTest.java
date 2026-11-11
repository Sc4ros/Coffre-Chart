package fr.coffres;

import fr.coffres.model.Project;
import fr.coffres.model.SlotItem;
import fr.coffres.model.StoredChest;
import fr.coffres.project.BlockItems;
import fr.coffres.project.Nbt;
import fr.coffres.project.SchematicReader;
import fr.coffres.store.DataStore;
import fr.coffres.store.ProjectStore;
import fr.coffres.web.Api;
import fr.coffres.web.AuthManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Building projects: reading every plan format, turning blocks into items, progress and visibility. */
class ProjectTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final long LIMIT = 64L * 1024 * 1024;
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @TempDir
    Path dir;

    // ------------------------------------------------------------------ a tiny NBT writer for the tests

    private static byte[] nbt(Map<String, Object> root, boolean gzip) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(gzip ? new GZIPOutputStream(bos) : bos)) {
            out.writeByte(10);
            out.writeUTF("");
            payload(out, root);
        }
        return bos.toByteArray();
    }

    private static int type(Object v) {
        return switch (v) {
            case Byte b -> 1;
            case Short s -> 2;
            case Integer i -> 3;
            case Long l -> 4;
            case byte[] a -> 7;
            case String s -> 8;
            case List<?> l -> 9;
            case Map<?, ?> m -> 10;
            case int[] a -> 11;
            case long[] a -> 12;
            default -> throw new IllegalArgumentException(v.getClass().toString());
        };
    }

    @SuppressWarnings("unchecked")
    private static void payload(DataOutputStream out, Object v) throws IOException {
        switch (v) {
            case Byte b -> out.writeByte(b);
            case Short s -> out.writeShort(s);
            case Integer i -> out.writeInt(i);
            case Long l -> out.writeLong(l);
            case byte[] a -> {
                out.writeInt(a.length);
                out.write(a);
            }
            case String s -> out.writeUTF(s);
            case List<?> l -> {
                out.writeByte(l.isEmpty() ? 0 : type(l.getFirst()));
                out.writeInt(l.size());
                for (Object o : l) {
                    payload(out, o);
                }
            }
            case Map<?, ?> m -> {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                    out.writeByte(type(e.getValue()));
                    out.writeUTF(e.getKey());
                    payload(out, e.getValue());
                }
                out.writeByte(0);
            }
            case int[] a -> {
                out.writeInt(a.length);
                for (int x : a) {
                    out.writeInt(x);
                }
            }
            case long[] a -> {
                out.writeInt(a.length);
                for (long x : a) {
                    out.writeLong(x);
                }
            }
            default -> throw new IllegalArgumentException(v.getClass().toString());
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> state(String name, Object... props) {
        return props.length == 0 ? map("Name", name) : map("Name", name, "Properties", map(props));
    }

    /** Packs palette indexes like Litematica does (an entry may span two longs). */
    private static long[] pack(int[] values, int bits) {
        long[] arr = new long[(int) (((long) values.length * bits + 63) / 64)];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < values.length; i++) {
            long start = (long) i * bits;
            int si = (int) (start >> 6);
            int ei = (int) (((long) (i + 1) * bits - 1) >> 6);
            int off = (int) (start & 63);
            arr[si] = arr[si] & ~(mask << off) | (values[i] & mask) << off;
            if (si != ei) {
                int endOff = 64 - off;
                arr[ei] = arr[ei] >>> (bits - endOff) << (bits - endOff) | (values[i] & mask) >> endOff;
            }
        }
        return arr;
    }

    private static byte[] varints(int... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int v : values) {
            while ((v & ~0x7F) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ formats

    @Test
    void readsLitematicaWithEntriesSpanningLongs() throws IOException {
        List<Object> palette = List.of(
                state("minecraft:air"),
                state("minecraft:stone"),
                state("minecraft:oak_door", "half", "lower", "facing", "north"),
                state("minecraft:oak_door", "half", "upper", "facing", "north"),
                state("minecraft:oak_slab", "type", "double"),
                state("minecraft:candle", "candles", "3", "lit", "false"));
        // 6 entries → 3 bits per block: 64 is not a multiple of 3, so some blocks sit across two longs.
        int volume = 5 * 3 * 7;
        int[] blocks = new int[volume];
        Map<Integer, Integer> count = new HashMap<>();
        for (int i = 0; i < volume; i++) {
            blocks[i] = (i * 7 + i / 3) % 6;
            count.merge(blocks[i], 1, Integer::sum);
        }
        Map<String, Object> region = map(
                "Position", map("x", 0, "y", 0, "z", 0),
                "Size", map("x", -5, "y", 3, "z", 7),
                "BlockStatePalette", palette,
                "BlockStates", pack(blocks, 3));
        Map<String, Object> root = map(
                "Version", 6,
                "Metadata", map("Name", "Maison", "Author", "Steve", "EnclosingSize", map("x", 5, "y", 3, "z", 7)),
                "Regions", map("Principale", region));

        SchematicReader.Schematic s = SchematicReader.read(nbt(root, true), LIMIT);
        assertEquals("litematic", s.format());
        assertEquals("Maison", s.name());
        assertEquals("Steve", s.author());
        assertEquals(List.of(5, 3, 7), List.of(s.sizeX(), s.sizeY(), s.sizeZ()));
        assertEquals((long) count.get(1), s.materials().get("stone"));
        assertEquals((long) count.get(2), s.materials().get("oak_door"), "only the lower half of a door counts");
        assertEquals(2L * count.get(4), s.materials().get("oak_slab"), "a double slab is two slabs");
        assertEquals(3L * count.get(5), s.materials().get("candle"));
        assertNull(s.materials().get("air"));
        assertEquals(s.materials().values().stream().mapToLong(Long::longValue).sum(), s.blocks());
    }

    @Test
    void readsSpongeVersion2And3() throws IOException {
        Map<String, Object> palette = map(
                "minecraft:stone", 0,
                "minecraft:wall_torch[facing=north]", 1,
                "minecraft:water[level=0]", 2,
                "minecraft:water[level=5]", 3,
                "minecraft:glass", 130);
        byte[] data = varints(0, 130, 130, 0, 1, 2, 3, 130);
        Map<String, Object> v2 = map("Version", 2, "Width", (short) 4, "Height", (short) 1, "Length", (short) 2,
                "PaletteMax", 131, "Palette", palette, "BlockData", data);
        SchematicReader.Schematic s2 = SchematicReader.read(nbt(v2, true), LIMIT);
        assertEquals("schem", s2.format());
        assertEquals(2L, s2.materials().get("stone"));
        assertEquals(3L, s2.materials().get("glass"), "palette index 130 is a two-byte varint");
        assertEquals(1L, s2.materials().get("torch"), "a wall torch is placed with a torch");
        assertEquals(1L, s2.materials().get("water_bucket"), "only water sources count");

        Map<String, Object> v3 = map("Schematic", map("Version", 3, "Width", (short) 4, "Height", (short) 1, "Length", (short) 2,
                "Metadata", map("Name", "Tour"), "Blocks", map("Palette", palette, "Data", data)));
        SchematicReader.Schematic s3 = SchematicReader.read(nbt(v3, true), LIMIT);
        assertEquals("Tour", s3.name());
        assertEquals(s2.materials(), s3.materials());
    }

    @Test
    void readsStructureFilesAndOldSchematics() throws IOException {
        Map<String, Object> structure = map(
                "size", List.of(2, 1, 2),
                "palette", List.of(state("minecraft:red_bed", "part", "head"), state("minecraft:red_bed", "part", "foot"),
                        state("minecraft:potted_poppy")),
                "blocks", List.of(map("state", 0, "pos", List.of(0, 0, 0)), map("state", 1, "pos", List.of(1, 0, 0)),
                        map("state", 2, "pos", List.of(0, 0, 1))));
        SchematicReader.Schematic st = SchematicReader.read(nbt(structure, false), LIMIT);
        assertEquals("nbt", st.format());
        assertEquals(1L, st.materials().get("red_bed"));
        assertEquals(1L, st.materials().get("flower_pot"));
        assertEquals(1L, st.materials().get("poppy"));

        Map<String, Object> legacy = map("Width", (short) 2, "Height", (short) 1, "Length", (short) 3, "Materials", "Alpha",
                "Blocks", new byte[]{35, 35, 64, 64, 44, 43}, "Data", new byte[]{14, 14, 0, 8, 0, 3});
        SchematicReader.Schematic old = SchematicReader.read(nbt(legacy, true), LIMIT);
        assertEquals("schematic", old.format());
        assertEquals(2L, old.materials().get("red_wool"));
        assertEquals(1L, old.materials().get("oak_door"));
        assertEquals(1L, old.materials().get("smooth_stone_slab"));
        assertEquals(2L, old.materials().get("cobblestone_slab"));
    }

    @Test
    void refusesBrokenOrHugeFiles() throws IOException {
        assertThrows(Nbt.NbtException.class, () -> SchematicReader.read("ceci n'est pas un schéma".getBytes(), LIMIT));
        // A byte array announcing 2 GB in a file of a few bytes must be refused before anything is allocated.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeByte(10);
            out.writeUTF("");
            out.writeByte(7);
            out.writeUTF("Blocks");
            out.writeInt(Integer.MAX_VALUE);
        }
        Nbt.NbtException e = assertThrows(Nbt.NbtException.class, () -> SchematicReader.read(bos.toByteArray(), LIMIT));
        assertTrue(e.getMessage().contains("trop gros"));
        // A valid NBT file that is not a plan.
        assertThrows(Nbt.NbtException.class, () -> SchematicReader.read(nbt(map("Hello", "world"), true), LIMIT));
    }

    @Test
    void blocksBecomeTheRightItems() {
        Map<String, Long> out = new HashMap<>();
        BlockItems.Sink sink = (id, n) -> out.merge(id, n, Long::sum);
        BlockItems.items("minecraft:oak_wall_sign", Map.of("facing", "east"), 2, sink);
        BlockItems.items("minecraft:carrots", Map.of("age", "7"), 5, sink);
        BlockItems.items("minecraft:redstone_wire", Map.of(), 3, sink);
        BlockItems.items("minecraft:snow", Map.of("layers", "4"), 2, sink);
        BlockItems.items("minecraft:sea_pickle", Map.of("pickles", "4"), 1, sink);
        BlockItems.items("minecraft:white_candle_cake", Map.of(), 1, sink);
        BlockItems.items("minecraft:piston_head", Map.of(), 9, sink);
        BlockItems.items("minecraft:sunflower", Map.of("half", "upper"), 9, sink);
        BlockItems.items("minecraft:oak_stairs", Map.of("half", "top"), 1, sink);
        BlockItems.items("minecraft:water_cauldron", Map.of("level", "3"), 1, sink);
        assertEquals(2L, out.get("oak_sign"));
        assertEquals(5L, out.get("carrot"));
        assertEquals(3L, out.get("redstone"));
        assertEquals(8L, out.get("snow"));
        assertEquals(4L, out.get("sea_pickle"));
        assertEquals(1L, out.get("cake"));
        assertEquals(1L, out.get("white_candle"));
        assertEquals(1L, out.get("oak_stairs"), "stairs use half=top, they are not a two-block thing");
        assertEquals(1L, out.get("cauldron"));
        assertFalse(out.containsKey("piston_head"));
        assertFalse(out.containsKey("sunflower"));
    }

    // ------------------------------------------------------------------ progress, visibility, storage

    private static StoredChest chest(String id, boolean pub, UUID owner, String item, int n) {
        StoredChest c = new StoredChest();
        c.id = id;
        c.world = "world";
        c.dim = "overworld";
        c.x = Math.abs(id.hashCode() % 1000);
        c.y = 64;
        c.type = "chest";
        c.name = "Coffre " + id;
        c.owner = owner.toString();
        c.ownerName = owner.equals(OWNER) ? "Owner" : "Other";
        c.pub = pub;
        c.slots = new ArrayList<>(List.of(new SlotItem(0, item, Math.min(64, n))));
        if (n > 64) {
            c.slots.add(new SlotItem(1, item, n - 64));
        }
        return c;
    }

    private static AuthManager.Session session(UUID uuid, boolean admin) {
        AuthManager.Session s = new AuthManager.Session();
        s.uuid = uuid.toString();
        s.name = "x";
        s.admin = admin;
        return s;
    }

    @Test
    @SuppressWarnings("unchecked")
    void progressCountsOnlyVisibleLinkedChests() {
        DataStore store = new DataStore(dir, LOG);
        store.putForTest(chest("linkpub1", true, OWNER, "stone", 100), null);
        store.putForTest(chest("linkpriv", false, OTHER, "stone", 50), null);
        store.putForTest(chest("elsewher", true, OWNER, "stone", 30), null);
        ProjectStore projects = new ProjectStore(dir.resolve("projects.json"), LOG, store::bump);
        Project p = new Project();
        p.id = "proj1234";
        p.name = "Mur";
        p.owner = OWNER.toString();
        p.ownerName = "Owner";
        p.shared = true;
        p.materials.put("stone", 200L);
        p.materials.put("glass", 10L);
        p.chests = new ArrayList<>(List.of("linkpub1", "linkpriv"));
        projects.put(p);
        Api api = new Api(store, projects);

        Map<String, Object> mine = api.project(session(OWNER, false), "proj1234");
        assertEquals(210L, mine.get("need"));
        assertEquals(100L, mine.get("have"), "the private chest of another player is not counted");
        assertEquals(1, mine.get("hiddenChests"));
        assertEquals(47, mine.get("percent"));
        Map<String, Object> stone = ((List<Map<String, Object>>) mine.get("materials")).getFirst();
        assertEquals(30L, stone.get("elsewhere"));

        Map<String, Object> admin = api.project(session(OTHER, true), "proj1234");
        assertEquals(150L, admin.get("have"));

        // A player who sets the list keeps the links they cannot see.
        List<String> merged = api.mergeLinks(session(OWNER, false), "proj1234", List.of("elsewher"));
        assertEquals(List.of("linkpriv", "elsewher"), merged);

        // Solo projects are hidden from other players.
        projects.edit("proj1234", null, false);
        assertNull(api.project(session(OTHER, false), "proj1234"));
        assertNotNull(api.project(session(OWNER, false), "proj1234"));
        assertTrue(api.projects(session(OTHER, false)).isEmpty());
    }

    @Test
    void projectsAreSavedAndLoaded() throws IOException {
        ProjectStore projects = new ProjectStore(dir.resolve("projects.json"), LOG, null);
        SchematicReader.Schematic plan = new SchematicReader.Schematic("schem", "Pont", null, 10, 4, 3, 74,
                new LinkedHashMap<>(Map.of("oak_planks", 64L, "lantern", 10L)));
        Project p = projects.create("Pont du village", OWNER.toString(), "Owner", false, "pont.schem", plan);
        assertTrue(projects.toggleChest(p.id, "aaaa1111"));
        assertTrue(projects.toggleChest(p.id, "bbbb2222"));
        assertFalse(projects.toggleChest(p.id, "aaaa1111"));
        projects.save();

        ProjectStore again = new ProjectStore(dir.resolve("projects.json"), LOG, null);
        again.load();
        Project loaded = again.get(p.id);
        assertNotNull(loaded);
        assertEquals("Pont du village", loaded.name);
        assertEquals(64L, loaded.materials.get("oak_planks"));
        assertEquals(List.of("bbbb2222"), loaded.chests);
        assertEquals("pont.schem", loaded.file);
        assertFalse(loaded.shared);

        again.pruneChests(java.util.Set.of());
        assertTrue(again.get(p.id).chests.isEmpty(), "links to containers that are gone are dropped");
        assertEquals(1, again.countOwnedBy(OWNER.toString()));
    }
}
