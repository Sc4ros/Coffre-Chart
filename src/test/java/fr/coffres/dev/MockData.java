package fr.coffres.dev;

import fr.coffres.model.LogEntry;
import fr.coffres.model.Project;
import fr.coffres.model.SlotItem;
import fr.coffres.model.StoredChest;
import fr.coffres.store.DataStore;
import fr.coffres.store.ProjectStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Made-up but believable storage for the demo site. */
final class MockData {

    static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    static final Map<String, UUID> PLAYERS = new LinkedHashMap<>();

    static {
        PLAYERS.put("Steve", STEVE);
        PLAYERS.put("Alex", UUID.fromString("ec561538-f3fd-461d-aff5-086b22154bce"));
        PLAYERS.put("Lea_mc", UUID.fromString("0f5c3b5e-7d0c-4b38-9a77-2a0c6d1e9b11"));
        PLAYERS.put("Kiwi", UUID.fromString("5a1e6f42-1b2d-4c4e-8f3a-9d7c2b6e4a22"));
        PLAYERS.put("Bastien", UUID.fromString("c3d4e5f6-0718-4a2b-8c9d-0e1f2a3b4c33"));
    }

    private static final Set<String> SINGLE = Set.of("iron_pickaxe", "diamond_sword", "diamond_pickaxe", "netherite_axe", "enchanted_book",
            "bow", "elytra", "totem_of_undying", "white_shulker_box", "blue_shulker_box", "lime_shulker_box", "shield", "trident", "diamond_chestplate");
    private static final Set<String> SIXTEEN = Set.of("ender_pearl", "egg", "snowball", "oak_sign", "honey_bottle");
    private static final Set<String> GLINT = Set.of("enchanted_book", "diamond_sword", "diamond_pickaxe", "netherite_axe", "diamond_chestplate", "bow", "trident");

    private record Def(String name, String zone, String type, boolean dbl, int x, int y, int z, String dim, String owner, boolean pub,
                       int linkedDaysAgo, Object... items) {
    }

    private static final List<Def> DEFS = List.of(
            new Def("Minerais", "Base", "chest", true, 120, 64, -30, "overworld", "Steve", true, 20,
                    "diamond", 214, "emerald", 87, "lapis_lazuli", 640, "redstone", 704, "coal", 960, "raw_iron", 380, "raw_gold", 142, "raw_copper", 192),
            new Def("Lingots", "Base", "chest", true, 122, 64, -30, "overworld", "Steve", true, 20,
                    "iron_ingot", 1186, "gold_ingot", 344, "copper_ingot", 708, "netherite_ingot", 6, "iron_block", 24, "diamond_block", 3),
            new Def("Bois", "Base", "barrel", false, 118, 64, -27, "overworld", "Alex", true, 18,
                    "oak_log", 448, "spruce_log", 320, "birch_log", 256, "dark_oak_log", 192, "oak_planks", 320, "stick", 120),
            new Def("Pierres", "Base", "chest", true, 124, 64, -30, "overworld", "Alex", true, 18,
                    "cobblestone", 1280, "stone", 640, "deepslate", 640, "andesite", 384, "diorite", 256, "granite", 192, "tuff", 64),
            new Def("Blocs de construction", "Base", "chest", true, 126, 64, -30, "overworld", "Lea_mc", true, 15,
                    "stone_bricks", 768, "oak_stairs", 192, "glass", 640, "smooth_stone", 320, "bricks", 256, "quartz_block", 128, "white_concrete", 384),
            new Def("Nourriture", "Base", "barrel", false, 116, 64, -25, "overworld", "Kiwi", true, 16,
                    "bread", 154, "cooked_beef", 96, "baked_potato", 210, "golden_carrot", 128, "apple", 37, "cooked_salmon", 64),
            new Def("Redstone", "Base", "chest", false, 128, 63, -30, "overworld", "Bastien", true, 14,
                    "redstone_torch", 90, "repeater", 64, "comparator", 32, "piston", 48, "sticky_piston", 24, "hopper", 36, "observer", 40, "redstone_block", 30),
            new Def("Outils et armes", "Base", "chest", false, 114, 64, -30, "overworld", "Steve", true, 12,
                    "diamond_pickaxe", 2, "diamond_sword", 2, "netherite_axe", 1, "bow", 1, "arrow", 320, "shield", 1, "trident", 1, "torch", 448),
            new Def("Enchantements", "Base", "chest", false, 112, 65, -32, "overworld", "Lea_mc", true, 10,
                    "enchanted_book", 17, "experience_bottle", 192, "book", 64, "lapis_lazuli", 128),
            new Def("Shulkers", "Base", "chest", false, 110, 64, -30, "overworld", "Steve", true, 8,
                    "white_shulker_box", 1, "blue_shulker_box", 1, "lime_shulker_box", 1, "ender_pearl", 48),
            new Def("Farm à fer", "Fermes", "chest", true, 240, 70, 15, "overworld", "Alex", true, 19,
                    "iron_ingot", 1640, "poppy", 230),
            new Def("Ferme auto", "Fermes", "chest", true, 236, 64, 22, "overworld", "Kiwi", true, 13,
                    "wheat", 690, "carrot", 512, "potato", 448, "wheat_seeds", 576, "pumpkin", 128, "melon_slice", 384, "sugar_cane", 512),
            new Def("Tonneau à poisson", "Fermes", "barrel", false, 232, 63, 30, "overworld", "Kiwi", true, 9,
                    "cod", 88, "salmon", 54, "tropical_fish", 12, "bone", 64, "string", 140),
            new Def("Butin du Nether", "Nether hub", "chest", true, 15, 72, -4, "nether", "Bastien", true, 11,
                    "netherrack", 1408, "quartz", 640, "glowstone_dust", 256, "blaze_rod", 64, "nether_wart", 320, "gold_nugget", 576, "magma_cream", 32, "obsidian", 128),
            new Def("Mine de diamants", "Mine", "barrel", false, -40, -54, 210, "overworld", "Steve", true, 6,
                    "diamond", 46, "cobbled_deepslate", 832, "raw_iron", 96, "redstone", 384, "torch", 128),
            new Def("Coffre perso d'Alex", null, "chest", false, 300, 70, 300, "overworld", "Alex", false, 5,
                    "elytra", 1, "totem_of_undying", 2, "diamond_chestplate", 1, "golden_apple", 12, "ender_pearl", 16),
            new Def("Coffre n°17", null, "trapped_chest", false, 130, 64, -24, "overworld", "Lea_mc", true, 2,
                    "grass_block", 192, "dirt", 640, "sand", 384, "oak_leaves", 128, "poppy", 23, "dandelion", 18)
    );

    private MockData() {
    }

    static void fill(DataStore store) {
        Random rnd = new Random(42);
        long now = System.currentTimeMillis();
        for (Def d : DEFS) {
            StoredChest c = new StoredChest();
            c.id = DataStore.newId();
            c.name = d.name;
            c.zone = d.zone;
            c.type = d.type;
            c.dbl = d.dbl;
            c.size = d.dbl ? 54 : 27;
            c.world = d.dim.equals("nether") ? "world_nether" : "world";
            c.dim = d.dim;
            c.x = d.x;
            c.y = d.y;
            c.z = d.z;
            c.owner = PLAYERS.get(d.owner).toString();
            c.ownerName = d.owner;
            c.pub = d.pub;
            c.created = now - d.linkedDaysAgo * 86_400_000L;
            c.slots = slots(d, rnd, c.size);
            c.recompute(true);

            // History: players and machines moving things since the chest was added.
            List<LogEntry> log = new ArrayList<>();
            Map<String, Long> current = new HashMap<>(c.totals());
            Map<String, Long> net = new HashMap<>();
            List<String> pool = new ArrayList<>(current.keySet());
            boolean farm = d.zone != null && d.zone.equals("Fermes");
            for (int day = d.linkedDaysAgo - 1; day >= 0; day--) {
                int sessions = rnd.nextInt(farm ? 2 : 4);
                for (int s = 0; s < sessions; s++) {
                    String player = farm || rnd.nextInt(3) > 0 ? d.owner : pick(rnd);
                    long t = now - day * 86_400_000L - (long) (rnd.nextDouble() * 9 + 1) * 3_600_000L - rnd.nextInt(3_600_000);
                    if (t > now - 60_000) {
                        t = now - 60_000 - rnd.nextInt(600_000);
                    }
                    int kinds = 1 + rnd.nextInt(3);
                    for (int k = 0; k < kinds; k++) {
                        String id = pool.get(rnd.nextInt(pool.size()));
                        long have = current.get(id);
                        long amount = Math.max(1, Math.round(have * (0.03 + rnd.nextDouble() * 0.12)));
                        long delta = rnd.nextInt(100) < 42 ? amount : -amount;
                        log.add(new LogEntry(t, c.id, player, PLAYERS.get(player).toString(), LogEntry.PLAYER, id, delta));
                        net.merge(id, delta, Long::sum);
                    }
                }
                if (farm) {
                    for (int hTick = 0; hTick < 24; hTick += 3) {
                        long t = now - day * 86_400_000L - hTick * 3_600_000L;
                        if (t >= now) {
                            continue;
                        }
                        String id = pool.getFirst();
                        long delta = 8 + rnd.nextInt(24);
                        log.add(new LogEntry(t, c.id, null, null, LogEntry.AUTO, id, delta));
                        net.merge(id, delta, Long::sum);
                    }
                }
            }
            // Contents when the chest was added = now - everything that happened since.
            long linkT = c.created;
            log.add(new LogEntry(linkT, c.id, d.owner, PLAYERS.get(d.owner).toString(), LogEntry.LINK, null, 0));
            for (Map.Entry<String, Long> e : current.entrySet()) {
                long atLink = e.getValue() - net.getOrDefault(e.getKey(), 0L);
                if (atLink > 0) {
                    log.add(new LogEntry(linkT, c.id, d.owner, PLAYERS.get(d.owner).toString(), LogEntry.LINK, e.getKey(), atLink));
                }
            }
            c.updated = log.stream().filter(e -> e.m != null).mapToLong(e -> e.t).max().orElse(c.created);
            c.lastPlayer = log.stream().filter(e -> e.p != null && LogEntry.PLAYER.equals(e.k)).max((a, b) -> Long.compare(a.t, b.t))
                    .map(e -> e.p).orElse(d.owner);
            log.sort((a, b) -> Long.compare(a.t, b.t));
            store.putForTest(c, log);
        }
    }

    /** Three building projects, linked to some of the demo containers. */
    static void fillProjects(ProjectStore projects, DataStore store) {
        long now = System.currentTimeMillis();
        Map<String, String> byName = new HashMap<>();
        for (StoredChest c : store.all()) {
            byName.put(c.name, c.id);
        }
        projects.put(project("chateau1", "Château de la colline", "Steve", true, "chateau_colline.litematic", "litematic", 64, 41, 72,
                now - 9 * 86_400_000L, List.of(byName.get("Pierres"), byName.get("Blocs de construction"), byName.get("Bois")),
                "stone_bricks", 4280, "cobblestone", 2210, "oak_planks", 1720, "deepslate_tiles", 960, "spruce_stairs", 884,
                "glass_pane", 612, "oak_log", 412, "stone_brick_stairs", 380, "spruce_trapdoor", 144, "white_wool", 132,
                "smooth_stone", 120, "lantern", 64, "spruce_fence", 58, "torch", 40, "red_bed", 6, "oak_door", 6,
                "water_bucket", 4, "chest", 3));
        projects.put(project("fermefer", "Ferme à fer v2", "Alex", false, "iron_farm.schem", "schem", 21, 30, 21,
                now - 3 * 86_400_000L, List.of(byName.get("Redstone"), byName.get("Coffre perso d'Alex")),
                "cobblestone", 640, "glass", 290, "hopper", 30, "chest", 16, "lava_bucket", 4, "water_bucket", 8, "white_bed", 3,
                "composter", 3, "observer", 4, "sticky_piston", 2, "redstone", 24, "rail", 12, "comparator", 2));
        projects.put(project("phare", "Phare du port", "Lea_mc", true, "phare.nbt", "nbt", 15, 48, 15,
                now - 86_400_000L, List.of(byName.get("Blocs de construction")),
                "white_concrete", 520, "red_concrete", 380, "quartz_block", 128, "glass", 96, "sea_lantern", 24, "smooth_stone", 220,
                "oak_stairs", 64, "iron_bars", 40));
    }

    private static Project project(String id, String name, String owner, boolean shared, String file, String format,
                                   int sx, int sy, int sz, long created, List<String> chests, Object... mats) {
        Project p = new Project();
        p.id = id;
        p.name = name;
        p.owner = PLAYERS.get(owner).toString();
        p.ownerName = owner;
        p.shared = shared;
        p.file = file;
        p.format = format;
        p.sx = sx;
        p.sy = sy;
        p.sz = sz;
        p.created = created;
        p.updated = created;
        for (int i = 0; i < mats.length; i += 2) {
            p.materials.put((String) mats[i], ((Integer) mats[i + 1]).longValue());
            p.blocks += (Integer) mats[i + 1];
        }
        for (String c : chests) {
            if (c != null) {
                p.chests.add(c);
            }
        }
        return p;
    }

    private static String pick(Random rnd) {
        List<String> names = new ArrayList<>(PLAYERS.keySet());
        return names.get(rnd.nextInt(names.size()));
    }

    private static int stack(String id) {
        return SINGLE.contains(id) ? 1 : SIXTEEN.contains(id) ? 16 : 64;
    }

    private static List<SlotItem> slots(Def d, Random rnd, int size) {
        List<SlotItem> out = new ArrayList<>();
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            free.add(i);
        }
        for (int i = 0; i < d.items.length; i += 2) {
            String id = (String) d.items[i];
            int count = (Integer) d.items[i + 1];
            int st = stack(id);
            while (count > 0 && !free.isEmpty()) {
                int n = Math.min(st, count);
                count -= n;
                int slot = free.remove(rnd.nextInt(Math.min(free.size(), 6)));
                SlotItem s = new SlotItem(slot, id, n);
                s.e = GLINT.contains(id);
                if (id.endsWith("shulker_box")) {
                    s.in = shulker(id);
                    s.name = id.startsWith("white") ? "Diamants de secours" : id.startsWith("blue") ? "Redstone" : null;
                }
                out.add(s);
            }
        }
        out.sort((a, b) -> Integer.compare(a.s, b.s));
        return out;
    }

    private static List<SlotItem> shulker(String id) {
        List<SlotItem> in = new ArrayList<>();
        if (id.startsWith("white")) {
            for (int i = 0; i < 4; i++) {
                in.add(new SlotItem(i, "diamond", 64));
            }
            in.add(new SlotItem(4, "diamond", 23));
        } else if (id.startsWith("blue")) {
            for (int i = 0; i < 9; i++) {
                in.add(new SlotItem(i, "redstone", 64));
            }
            in.add(new SlotItem(9, "repeater", 32));
        } else {
            for (int i = 0; i < 12; i++) {
                in.add(new SlotItem(i, "oak_log", 64));
            }
        }
        return in;
    }

    /** Someone takes or puts a few items somewhere (keeps the demo alive). */
    static void randomMove(DataStore store, Random rnd) {
        List<StoredChest> all = store.all();
        StoredChest c = all.get(rnd.nextInt(all.size()));
        if (!c.pub || c.slots.isEmpty()) {
            return;
        }
        String player = rnd.nextBoolean() ? "Steve" : "Alex";
        List<SlotItem> copy = new ArrayList<>();
        for (SlotItem s : c.slots) {
            SlotItem x = new SlotItem(s.s, s.id, s.n);
            x.name = s.name;
            x.e = s.e;
            x.in = s.in;
            copy.add(x);
        }
        SlotItem target = copy.get(rnd.nextInt(copy.size()));
        int st = stack(target.id);
        if (st == 1) {
            return;
        }
        int change = 1 + rnd.nextInt(Math.max(1, st / 2));
        if (rnd.nextBoolean()) {
            target.n = Math.min(st, target.n + change);
        } else {
            target.n -= change;
            if (target.n <= 0) {
                copy.remove(target);
            }
        }
        Map<String, Long> delta = store.updateContents(c.id, copy, c.size, c.dbl);
        long now = System.currentTimeMillis();
        List<LogEntry> entries = new ArrayList<>();
        delta.forEach((k, v) -> entries.add(new LogEntry(now, c.id, player, PLAYERS.get(player).toString(), LogEntry.PLAYER, k, v)));
        store.append(entries);
        store.setLastPlayer(c.id, player);
        if (!entries.isEmpty()) {
            System.out.println("  [jeu] " + player + " " + delta + " dans " + c.name);
        }
    }
}
