package fr.coffres.web;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Login for the site. A player proves who they are with a short code shown in the game chat
 * (asked from the site, or with /coffres web). The site then keeps a long random token.
 * Only a SHA-256 hash of each token is stored on disk.
 */
public final class AuthManager {

    public static final class Session {
        public String uuid;
        public String name;
        public boolean admin;
        public long created;
        public long lastSeen;
        public long expires;
    }

    private record Pending(UUID uuid, String name, boolean admin, long expires) {
    }

    private static final long CODE_TTL = 5 * 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private final Logger logger;
    private final Gson gson = new Gson();
    private final Map<String, Pending> codes = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, long[]> failures = new HashMap<>();
    private final Map<String, Long> lastRequest = new HashMap<>();
    private long sessionMillis = 180L * 86_400_000L;
    private boolean dirty;

    public AuthManager(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public synchronized void setSessionDays(int days) {
        this.sessionMillis = Math.max(1, days) * 86_400_000L;
    }

    public synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Map<String, Session> loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Session>>() {}.getType());
            if (loaded != null) {
                long now = System.currentTimeMillis();
                loaded.values().removeIf(s -> s.expires < now);
                sessions.putAll(loaded);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "sessions.json illisible, tout le monde devra se reconnecter", e);
        }
    }

    public void save() {
        String json;
        synchronized (this) {
            if (!dirty) {
                return;
            }
            json = gson.toJson(sessions);
            dirty = false;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Sauvegarde des sessions impossible", e);
        }
    }

    /** New 6-digit code for this player (replaces their previous one). */
    public synchronized String createCode(UUID uuid, String name, boolean admin) {
        codes.values().removeIf(p -> p.uuid.equals(uuid) || p.expires < System.currentTimeMillis());
        String code;
        do {
            code = String.format("%06d", RANDOM.nextInt(1_000_000));
        } while (codes.containsKey(code));
        codes.put(code, new Pending(uuid, name, admin, System.currentTimeMillis() + CODE_TTL));
        return code;
    }

    /** Anti-spam for "send me a code": one request every 15 s per player name. */
    public synchronized boolean allowCodeRequest(String name) {
        long now = System.currentTimeMillis();
        String key = name.toLowerCase();
        Long last = lastRequest.get(key);
        if (last != null && now - last < 15_000) {
            return false;
        }
        lastRequest.put(key, now);
        if (lastRequest.size() > 5000) {
            lastRequest.clear();
        }
        return true;
    }

    /** True when this address typed too many wrong codes recently. */
    public synchronized boolean isBlocked(String ip) {
        long[] f = failures.get(ip);
        return f != null && f[0] >= 8 && System.currentTimeMillis() - f[1] < 10 * 60_000L;
    }

    /** Exchanges a code for a session token, or returns null. */
    public synchronized String verify(String code, String ip) {
        if (isBlocked(ip)) {
            return null;
        }
        String clean = code == null ? "" : code.replaceAll("\\D", "");
        Pending p = codes.get(clean);
        long now = System.currentTimeMillis();
        if (p == null || p.expires < now) {
            long[] f = failures.computeIfAbsent(ip, k -> new long[2]);
            if (now - f[1] > 10 * 60_000L) {
                f[0] = 0;
            }
            f[0]++;
            f[1] = now;
            return null;
        }
        codes.remove(clean);
        failures.remove(ip);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Session s = new Session();
        s.uuid = p.uuid.toString();
        s.name = p.name;
        s.admin = p.admin;
        s.created = now;
        s.lastSeen = now;
        s.expires = now + sessionMillis;
        sessions.put(hash(token), s);
        dirty = true;
        return token;
    }

    /** Who the code belongs to (without consuming it), or null. */
    public synchronized UUID codeOwner(String code) {
        Pending p = codes.get(code == null ? "" : code.replaceAll("\\D", ""));
        return p == null || p.expires < System.currentTimeMillis() ? null : p.uuid;
    }

    public synchronized Session authenticate(String token) {
        if (token == null || token.length() < 20) {
            return null;
        }
        Session s = sessions.get(hash(token));
        if (s == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (s.expires < now) {
            sessions.remove(hash(token));
            dirty = true;
            return null;
        }
        if (now - s.lastSeen > 3_600_000L) {
            s.lastSeen = now;
            s.expires = now + sessionMillis;
            dirty = true;
        }
        return s;
    }

    public synchronized void logout(String token) {
        if (token != null && sessions.remove(hash(token)) != null) {
            dirty = true;
        }
    }

    /** Disconnects every device of a player. Returns how many were removed. */
    public synchronized int revokeAll(UUID uuid) {
        int n = 0;
        Iterator<Session> it = sessions.values().iterator();
        while (it.hasNext()) {
            if (it.next().uuid.equals(uuid.toString())) {
                it.remove();
                n++;
            }
        }
        if (n > 0) {
            dirty = true;
        }
        return n;
    }

    /** Keeps the admin flag and the name of saved sessions up to date (called when a player joins). */
    public synchronized void refreshPlayer(UUID uuid, String name, boolean admin) {
        for (Session s : sessions.values()) {
            if (s.uuid.equals(uuid.toString()) && (s.admin != admin || !s.name.equals(name))) {
                s.admin = admin;
                s.name = name;
                dirty = true;
            }
        }
    }

    public synchronized List<String> knownPlayers() {
        List<String> names = new ArrayList<>();
        for (Session s : sessions.values()) {
            if (!names.contains(s.name)) {
                names.add(s.name);
            }
        }
        return names;
    }

    private static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Test helper. */
    public synchronized String createSessionForTest(UUID uuid, String name, boolean admin) {
        String code = createCode(uuid, name, admin);
        return verify(code, "test");
    }
}
