package fr.coffres.project;

import java.util.Map;
import java.util.Set;

/**
 * Turns a block of a schematic into the items needed to place it, with the ids used in chests
 * (no namespace, e.g. "oak_door"). Examples:
 * <ul>
 *   <li>the upper half of a door, a bed's head, a piston head → nothing (counted with the other half);</li>
 *   <li>a double slab → 2 slabs, 3 candles → 3 candles, 5 snow layers → 5 snow;</li>
 *   <li>a wall torch → torch, carrots → carrot, redstone wire → redstone, a potted poppy → flower pot + poppy;</li>
 *   <li>a water source → water bucket (flowing water is ignored).</li>
 * </ul>
 */
public final class BlockItems {

    /** Receives each item and how many of it one block needs. */
    @FunctionalInterface
    public interface Sink {
        void add(String item, long count);
    }

    /** Blocks that are never placed by hand (air, portals, technical blocks…). */
    private static final Set<String> SKIP = Set.of(
            "air", "cave_air", "void_air", "structure_void", "moving_piston", "piston_head", "fire", "soul_fire",
            "nether_portal", "end_portal", "end_gateway", "bubble_column", "frosted_ice", "light");

    private static final Map<String, String> RENAME = Map.ofEntries(
            Map.entry("redstone_wire", "redstone"),
            Map.entry("tripwire", "string"),
            Map.entry("cocoa", "cocoa_beans"),
            Map.entry("carrots", "carrot"),
            Map.entry("potatoes", "potato"),
            Map.entry("beetroots", "beetroot_seeds"),
            Map.entry("wheat", "wheat_seeds"),
            Map.entry("melon_stem", "melon_seeds"),
            Map.entry("attached_melon_stem", "melon_seeds"),
            Map.entry("pumpkin_stem", "pumpkin_seeds"),
            Map.entry("attached_pumpkin_stem", "pumpkin_seeds"),
            Map.entry("sweet_berry_bush", "sweet_berries"),
            Map.entry("cave_vines", "glow_berries"),
            Map.entry("cave_vines_plant", "glow_berries"),
            Map.entry("kelp_plant", "kelp"),
            Map.entry("bamboo_sapling", "bamboo"),
            Map.entry("tall_seagrass", "seagrass"),
            Map.entry("weeping_vines_plant", "weeping_vines"),
            Map.entry("twisting_vines_plant", "twisting_vines"),
            Map.entry("big_dripleaf_stem", "big_dripleaf"),
            Map.entry("farmland", "dirt"),
            Map.entry("dirt_path", "dirt"),
            Map.entry("powder_snow", "powder_snow_bucket"),
            Map.entry("water_cauldron", "cauldron"),
            Map.entry("lava_cauldron", "cauldron"),
            Map.entry("powder_snow_cauldron", "cauldron"),
            Map.entry("torchflower_crop", "torchflower_seeds"),
            Map.entry("pitcher_crop", "pitcher_pod"),
            Map.entry("azalea_bush", "azalea"),
            Map.entry("flowering_azalea_bush", "flowering_azalea"));

    /** Block state properties that say how many items are stacked in one block. */
    private static final String[] AMOUNT_PROPS = {"candles", "pickles", "eggs", "flower_amount", "segment_amount"};

    private BlockItems() {
    }

    /** Removes "minecraft:" (or any other namespace). */
    public static String path(String id) {
        if (id == null) {
            return "";
        }
        int colon = id.indexOf(':');
        return (colon >= 0 ? id.substring(colon + 1) : id).trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Items needed for {@code count} blocks of this state.
     *
     * @param block block id, with or without namespace
     * @param props block state properties (may be empty or null)
     */
    public static void items(String block, Map<String, String> props, long count, Sink out) {
        if (count <= 0) {
            return;
        }
        String name = path(block);
        if (name.isEmpty() || SKIP.contains(name)) {
            return;
        }
        Map<String, String> p = props == null ? Map.of() : props;

        // Two-block-tall things are counted once (doors, tall flowers, small dripleaf…). Stairs and trapdoors use top/bottom.
        if ("upper".equals(p.get("half"))) {
            return;
        }
        if (name.endsWith("_bed") && "head".equals(p.get("part"))) {
            return;
        }
        // Liquids: only sources can be placed (with a bucket).
        if (name.equals("water") || name.equals("lava")) {
            String level = p.get("level");
            if (level == null || level.equals("0")) {
                out.add(name + "_bucket", count);
            }
            return;
        }
        // Wall-mounted variants are placed with the normal item: wall_torch → torch, oak_wall_sign → oak_sign…
        if (name.startsWith("wall_")) {
            name = name.substring(5);
        }
        name = name.replace("_wall_", "_");

        if (name.startsWith("potted_")) {
            out.add("flower_pot", count);
            String plant = name.substring(7);
            out.add(RENAME.getOrDefault(plant, plant), count);
            return;
        }
        if (name.equals("candle_cake") || name.endsWith("_candle_cake")) {
            out.add("cake", count);
            out.add(name.substring(0, name.length() - "_cake".length()), count);
            return;
        }

        long mult = 1;
        if (name.endsWith("_slab") && "double".equals(p.get("type"))) {
            mult = 2;
        } else if (name.equals("snow")) {
            mult = intProp(p, "layers", 1, 8);
        } else {
            for (String key : AMOUNT_PROPS) {
                if (p.containsKey(key)) {
                    mult = intProp(p, key, 1, 8);
                    break;
                }
            }
        }
        out.add(RENAME.getOrDefault(name, name), count * mult);
    }

    private static long intProp(Map<String, String> p, String key, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(p.get(key))));
        } catch (RuntimeException e) {
            return min;
        }
    }

    /** Parses "minecraft:oak_door[facing=north,half=upper]" into the id and its properties. */
    public static String[] split(String state) {
        int b = state.indexOf('[');
        if (b < 0) {
            return new String[]{state, ""};
        }
        int e = state.lastIndexOf(']');
        return new String[]{state.substring(0, b), state.substring(b + 1, e > b ? e : state.length())};
    }

    public static Map<String, String> props(String inside) {
        if (inside == null || inside.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new java.util.HashMap<>();
        for (String part : inside.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return out;
    }
}
