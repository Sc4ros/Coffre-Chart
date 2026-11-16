package fr.coffres;

import fr.coffres.model.Project;
import fr.coffres.model.StoredChest;
import fr.coffres.model.Placement;
import fr.coffres.project.Build;
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

    public boolean canSee(Player p, Project pr) {
        return pr.shared || p.hasPermission("coffres.admin") || p.getUniqueId().toString().equals(pr.owner) || inGroup(p, pr);
    }

    /** Link containers and check the build again (same people as the site). */
    public boolean canLink(Player p, Project pr) {
        return canSee(p, pr);
    }

    private boolean inGroup(Player p, Project pr) {
        return !pr.shared && pr.group != null && plugin.groups().isMember(pr.group, p.getUniqueId().toString());
    }

    /** "Projet commun", "Groupe « Les potes »" or "Projet solo". */
    public String visibilityText(Project pr) {
        if (pr.shared) {
            return "Projet commun";
        }
        fr.coffres.model.Group g = pr.group == null ? null : plugin.groups().get(pr.group);
        return g != null ? "Groupe « " + g.name + " »" : "Projet solo";
    }

    /** Who may count with their inventory near the build: null = everyone (shared project). */
    public java.util.Set<String> people(Project pr) {
        if (pr.shared) {
            return null;
        }
        java.util.Set<String> out = new java.util.HashSet<>();
        if (pr.group != null) {
            out.addAll(plugin.groups().people(pr.group));
        }
        out.add(pr.owner);
        return out;
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
                Build b = pr.placement == null ? null : plugin.plans().build(pr.id);
                return ProjectProgress.of(pr, store.chestsLocked(), c -> canSee(p, c), store.stackSizesLocked(),
                        b != null && b.at == pr.placement ? b.placedItems() : null,
                        plugin.plans().carried(pr.id).items());
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
            case "placer", "place" -> place(p, java.util.Arrays.copyOfRange(args, 1, args.length));
            case "verifier", "vérifier", "check" -> {
                if (rest.isBlank()) {
                    Msg.send(p, "<gray>Utilise</gray> <white>/coffres projet verifier \\<projet></white>");
                    return;
                }
                Project pr = find(p, rest);
                if (pr == null) {
                    notFound(p, rest);
                } else {
                    check(p, pr);
                }
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
        Msg.raw(p, "  <aqua>/coffres projet placer \\<nom> [rotation] [miroir]</aqua> <dark_gray>·</dark_gray> <gray>le plan commence à tes pieds : "
                + "Coffres compte les blocs déjà posés</gray>");
        Msg.raw(p, "  <aqua>/coffres projet verifier \\<nom></aqua> <dark_gray>·</dark_gray> <gray>revérifie tous les blocs posés</gray>");
        Msg.raw(p, "  <gray>Pour créer un projet, envoie ton fichier <white>.litematic</white> ou <white>.schem</white> sur le site :</gray> "
                + "<click:run_command:'/coffres web'><aqua>[Ouvrir le site]</aqua></click>");
    }

    private void notFound(Player p, String query) {
        Msg.send(p, "<yellow>Aucun projet</yellow> <white><q></white><yellow>.</yellow> <click:run_command:'/coffres projet'><aqua>[Voir les projets]</aqua></click>",
                Placeholder.unparsed("q", query));
    }

    /**
     * /coffres projet placer &lt;nom…&gt; [0|90|180|270] [aucun|gauche-droite|avant-arriere]: the plan's origin goes to
     * the block where the player stands.
     */
    private void place(Player p, String[] args) {
        List<String> words = new ArrayList<>(java.util.Arrays.asList(args));
        String mirror = "none";
        int rot = 0;
        if (!words.isEmpty()) {
            String m = mirrorArg(words.getLast());
            if (m != null) {
                mirror = m;
                words.removeLast();
            }
        }
        if (!words.isEmpty() && words.getLast().matches("-?\\d{1,3}°?")) {
            int r = Integer.parseInt(words.getLast().replace("°", ""));
            if (Placement.normRot(r) != r && r != 360) {
                Msg.send(p, "<red>La rotation doit être 0, 90, 180 ou 270.</red>");
                return;
            }
            rot = Placement.normRot(r);
            words.removeLast();
        }
        String query = String.join(" ", words);
        if (query.isBlank()) {
            Msg.send(p, "<gray>Utilise</gray> <white>/coffres projet placer \\<projet> [0|90|180|270] [aucun|gauche-droite|avant-arriere]</white>");
            Msg.raw(p, "  <dark_gray>Le plan commence au bloc où tu te tiens (l'origine de Litematica, ou le coin le plus bas pour les autres fichiers).</dark_gray>");
            return;
        }
        Project pr = find(p, query);
        if (pr == null) {
            notFound(p, query);
            return;
        }
        if (!(p.hasPermission("coffres.admin") || p.getUniqueId().toString().equals(pr.owner))) {
            Msg.send(p, "<red>Seul le créateur du projet (ou un admin) peut le placer.</red>");
            return;
        }
        if (pr.planBlocks <= 0) {
            Msg.send(p, "<yellow><why></yellow>", Placeholder.unparsed("why", pr.planNote != null ? pr.planNote
                    : "Ce projet a été créé avant la vérification des blocs posés : renvoie son fichier sur le site."));
            return;
        }
        org.bukkit.Location l = p.getLocation();
        Placement at = new Placement(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), rot, mirror);
        at.by = p.getName();
        at.at = System.currentTimeMillis();
        plugin.projects().setPlacement(pr.id, at);
        Project fresh = plugin.projects().get(pr.id);
        String name = pr.name;
        Msg.send(p, "<green>✔</green> <light_purple><project></light_purple> <gray>placé en</gray> <white>" + at.x + " " + at.y + " " + at.z
                        + "</white> <dark_gray>(rotation " + at.rot + "°" + ("none".equals(at.mirror) ? "" : ", miroir") + ")</dark_gray> "
                        + "<gray>· vérification des blocs posés…</gray>",
                Placeholder.unparsed("project", name));
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Build b = fresh == null ? null : plugin.plans().activate(fresh);
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (b == null) {
                    Msg.send(p, "<red>Le plan de ce projet est illisible : renvoie son fichier sur le site.</red>");
                } else {
                    plugin.buildScanner().rescan(pr.id, p.getUniqueId());
                }
            });
        });
    }

    private static String mirrorArg(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "aucun", "none", "non" -> "none";
            case "gauche-droite", "left-right", "lr" -> "lr";
            case "avant-arriere", "avant-arrière", "front-back", "fb" -> "fb";
            default -> null;
        };
    }

    /** /coffres projet verifier: checks every block again. */
    public void check(Player p, Project pr) {
        if (pr.placement == null || plugin.plans().build(pr.id) == null) {
            Msg.send(p, "<yellow>Ce projet n'est pas encore placé dans le monde.</yellow> <gray>Sur le site, ou en jeu :</gray> "
                    + "<white>/coffres projet placer <name></white>", Placeholder.unparsed("name", pr.name));
            return;
        }
        plugin.buildScanner().rescan(pr.id, p.getUniqueId());
        Msg.send(p, "<gray>Vérification des blocs posés de</gray> <light_purple><project></light_purple><gray>…</gray>",
                Placeholder.unparsed("project", pr.name));
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
            Msg.send(p, "<red>Ce projet est à <owner> : seuls lui, son groupe (ou un admin) peuvent y lier des coffres.</red>",
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
