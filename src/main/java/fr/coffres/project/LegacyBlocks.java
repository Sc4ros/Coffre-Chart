package fr.coffres.project;

/**
 * Old MCEdit / WorldEdit ".schematic" files (Minecraft 1.12 and before) store numeric block ids.
 * This table turns an id and its data value into today's item ids. Details stored in block entities
 * (bed color, banner pattern, head type, flower pot contents) are not read: a default is used.
 */
final class LegacyBlocks {

    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};

    /** Simple ids where the data value does not change the item. Null = not simple (see {@link #items}) or unused. */
    private static final String[] SIMPLE = new String[256];

    static {
        String[][] t = {
                {"2", "grass_block"}, {"4", "cobblestone"}, {"7", "bedrock"}, {"13", "gravel"}, {"14", "gold_ore"},
                {"15", "iron_ore"}, {"16", "coal_ore"}, {"20", "glass"}, {"21", "lapis_ore"}, {"22", "lapis_block"},
                {"23", "dispenser"}, {"25", "note_block"}, {"27", "powered_rail"}, {"28", "detector_rail"},
                {"29", "sticky_piston"}, {"30", "cobweb"}, {"32", "dead_bush"}, {"33", "piston"}, {"37", "dandelion"},
                {"39", "brown_mushroom"}, {"40", "red_mushroom"}, {"41", "gold_block"}, {"42", "iron_block"},
                {"45", "bricks"}, {"46", "tnt"}, {"47", "bookshelf"}, {"48", "mossy_cobblestone"}, {"49", "obsidian"},
                {"50", "torch"}, {"52", "spawner"}, {"53", "oak_stairs"}, {"54", "chest"}, {"55", "redstone"},
                {"56", "diamond_ore"}, {"57", "diamond_block"}, {"58", "crafting_table"}, {"59", "wheat_seeds"},
                {"60", "dirt"}, {"61", "furnace"}, {"62", "furnace"}, {"63", "oak_sign"}, {"65", "ladder"}, {"66", "rail"},
                {"67", "cobblestone_stairs"}, {"68", "oak_sign"}, {"69", "lever"}, {"70", "stone_pressure_plate"},
                {"72", "oak_pressure_plate"}, {"73", "redstone_ore"}, {"74", "redstone_ore"}, {"75", "redstone_torch"},
                {"76", "redstone_torch"}, {"77", "stone_button"}, {"79", "ice"}, {"80", "snow_block"}, {"81", "cactus"},
                {"82", "clay"}, {"83", "sugar_cane"}, {"84", "jukebox"}, {"85", "oak_fence"}, {"86", "carved_pumpkin"},
                {"87", "netherrack"}, {"88", "soul_sand"}, {"89", "glowstone"}, {"91", "jack_o_lantern"}, {"92", "cake"},
                {"93", "repeater"}, {"94", "repeater"}, {"96", "oak_trapdoor"}, {"99", "brown_mushroom_block"},
                {"100", "red_mushroom_block"}, {"101", "iron_bars"}, {"102", "glass_pane"}, {"103", "melon"},
                {"104", "pumpkin_seeds"}, {"105", "melon_seeds"}, {"106", "vine"}, {"107", "oak_fence_gate"},
                {"108", "brick_stairs"}, {"109", "stone_brick_stairs"}, {"110", "mycelium"}, {"111", "lily_pad"},
                {"112", "nether_bricks"}, {"113", "nether_brick_fence"}, {"114", "nether_brick_stairs"},
                {"115", "nether_wart"}, {"116", "enchanting_table"}, {"117", "brewing_stand"}, {"118", "cauldron"},
                {"120", "end_portal_frame"}, {"121", "end_stone"}, {"122", "dragon_egg"}, {"123", "redstone_lamp"},
                {"124", "redstone_lamp"}, {"127", "cocoa_beans"}, {"128", "sandstone_stairs"}, {"129", "emerald_ore"},
                {"130", "ender_chest"}, {"131", "tripwire_hook"}, {"132", "string"}, {"133", "emerald_block"},
                {"134", "spruce_stairs"}, {"135", "birch_stairs"}, {"136", "jungle_stairs"}, {"137", "command_block"},
                {"138", "beacon"}, {"140", "flower_pot"}, {"141", "carrot"}, {"142", "potato"}, {"143", "oak_button"},
                {"144", "skeleton_skull"}, {"146", "trapped_chest"}, {"147", "light_weighted_pressure_plate"},
                {"148", "heavy_weighted_pressure_plate"}, {"149", "comparator"}, {"150", "comparator"},
                {"151", "daylight_detector"}, {"152", "redstone_block"}, {"153", "nether_quartz_ore"}, {"154", "hopper"},
                {"156", "quartz_stairs"}, {"157", "activator_rail"}, {"158", "dropper"}, {"163", "acacia_stairs"},
                {"164", "dark_oak_stairs"}, {"165", "slime_block"}, {"166", "barrier"}, {"167", "iron_trapdoor"},
                {"169", "sea_lantern"}, {"170", "hay_block"}, {"172", "terracotta"}, {"173", "coal_block"},
                {"174", "packed_ice"}, {"176", "white_banner"}, {"177", "white_banner"}, {"178", "daylight_detector"},
                {"180", "red_sandstone_stairs"}, {"182", "red_sandstone_slab"}, {"183", "spruce_fence_gate"},
                {"184", "birch_fence_gate"}, {"185", "jungle_fence_gate"}, {"186", "dark_oak_fence_gate"},
                {"187", "acacia_fence_gate"}, {"188", "spruce_fence"}, {"189", "birch_fence"}, {"190", "jungle_fence"},
                {"191", "dark_oak_fence"}, {"192", "acacia_fence"}, {"198", "end_rod"}, {"199", "chorus_plant"},
                {"200", "chorus_flower"}, {"201", "purpur_block"}, {"202", "purpur_pillar"}, {"203", "purpur_stairs"},
                {"205", "purpur_slab"}, {"206", "end_stone_bricks"}, {"207", "beetroot_seeds"}, {"208", "dirt"},
                {"210", "repeating_command_block"}, {"211", "chain_command_block"}, {"213", "magma_block"},
                {"214", "nether_wart_block"}, {"215", "red_nether_bricks"}, {"216", "bone_block"}, {"218", "observer"},
                {"255", "structure_block"},
        };
        for (String[] e : t) {
            SIMPLE[Integer.parseInt(e[0])] = e[1];
        }
        for (int i = 0; i < 16; i++) {
            SIMPLE[219 + i] = COLORS[i] + "_shulker_box";
            SIMPLE[235 + i] = COLORS[i] + "_glazed_terracotta";
        }
    }

    private LegacyBlocks() {
    }

    private static String pick(String[] names, int i) {
        return i >= 0 && i < names.length ? names[i] : names[0];
    }

    /** Items for {@code count} blocks with this legacy id and data value. Unknown ids are reported as "legacy_ID". */
    static void items(int id, int data, long count, BlockItems.Sink out) {
        if (id <= 0 || count <= 0) {
            return;
        }
        if (id < 256 && SIMPLE[id] != null) {
            out.add(SIMPLE[id], count);
            return;
        }
        int d = data & 0xF;
        switch (id) {
            case 1 -> out.add(pick(new String[]{"stone", "granite", "polished_granite", "diorite", "polished_diorite", "andesite", "polished_andesite"}, d), count);
            case 3 -> out.add(pick(new String[]{"dirt", "coarse_dirt", "podzol"}, d), count);
            case 5 -> out.add(pick(WOODS, d) + "_planks", count);
            case 6 -> out.add(pick(WOODS, d & 7) + "_sapling", count);
            case 8, 9 -> {
                if (d == 0) {
                    out.add("water_bucket", count);
                }
            }
            case 10, 11 -> {
                if (d == 0) {
                    out.add("lava_bucket", count);
                }
            }
            case 12 -> out.add(d == 1 ? "red_sand" : "sand", count);
            case 17 -> out.add(pick(WOODS, d & 3) + "_log", count);
            case 18 -> out.add(pick(WOODS, d & 3) + "_leaves", count);
            case 19 -> out.add(d == 1 ? "wet_sponge" : "sponge", count);
            case 24 -> out.add(pick(new String[]{"sandstone", "chiseled_sandstone", "cut_sandstone"}, d), count);
            case 26 -> {
                if ((d & 8) == 0) {
                    out.add("red_bed", count);
                }
            }
            case 31 -> out.add(pick(new String[]{"dead_bush", "short_grass", "fern"}, d), count);
            case 35 -> out.add(COLORS[d] + "_wool", count);
            case 38 -> out.add(pick(new String[]{"poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
                    "white_tulip", "pink_tulip", "oxeye_daisy"}, d), count);
            case 43 -> {
                if ((d & 8) != 0 && (d & 7) == 0) {
                    out.add("smooth_stone", count);
                } else if ((d & 8) != 0 && (d & 7) == 1) {
                    out.add("smooth_sandstone", count);
                } else if ((d & 8) != 0 && (d & 7) == 7) {
                    out.add("smooth_quartz", count);
                } else {
                    out.add(stoneSlab(d & 7), count * 2);
                }
            }
            case 44 -> out.add(stoneSlab(d & 7), count);
            case 64, 71, 193, 194, 195, 196, 197 -> {
                if ((d & 8) == 0) {
                    out.add(switch (id) {
                        case 64 -> "oak_door";
                        case 71 -> "iron_door";
                        case 193 -> "spruce_door";
                        case 194 -> "birch_door";
                        case 195 -> "jungle_door";
                        case 196 -> "acacia_door";
                        default -> "dark_oak_door";
                    }, count);
                }
            }
            case 78 -> out.add("snow", count * ((d & 7) + 1));
            case 95 -> out.add(COLORS[d] + "_stained_glass", count);
            case 97 -> out.add(pick(new String[]{"infested_stone", "infested_cobblestone", "infested_stone_bricks",
                    "infested_mossy_stone_bricks", "infested_cracked_stone_bricks", "infested_chiseled_stone_bricks"}, d), count);
            case 98 -> out.add(pick(new String[]{"stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks"}, d), count);
            case 125 -> out.add(pick(WOODS, d & 7) + "_slab", count * 2);
            case 126 -> out.add(pick(WOODS, d & 7) + "_slab", count);
            case 139 -> out.add(d == 1 ? "mossy_cobblestone_wall" : "cobblestone_wall", count);
            case 145 -> out.add(pick(new String[]{"anvil", "chipped_anvil", "damaged_anvil"}, d >> 2), count);
            case 155 -> out.add(d == 1 ? "chiseled_quartz_block" : d >= 2 ? "quartz_pillar" : "quartz_block", count);
            case 159 -> out.add(COLORS[d] + "_terracotta", count);
            case 160 -> out.add(COLORS[d] + "_stained_glass_pane", count);
            case 161 -> out.add(((d & 3) == 1 ? "dark_oak" : "acacia") + "_leaves", count);
            case 162 -> out.add(((d & 3) == 1 ? "dark_oak" : "acacia") + "_log", count);
            case 168 -> out.add(pick(new String[]{"prismarine", "prismarine_bricks", "dark_prismarine"}, d), count);
            case 171 -> out.add(COLORS[d] + "_carpet", count);
            case 175 -> {
                if ((d & 8) == 0) {
                    out.add(pick(new String[]{"sunflower", "lilac", "tall_grass", "large_fern", "rose_bush", "peony"}, d & 7), count);
                }
            }
            case 179 -> out.add(pick(new String[]{"red_sandstone", "chiseled_red_sandstone", "cut_red_sandstone"}, d), count);
            case 181 -> out.add("red_sandstone_slab", count * 2);
            case 204 -> out.add("purpur_slab", count * 2);
            case 251 -> out.add(COLORS[d] + "_concrete", count);
            case 252 -> out.add(COLORS[d] + "_concrete_powder", count);
            // 34 piston head, 36 moving piston, 51 fire, 90 portal, 119 end portal, 209 end gateway, 212 frosted ice, 217 structure void
            case 34, 36, 51, 90, 119, 209, 212, 217 -> {
            }
            default -> out.add("legacy_" + id, count);
        }
    }

    private static String stoneSlab(int d) {
        return switch (d) {
            case 1 -> "sandstone_slab";
            case 2 -> "oak_slab";
            case 3 -> "cobblestone_slab";
            case 4 -> "brick_slab";
            case 5 -> "stone_brick_slab";
            case 6 -> "nether_brick_slab";
            case 7 -> "quartz_slab";
            default -> "smooth_stone_slab";
        };
    }
}
