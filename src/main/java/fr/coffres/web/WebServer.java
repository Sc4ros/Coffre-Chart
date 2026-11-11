package fr.coffres.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.coffres.model.Project;
import fr.coffres.project.Nbt;
import fr.coffres.project.SchematicReader;
import fr.coffres.store.DataStore;
import fr.coffres.store.ProjectStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

/**
 * The Coffres website: static files (the app) + a small JSON API.
 * Uses the HTTP server built into Java, so the plugin needs no extra library.
 */
public final class WebServer {

    /** Values the site shows that come from the config. */
    public record Settings(String serverName, String publicUrl, int locateSeconds, int maxFileMb, int maxProjects) {
    }

    private record StaticFile(byte[] data, byte[] gz, String type, String etag) {
    }

    private static final class HttpError extends RuntimeException {
        final int code;

        HttpError(int code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    private static final Pattern PLAYER_NAME = Pattern.compile("[.*]?[A-Za-z0-9_]{2,16}");
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_]{1,80}");
    private static final Pattern CHEST_ID = Pattern.compile("[a-z0-9]{4,16}");
    /** A plan may not be bigger than this once decompressed. */
    private static final long MAX_UNPACKED = 192L * 1024 * 1024;
    private static final String CSP = "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
            + "script-src 'self'; connect-src 'self'; manifest-src 'self'; worker-src 'self'; frame-ancestors 'none'; base-uri 'none'";

    private final Logger logger;
    private final DataStore store;
    private final ProjectStore projects;
    private final AuthManager auth;
    private final AssetManager assets;
    private final GameBridge game;
    private final Api api;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Map<String, StaticFile> statics = new java.util.concurrent.ConcurrentHashMap<>();
    /** Reading plans takes memory: at most two at a time. */
    private final Semaphore parsing = new Semaphore(2);
    private volatile Settings settings;
    private volatile Path liveDir;
    private HttpServer server;
    private ExecutorService pool;

    public WebServer(Logger logger, DataStore store, ProjectStore projects, AuthManager auth, AssetManager assets, GameBridge game,
                     Settings settings) {
        this.logger = logger;
        this.store = store;
        this.projects = projects;
        this.auth = auth;
        this.assets = assets;
        this.game = game;
        this.settings = settings;
        this.api = new Api(store, projects);
    }

    public void setSettings(Settings settings) {
        this.settings = settings;
    }

    /** Loads the app files from the plugin jar, then from {@code overrideDir} (files there replace the built-in ones). */
    public void loadStatic(Class<?> anchor, Path overrideDir) {
        statics.clear();
        try {
            URI src = anchor.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path p = Path.of(src);
            if (Files.isDirectory(p)) {
                loadDir(p.resolve("web"));
            } else {
                try (JarFile jar = new JarFile(p.toFile())) {
                    Enumeration<JarEntry> en = jar.entries();
                    while (en.hasMoreElements()) {
                        JarEntry e = en.nextElement();
                        if (!e.isDirectory() && e.getName().startsWith("web/")) {
                            try (InputStream in = jar.getInputStream(e)) {
                                putStatic(e.getName().substring(4), in.readAllBytes());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Fichiers du site introuvables", e);
        }
        if (overrideDir != null && Files.isDirectory(overrideDir)) {
            loadDir(overrideDir);
        }
    }

    private void loadDir(Path base) {
        if (!Files.isDirectory(base)) {
            return;
        }
        try (Stream<Path> s = Files.walk(base)) {
            for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                String rel = base.relativize(f).toString().replace('\\', '/');
                putStatic(rel, Files.readAllBytes(f));
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Lecture de " + base + " impossible", e);
        }
    }

    private void putStatic(String path, byte[] data) {
        String type = mime(path);
        CRC32 crc = new CRC32();
        crc.update(data);
        byte[] gz = compressible(type) && data.length > 600 ? gzip(data) : null;
        statics.put(path, new StaticFile(data, gz, type, "\"" + Long.toHexString(crc.getValue()) + "-" + data.length + "\""));
    }

    public void start(String bind, int port) throws IOException {
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "Coffres-web-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        server.createContext("/", this::handle);
        server.setExecutor(pool);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    // ------------------------------------------------------------------ routing

    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getRawPath();
            if (path.startsWith("/api/")) {
                handleApi(ex, path.substring(5));
            } else if (path.startsWith("/tex/")) {
                handleTexture(ex, path.substring(5));
            } else if (path.equals("/assets/index.json")) {
                sendBytes(ex, 200, assets.indexJson(), "application/json; charset=utf-8", "no-cache");
            } else if (path.equals("/assets/lang.json")) {
                sendBytes(ex, 200, assets.langJson(), "application/json; charset=utf-8", "public, max-age=86400");
            } else {
                handleStatic(ex, path);
            }
        } catch (HttpError e) {
            trySendJson(ex, e.code, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Erreur du site sur " + ex.getRequestURI(), e);
            trySendJson(ex, 500, Map.of("error", "Erreur interne du serveur"));
        } finally {
            ex.close();
        }
    }

    /** Development only: serve the app files straight from this folder (edits show up without restarting). */
    public void setLiveDir(Path dir) {
        this.liveDir = dir;
    }

    private void handleStatic(HttpExchange ex, String path) throws IOException {
        if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
            throw new HttpError(405, "Méthode non autorisée");
        }
        String rel = URLDecoder.decode(path, StandardCharsets.UTF_8);
        if (rel.contains("..")) {
            throw new HttpError(404, "Introuvable");
        }
        rel = rel.startsWith("/") ? rel.substring(1) : rel;
        if (rel.isEmpty()) {
            rel = "index.html";
        }
        Path live = liveDir;
        if (live != null && Files.isRegularFile(live.resolve(rel))) {
            putStatic(rel, Files.readAllBytes(live.resolve(rel)));
        }
        StaticFile f = statics.get(rel);
        if (f == null) {
            if (rel.contains(".")) {
                throw new HttpError(404, "Introuvable");
            }
            f = statics.get("index.html");
            if (f == null) {
                throw new HttpError(404, "Site introuvable dans le plugin");
            }
        }
        Headers h = ex.getResponseHeaders();
        security(h);
        h.set("ETag", f.etag);
        boolean image = f.type.startsWith("image/");
        h.set("Cache-Control", image ? "public, max-age=604800" : "no-cache");
        if (rel.equals("sw.js")) {
            h.set("Service-Worker-Allowed", "/");
        }
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        if (f.etag.equals(inm)) {
            ex.sendResponseHeaders(304, -1);
            return;
        }
        h.set("Content-Type", f.type);
        byte[] body = f.data;
        if (f.gz != null && acceptsGzip(ex)) {
            h.set("Content-Encoding", "gzip");
            h.set("Vary", "Accept-Encoding");
            body = f.gz;
        }
        writeBody(ex, 200, body);
    }

    private void handleTexture(HttpExchange ex, String rest) throws IOException {
        int slash = rest.indexOf('/');
        if (slash < 0 || !rest.endsWith(".png")) {
            throw new HttpError(404, "Introuvable");
        }
        byte[] png = assets.texture(rest.substring(0, slash), rest.substring(slash + 1, rest.length() - 4));
        if (png == null) {
            throw new HttpError(404, "Introuvable");
        }
        sendBytes(ex, 200, png, "image/png", "public, max-age=2592000, immutable");
    }

    private void handleApi(HttpExchange ex, String route) throws Exception {
        String method = ex.getRequestMethod();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());

        // Public endpoints
        switch (route) {
            case "info" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("server", settings.serverName());
                m.put("version", game.minecraftVersion());
                m.put("publicUrl", settings.publicUrl());
                m.put("assets", assets.status());
                sendJson(ex, 200, m);
                return;
            }
            case "auth/request" -> {
                requirePost(method);
                JsonObject body = body(ex);
                String name = str(body, "name");
                if (name == null || !PLAYER_NAME.matcher(name.trim()).matches()) {
                    throw new HttpError(400, "Pseudo Minecraft invalide.");
                }
                if (!auth.allowCodeRequest(name.trim())) {
                    throw new HttpError(429, "Patiente quelques secondes avant de redemander un code.");
                }
                GameBridge.PlayerRef ref = game.sendLoginCode(name.trim(), auth).get(8, TimeUnit.SECONDS);
                if (ref == null) {
                    throw new HttpError(404, name.trim() + " n'est pas connecté au serveur. Connecte-toi en jeu pour recevoir ton code.");
                }
                sendJson(ex, 200, Map.of("ok", true, "name", ref.name()));
                return;
            }
            case "auth/verify" -> {
                requirePost(method);
                String ip = clientIp(ex);
                if (auth.isBlocked(ip)) {
                    throw new HttpError(429, "Trop d'essais. Réessaie dans 10 minutes.");
                }
                String code = str(body(ex), "code");
                String token = auth.verify(code, ip);
                if (token == null) {
                    throw new HttpError(401, "Code incorrect ou expiré.");
                }
                AuthManager.Session s = auth.authenticate(token);
                sendJson(ex, 200, Map.of("token", token, "name", s.name, "uuid", s.uuid));
                return;
            }
            default -> {
                // needs a session, below
            }
        }

        String token = bearer(ex);
        AuthManager.Session s = auth.authenticate(token);
        if (s == null) {
            throw new HttpError(401, "Connexion requise.");
        }

        if (route.equals("auth/logout")) {
            requirePost(method);
            auth.logout(token);
            sendJson(ex, 200, Map.of("ok", true));
            return;
        }
        if (route.equals("rev")) {
            sendJson(ex, 200, Map.of("rev", store.rev()));
            return;
        }
        if (route.equals("me")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name);
            m.put("uuid", s.uuid);
            m.put("admin", s.admin);
            m.put("server", settings.serverName());
            m.put("version", game.minecraftVersion());
            m.put("publicUrl", settings.publicUrl());
            m.put("locateSeconds", settings.locateSeconds());
            m.put("maxFileMb", settings.maxFileMb());
            GameBridge.Position pos = game.position(UUID.fromString(s.uuid));
            m.put("online", pos != null);
            m.put("pos", pos);
            m.put("players", game.onlinePlayers());
            m.put("rev", store.rev());
            sendJson(ex, 200, m);
            return;
        }
        if (route.equals("overview")) {
            sendJson(ex, 200, api.overview(s, days(q, 7)));
            return;
        }
        if (route.equals("items")) {
            sendJson(ex, 200, api.items(s));
            return;
        }
        if (route.startsWith("item/")) {
            String id = route.substring(5);
            if (!ITEM_ID.matcher(id).matches()) {
                throw new HttpError(400, "Objet invalide.");
            }
            sendJson(ex, 200, api.item(s, id, days(q, 7)));
            return;
        }
        if (route.equals("chests")) {
            sendJson(ex, 200, api.chests(s));
            return;
        }
        if (route.startsWith("chest/")) {
            String id = route.substring(6);
            if (!CHEST_ID.matcher(id).matches()) {
                throw new HttpError(400, "Coffre invalide.");
            }
            if ("POST".equals(method)) {
                if (!api.canEdit(s, id)) {
                    throw new HttpError(403, "Seul le propriétaire du coffre (ou un admin) peut le modifier.");
                }
                JsonObject b = body(ex);
                Boolean pub = b.has("pub") && !b.get("pub").isJsonNull() ? b.get("pub").getAsBoolean() : null;
                store.edit(id, str(b, "name"), str(b, "zone"), pub);
            }
            Map<String, Object> c = api.chest(s, id);
            if (c == null) {
                throw new HttpError(404, "Ce coffre n'existe plus ou est privé.");
            }
            sendJson(ex, 200, c);
            return;
        }
        if (route.equals("locate")) {
            requirePost(method);
            JsonObject b = body(ex);
            List<String> ids = new ArrayList<>();
            if (b.has("chests") && b.get("chests").isJsonArray()) {
                JsonArray arr = b.getAsJsonArray("chests");
                for (JsonElement e : arr) {
                    String id = e.getAsString();
                    if (CHEST_ID.matcher(id).matches() && ids.size() < 8) {
                        ids.add(id);
                    }
                }
            }
            ids = api.filterVisible(s, ids);
            if (ids.isEmpty()) {
                throw new HttpError(404, "Aucun coffre à localiser.");
            }
            GameBridge.LocateResult r = game.locate(UUID.fromString(s.uuid), ids).get(8, TimeUnit.SECONDS);
            sendJson(ex, r.ok() ? 200 : 409, Map.of("ok", r.ok(), "message", r.message()));
            return;
        }
        if (route.equals("activity")) {
            long before = parseLong(q.get("before"), Long.MAX_VALUE);
            int limit = (int) parseLong(q.get("limit"), 50);
            String kind = q.get("kind");
            sendJson(ex, 200, api.activity(s, blankToNull(q.get("chest")), blankToNull(q.get("item")),
                    blankToNull(q.get("player")), blankToNull(kind), before, limit));
            return;
        }
        if (route.equals("players")) {
            sendJson(ex, 200, api.players(s, days(q, 30)));
            return;
        }
        if (route.equals("projects")) {
            sendJson(ex, 200, api.projects(s));
            return;
        }
        if (route.equals("projects/upload")) {
            requirePost(method);
            int max = settings.maxProjects();
            if (!s.admin && projects.countOwnedBy(s.uuid) >= max) {
                throw new HttpError(409, "Tu as déjà " + max + " projets. Supprimes-en un pour en créer un nouveau.");
            }
            String fileName = fileName(q.get("file"));
            SchematicReader.Schematic plan = parsePlan(ex);
            String name = blankToNull(q.get("name"));
            if (name == null) {
                name = plan.name() != null ? plan.name() : baseName(fileName);
            }
            Project p = projects.create(name, s.uuid, s.name, "1".equals(q.get("shared")), fileName, plan);
            logger.info(s.name + " a créé le projet « " + p.name + " » (" + p.materials.size() + " sortes de blocs, "
                    + p.blocks + " au total).");
            sendJson(ex, 200, api.project(s, p.id));
            return;
        }
        if (route.startsWith("project/")) {
            handleProject(ex, s, method, q, route.substring(8));
            return;
        }
        throw new HttpError(404, "Route inconnue.");
    }

    /** /api/project/{id}[/chests|/file|/delete] */
    private void handleProject(HttpExchange ex, AuthManager.Session s, String method, Map<String, String> q, String rest)
            throws Exception {
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        String action = slash < 0 ? "" : rest.substring(slash + 1);
        if (!CHEST_ID.matcher(id).matches()) {
            throw new HttpError(400, "Projet invalide.");
        }
        Project p = projects.get(id);
        if (p == null || !Api.canSee(p, s)) {
            throw new HttpError(404, "Ce projet n'existe plus ou est privé.");
        }
        switch (action) {
            case "" -> {
                if ("POST".equals(method)) {
                    requireProjectEdit(p, s);
                    JsonObject b = body(ex);
                    Boolean shared = b.has("shared") && !b.get("shared").isJsonNull() ? b.get("shared").getAsBoolean() : null;
                    projects.edit(id, str(b, "name"), shared);
                }
            }
            case "chests" -> {
                requirePost(method);
                if (!Api.canLink(p, s)) {
                    throw new HttpError(403, "Seul le créateur de ce projet solo (ou un admin) peut y lier des coffres.");
                }
                JsonObject b = body(ex);
                List<String> ids = new ArrayList<>();
                if (b.has("chests") && b.get("chests").isJsonArray()) {
                    for (JsonElement e : b.getAsJsonArray("chests")) {
                        String cid = e.isJsonPrimitive() ? e.getAsString() : "";
                        if (CHEST_ID.matcher(cid).matches() && ids.size() < 1000) {
                            ids.add(cid);
                        }
                    }
                }
                projects.setChests(id, api.mergeLinks(s, id, ids));
            }
            case "file" -> {
                requirePost(method);
                requireProjectEdit(p, s);
                projects.replaceFile(id, fileName(q.get("file")), parsePlan(ex));
            }
            case "delete" -> {
                requirePost(method);
                requireProjectEdit(p, s);
                projects.delete(id);
                logger.info(s.name + " a supprimé le projet « " + p.name + " ».");
                sendJson(ex, 200, Map.of("ok", true));
                return;
            }
            default -> throw new HttpError(404, "Route inconnue.");
        }
        Map<String, Object> out = api.project(s, id);
        if (out == null) {
            throw new HttpError(404, "Ce projet n'existe plus ou est privé.");
        }
        sendJson(ex, 200, out);
    }

    private static void requireProjectEdit(Project p, AuthManager.Session s) {
        if (!Api.canEdit(p, s)) {
            throw new HttpError(403, "Seul le créateur du projet (ou un admin) peut le modifier.");
        }
    }

    /** Reads the plan sent as the raw request body (.litematic, .schem, .schematic, .nbt). */
    private SchematicReader.Schematic parsePlan(HttpExchange ex) throws IOException, InterruptedException {
        long max = (long) settings.maxFileMb() * 1024 * 1024;
        String len = ex.getRequestHeaders().getFirst("Content-Length");
        if (len != null && parseLong(len, 0) > max) {
            throw new HttpError(413, "Fichier trop gros (" + settings.maxFileMb() + " Mo maximum).");
        }
        byte[] data;
        try (InputStream in = ex.getRequestBody()) {
            data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 16, max + 1));
        }
        if (data.length == 0) {
            throw new HttpError(400, "Aucun fichier reçu.");
        }
        if (data.length > max) {
            throw new HttpError(413, "Fichier trop gros (" + settings.maxFileMb() + " Mo maximum).");
        }
        if (!parsing.tryAcquire(30, TimeUnit.SECONDS)) {
            throw new HttpError(503, "Le serveur lit déjà d'autres schémas. Réessaie dans un instant.");
        }
        try {
            return SchematicReader.read(data, MAX_UNPACKED);
        } catch (Nbt.NbtException e) {
            throw new HttpError(400, e.getMessage());
        } catch (IOException | RuntimeException e) {
            throw new HttpError(400, "Ce fichier n'est pas un schéma reconnu (.litematic, .schem, .schematic ou .nbt).");
        } catch (OutOfMemoryError e) {
            throw new HttpError(413, "Ce schéma est trop gros pour la mémoire du serveur.");
        } finally {
            parsing.release();
        }
    }

    /** Keeps only the file name sent by the browser, cleaned up. */
    private static String fileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "plan";
        }
        String n = raw.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = DataStore.clean(n, 80);
        return n.isEmpty() ? "plan" : n;
    }

    /** "chateau_fort.litematic" → "chateau fort". */
    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String b = (dot > 0 ? fileName.substring(0, dot) : fileName).replace('_', ' ').trim();
        return b.isEmpty() ? "Projet" : b;
    }

    // ------------------------------------------------------------------ helpers

    private static int days(Map<String, String> q, int def) {
        long d = parseLong(q.get("days"), def);
        return (int) Math.max(1, Math.min(60, d));
    }

    private static long parseLong(String s, long def) {
        if (s == null) {
            return def;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static void requirePost(String method) {
        if (!"POST".equals(method)) {
            throw new HttpError(405, "Méthode non autorisée");
        }
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        return o.get(key).getAsString();
    }

    private JsonObject body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(64 * 1024);
            if (data.length == 0) {
                return new JsonObject();
            }
            try {
                JsonObject o = gson.fromJson(new String(data, StandardCharsets.UTF_8), JsonObject.class);
                return o == null ? new JsonObject() : o;
            } catch (Exception e) {
                throw new HttpError(400, "Requête invalide.");
            }
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            return h.substring(7).trim();
        }
        return null;
    }

    private static String clientIp(HttpExchange ex) {
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
            ip = fwd.split(",")[0].trim();
        }
        return ip;
    }

    private static boolean acceptsGzip(HttpExchange ex) {
        String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
        return ae != null && ae.contains("gzip");
    }

    private static void security(Headers h) {
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Frame-Options", "DENY");
        h.set("Content-Security-Policy", CSP);
    }

    private void sendJson(HttpExchange ex, int code, Object body) throws IOException {
        byte[] data = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        security(h);
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        if (data.length > 860 && acceptsGzip(ex)) {
            h.set("Content-Encoding", "gzip");
            h.set("Vary", "Accept-Encoding");
            data = gzip(data);
        }
        writeBody(ex, code, data);
    }

    private void trySendJson(HttpExchange ex, int code, Object body) {
        try {
            sendJson(ex, code, body);
        } catch (IOException ignored) {
            // client went away
        }
    }

    private static void sendBytes(HttpExchange ex, int code, byte[] data, String type, String cache) throws IOException {
        Headers h = ex.getResponseHeaders();
        security(h);
        h.set("Content-Type", type);
        h.set("Cache-Control", cache);
        if (type.startsWith("application/json") && data.length > 860 && acceptsGzip(ex)) {
            h.set("Content-Encoding", "gzip");
            h.set("Vary", "Accept-Encoding");
            data = gzip(data);
        }
        writeBody(ex, code, data);
    }

    private static void writeBody(HttpExchange ex, int code, byte[] data) throws IOException {
        if ("HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(code, -1);
            return;
        }
        ex.sendResponseHeaders(code, data.length == 0 ? -1 : data.length);
        if (data.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }
    }

    private static byte[] gzip(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 3 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        } catch (IOException e) {
            return data;
        }
        return bos.toByteArray();
    }

    private static boolean compressible(String type) {
        return type.startsWith("text/") || type.contains("javascript") || type.contains("json") || type.contains("svg")
                || type.contains("manifest");
    }

    private static String mime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".webmanifest")) return "application/manifest+json; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }
}
