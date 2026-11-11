package fr.coffres.dev;

import fr.coffres.model.StoredChest;
import fr.coffres.store.DataStore;
import fr.coffres.store.ProjectStore;
import fr.coffres.web.AssetManager;
import fr.coffres.web.AuthManager;
import fr.coffres.web.GameBridge;
import fr.coffres.web.WebServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Runs only the Coffres website with made-up data, without Minecraft.
 * Usage: ./gradlew runDev  then open http://localhost:8125 and log in with the code printed in the console.
 */
public final class DevServer {

    public static void main(String[] args) throws Exception {
        Logger log = Logger.getLogger("Coffres-dev");
        Path dir = Path.of("data");
        Files.createDirectories(dir);
        Path webDir = args.length > 0 ? Path.of(args[0]) : null;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8125;

        DataStore store = new DataStore(dir.resolve("store"), log);
        store.configure(true, 60, 300_000);
        MockData.fill(store);
        ProjectStore projects = new ProjectStore(dir.resolve("store").resolve("projects.json"), log, store::bump);
        MockData.fillProjects(projects, store);

        AuthManager auth = new AuthManager(dir.resolve("sessions.json"), log);
        AssetManager assets = new AssetManager(dir.resolve("cache"), log, "26.2", "fr_fr", true);
        assets.start();

        FakeGame game = new FakeGame(store);
        WebServer web = new WebServer(log, store, projects, auth, assets, game,
                new WebServer.Settings("Serveur de test", "http://localhost:" + port, 60, 20, 15));
        if (webDir != null) {
            web.loadStatic(DevServer.class, webDir);
            web.setLiveDir(webDir);
        }
        web.start("127.0.0.1", port);

        String code = auth.createCode(MockData.STEVE, "Steve", true);
        System.out.println();
        System.out.println("  Coffres (démo) : http://localhost:" + port + "/#/connexion?code=" + code);
        System.out.println("  Code de connexion pour Steve : " + code + "   (ou demande un code pour Steve/Alex sur la page de connexion)");
        System.out.println();

        // A bit of life: someone moves items every few seconds.
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        Random rnd = new Random();
        timer.scheduleAtFixedRate(() -> {
            try {
                MockData.randomMove(store, rnd);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 20, 25, TimeUnit.SECONDS);
        Thread.currentThread().join();
    }

    /** Pretends Steve and Alex are online near the base. */
    static final class FakeGame implements GameBridge {
        private final DataStore store;

        FakeGame(DataStore store) {
            this.store = store;
        }

        @Override
        public CompletableFuture<PlayerRef> sendLoginCode(String name, AuthManager auth) {
            for (Map.Entry<String, UUID> e : MockData.PLAYERS.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && onlinePlayers().contains(e.getKey())) {
                    String code = auth.createCode(e.getValue(), e.getKey(), e.getKey().equals("Steve"));
                    System.out.println("  [chat → " + e.getKey() + "] Ton code pour le site : " + code);
                    return CompletableFuture.completedFuture(new PlayerRef(e.getValue(), e.getKey(), false));
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<LocateResult> locate(UUID player, List<String> chestIds) {
            List<String> names = new ArrayList<>();
            for (String id : chestIds) {
                StoredChest c = store.get(id);
                if (c != null) {
                    names.add(c.name);
                }
            }
            System.out.println("  [jeu] " + player + " localise " + names);
            if (!player.equals(MockData.STEVE)) {
                return CompletableFuture.completedFuture(new LocateResult(false, "Connecte-toi au serveur Minecraft pour que le coffre s'allume en jeu."));
            }
            return CompletableFuture.completedFuture(new LocateResult(true,
                    names.size() > 1 ? names.size() + " coffres brillent en jeu pendant 60 s." : "« " + names.getFirst() + " » brille en jeu pendant 60 s."));
        }

        @Override
        public Position position(UUID player) {
            if (player.equals(MockData.STEVE)) {
                return new Position("world", "overworld", 118.4, 64, -22.7, 45f);
            }
            return null;
        }

        @Override
        public List<String> onlinePlayers() {
            return List.of("Steve", "Alex");
        }

        @Override
        public String minecraftVersion() {
            return "26.2";
        }
    }
}
