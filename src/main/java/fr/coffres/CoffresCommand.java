package fr.coffres;

import fr.coffres.game.Locator;
import fr.coffres.model.StoredChest;
import fr.coffres.store.DataStore;
import fr.coffres.track.Containers;
import fr.coffres.web.AssetManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** /coffres and its sub-commands (French names, with a few aliases). */
public final class CoffresCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("ajouter", "web", "chercher", "liste", "projet", "nom", "zone", "prive", "public",
            "info", "retirer", "localiser", "deconnexion", "aide");

    private final CoffresPlugin plugin;

    public CoffresCommand(CoffresPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String rest = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";

        if (sub.equals("reload")) {
            if (!sender.hasPermission("coffres.admin")) {
                Msg.send(sender, "<red>Réservé aux admins.</red>");
                return true;
            }
            plugin.reload();
            Msg.send(sender, "<green>Configuration rechargée.</green> <gray>Site : " + plugin.publicUrl() + "</gray>");
            return true;
        }
        if (!(sender instanceof Player p)) {
            Msg.send(sender, "<gray>Site : " + plugin.publicUrl() + " · /coffres reload pour recharger la config.</gray>");
            return true;
        }

        switch (sub) {
            case "" -> plugin.menus().openMain(p);
            case "ajouter", "lier", "mode", "add" -> plugin.linkMode().toggle(p);
            case "web", "site", "connexion", "app" -> plugin.sendWebAccess(p);
            case "chercher", "recherche", "trouver", "search" -> {
                if (rest.isBlank()) {
                    Msg.send(p, "<gray>Utilise</gray> <white>/coffres chercher \\<objet></white> <gray>(ex. diamant).</gray>");
                } else {
                    search(p, rest);
                }
            }
            case "liste", "list" -> plugin.menus().openList(p, 0);
            case "projet", "projets", "project", "projects" -> plugin.projectsGame().command(p, Arrays.copyOfRange(args, 1, args.length));
            case "nom", "renommer", "rename" -> {
                StoredChest c = target(p, true);
                if (c == null) {
                    return true;
                }
                if (rest.isBlank()) {
                    Msg.send(p, "<gray>Utilise</gray> <white>/coffres nom \\<nouveau nom></white>");
                    return true;
                }
                plugin.store().edit(c.id, rest, null, null);
                Msg.send(p, "<green>✔</green> <gray>Renommé en</gray> <white><name></white>", Placeholder.unparsed("name", DataStore.clean(rest, 40)));
            }
            case "zone" -> {
                StoredChest c = target(p, true);
                if (c == null) {
                    return true;
                }
                String z = rest.equals("-") ? "" : rest;
                plugin.store().edit(c.id, null, z, null);
                Msg.send(p, z.isBlank() ? "<green>✔</green> <gray>Zone retirée.</gray>"
                        : "<green>✔</green> <gray>Rangé dans la zone</gray> <white><zone></white>", Placeholder.unparsed("zone", DataStore.clean(z, 32)));
            }
            case "prive", "privé", "private" -> visibility(p, false);
            case "public" -> visibility(p, true);
            case "info" -> info(p);
            case "retirer", "supprimer", "remove" -> {
                StoredChest c = target(p, false);
                if (c != null) {
                    plugin.tracker().remove(p, c);
                }
            }
            case "localiser", "locate" -> {
                List<StoredChest> list = new ArrayList<>();
                for (int i = 1; i < args.length; i++) {
                    StoredChest c = plugin.store().get(args[i]);
                    if (c != null && visible(p, c)) {
                        list.add(c);
                    }
                }
                if (list.isEmpty()) {
                    Msg.send(p, "<red>Coffre introuvable.</red>");
                } else {
                    plugin.locator().locate(p, list);
                }
            }
            case "deconnexion", "logout" -> {
                int n = plugin.auth().revokeAll(p.getUniqueId());
                Msg.send(p, "<green>✔</green> <gray>Déconnecté du site sur " + n + " appareil(s).</gray>");
            }
            default -> help(p);
        }
        return true;
    }

    private void help(Player p) {
        Msg.send(p, "<white>Commandes</white>");
        String[][] lines = {
                {"/coffres", "ouvre le menu"},
                {"/coffres ajouter", "mode ajout : clic droit sur des coffres pour les compter"},
                {"/coffres web", "lien et code pour le site (téléphone, PC)"},
                {"/coffres chercher <objet>", "trouver un objet dans les coffres"},
                {"/coffres projet", "projets de construction : blocs qu'il faut, avancement"},
                {"/coffres projet lier <projet>", "clic droit sur des coffres pour les lier au projet"},
                {"/coffres nom <nom>", "renommer le coffre que tu regardes"},
                {"/coffres zone <zone>", "ranger le coffre dans une zone (Base, Ferme…)"},
                {"/coffres prive | public", "qui le voit sur le site"},
                {"/coffres info", "infos sur le coffre que tu regardes"},
                {"/coffres retirer", "ne plus suivre le coffre que tu regardes"},
                {"/coffres deconnexion", "te déconnecter du site partout"},
        };
        for (String[] l : lines) {
            String suggest = l[0].contains("<") ? l[0].substring(0, l[0].indexOf('<')) : l[0].split(" \\|")[0];
            p.sendMessage(Component.text("  ")
                    .append(Component.text(l[0], NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.suggestCommand(suggest))
                            .hoverEvent(HoverEvent.showText(Component.text("Cliquer pour écrire la commande"))))
                    .append(Component.text(" · ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(l[1], NamedTextColor.GRAY)));
        }
    }

    /** The registered container the player looks at (or the one they just added). */
    private StoredChest target(Player p, boolean allowLast) {
        Block b = p.getTargetBlockExact(6);
        StoredChest c = b == null ? null : plugin.tracker().chestOf(b);
        if (c == null && allowLast && (b == null || !plugin.containers().isTracked(b.getType()))) {
            c = plugin.store().get(plugin.tracker().lastLinked(p.getUniqueId()));
        }
        if (c == null) {
            if (b != null && plugin.containers().isTracked(b.getType())) {
                Msg.send(p, "<yellow>Ce coffre n'est pas encore suivi.</yellow> <click:run_command:'/coffres ajouter'><aqua>[Mode ajout]</aqua></click>");
            } else {
                Msg.send(p, "<yellow>Regarde un coffre suivi (à moins de 6 blocs).</yellow>");
            }
            return null;
        }
        if (!plugin.tracker().canManage(p, c)) {
            Msg.send(p, "<red>Ce coffre est à <owner>.</red>", Placeholder.unparsed("owner", c.ownerName == null ? "quelqu'un d'autre" : c.ownerName));
            return null;
        }
        return c;
    }

    private void visibility(Player p, boolean pub) {
        StoredChest c = target(p, true);
        if (c == null) {
            return;
        }
        plugin.store().edit(c.id, null, null, pub);
        Msg.send(p, pub ? "<green>✔</green> <white><name></white> <gray>est visible par tout le serveur sur le site.</gray>"
                : "<green>✔</green> <white><name></white> <gray>est privé : seuls toi et les admins le voient.</gray>", Placeholder.unparsed("name", c.name));
    }

    private void info(Player p) {
        Block b = p.getTargetBlockExact(6);
        StoredChest c = b == null ? null : plugin.tracker().chestOf(b);
        if (c == null || !visible(p, c)) {
            Msg.send(p, "<yellow>Regarde un coffre suivi.</yellow>");
            return;
        }
        Msg.send(p, "<white><b><name></b></white>" + (c.pub ? "" : " <gold>🔒</gold>"), Placeholder.unparsed("name", c.name));
        Msg.raw(p, "  <gray>Zone :</gray> <white><zone></white> <dark_gray>·</dark_gray> <gray>ajouté par</gray> <white><owner></white>",
                Placeholder.unparsed("zone", c.zone == null ? "aucune" : c.zone),
                Placeholder.unparsed("owner", c.ownerName == null ? "?" : c.ownerName));
        Msg.raw(p, "  <gray>" + Msg.number(c.itemCount()) + " objets · " + c.slots.size() + "/" + c.size + " cases"
                + (c.lastPlayer != null ? " · dernier passage :</gray> <white><last></white>" : "</gray>"),
                Placeholder.unparsed("last", c.lastPlayer == null ? "" : c.lastPlayer));
        c.totals().entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                .limit(5)
                .forEach(e -> p.sendMessage(Component.text("   • ", NamedTextColor.DARK_GRAY)
                        .append(itemName(e.getKey()).color(NamedTextColor.GRAY))
                        .append(Component.text(" ×" + Msg.number(e.getValue()), NamedTextColor.WHITE))));
    }

    private boolean visible(Player p, StoredChest c) {
        return c.pub || p.hasPermission("coffres.admin") || p.getUniqueId().toString().equals(c.owner);
    }

    private static Component itemName(String id) {
        Material m = Material.matchMaterial(id);
        return m != null ? Component.translatable(m.translationKey()) : Component.text(id);
    }

    /** Finds an item in the containers the player can see, lists where it is and makes the closest ones glow. */
    private void search(Player p, String query) {
        String q = AssetManager.normalize(query);
        String qid = q.replace(' ', '_');
        AssetManager assets = plugin.assets();
        Set<String> candidates = new LinkedHashSet<>(assets.searchNames(query));

        Map<String, Long> totals = new HashMap<>();
        Map<String, List<StoredChest>> where = new HashMap<>();
        for (StoredChest c : plugin.store().all()) {
            if (!visible(p, c)) {
                continue;
            }
            for (Map.Entry<String, Long> e : c.totals().entrySet()) {
                String id = e.getKey();
                if (candidates.contains(id) || id.contains(qid)) {
                    totals.merge(id, e.getValue(), Long::sum);
                    where.computeIfAbsent(id, k -> new ArrayList<>()).add(c);
                }
            }
        }
        if (totals.isEmpty()) {
            Msg.send(p, "<gray>Aucun</gray> <white><q></white> <gray>dans les coffres suivis.</gray>", Placeholder.unparsed("q", query));
            return;
        }
        List<String> ids = new ArrayList<>(totals.keySet());
        ids.sort((a, b) -> {
            boolean ea = AssetManager.normalize(assets.displayName(a)).equals(q) || a.equals(qid);
            boolean eb = AssetManager.normalize(assets.displayName(b)).equals(q) || b.equals(qid);
            if (ea != eb) {
                return ea ? -1 : 1;
            }
            return Long.compare(totals.get(b), totals.get(a));
        });
        String world = p.getWorld().getName();
        Msg.send(p, "<gray>Résultats pour</gray> <white><q></white>", Placeholder.unparsed("q", query));
        for (int i = 0; i < Math.min(4, ids.size()); i++) {
            String id = ids.get(i);
            List<StoredChest> chests = where.get(id);
            chests.sort((a, b) -> Double.compare(distance(p, a, world), distance(p, b, world)));
            p.sendMessage(Component.text(" ▸ ", NamedTextColor.DARK_GRAY)
                    .append(itemName(id).color(NamedTextColor.WHITE))
                    .append(Component.text(" ×" + Msg.number(totals.get(id)), NamedTextColor.AQUA))
                    .append(Component.text(" dans " + chests.size() + " coffre(s)", NamedTextColor.GRAY)));
            for (int j = 0; j < Math.min(3, chests.size()); j++) {
                StoredChest c = chests.get(j);
                Long n = c.totals().get(id);
                String dist = c.world.equals(world) ? Math.round(distance(p, c, world)) + " m " + Locator.direction(p, c) : Containers.dimensionName(c.dim);
                Component locate = Component.text("[📍]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/coffres localiser " + c.id))
                        .hoverEvent(HoverEvent.showText(Component.text("Faire briller ce coffre")));
                p.sendMessage(Component.text("     ")
                        .append(locate)
                        .append(Component.text(" " + c.name, NamedTextColor.WHITE))
                        .append(Component.text(c.zone == null ? "" : " (" + c.zone + ")", NamedTextColor.GRAY))
                        .append(Component.text(" ×" + Msg.number(n == null ? 0 : n), NamedTextColor.WHITE))
                        .append(Component.text(" · " + dist, NamedTextColor.DARK_GRAY)));
            }
        }
        List<StoredChest> best = where.get(ids.getFirst());
        List<StoredChest> glow = new ArrayList<>();
        for (StoredChest c : best) {
            if (c.world.equals(world) && glow.size() < 4) {
                glow.add(c);
            }
        }
        if (!glow.isEmpty()) {
            plugin.locator().locate(p, glow);
        }
    }

    private static double distance(Player p, StoredChest c, String world) {
        if (!c.world.equals(world)) {
            return Double.MAX_VALUE;
        }
        double dx = c.x + 0.5 - p.getLocation().getX();
        double dy = c.y + 0.5 - p.getLocation().getY();
        double dz = c.z + 0.5 - p.getLocation().getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : SUBS) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            if (sender.hasPermission("coffres.admin") && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                out.add("reload");
            }
            return out;
        }
        if (args.length >= 2 && (args[0].equalsIgnoreCase("projet") || args[0].equalsIgnoreCase("projets")) && sender instanceof Player p) {
            if (args.length == 2) {
                List<String> out = new ArrayList<>();
                for (String s : List.of("lier", "aide")) {
                    if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        out.add(s);
                    }
                }
                out.addAll(plugin.projectsGame().names(p, args[1]));
                return out;
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("lier")) {
                return plugin.projectsGame().names(p, args[2]);
            }
            return List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("zone")) {
            Set<String> zones = new LinkedHashSet<>();
            for (StoredChest c : plugin.store().all()) {
                if (c.zone != null && c.zone.toLowerCase().startsWith(args[1].toLowerCase())) {
                    zones.add(c.zone);
                }
            }
            return new ArrayList<>(zones);
        }
        return List.of();
    }
}
