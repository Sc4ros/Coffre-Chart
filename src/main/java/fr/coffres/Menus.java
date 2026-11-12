package fr.coffres;

import fr.coffres.model.Project;
import fr.coffres.model.StoredChest;
import fr.coffres.project.ProjectProgress;
import fr.coffres.track.Containers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The in-game menus opened with /coffres. */
public final class Menus implements Listener {

    private static final int PER_PAGE = 45;

    private final CoffresPlugin plugin;

    private static final class Holder implements InventoryHolder {
        final String kind;
        final int page;
        final Map<Integer, String> chestBySlot = new HashMap<>();
        /** Project id (project menu) or project ids / item ids by slot. */
        String project;
        final Map<Integer, String> idBySlot = new HashMap<>();
        Inventory inv;

        Holder(String kind, int page) {
            this.kind = kind;
            this.page = page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inv;
        }
    }

    public Menus(CoffresPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ main menu

    public void openMain(Player p) {
        Holder h = new Holder("main", 0);
        Inventory inv = Bukkit.createInventory(h, 27, Msg.parse("<gradient:#34C759:#0088FF><b>Coffres</b></gradient>"));
        h.inv = inv;
        fill(inv);

        List<StoredChest> visible = visibleChests(p);
        long items = 0;
        int mine = 0;
        for (StoredChest c : visible) {
            items += c.itemCount();
            if (p.getUniqueId().toString().equals(c.owner)) {
                mine++;
            }
        }
        boolean on = plugin.linkMode().isActive(p);
        inv.setItem(10, item(Material.CHEST, on ? "<green><b>Mode ajout : activé</b></green>" : "<aqua><b>Ajouter des coffres</b></aqua>", on,
                "<gray>Active le mode, puis fais un</gray>",
                "<gray><white>clic droit</white> sur tes coffres, tonneaux</gray>",
                "<gray>ou shulkers pour les compter.</gray>",
                "<gray>Reclique pour en retirer un.</gray>",
                "",
                on ? "<yellow>▶ Clique pour arrêter</yellow>" : "<yellow>▶ Clique pour activer</yellow>"));
        inv.setItem(12, item(Material.RECOVERY_COMPASS, "<aqua><b>Ouvrir le site</b></aqua>", false,
                "<gray>Ton inventaire sur téléphone et PC :</gray>",
                "<gray>graphiques, recherche, historique.</gray>",
                "",
                "<dark_gray>" + esc(plugin.publicUrl()) + "</dark_gray>",
                "",
                "<yellow>▶ Clique pour recevoir le lien</yellow>"));
        inv.setItem(14, item(Material.BARREL, "<aqua><b>Les coffres</b></aqua>", false,
                "<gray>" + visible.size() + " coffre(s) suivis, dont " + mine + " à toi.</gray>",
                "<gray>Clique sur un coffre pour le faire</gray>",
                "<gray>briller et le retrouver.</gray>",
                "",
                "<yellow>▶ Voir la liste</yellow>"));
        inv.setItem(16, item(Material.SPYGLASS, "<aqua><b>Chercher un objet</b></aqua>", false,
                "<gray>Où sont mes diamants ?</gray>",
                "<gray>Tape <white>/coffres chercher diamant</white></gray>",
                "",
                "<yellow>▶ Clique pour écrire la commande</yellow>"));
        List<Project> projects = plugin.projectsGame().visible(p);
        inv.setItem(21, item(Material.SCAFFOLDING, "<light_purple><b>Projets</b></light_purple>", false,
                "<gray>Tes constructions en cours : les blocs</gray>",
                "<gray>qu'il faut, ceux qu'on a déjà, ce qu'il manque.</gray>",
                "",
                "<gray>" + projects.size() + " projet(s).</gray>",
                "<dark_gray>Nouveau projet : envoie ton fichier</dark_gray>",
                "<dark_gray>.litematic ou .schem sur le site.</dark_gray>",
                "",
                "<yellow>▶ Voir les projets</yellow>"));
        inv.setItem(23, item(Material.KNOWLEDGE_BOOK, "<white><b>Résumé</b></white>", false,
                "<gray>" + Msg.number(items) + " objets au total</gray>",
                "<gray>dans " + visible.size() + " coffre(s).</gray>"));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ projects

    public void openProjects(Player p, int page) {
        List<Project> list = plugin.projectsGame().visible(p);
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Holder h = new Holder("projects", pg);
        Inventory inv = Bukkit.createInventory(h, 54, Msg.parse("<dark_gray>Projets" + (pages > 1 ? " · page " + (pg + 1) + "/" + pages : "") + "</dark_gray>"));
        h.inv = inv;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = pg * PER_PAGE + i;
            if (idx >= list.size()) {
                break;
            }
            Project pr = list.get(idx);
            inv.setItem(i, projectItem(p, pr));
            h.idBySlot.put(i, pr.id);
        }
        bottomBar(inv, pg, pages);
        if (list.isEmpty()) {
            inv.setItem(22, item(Material.SCAFFOLDING, "<gray>Aucun projet pour l'instant</gray>", false,
                    "<gray>Sur le site, onglet <white>Projets</white>, envoie</gray>",
                    "<gray>ton fichier <white>.litematic</white>, <white>.schem</white> ou <white>.nbt</white> :</gray>",
                    "<gray>Coffres compte les blocs qu'il faut.</gray>",
                    "",
                    "<yellow>▶ Clique pour recevoir le lien du site</yellow>"));
            h.idBySlot.put(22, "_web");
        }
        p.openInventory(inv);
    }

    private ItemStack projectItem(Player p, Project pr) {
        ProjectProgress progress = plugin.projectsGame().progress(p, pr);
        Material icon = Material.SCAFFOLDING;
        if (!pr.materials.isEmpty()) {
            Material m = Material.matchMaterial(pr.materials.keySet().iterator().next());
            if (m != null && m.isItem() && !m.isAir()) {
                icon = m;
            }
        }
        List<String> lore = new ArrayList<>();
        lore.add(ProjectsInGame.bar(progress.ratio(), 20) + " <white>" + progress.percent() + " %</white>");
        lore.add("<gray>" + Msg.number(progress.have) + " / " + Msg.number(progress.need) + " blocs · "
                + progress.doneKinds + "/" + progress.lines.size() + " sortes complètes</gray>");
        lore.add("<gray>" + (pr.shared ? "Projet commun" : "Projet solo") + " · par " + esc(pr.ownerName == null ? "?" : pr.ownerName)
                + " · " + progress.linked.size() + " coffre(s) lié(s)</gray>");
        List<ProjectProgress.Line> missing = progress.lines.stream().filter(l -> l.missing() > 0)
                .sorted((a, b) -> Long.compare(b.missing(), a.missing())).limit(4).toList();
        ItemStack it = item(icon, "<white><b>" + esc(pr.name) + "</b></white>", progress.percent() >= 100, lore.toArray(String[]::new));
        ItemMeta meta = it.getItemMeta();
        List<Component> l = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        if (!missing.isEmpty()) {
            l.add(Component.empty());
            l.add(line("<gray>Il manque surtout :</gray>"));
            for (ProjectProgress.Line m : missing) {
                l.add(Component.text("• ", NamedTextColor.DARK_GRAY)
                        .append(itemName(m.id()).color(NamedTextColor.GRAY))
                        .append(Component.text(" ×" + Msg.number(m.missing()), NamedTextColor.WHITE))
                        .decoration(TextDecoration.ITALIC, false));
            }
        } else {
            l.add(Component.empty());
            l.add(line("<green>✔ Tous les blocs sont dans les coffres !</green>"));
        }
        l.add(Component.empty());
        l.add(line("<yellow>▶ Clic : les blocs qu'il manque</yellow>"));
        if (ProjectsInGame.canLink(p, pr)) {
            l.add(line("<yellow>▶ Clic droit : lier des coffres</yellow>"));
        }
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    public void openProject(Player p, String projectId, int page) {
        Project pr = plugin.projects().get(projectId);
        if (pr == null || !ProjectsInGame.canSee(p, pr)) {
            openProjects(p, 0);
            return;
        }
        ProjectProgress progress = plugin.projectsGame().progress(p, pr);
        List<ProjectProgress.Line> lines = new ArrayList<>(progress.lines);
        // what is missing first (most missing first), then what is complete
        lines.sort((a, b) -> a.done() != b.done() ? (a.done() ? 1 : -1) : Long.compare(b.missing() != 0 ? b.missing() : b.need(), a.missing() != 0 ? a.missing() : a.need()));
        int pages = Math.max(1, (lines.size() + PER_PAGE - 1) / PER_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Holder h = new Holder("project", pg);
        h.project = pr.id;
        String title = pr.name.length() > 22 ? pr.name.substring(0, 21) + "…" : pr.name;
        Inventory inv = Bukkit.createInventory(h, 54, Msg.parse("<dark_gray><title> · " + progress.percent() + " %</dark_gray>",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("title", title)));
        h.inv = inv;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = pg * PER_PAGE + i;
            if (idx >= lines.size()) {
                break;
            }
            ProjectProgress.Line ln = lines.get(idx);
            inv.setItem(i, materialItem(ln));
            h.idBySlot.put(i, ln.id());
        }
        bottomBar(inv, pg, pages);
        inv.setItem(48, item(Material.SCAFFOLDING, "<light_purple><b>" + esc(pr.name) + "</b></light_purple>", false,
                ProjectsInGame.bar(progress.ratio(), 20) + " <white>" + progress.percent() + " %</white>",
                "<gray>" + Msg.number(progress.have) + " / " + Msg.number(progress.need) + " blocs dans les coffres liés</gray>",
                "<gray>Manque " + Msg.number(progress.missing()) + " blocs · " + (progress.lines.size() - progress.doneKinds) + " sorte(s) incomplète(s)</gray>",
                "",
                "<dark_gray>" + esc(pr.file == null ? "" : pr.file) + " · " + pr.sx + "×" + pr.sy + "×" + pr.sz + "</dark_gray>",
                "<dark_gray>" + (pr.shared ? "Projet commun" : "Projet solo") + " · par " + esc(pr.ownerName == null ? "?" : pr.ownerName) + "</dark_gray>"));
        if (ProjectsInGame.canLink(p, pr)) {
            boolean on = pr.id.equals(plugin.linkMode().projectOf(p));
            inv.setItem(47, item(Material.LEAD, on ? "<green><b>Liaison : activée</b></green>" : "<aqua><b>Lier des coffres</b></aqua>", on,
                    "<gray>" + progress.linked.size() + " coffre(s) lié(s) à ce projet.</gray>",
                    "<gray>Active, puis <white>clic droit</white> sur les coffres</gray>",
                    "<gray>qui contiennent les matériaux.</gray>",
                    "",
                    on ? "<yellow>▶ Clique pour arrêter</yellow>" : "<yellow>▶ Clique pour activer</yellow>"));
        }
        inv.setItem(50, item(Material.GLOWSTONE_DUST, "<aqua><b>Faire briller les coffres liés</b></aqua>", false,
                "<gray>Les coffres du projet s'allument</gray>",
                "<gray>à travers les murs.</gray>"));
        inv.setItem(51, item(Material.RECOVERY_COMPASS, "<aqua><b>Voir sur le site</b></aqua>", false,
                "<gray>Graphique, liste à copier, lier des</gray>",
                "<gray>coffres en quelques clics.</gray>"));
        p.openInventory(inv);
    }

    private ItemStack materialItem(ProjectProgress.Line ln) {
        Material m = Material.matchMaterial(ln.id());
        boolean known = m != null && m.isItem() && !m.isAir();
        ItemStack it = new ItemStack(known ? m : Material.PAPER);
        it.setAmount((int) Math.max(1, Math.min(it.getMaxStackSize(), ln.done() ? 1 : Math.min(64, ln.missing()))));
        ItemMeta meta = it.getItemMeta();
        Component name = known ? Component.translatable(m.translationKey()) : Component.text(ln.id());
        meta.displayName((ln.done() ? Component.text("✔ ", NamedTextColor.GREEN).append(name.color(NamedTextColor.GREEN))
                : name.color(NamedTextColor.WHITE)).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        double ratio = ln.need() == 0 ? 1 : Math.min(1, (double) ln.have() / ln.need());
        lore.add(line(ProjectsInGame.bar(ratio, 16) + " <white>" + (int) Math.floor(ratio * 100) + " %</white>"));
        lore.add(line("<gray>Il en faut :</gray> <white>" + ProjectsInGame.stacks(ln.need(), ln.stack()) + "</white>"));
        lore.add(line("<gray>Dans les coffres liés :</gray> <white>" + Msg.number(ln.have()) + "</white>"));
        if (!ln.done()) {
            lore.add(line("<gray>Manque :</gray> <gold>" + ProjectsInGame.stacks(ln.missing(), ln.stack()) + "</gold>"));
        }
        if (ln.elsewhere() > 0) {
            lore.add(line("<gray>Ailleurs dans les coffres :</gray> <aqua>" + Msg.number(ln.elsewhere()) + "</aqua>"));
        }
        lore.add(Component.empty());
        lore.add(line("<yellow>▶ Clic : faire briller les coffres qui en ont</yellow>"));
        meta.lore(lore);
        if (ln.done()) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    private static void bottomBar(Inventory inv, int pg, int pages) {
        ItemStack bar = item(Material.BLACK_STAINED_GLASS_PANE, " ", false);
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, bar);
        }
        if (pg > 0) {
            inv.setItem(45, item(Material.ARROW, "<white>← Page précédente</white>", false));
        }
        if (pg < pages - 1) {
            inv.setItem(53, item(Material.ARROW, "<white>Page suivante →</white>", false));
        }
        inv.setItem(49, item(Material.OAK_DOOR, "<white>Retour</white>", false));
    }

    private static Component itemName(String id) {
        Material m = Material.matchMaterial(id);
        return m != null ? Component.translatable(m.translationKey()) : Component.text(id);
    }

    // ------------------------------------------------------------------ chest list

    public void openList(Player p, int page) {
        List<StoredChest> list = visibleChests(p);
        String me = p.getUniqueId().toString();
        // the player's own containers first, then the shared ones (and, for admins, everyone else's)
        list.sort(Comparator.comparing((StoredChest c) -> me.equals(c.owner) ? 0 : c.pub ? 1 : 2)
                .thenComparing(c -> c.zone == null ? "~" : c.zone.toLowerCase())
                .thenComparing(c -> c.name.toLowerCase()));
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Holder h = new Holder("list", pg);
        Inventory inv = Bukkit.createInventory(h, 54, Msg.parse("<dark_gray>Coffres · page " + (pg + 1) + "/" + pages + "</dark_gray>"));
        h.inv = inv;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = pg * PER_PAGE + i;
            if (idx >= list.size()) {
                break;
            }
            StoredChest c = list.get(idx);
            inv.setItem(i, chestItem(p, c));
            h.chestBySlot.put(i, c.id);
        }
        ItemStack bar = item(Material.BLACK_STAINED_GLASS_PANE, " ", false);
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, bar);
        }
        if (pg > 0) {
            inv.setItem(45, item(Material.ARROW, "<white>← Page précédente</white>", false));
        }
        if (pg < pages - 1) {
            inv.setItem(53, item(Material.ARROW, "<white>Page suivante →</white>", false));
        }
        inv.setItem(49, item(Material.OAK_DOOR, "<white>Retour</white>", false));
        if (list.isEmpty()) {
            inv.setItem(22, item(Material.STRUCTURE_VOID, "<gray>Aucun coffre pour l'instant</gray>", false,
                    "<gray>Utilise le mode ajout depuis</gray>", "<gray>le menu <white>/coffres</white>.</gray>"));
        }
        p.openInventory(inv);
    }

    private ItemStack chestItem(Player p, StoredChest c) {
        Material mat = Material.matchMaterial(c.type == null ? "chest" : c.type);
        if (mat == null || !mat.isItem()) {
            mat = Material.CHEST;
        }
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(c.name, NamedTextColor.WHITE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (c.zone != null) {
            lore.add(line("<gray>Zone :</gray> <white>" + esc(c.zone) + "</white>"));
        }
        lore.add(line("<gray>" + c.x + " " + c.y + " " + c.z + " · " + Containers.dimensionName(c.dim) + "</gray>"));
        lore.add(line("<gray>" + Msg.number(c.itemCount()) + " objets · " + c.slots.size() + "/" + c.size + " cases</gray>"));
        boolean mine = p.getUniqueId().toString().equals(c.owner);
        if (c.pub) {
            lore.add(line("<light_purple>Coffre commun</light_purple>" + (mine ? "" : " <gray>· à " + esc(c.ownerName == null ? "?" : c.ownerName) + "</gray>")));
        } else {
            lore.add(line("<gold>🔒 Privé</gold>" + (mine ? "" : " <gray>· à " + esc(c.ownerName == null ? "?" : c.ownerName) + "</gray>")));
        }
        c.totals().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(4)
                .forEach(e -> {
                    Material m = Material.matchMaterial(e.getKey());
                    Component name = m != null ? Component.translatable(m.translationKey()) : Component.text(e.getKey());
                    lore.add(Component.text("• ", NamedTextColor.DARK_GRAY)
                            .append(name.color(NamedTextColor.GRAY))
                            .append(Component.text(" ×" + Msg.number(e.getValue()), NamedTextColor.WHITE))
                            .decoration(TextDecoration.ITALIC, false));
                });
        lore.add(Component.empty());
        lore.add(line("<yellow>▶ Clic : le faire briller</yellow>"));
        if (plugin.tracker().canManage(p, c)) {
            lore.add(line("<red>Maj + clic droit : ne plus le suivre</red>"));
        }
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Holder h)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) {
            return;
        }
        int slot = e.getRawSlot();
        if (h.kind.equals("main")) {
            switch (slot) {
                case 10 -> {
                    p.closeInventory();
                    plugin.linkMode().toggle(p);
                }
                case 12 -> {
                    p.closeInventory();
                    plugin.sendWebAccess(p);
                }
                case 14 -> openList(p, 0);
                case 16 -> {
                    p.closeInventory();
                    Msg.send(p, "<click:suggest_command:'/coffres chercher '><hover:show_text:'Cliquer pour écrire la commande'><aqua>[Écrire /coffres chercher …]</aqua></hover></click> <gray>puis le nom de l'objet (ex. diamant, fer, bûche).</gray>");
                }
                case 21 -> openProjects(p, 0);
                default -> {
                }
            }
            return;
        }
        if (h.kind.equals("projects")) {
            if (slot == 45 && h.page > 0) {
                openProjects(p, h.page - 1);
            } else if (slot == 53) {
                openProjects(p, h.page + 1);
            } else if (slot == 49) {
                openMain(p);
            } else if ("_web".equals(h.idBySlot.get(slot))) {
                p.closeInventory();
                plugin.sendWebAccess(p);
            } else if (h.idBySlot.containsKey(slot)) {
                Project pr = plugin.projects().get(h.idBySlot.get(slot));
                if (pr == null) {
                    openProjects(p, h.page);
                } else if (e.getClick().isRightClick() && ProjectsInGame.canLink(p, pr)) {
                    p.closeInventory();
                    plugin.projectsGame().startLinking(p, pr);
                } else {
                    openProject(p, pr.id, 0);
                }
            }
            return;
        }
        if (h.kind.equals("project")) {
            Project pr = plugin.projects().get(h.project);
            if (pr == null) {
                openProjects(p, 0);
                return;
            }
            switch (slot) {
                case 45 -> {
                    if (h.page > 0) {
                        openProject(p, pr.id, h.page - 1);
                    }
                }
                case 53 -> openProject(p, pr.id, h.page + 1);
                case 49 -> openProjects(p, 0);
                case 47 -> {
                    if (ProjectsInGame.canLink(p, pr)) {
                        p.closeInventory();
                        plugin.projectsGame().startLinking(p, pr);
                    }
                }
                case 50 -> {
                    p.closeInventory();
                    plugin.projectsGame().locateLinked(p, pr);
                }
                case 51 -> {
                    p.closeInventory();
                    plugin.sendWebAccess(p);
                }
                default -> {
                    String item = h.idBySlot.get(slot);
                    if (item != null) {
                        p.closeInventory();
                        plugin.projectsGame().locateItem(p, item);
                    }
                }
            }
        }
        if (h.kind.equals("list")) {
            if (slot == 45 && h.page > 0) {
                openList(p, h.page - 1);
            } else if (slot == 53) {
                openList(p, h.page + 1);
            } else if (slot == 49) {
                openMain(p);
            } else if (h.chestBySlot.containsKey(slot)) {
                StoredChest c = plugin.store().get(h.chestBySlot.get(slot));
                if (c == null) {
                    openList(p, h.page);
                    return;
                }
                if (e.getClick() == ClickType.SHIFT_RIGHT) {
                    if (plugin.tracker().canManage(p, c)) {
                        plugin.tracker().remove(p, c);
                        openList(p, h.page);
                    }
                    return;
                }
                p.closeInventory();
                plugin.locator().locate(p, List.of(c));
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Holder) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ helpers

    List<StoredChest> visibleChests(Player p) {
        boolean admin = p.hasPermission("coffres.admin");
        String me = p.getUniqueId().toString();
        List<StoredChest> out = new ArrayList<>();
        for (StoredChest c : plugin.store().all()) {
            if (c.pub || admin || me.equals(c.owner)) {
                out.add(c);
            }
        }
        return out;
    }

    private static void fill(Inventory inv) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", false);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, pane);
        }
    }

    private static Component line(String mm) {
        return Msg.parse(mm).decoration(TextDecoration.ITALIC, false);
    }

    private static String esc(String s) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(s);
    }

    static ItemStack item(Material m, String name, boolean glint, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(line(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) {
            l.add(s.isEmpty() ? Component.empty() : line(s));
        }
        if (!l.isEmpty()) {
            meta.lore(l);
        }
        if (glint) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }
}
