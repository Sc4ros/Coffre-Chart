package fr.coffres;

import fr.coffres.model.Project;
import fr.coffres.model.StoredChest;
import fr.coffres.project.ProjectProgress;
import fr.coffres.store.DataStore;
import fr.coffres.store.ProjectStore;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Building projects in game: /coffres projet, linking containers with a right click, progress for a player.
 * Projects are created on the site (that is where the .litematic / .schem file is sent).
 */
public final class ProjectsInGame {

    private final CoffresPlugin plugin;

    public ProjectsInGame(CoffresPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ rules (same as the site)

    public static boolean canSee(Player p, Project pr) {
        return pr.shared || p.hasPermission("coffres.admin") || p.getUniqueId().toString().equals(pr.owner);
    }

    public static boolean canLink(Player p, Project pr) {
        return pr.shared || p.hasPermission("coffres.admin") || p.getUniqueId().toString().equals(pr.owner);
    }

    static boolean canSee(Player p, StoredChest c) {
        return c.pub || p.hasPermission("coffres.admin") || p.getUniqueId().toString().equals(c.owner);
    }

    /** Projects this player can see: unfinished first, then the most recently changed. */
    public List<Project> visible(Player p) {
        List<Project> out = new ArrayList<>();
        for (Project pr : plugin.projects().all()) {
            if (canSee(p, pr)) {
                out.add(pr);
            }
        }
        out.sort(Comparator.comparingLong((Project x) -> -x.updated));
        return out;
    }

    /** Progress as this player sees it (only containers they can see are counted). */
    public ProjectProgress progress(Player p, Project pr) {
        DataStore store = plugin.store();
        synchronized (store) {
            synchronized (plugin.projects()) {
                return ProjectProgress.of(pr, store.chestsLocked(), c -> canSee(p, c), store.stackSizesLocked());
            }
        }
    }

    /** Finds a visible project by id or name (exact name first, then the beginning of a name). */
    public Project find(Player p, String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return null;
        }
        List<Project> list = visible(p);
        for (Project pr : list) {
            if (pr.id.equals(q) || pr.name.toLowerCase(Locale.ROOT).equals(q)) {
                return pr;
            }
        }
        for (Project pr : list) {
            if (pr.name.toLowerCase(Locale.ROOT).startsWith(q)) {
                return pr;
            }
        }
        for (Project pr : list) {
            if (pr.name.toLowerCase(Locale.ROOT).contains(q)) {
                return pr;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ /coffres projet …

    /** {@code args} are the words after "projet". */
    public void command(Player p, String[] args) {
        if (args.length == 0) {
            plugin.menus().openProjects(p, 0);
            return;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        String rest = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        switch (first) {
            case "lier", "link" -> {
                if (rest.isBlank()) {
                    Msg.send(p, "<gray>Utilise</gray> <white>/coffres projet lier \\<projet></white>");
                    return;
                }
                startLinking(p, rest);
            }
            case "arreter", "arrêter", "stop" -> {
                if (plugin.linkMode().isActive(p)) {
                    plugin.linkMode().stop(p, false);
                }
            }
            case "aide", "help" -> help(p);
            default -> {
                Project pr = find(p, String.join(" ", args));
                if (pr == null) {
                    notFound(p, String.join(" ", args));
                } else {
                    plugin.menus().openProject(p, pr.id, 0);
                }
            }
        }
    }

    private void help(Player p) {
        Msg.send(p, "<white>Projets de construction</white>");
        Msg.raw(p, "  <aqua>/coffres projet</aqua> <dark_gray>·</dark_gray> <gray>la liste des projets et leur avancement</gray>");
        Msg.raw(p, "  <aqua>/coffres projet \\<nom></aqua> <dark_gray>·</dark_gray> <gray>les blocs qu'il manque</gray>");
        Msg.raw(p, "  <aqua>/coffres projet lier \\<nom></aqua> <dark_gray>·</dark_gray> <gray>clic droit sur des coffres pour les lier au projet</gray>");
        Msg.raw(p, "  <gray>Pour créer un projet, envoie ton fichier <white>.litematic</white> ou <white>.schem</white> sur le site :</gray> "
                + "<click:run_command:'/coffres web'><aqua>[Ouvrir le site]</aqua></click>");
    }

    private void notFound(Player p, String query) {
        Msg.send(p, "<yellow>Aucun projet</yellow> <white><q></white><yellow>.</yellow> <click:run_command:'/coffres projet'><aqua>[Voir les projets]</aqua></click>",
                Placeholder.unparsed("q", query));
    }

    public void startLinking(Player p, String query) {
        Project pr = find(p, query);
        if (pr == null) {
            notFound(p, query);
            return;
        }
        startLinking(p, pr);
    }

    public void startLinking(Player p, Project pr) {
        if (!canLink(p, pr)) {
            Msg.send(p, "<red>Ce projet est à <owner> : seul lui (ou un admin) peut y lier des coffres.</red>",
                    Placeholder.unparsed("owner", pr.ownerName == null ? "quelqu'un d'autre" : pr.ownerName));
            return;
        }
        plugin.linkMode().toggleProject(p, pr.id, pr.name);
    }

    /** Right click in project mode: adds the container to Coffres if needed, then links or unlinks it. */
    public void linkAt(Player p, Block b, String projectId) {
        ProjectStore projects = plugin.projects();
        Project pr = projects.get(projectId);
        if (pr == null || !canLink(p, pr)) {
            plugin.linkMode().stop(p, false);
            Msg.send(p, "<red>Ce projet n'existe plus.</red>");
            return;
        }
        StoredChest c = plugin.tracker().chestOf(b);
        if (c == null) {
            c = plugin.tracker().add(p, b);
            if (c == null) {
                return;
            }
        }
        if (!canSee(p, c)) {
            Msg.send(p, "<red>Ce coffre est privé.</red>");
            return;
        }
        Boolean linked = projects.toggleChest(pr.id, c.id);
        if (linked == null) {
            return;
        }
        int n = projects.get(pr.id) == null ? 0 : projects.get(pr.id).chests.size();
        if (linked) {
            ProjectProgress progress = progress(p, pr);
            long useful = ProjectProgress.useful(pr, c);
            p.spawnParticle(Particle.WAX_ON, b.getLocation().add(0.5, 0.7, 0.5), 18, 0.35, 0.3, 0.35, 0);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.3f);
            Msg.send(p, "<green>✔</green> <white><chest></white> <gray>lié à</gray> <light_purple><project></light_purple> "
                            + "<dark_gray>·</dark_gray> <gray><useful> blocs utiles · projet à</gray> <white><pct> %</white>",
                    Placeholder.unparsed("chest", c.name), Placeholder.unparsed("project", pr.name),
                    Placeholder.unparsed("useful", Msg.number(useful)), Placeholder.unparsed("pct", String.valueOf(progress.percent())));
        } else {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.9f);
            Msg.send(p, "<yellow>✖</yellow> <white><chest></white> <gray>n'est plus lié à</gray> <light_purple><project></light_purple> "
                            + "<dark_gray>(" + n + " coffre" + (n > 1 ? "s" : "") + ")</dark_gray>",
                    Placeholder.unparsed("chest", c.name), Placeholder.unparsed("project", pr.name));
        }
    }

    /** Makes the linked containers glow (the closest ones first). */
    public void locateLinked(Player p, Project pr) {
        ProjectProgress progress = progress(p, pr);
        locateClosest(p, progress.linked, "Aucun coffre n'est lié à ce projet pour l'instant.");
    }

    /** Makes the closest visible containers that hold this item glow. */
    public void locateItem(Player p, String itemId) {
        List<StoredChest> with = new ArrayList<>();
        for (StoredChest c : plugin.store().all()) {
            Long n = c.totals().get(itemId);
            if (n != null && n > 0 && canSee(p, c)) {
                with.add(c);
            }
        }
        locateClosest(p, with, "Il n'y en a dans aucun coffre suivi.");
    }

    private void locateClosest(Player p, List<StoredChest> chests, String none) {
        String world = p.getWorld().getName();
        List<StoredChest> here = new ArrayList<>();
        for (StoredChest c : chests) {
            if (Objects.equals(c.world, world)) {
                here.add(c);
            }
        }
        if (here.isEmpty()) {
            Msg.send(p, "<yellow>" + (chests.isEmpty() ? none : "Ces coffres sont dans une autre dimension.") + "</yellow>");
            return;
        }
        here.sort(Comparator.comparingDouble(c -> p.getLocation().distanceSquared(new org.bukkit.Location(p.getWorld(), c.x + 0.5, c.y + 0.5, c.z + 0.5))));
        plugin.locator().locate(p, here.size() > 8 ? new ArrayList<>(here.subList(0, 8)) : here);
    }

    /** Names for tab completion. */
    public List<String> names(Player p, String prefix) {
        List<String> out = new ArrayList<>();
        String q = prefix.toLowerCase(Locale.ROOT);
        for (Project pr : visible(p)) {
            if (pr.name.toLowerCase(Locale.ROOT).startsWith(q)) {
                out.add(pr.name);
            }
        }
        return out;
    }

    /** "■■■■■■□□□□" */
    public static String bar(double ratio, int width) {
        int full = (int) Math.round(Math.max(0, Math.min(1, ratio)) * width);
        return "<green>" + "■".repeat(full) + "</green><dark_gray>" + "■".repeat(width - full) + "</dark_gray>";
    }

    /** "8 stacks + 8" or "40". */
    public static String stacks(long n, int stack) {
        if (stack <= 1 || n < stack) {
            return Msg.number(n);
        }
        long st = n / stack;
        long rest = n % stack;
        return Msg.number(n) + " (" + Msg.number(st) + " stack" + (st > 1 ? "s" : "") + (rest > 0 ? " + " + rest : "") + ")";
    }
}
