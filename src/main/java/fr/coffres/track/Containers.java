package fr.coffres.track;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Which blocks Coffres follows, and how to read them. */
public final class Containers {

    private final List<String> patterns = new ArrayList<>();

    public void setPatterns(List<String> list) {
        patterns.clear();
        for (String s : list) {
            patterns.add(s.trim().toUpperCase(Locale.ROOT));
        }
    }

    public boolean isTracked(Material m) {
        if (m == null || m == Material.ENDER_CHEST || !m.isBlock()) {
            return false;
        }
        String name = m.name();
        for (String p : patterns) {
            if (match(p, name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean match(String pattern, String name) {
        if (pattern.equals("*")) {
            return true;
        }
        boolean start = pattern.startsWith("*");
        boolean end = pattern.endsWith("*") && pattern.length() > 1;
        String core = pattern.substring(start ? 1 : 0, pattern.length() - (end ? 1 : 0));
        if (start && end) {
            return name.contains(core);
        }
        if (start) {
            return name.endsWith(core);
        }
        if (end) {
            return name.startsWith(core);
        }
        return name.equals(core);
    }

    /** Live inventory of a tracked container (whole double chest), or null. */
    public Inventory inventoryOf(Block b) {
        if (!isTracked(b.getType())) {
            return null;
        }
        BlockState st = b.getState(false);
        if (st instanceof Container c) {
            return c.getInventory();
        }
        return null;
    }

    /** The block itself, plus the other half when it is part of a double chest. */
    public List<Block> halves(Block b) {
        List<Block> out = new ArrayList<>(2);
        out.add(b);
        BlockState st = b.getState(false);
        if (st instanceof Chest chest && chest.getInventory() instanceof DoubleChestInventory dci) {
            for (Inventory side : new Inventory[]{dci.getLeftSide(), dci.getRightSide()}) {
                Location l = side.getLocation();
                if (l != null) {
                    Block other = l.getBlock();
                    if (!other.equals(b)) {
                        out.add(other);
                    }
                }
            }
        }
        return out;
    }

    /** Integer block positions a container inventory may be registered at (both halves for a double chest). */
    public static List<int[]> positionsOf(Inventory inv) {
        List<int[]> out = new ArrayList<>(2);
        if (inv instanceof DoubleChestInventory dci) {
            addPos(out, dci.getLeftSide().getLocation());
            addPos(out, dci.getRightSide().getLocation());
            return out;
        }
        addPos(out, inv.getLocation());
        return out;
    }

    private static void addPos(List<int[]> out, Location l) {
        if (l != null) {
            out.add(new int[]{l.getBlockX(), l.getBlockY(), l.getBlockZ()});
        }
    }

    public static String dimension(World w) {
        return switch (w.getEnvironment()) {
            case NORMAL -> "overworld";
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> "custom";
        };
    }

    public static String defaultName(Material m) {
        String n = m.name();
        if (n.equals("TRAPPED_CHEST")) return "Coffre piégé";
        if (n.equals("BARREL")) return "Tonneau";
        if (n.endsWith("SHULKER_BOX")) return "Shulker";
        if (n.contains("COPPER_CHEST")) return "Coffre en cuivre";
        if (n.endsWith("CHEST")) return "Coffre";
        return "Conteneur";
    }

    public static String dimensionName(String dim) {
        return switch (dim) {
            case "nether" -> "le Nether";
            case "end" -> "l'End";
            case "overworld" -> "le monde normal";
            default -> "un autre monde";
        };
    }
}
