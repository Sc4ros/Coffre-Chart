package fr.coffres.game;

import com.destroystokyo.paper.ParticleBuilder;
import fr.coffres.CoffresPlugin;
import fr.coffres.Msg;
import fr.coffres.model.StoredChest;
import fr.coffres.track.Containers;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Localiser en jeu": makes containers glow through walls for one player only,
 * draws a light beam above them and shows the direction in the action bar.
 */
public final class Locator {

    private static final Color BLUE = Color.fromRGB(0x0088FF);
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private final CoffresPlugin plugin;
    private final Containers containers;
    private final Map<UUID, Run> runs = new HashMap<>();
    private int durationSeconds = 60;

    private final class Run {
        final List<StoredChest> targets;
        final List<ItemDisplay> displays = new ArrayList<>();
        final java.util.Set<String> glowing = new java.util.HashSet<>();
        BukkitTask task;
        int ticks;
        boolean arrivedSaid;

        Run(List<StoredChest> targets) {
            this.targets = targets;
        }
    }

    public Locator(CoffresPlugin plugin, Containers containers) {
        this.plugin = plugin;
        this.containers = containers;
    }

    public void setDurationSeconds(int s) {
        durationSeconds = Math.max(5, s);
    }

    public int durationSeconds() {
        return durationSeconds;
    }

    /** Starts highlighting. Returns a short French message for the site. */
    public String locate(Player p, List<StoredChest> chests) {
        stop(p.getUniqueId());
        String world = p.getWorld().getName();
        List<StoredChest> here = new ArrayList<>();
        for (StoredChest c : chests) {
            if (c.world.equals(world)) {
                here.add(c);
            }
        }
        if (here.isEmpty()) {
            StoredChest first = chests.getFirst();
            String where = Containers.dimensionName(first.dim);
            Msg.send(p, "<yellow><name></yellow> <gray>est dans <where> (<x> <y> <z>).</gray>",
                    Placeholder.unparsed("name", first.name), Placeholder.unparsed("where", where),
                    Placeholder.unparsed("x", String.valueOf(first.x)), Placeholder.unparsed("y", String.valueOf(first.y)),
                    Placeholder.unparsed("z", String.valueOf(first.z)));
            return "Ce coffre est dans " + where + ", pas dans le monde où tu es.";
        }
        here.sort((a, b) -> Double.compare(dist2(p, a), dist2(p, b)));
        Run run = new Run(here.size() > 8 ? new ArrayList<>(here.subList(0, 8)) : here);
        spawnLoaded(p, run);
        runs.put(p.getUniqueId(), run);
        int maxTicks = durationSeconds * 20;
        run.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            run.ticks += 4;
            if (!p.isOnline() || run.ticks > maxTicks || !p.getWorld().getName().equals(world)) {
                stop(p.getUniqueId());
                return;
            }
            effects(p, run);
        }, 1L, 4L);

        StoredChest near = run.targets.getFirst();
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.7f);
        String dir = direction(p, near);
        Msg.send(p, "<aqua>📍</aqua> <white><name></white>" + (near.zone != null ? " <gray>(<zone>)</gray>" : "")
                        + " <gray>en</gray> <white><x> <y> <z></white> <dark_gray>·</dark_gray> <gray><dist> blocs <dir></gray>",
                Placeholder.unparsed("name", near.name), Placeholder.unparsed("zone", near.zone == null ? "" : near.zone),
                Placeholder.unparsed("x", String.valueOf(near.x)), Placeholder.unparsed("y", String.valueOf(near.y)),
                Placeholder.unparsed("z", String.valueOf(near.z)),
                Placeholder.unparsed("dist", String.valueOf(Math.round(Math.sqrt(dist2(p, near))))),
                Placeholder.unparsed("dir", dir));
        if (run.targets.size() > 1) {
            Msg.send(p, "<gray>" + run.targets.size() + " coffres brillent en bleu pendant " + durationSeconds + " s.</gray>");
        }
        return run.targets.size() > 1
                ? run.targets.size() + " coffres brillent en jeu pendant " + durationSeconds + " s."
                : "« " + near.name + " » brille en jeu pendant " + durationSeconds + " s.";
    }

    /** Glow for every target whose chunk is loaded (never loads chunks; far chests light up as the player gets closer). */
    private void spawnLoaded(Player p, Run run) {
        World w = p.getWorld();
        for (StoredChest c : run.targets) {
            if (run.glowing.contains(c.id) || !w.isChunkLoaded(c.x >> 4, c.z >> 4)) {
                continue;
            }
            run.glowing.add(c.id);
            for (Block half : containers.halves(w.getBlockAt(c.x, c.y, c.z))) {
                spawnGlow(p, half, c, run);
            }
        }
    }

    private void spawnGlow(Player p, Block b, StoredChest c, Run run) {
        Material mat = Material.matchMaterial(c.type == null ? "chest" : c.type);
        if (mat == null || !mat.isItem()) {
            mat = Material.CHEST;
        }
        float yaw = 0;
        BlockData data = b.getBlockData();
        if (data instanceof Directional d) {
            yaw = yawOf(d.getFacing());
        }
        Location center = b.getLocation().add(0.5, 0.5, 0.5);
        center.setYaw(yaw);
        Material item = mat;
        ItemDisplay d = b.getWorld().spawn(center, ItemDisplay.class, ent -> {
            ent.setItemStack(new ItemStack(item));
            ent.setPersistent(false);
            ent.setVisibleByDefault(false);
            ent.setGlowing(true);
            ent.setGlowColorOverride(BLUE);
            ent.setBrightness(new Display.Brightness(15, 15));
            ent.setViewRange(3f);
            ent.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1.04f, 1.04f, 1.04f), new AxisAngle4f()));
        });
        p.showEntity(plugin, d);
        run.displays.add(d);
    }

    private static float yawOf(BlockFace f) {
        return switch (f) {
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> -90f;
            default -> 0f;
        };
    }

    private void effects(Player p, Run run) {
        if (run.ticks % 20 == 0 && run.glowing.size() < run.targets.size()) {
            spawnLoaded(p, run);
        }
        boolean beam = run.ticks % 8 == 0;
        for (StoredChest c : run.targets) {
            Location top = new Location(p.getWorld(), c.x + 0.5, c.y + 1.1, c.z + 0.5);
            new ParticleBuilder(Particle.END_ROD).location(top).count(2).offset(0.2, 0.1, 0.2).extra(0.01).receivers(p).force(true).spawn();
            if (beam) {
                for (int i = 0; i < 14; i += 2) {
                    new ParticleBuilder(Particle.DUST).location(top.clone().add(0, i, 0)).count(1).color(BLUE, 1.6f)
                            .receivers(p).force(true).spawn();
                }
            }
        }
        StoredChest near = run.targets.getFirst();
        double best = dist2(p, near);
        for (StoredChest c : run.targets) {
            double d = dist2(p, c);
            if (d < best) {
                best = d;
                near = c;
            }
        }
        long dist = Math.round(Math.sqrt(best));
        if (dist <= 3) {
            if (!run.arrivedSaid) {
                run.arrivedSaid = true;
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
            }
            Msg.actionBar(p, "<green>✔ Tu y es !</green> <white><name></white>", Placeholder.unparsed("name", near.name));
            return;
        }
        int dy = near.y - p.getLocation().getBlockY();
        String height = dy > 2 ? " <gray>· ↑ " + dy + "</gray>" : dy < -2 ? " <gray>· ↓ " + (-dy) + "</gray>" : "";
        Msg.actionBar(p, "<aqua>" + arrow(p, near) + "</aqua> <white><name></white> <gray>· " + dist + " m</gray>" + height,
                Placeholder.unparsed("name", near.name));
    }

    private static double dist2(Player p, StoredChest c) {
        Location l = p.getLocation();
        double dx = c.x + 0.5 - l.getX();
        double dy = c.y + 0.5 - l.getY();
        double dz = c.z + 0.5 - l.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Arrow relative to where the player looks. */
    private static String arrow(Player p, StoredChest c) {
        Location l = p.getLocation();
        double dx = c.x + 0.5 - l.getX();
        double dz = c.z + 0.5 - l.getZ();
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((target - l.getYaw()) % 360 + 540) % 360 - 180;
        int idx = (int) Math.round(rel / 45.0);
        return ARROWS[((idx % 8) + 8) % 8];
    }

    /** "vers le nord-est" style direction. */
    public static String direction(Player p, StoredChest c) {
        Location l = p.getLocation();
        double dx = c.x + 0.5 - l.getX();
        double dz = c.z + 0.5 - l.getZ();
        if (dx * dx + dz * dz < 4) {
            return "juste ici";
        }
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        String[] names = {"nord", "nord-est", "est", "sud-est", "sud", "sud-ouest", "ouest", "nord-ouest"};
        int idx = (int) Math.round(((angle % 360) + 360) % 360 / 45.0) % 8;
        return "vers le " + names[idx];
    }

    public void stop(UUID player) {
        Run run = runs.remove(player);
        if (run == null) {
            return;
        }
        if (run.task != null) {
            run.task.cancel();
        }
        for (ItemDisplay d : run.displays) {
            if (d.isValid()) {
                d.remove();
            }
        }
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            p.sendActionBar(net.kyori.adventure.text.Component.empty());
        }
    }

    public void stopAll() {
        for (UUID u : new ArrayList<>(runs.keySet())) {
            stop(u);
        }
    }
}
