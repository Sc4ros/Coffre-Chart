package fr.coffres.game;

import fr.coffres.CoffresPlugin;
import fr.coffres.Msg;
import fr.coffres.model.StoredChest;
import fr.coffres.store.DataStore;
import fr.coffres.track.Containers;
import fr.coffres.web.AuthManager;
import fr.coffres.web.GameBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** The site's window on the game. Every Bukkit call is done on the main thread. */
public final class BukkitBridge implements GameBridge {

    private final CoffresPlugin plugin;
    private final DataStore store;
    private final Locator locator;
    private final Map<UUID, Position> positions = new ConcurrentHashMap<>();
    private volatile List<String> online = List.of();

    public BukkitBridge(CoffresPlugin plugin, DataStore store, Locator locator) {
        this.plugin = plugin;
        this.store = store;
        this.locator = locator;
    }

    /** Called every second on the main thread. */
    public void updatePositions() {
        List<String> names = new ArrayList<>();
        positions.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            positions.put(p.getUniqueId(), new Position(l.getWorld().getName(), Containers.dimension(l.getWorld()),
                    Math.round(l.getX() * 10) / 10.0, Math.round(l.getY() * 10) / 10.0, Math.round(l.getZ() * 10) / 10.0, l.getYaw()));
            names.add(p.getName());
        }
        online = List.copyOf(names);
    }

    public void forget(UUID uuid) {
        positions.remove(uuid);
    }

    @Override
    public CompletableFuture<PlayerRef> sendLoginCode(String name, AuthManager auth) {
        CompletableFuture<PlayerRef> f = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(name);
            if (p == null) {
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (o.getName().equalsIgnoreCase(name)) {
                        p = o;
                    }
                }
            }
            if (p == null) {
                f.complete(null);
                return;
            }
            boolean admin = p.hasPermission("coffres.admin");
            String code = auth.createCode(p.getUniqueId(), p.getName(), admin);
            sendCode(p, code, false);
            f.complete(new PlayerRef(p.getUniqueId(), p.getName(), admin));
        });
        return f;
    }

    /** Shows a login code (and the site link when {@code withLink}). Main thread. */
    public void sendCode(Player p, String code, boolean withLink) {
        String pretty = code.substring(0, 3) + " " + code.substring(3);
        Msg.send(p, "<gray>Ton code pour le site :</gray> <click:copy_to_clipboard:'" + code + "'><hover:show_text:'Cliquer pour copier'>"
                + "<white><b>" + pretty + "</b></white></hover></click> <dark_gray>(valable 5 min)</dark_gray>");
        if (withLink) {
            String url = plugin.publicUrl() + "/#/connexion?code=" + code;
            Component link = Component.text("Ouvrir le site", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(Component.text("Ouvrir le site, déjà connecté")));
            Msg.raw(p, "  <link> <dark_gray>·</dark_gray> <gray><site></gray>",
                    Placeholder.component("link", link), Placeholder.unparsed("site", plugin.publicUrl()));
        } else {
            Msg.raw(p, "  <dark_gray>Quelqu'un vient de demander un code avec ton pseudo sur le site. Si ce n'est pas toi, ignore ce message.</dark_gray>");
        }
    }

    @Override
    public CompletableFuture<LocateResult> locate(UUID player, List<String> chestIds) {
        CompletableFuture<LocateResult> f = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(player);
            if (p == null) {
                f.complete(new LocateResult(false, "Connecte-toi au serveur Minecraft pour que le coffre s'allume en jeu."));
                return;
            }
            List<StoredChest> chests = new ArrayList<>();
            for (String id : chestIds) {
                StoredChest c = store.get(id);
                if (c != null) {
                    chests.add(c);
                }
            }
            if (chests.isEmpty()) {
                f.complete(new LocateResult(false, "Ce coffre n'existe plus."));
                return;
            }
            String msg = locator.locate(p, chests);
            f.complete(new LocateResult(true, msg));
        });
        return f;
    }

    @Override
    public Position position(UUID player) {
        return positions.get(player);
    }

    @Override
    public List<String> onlinePlayers() {
        return online;
    }

    @Override
    public String minecraftVersion() {
        return Bukkit.getMinecraftVersion();
    }
}
