package fr.coffres.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Item textures and item names for the site.
 * On first start it downloads the official Minecraft client from Mojang (like BlueMap does),
 * keeps only the item/block PNG textures and the item names, and caches them in the plugin folder.
 * Nothing about the server or its players is sent anywhere.
 */
public final class AssetManager {

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String RESOURCES = "https://resources.download.minecraft.net/";
    private static final Pattern SAFE = Pattern.compile("[a-z0-9_]{1,80}");
    private static final String TEX = "assets/minecraft/textures/";

    private final Path root;
    private final Logger logger;
    private final String mcVersion;
    private final String language;
    private final boolean download;
    private final Gson gson = new Gson();

    private volatile Path dir;
    private volatile byte[] indexJson = "{\"ready\":false,\"items\":[],\"blocks\":[]}".getBytes(StandardCharsets.UTF_8);
    private volatile byte[] langJson = "{}".getBytes(StandardCharsets.UTF_8);
    private volatile Map<String, String> names = Map.of();
    private volatile String status = "en attente";

    public AssetManager(Path root, Logger logger, String mcVersion, String language, boolean download) {
        this.root = root;
        this.logger = logger;
        this.mcVersion = mcVersion;
        this.language = SAFE.matcher(language == null ? "" : language).matches() ? language : "fr_fr";
        this.download = download;
    }

    /** Loads the cache, downloading it in the background if needed. Never blocks. */
    public void start() {
        Thread t = new Thread(this::prepare, "Coffres-assets");
        t.setDaemon(true);
        t.start();
    }

    public String status() {
        return status;
    }

    public byte[] indexJson() {
        return indexJson;
    }

    public byte[] langJson() {
        return langJson;
    }

    /** Translated item name, or a prettified id. */
    public String displayName(String id) {
        String n = names.get("item.minecraft." + id);
        if (n == null) {
            n = names.get("block.minecraft." + id);
        }
        if (n != null) {
            return n;
        }
        String[] parts = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    /** All item ids whose translated name or id contains the query. */
    public List<String> searchNames(String query) {
        String q = normalize(query);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : names.entrySet()) {
            String key = e.getKey();
            String id = key.substring(key.lastIndexOf('.') + 1);
            if (normalize(e.getValue()).contains(q) || id.contains(q.replace(' ', '_'))) {
                if (!out.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    public static String normalize(String s) {
        String n = java.text.Normalizer.normalize(s.toLowerCase(), java.text.Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").trim();
    }

    /** PNG bytes of item/NAME.png or block/NAME.png, or null. */
    public byte[] texture(String kind, String name) {
        Path d = dir;
        if (d == null || !("item".equals(kind) || "block".equals(kind)) || !SAFE.matcher(name).matches()) {
            return null;
        }
        try {
            Path p = d.resolve(kind).resolve(name + ".png");
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void prepare() {
        try {
            Path vdir = root.resolve(mcVersion.replaceAll("[^0-9A-Za-z._-]", "_"));
            Path done = vdir.resolve("done-" + language);
            if (!Files.exists(done)) {
                if (!download) {
                    status = "désactivé (assets.download-from-mojang: false)";
                    return;
                }
                status = "téléchargement…";
                logger.info("Téléchargement des textures et noms d'objets Minecraft " + mcVersion + " depuis Mojang (une seule fois)…");
                fetch(vdir);
                Files.writeString(done, String.valueOf(System.currentTimeMillis()));
                logger.info("Textures prêtes pour le site.");
            }
            dir = vdir;
            buildIndex(vdir);
            status = "prêt";
        } catch (Exception e) {
            status = "échec : " + e.getMessage();
            logger.log(Level.WARNING, "Impossible de récupérer les textures Minecraft (le site utilisera des icônes simplifiées) : " + e.getMessage());
        }
    }

    private void fetch(Path vdir) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JsonObject manifest = getJson(http, MANIFEST);
        String versionUrl = null;
        for (JsonElement v : manifest.getAsJsonArray("versions")) {
            JsonObject o = v.getAsJsonObject();
            if (o.get("id").getAsString().equals(mcVersion)) {
                versionUrl = o.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            String latest = manifest.getAsJsonObject("latest").get("release").getAsString();
            logger.warning("Version " + mcVersion + " introuvable chez Mojang, utilisation de " + latest);
            for (JsonElement v : manifest.getAsJsonArray("versions")) {
                JsonObject o = v.getAsJsonObject();
                if (o.get("id").getAsString().equals(latest)) {
                    versionUrl = o.get("url").getAsString();
                }
            }
        }
        if (versionUrl == null) {
            throw new IOException("version introuvable");
        }
        JsonObject version = getJson(http, versionUrl);
        String clientUrl = version.getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString();

        Files.createDirectories(vdir.resolve("item"));
        Files.createDirectories(vdir.resolve("block"));
        Map<String, String> lang = new TreeMap<>();

        HttpResponse<InputStream> resp = http.send(HttpRequest.newBuilder(URI.create(clientUrl))
                .timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("client.jar HTTP " + resp.statusCode());
        }
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(resp.body())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith(TEX) && name.endsWith(".png")) {
                    String rest = name.substring(TEX.length());
                    int slash = rest.indexOf('/');
                    if (slash < 0) {
                        continue;
                    }
                    String kind = rest.substring(0, slash);
                    String file = rest.substring(slash + 1);
                    if ((kind.equals("item") || kind.equals("block")) && file.indexOf('/') < 0) {
                        Files.write(vdir.resolve(kind).resolve(file), zip.readAllBytes());
                        count++;
                    }
                } else if (name.equals("assets/minecraft/lang/en_us.json")) {
                    lang.putAll(filterLang(new String(zip.readAllBytes(), StandardCharsets.UTF_8)));
                }
            }
        }
        if (count == 0) {
            throw new IOException("aucune texture trouvée dans le client");
        }

        if (!language.equals("en_us")) {
            try {
                JsonObject index = getJson(http, version.getAsJsonObject("assetIndex").get("url").getAsString());
                JsonObject obj = index.getAsJsonObject("objects").getAsJsonObject("minecraft/lang/" + language + ".json");
                if (obj != null) {
                    String hash = obj.get("hash").getAsString();
                    String body = getString(http, RESOURCES + hash.substring(0, 2) + "/" + hash);
                    lang.putAll(filterLang(body));
                } else {
                    logger.warning("Langue " + language + " introuvable, noms en anglais.");
                }
            } catch (Exception e) {
                logger.warning("Noms d'objets en " + language + " indisponibles : " + e.getMessage());
            }
        }
        Files.writeString(vdir.resolve("lang-" + language + ".json"), gson.toJson(lang), StandardCharsets.UTF_8);
    }

    private Map<String, String> filterLang(String json) {
        JsonObject o = gson.fromJson(json, JsonObject.class);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String k = e.getKey();
            if ((k.startsWith("item.minecraft.") || k.startsWith("block.minecraft.")) && k.indexOf('.', k.indexOf("minecraft.") + 10) < 0) {
                out.put(k, e.getValue().getAsString());
            }
        }
        return out;
    }

    private void buildIndex(Path vdir) throws IOException {
        JsonObject idx = new JsonObject();
        idx.addProperty("ready", true);
        idx.addProperty("version", mcVersion);
        idx.add("items", list(vdir.resolve("item")));
        idx.add("blocks", list(vdir.resolve("block")));
        indexJson = gson.toJson(idx).getBytes(StandardCharsets.UTF_8);
        Path langFile = vdir.resolve("lang-" + language + ".json");
        if (Files.exists(langFile)) {
            String text = Files.readString(langFile, StandardCharsets.UTF_8);
            Map<String, String> map = new java.util.HashMap<>();
            JsonObject o = gson.fromJson(text, JsonObject.class);
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                map.put(e.getKey(), e.getValue().getAsString());
            }
            names = Collections.unmodifiableMap(map);
            langJson = text.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static JsonArray list(Path d) throws IOException {
        JsonArray arr = new JsonArray();
        if (!Files.isDirectory(d)) {
            return arr;
        }
        try (Stream<Path> s = Files.list(d)) {
            s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".png"))
                    .map(n -> n.substring(0, n.length() - 4))
                    .sorted()
                    .forEach(arr::add);
        }
        return arr;
    }

    private JsonObject getJson(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            throw new IOException(url + " HTTP " + r.statusCode());
        }
        try (InputStreamReader reader = new InputStreamReader(r.body(), StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, JsonObject.class);
        }
    }

    private String getString(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) {
            throw new IOException(url + " HTTP " + r.statusCode());
        }
        return r.body();
    }
}
