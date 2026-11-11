package fr.coffres;

import fr.coffres.game.BukkitBridge;
import fr.coffres.game.Locator;
import fr.coffres.store.DataStore;
import fr.coffres.store.ProjectStore;
import fr.coffres.track.ChestTracker;
import fr.coffres.track.Containers;
import fr.coffres.web.AssetManager;
import fr.coffres.web.AuthManager;
import fr.coffres.web.WebServer;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Level;

/** Coffres: an inventory of the server's chests, with a website for phones and computers. */
public final class CoffresPlugin extends JavaPlugin implements Listener {

    private DataStore store;
    private ProjectStore projects;
    private AuthManager auth;
    private AssetManager assets;
    private Containers containers;
    private ChestTracker tracker;
    private LinkMode linkMode;
    private Locator locator;
    private BukkitBridge bridge;
    private Menus menus;
    private ProjectsInGame projectsGame;
    private WebServer web;
    private String webKey = "";
    private String publicUrl = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Path data = getDataFolder().toPath();

        containers = new Containers();
        store = new DataStore(data, getLogger());
        projects = new ProjectStore(data.resolve("projects.json"), getLogger(), store::bump);
        auth = new AuthManager(data.resolve("sessions.json"), getLogger());
        linkMode = new LinkMode();
        locator = new Locator(this, containers);
        tracker = new ChestTracker(this, store, containers);
        bridge = new BukkitBridge(this, store, locator);
        menus = new Menus(this);
        projectsGame = new ProjectsInGame(this);

        applyConfig();
        store.load();
        projects.load();
        projects.pruneChests(new HashSet<>(store.ids()));
        auth.load();

        FileConfiguration cfg = getConfig();
        assets = new AssetManager(data.resolve("cache"), getLogger(), Bukkit.getMinecraftVersion(),
                cfg.getString("assets.language", "fr_fr"), cfg.getBoolean("assets.download-from-mojang", true));
        assets.start();

        Bukkit.getPluginManager().registerEvents(tracker, this);
        Bukkit.getPluginManager().registerEvents(menus, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("coffres");
        if (cmd != null) {
            CoffresCommand handler = new CoffresCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        Bukkit.getScheduler().runTaskTimer(this, tracker::tick, 20L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            linkMode.tick();
            bridge.updatePositions();
        }, 20L, 20L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            store.save();
            projects.pruneChests(new HashSet<>(store.ids()));
            projects.save();
            auth.save();
        }, 600L, 600L);

        for (Player p : Bukkit.getOnlinePlayers()) {
            auth.refreshPlayer(p.getUniqueId(), p.getName(), p.hasPermission("coffres.admin"));
        }
        startWeb();
        getLogger().info(store.all().size() + " coffre(s) suivis, " + projects.all().size() + " projet(s).");
    }

    @Override
    public void onDisable() {
        if (tracker != null) {
            tracker.flushAll();
        }
        if (locator != null) {
            locator.stopAll();
        }
        if (web != null) {
            web.stop();
            web = null;
        }
        if (store != null) {
            store.save();
        }
        if (projects != null) {
            projects.save();
        }
        if (auth != null) {
            auth.save();
        }
    }

    private void applyConfig() {
        FileConfiguration cfg = getConfig();
        containers.setPatterns(cfg.getStringList("tracking.containers").isEmpty()
                ? List.of("*CHEST", "BARREL", "*SHULKER_BOX")
                : cfg.getStringList("tracking.containers"));
        store.configure(cfg.getBoolean("tracking.count-shulker-contents", true),
                cfg.getInt("history.retention-days", 60), cfg.getInt("history.max-entries", 300_000));
        tracker.configure(cfg.getInt("tracking.chests-per-tick", 10), cfg.getInt("tracking.auto-group-seconds", 60),
                cfg.getBoolean("tracking.public-by-default", true));
        linkMode.setTimeoutSeconds(cfg.getInt("tracking.link-mode-timeout-seconds", 180));
        locator.setDurationSeconds(cfg.getInt("locate.duration-seconds", 60));
        auth.setSessionDays(cfg.getInt("auth.session-days", 180));

        int port = cfg.getInt("web.port", 8125);
        String configured = cfg.getString("web.public-url", "").trim();
        if (configured.isEmpty()) {
            String ip = Bukkit.getIp();
            publicUrl = "http://" + (ip == null || ip.isBlank() || ip.equals("0.0.0.0") ? "IP-DU-SERVEUR" : ip) + ":" + port;
        } else {
            publicUrl = configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        }
    }

    private WebServer.Settings webSettings() {
        FileConfiguration cfg = getConfig();
        return new WebServer.Settings(cfg.getString("web.server-name", "Mon serveur"), publicUrl, locator.durationSeconds(),
                Math.max(1, Math.min(200, cfg.getInt("projects.max-file-mb", 20))),
                Math.max(1, cfg.getInt("projects.max-per-player", 15)));
    }

    private void startWeb() {
        FileConfiguration cfg = getConfig();
        if (!cfg.getBoolean("web.enabled", true)) {
            getLogger().info("Site désactivé (web.enabled: false).");
            return;
        }
        String bind = cfg.getString("web.bind", "0.0.0.0");
        int port = cfg.getInt("web.port", 8125);
        webKey = bind + ":" + port;
        web = new WebServer(getLogger(), store, projects, auth, assets, bridge, webSettings());
        web.loadStatic(CoffresPlugin.class, getDataFolder().toPath().resolve("web"));
        try {
            web.start(bind, port);
            getLogger().info("Site Coffres en ligne sur le port " + port + " → " + publicUrl);
            if (cfg.getString("web.public-url", "").isBlank()) {
                getLogger().info("Astuce : indique l'adresse exacte du site dans config.yml (web.public-url) pour que les liens en jeu soient bons.");
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Impossible de démarrer le site sur le port " + port
                    + " (port déjà utilisé ou non autorisé par l'hébergeur ?) : " + e.getMessage());
            web = null;
        }
    }

    public void reload() {
        reloadConfig();
        applyConfig();
        String bind = getConfig().getString("web.bind", "0.0.0.0");
        int port = getConfig().getInt("web.port", 8125);
        boolean enabled = getConfig().getBoolean("web.enabled", true);
        if (web == null || !enabled || !webKey.equals(bind + ":" + port)) {
            if (web != null) {
                web.stop();
                web = null;
            }
            if (enabled) {
                startWeb();
            }
        } else {
            web.setSettings(webSettings());
            web.loadStatic(CoffresPlugin.class, getDataFolder().toPath().resolve("web"));
        }
    }

    /** Sends the site link and a login code to a player. */
    public void sendWebAccess(Player p) {
        if (web == null) {
            Msg.send(p, "<red>Le site est désactivé sur ce serveur.</red>");
            return;
        }
        String code = auth.createCode(p.getUniqueId(), p.getName(), p.hasPermission("coffres.admin"));
        bridge.sendCode(p, code, true);
        Msg.raw(p, "  <dark_gray>Sur iPhone : ouvre le lien dans Safari, puis Partager → « Sur l'écran d'accueil ».</dark_gray>");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        auth.refreshPlayer(p.getUniqueId(), p.getName(), p.hasPermission("coffres.admin"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        linkMode.forget(e.getPlayer().getUniqueId());
        locator.stop(e.getPlayer().getUniqueId());
        bridge.forget(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ accessors

    public DataStore store() {
        return store;
    }

    public ProjectStore projects() {
        return projects;
    }

    public ProjectsInGame projectsGame() {
        return projectsGame;
    }

    public AuthManager auth() {
        return auth;
    }

    public AssetManager assets() {
        return assets;
    }

    public Containers containers() {
        return containers;
    }

    public ChestTracker tracker() {
        return tracker;
    }

    public LinkMode linkMode() {
        return linkMode;
    }

    public Locator locator() {
        return locator;
    }

    public Menus menus() {
        return menus;
    }

    public String publicUrl() {
        return publicUrl;
    }
}
