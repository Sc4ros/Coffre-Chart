package fr.coffres;

import fr.coffres.model.LogEntry;
import fr.coffres.model.SlotItem;
import fr.coffres.model.StoredChest;
import fr.coffres.store.DataStore;
import fr.coffres.store.ProjectStore;
import fr.coffres.web.Api;
import fr.coffres.web.AuthManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Containers are private until their owner shares them; the site shows "Mes coffres" and "Communs" separately. */
class VisibilityTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final UUID ME = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @TempDir
    Path dir;

    private static StoredChest chest(String id, UUID owner, boolean common, String item, int n) {
        StoredChest c = new StoredChest();
        c.id = id;
        c.world = "world";
        c.dim = "overworld";
        c.x = Math.abs(id.hashCode() % 1000);
        c.y = 64;
        c.type = "chest";
        c.name = "Coffre " + id;
        c.owner = owner.toString();
        c.ownerName = owner.equals(ME) ? "Moi" : "Autre";
        c.pub = common;
        c.slots = new ArrayList<>(List.of(new SlotItem(0, item, n)));
        return c;
    }

    private static AuthManager.Session session(UUID uuid, boolean admin) {
        AuthManager.Session s = new AuthManager.Session();
        s.uuid = uuid.toString();
        s.name = "x";
        s.admin = admin;
        return s;
    }

    private static Set<String> itemIds(List<Map<String, Object>> items) {
        return items.stream().map(m -> (String) m.get("id")).collect(Collectors.toSet());
    }

    @Test
    @SuppressWarnings("unchecked")
    void mineAndCommonAreSeparate() {
        DataStore store = new DataStore(dir, LOG);
        long t = System.currentTimeMillis() - 60_000;
        store.putForTest(chest("mineprv1", ME, false, "stone", 10), null);
        store.putForTest(chest("minecom1", ME, true, "dirt", 5), null);
        store.putForTest(chest("othprv11", OTHER, false, "diamond", 3),
                List.of(new LogEntry(t, "othprv11", "Autre", OTHER.toString(), LogEntry.PLAYER, "diamond", 3)));
        store.putForTest(chest("othcom11", OTHER, true, "glass", 7),
                List.of(new LogEntry(t + 1, "othcom11", "Autre", OTHER.toString(), LogEntry.PLAYER, "glass", 7)));
        Api api = new Api(store, new ProjectStore(dir.resolve("projects.json"), LOG, null));
        AuthManager.Session me = session(ME, false);

        assertEquals(Set.of("stone", "dirt"), itemIds(api.items(me.view("mine"))), "Mes coffres: only what I added");
        assertEquals(Set.of("dirt", "glass"), itemIds(api.items(me.view("common"))), "Communs: shared containers of everyone");
        assertEquals(Set.of("stone", "dirt", "glass"), itemIds(api.items(me)), "never the private containers of others");
        assertEquals(2, api.chests(me.view("mine")).size());
        assertEquals(2, api.chests(me.view("common")).size());

        // The private container of another player never shows up, not even in the history.
        List<Map<String, Object>> events = (List<Map<String, Object>>) api.activity(me.view("common"), null, null, null, null, Long.MAX_VALUE, 50).get("events");
        assertEquals(1, events.size());
        assertEquals("othcom11", ((Map<String, Object>) events.getFirst().get("chest")).get("id"));
        assertTrue(((List<?>) api.activity(me.view("mine"), null, null, null, null, Long.MAX_VALUE, 50).get("events")).isEmpty());

        assertEquals(Map.of("mine", 2, "common", 2, "all", 3), api.counts(me));
        // Admins can see everything, but only in the "Tous" part.
        AuthManager.Session admin = session(OTHER, true);
        assertEquals(4, api.chests(admin).size());
        assertEquals(2, api.chests(admin.view("mine")).size());

        // Only the owner may share a container; an admin may not expose someone else's.
        assertTrue(api.isOwner(me, "mineprv1"));
        assertFalse(api.isOwner(admin, "mineprv1"));
    }

    @Test
    void containersFromOlderVersionsBecomePrivateOnce() throws IOException {
        DataStore store = new DataStore(dir, LOG);
        StoredChest c = chest("oldpub11", ME, true, "iron_ingot", 20);
        store.register(c, c.slots, "Moi", ME.toString());
        store.save();
        // Pretend the file was written by version 1.x, where containers were visible to everyone by default.
        Path file = dir.resolve("chests.json");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8).replace("\"version\":2", "\"version\":1"), StandardCharsets.UTF_8);

        DataStore loaded = new DataStore(dir, LOG);
        loaded.load();
        assertFalse(loaded.get("oldpub11").pub, "old public containers become private");

        // The owner shares it again: it stays shared after the next restart (the migration runs only once).
        loaded.edit("oldpub11", null, null, true);
        loaded.save();
        DataStore again = new DataStore(dir, LOG);
        again.load();
        assertTrue(again.get("oldpub11").pub);
    }
}
