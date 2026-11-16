package fr.coffres.dev;

import fr.coffres.model.Placement;
import fr.coffres.model.Project;
import fr.coffres.model.StoredChest;
import fr.coffres.project.Build;
import fr.coffres.project.SchematicReader;
import fr.coffres.model.Group;
import fr.coffres.store.GroupStore;
import fr.coffres.store.PlanStore;
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
        PlanStore plans = new PlanStore(dir.resolve("store").resolve("plans"), log);
        demoHouse(projects, plans, store);
        GroupStore groups = new GroupStore(dir.resolve("store").resolve("groups.json"), log, store::bump);
        MockData.PLAYERS.forEach((name, uuid) -> groups.seen(uuid.toString(), name));
        // Alex's iron farm is shared with a group of friends that Steve is part of.
        Group friends = groups.create("Les potes", MockData.PLAYERS.get("Alex").toString(), "Alex");
        groups.add(friends.id, MockData.STEVE.toString(), "Steve");
        projects.setVisibility("fermefer", false, friends.id);

        AuthManager auth = new AuthManager(dir.resolve("sessions.json"), log);
        AssetManager assets = new AssetManager(dir.resolve("cache"), log, "26.2", "fr_fr", true);
        assets.start();

        FakeGame game = new FakeGame(store, plans);
        WebServer web = new WebServer(log, store, projects, plans, groups, auth, assets, game,
                new WebServer.Settings("Serveur de test", "http://localhost:" + port, 60, 20, 15, 10_000_000));
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

    /** The small house of docs/exemples, placed in the world and half built. */
    private static void demoHouse(ProjectStore projects, PlanStore plans, DataStore store) throws Exception {
        Path file = Path.of("docs", "exemples", "petite_maison.litematic");
        if (!Files.isRegularFile(file)) {
            file = Path.of("..", "..", "docs", "exemples", "petite_maison.litematic"); // ./gradlew runDev runs in build/dev
        }
        if (!Files.isRegularFile(file)) {
            return;
        }
        SchematicReader.Schematic s = SchematicReader.read(Files.readAllBytes(file), 64L * 1024 * 1024);
        Project p = projects.create("Petite maison", MockData.STEVE.toString(), "Steve", true, "petite_maison.litematic", s);
        plans.savePlan(p.id, s.plan());
        for (StoredChest c : store.all()) {
            if (c.name.equals("Bois") || c.name.equals("Pierres")) {
                projects.toggleChest(p.id, c.id);
            }
        }
        Placement at = new Placement("world", 120, 64, -30, 90, "none");
        at.by = "Steve";
        at.at = System.currentTimeMillis() - 3_600_000L;
        projects.setPlacement(p.id, at);
        Build b = plans.activate(projects.get(p.id));
        FakeGame.build(plans, b, 0.45);
        b.scanFinished(0);
    }

    /** Pretends Steve and Alex are online near the base. */
    static final class FakeGame implements GameBridge {
        private final DataStore store;
        private final PlanStore plans;
        private final Map<String, Double> built = new java.util.concurrent.ConcurrentHashMap<>();

        FakeGame(DataStore store, PlanStore plans) {
            this.store = store;
            this.plans = plans;
        }

        /** The lowest blocks first, like a real build going up. */
        static void build(PlanStore plans, Build b, double fraction) {
            int n = (int) Math.round(b.size() * Math.min(1, fraction));
            for (int k = 0; k < b.size(); k++) {
                b.set(k, k < n);
            }
            // Steve and Alex are on the site with some blocks in their pockets.
            plans.setCarried(b.projectId, Map.of("spruce_slab", 40L, "spruce_stairs", 12L, "glass_pane", 6L),
                    List.of(new Build.Carrier("Steve", 46, Map.of("spruce_slab", 40L, "glass_pane", 6L)),
                            new Build.Carrier("Alex", 12, Map.of("spruce_stairs", 12L))));
        }

        @Override
        public List<String> worlds() {
            return List.of("world", "world_nether", "world_the_end");
        }

        /** Each check finds a bit more built than the last one, in about 3 seconds. */
        @Override
        public void rescan(String projectId, UUID who) {
            Build b = plans.build(projectId);
            if (b == null) {
                return;
            }
            double f = built.merge(projectId, 0.45, (old, x) -> Math.min(1, old + 0.1));
            b.scanStarted();
            store.bump();
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 1; i <= 6; i++) {
                        Thread.sleep(500);
                        b.scanProgress(i / 6.0);
                    }
                    build(plans, b, f);
                    b.scanFinished(b.at.world.equals("world") ? 0 : b.size() / 3);
                    store.bump();
                    System.out.println("  [jeu] vérification de " + projectId + " : " + b.placedCount() + "/" + b.size() + " blocs posés");
                } catch (InterruptedException ignored) {
                    // stop
                }
            });
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
