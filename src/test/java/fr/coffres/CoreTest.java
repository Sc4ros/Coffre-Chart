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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Counting, history, visibility and login: the logic that does not need a Minecraft server. */
class CoreTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @TempDir
    Path dir;

    private static SlotItem slot(int s, String id, int n) {
        return new SlotItem(s, id, n);
    }

    private static StoredChest chest(String id, boolean pub, List<SlotItem> slots) {
        StoredChest c = new StoredChest();
        c.id = id;
        c.world = "world";
        c.dim = "overworld";
        c.x = id.hashCode() % 100;
        c.y = 64;
        c.z = 0;
        c.type = "chest";
        c.name = "Coffre " + id;
        c.owner = OWNER.toString();
        c.ownerName = "Owner";
        c.pub = pub;
        c.slots = slots;
        return c;
    }

    private static AuthManager.Session session(UUID uuid, boolean admin) {
        AuthManager.Session s = new AuthManager.Session();
        s.uuid = uuid.toString();
        s.name = "x";
        s.admin = admin;
        return s;
    }

    @Test
    void shulkerContentsAreCounted() {
        SlotItem box = slot(0, "white_shulker_box", 1);
        box.in = List.of(slot(0, "diamond", 64), slot(1, "diamond", 10));
        Map<String, Long> totals = StoredChest.computeTotals(List.of(box, slot(1, "diamond", 5)), true);
        assertEquals(79L, totals.get("diamond"));
        assertEquals(1L, totals.get("white_shulker_box"));
        assertEquals(5L, StoredChest.computeTotals(List.of(box, slot(1, "diamond", 5)), false).get("diamond"));
    }

    @Test
    void updateContentsReturnsOnlyRealChanges() {
        DataStore store = new DataStore(dir, LOG);
        StoredChest c = chest("aaaa1111", true, new ArrayList<>(List.of(slot(0, "iron_ingot", 64), slot(1, "coal", 10))));
        store.putForTest(c, null);

        Map<String, Long> d = store.updateContents(c.id, List.of(slot(0, "iron_ingot", 32), slot(1, "coal", 10), slot(2, "diamond", 3)), 27, false);
        assertEquals(Map.of("iron_ingot", -32L, "diamond", 3L), d);

        long rev = store.rev();
        // Same items moved to other slots: no history line, but the slot layout is updated.
        Map<String, Long> moved = store.updateContents(c.id, List.of(slot(5, "iron_ingot", 32), slot(1, "coal", 10), slot(2, "diamond", 3)), 27, false);
        assertTrue(moved.isEmpty());
        assertTrue(store.rev() > rev);
        assertEquals(5, store.get(c.id).slots.getFirst().s);

        // Nothing at all changed.
        long rev2 = store.rev();
        assertTrue(store.updateContents(c.id, List.of(slot(5, "iron_ingot", 32), slot(1, "coal", 10), slot(2, "diamond", 3)), 27, false).isEmpty());
        assertEquals(rev2, store.rev());
    }

    @Test
    void saveAndLoadKeepEverything() {
        DataStore store = new DataStore(dir, LOG);
        StoredChest c = chest("bbbb2222", false, new ArrayList<>(List.of(slot(0, "oak_log", 64))));
        store.register(c, c.slots, "Owner", OWNER.toString());
        store.append(List.of(new LogEntry(System.currentTimeMillis(), c.id, "Owner", OWNER.toString(), LogEntry.PLAYER, "oak_log", -10)));
        store.save();

        DataStore again = new DataStore(dir, LOG);
        again.load();
        StoredChest loaded = again.get(c.id);
        assertNotNull(loaded);
        assertEquals(64L, loaded.totals().get("oak_log"));
        assertFalse(loaded.pub);
        assertNotNull(again.atLocation("world", c.x, 64, 0));
        synchronized (again) {
            // link marker + link count + player line
            assertEquals(3, again.logLocked().size());
        }
    }

    @Test
    void privateChestsAreHiddenFromOthers() {
        DataStore store = new DataStore(dir, LOG);
        store.putForTest(chest("pub11111", true, new ArrayList<>(List.of(slot(0, "stone", 64)))), null);
        store.putForTest(chest("priv2222", false, new ArrayList<>(List.of(slot(0, "diamond", 9)))), null);
        Api api = new Api(store, new ProjectStore(dir.resolve("projects.json"), LOG, null));

        assertEquals(1, api.items(session(OTHER, false)).size());
        assertEquals(2, api.items(session(OWNER, false)).size());
        assertEquals(2, api.items(session(OTHER, true)).size());
        assertNull(api.chest(session(OTHER, false), "priv2222"));
        assertNotNull(api.chest(session(OWNER, false), "priv2222"));
        assertTrue(api.filterVisible(session(OTHER, false), List.of("priv2222")).isEmpty());
        assertFalse(api.canEdit(session(OTHER, false), "pub11111"));
        assertTrue(api.canEdit(session(OTHER, true), "pub11111"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void historyIsRebuiltBackwardsAndGrouped() {
        DataStore store = new DataStore(dir, LOG);
        long now = System.currentTimeMillis();
        StoredChest c = chest("hist3333", true, new ArrayList<>(List.of(slot(0, "diamond", 50), slot(1, "coal", 20))));
        List<LogEntry> log = List.of(
                // 3 hours ago: Owner put 30 diamonds and 20 coal in one go
                new LogEntry(now - 3 * 3_600_000L, c.id, "Owner", OWNER.toString(), LogEntry.PLAYER, "diamond", 30),
                new LogEntry(now - 3 * 3_600_000L, c.id, "Owner", OWNER.toString(), LogEntry.PLAYER, "coal", 20),
                // 30 minutes ago: someone else took 5 diamonds
                new LogEntry(now - 1_800_000L, c.id, "Other", OTHER.toString(), LogEntry.PLAYER, "diamond", -5));
        store.putForTest(c, new ArrayList<>(log));
        Api api = new Api(store, new ProjectStore(dir.resolve("projects.json"), LOG, null));
        AuthManager.Session s = session(OTHER, false);

        Map<String, Object> item = api.item(s, "diamond", 1);
        List<Long> pts = (List<Long>) ((Map<String, Object>) item.get("trend")).get("points");
        assertEquals(50L, pts.getLast());            // now
        assertEquals(55L, pts.get(pts.size() - 2));  // one hour ago, before the 5 were taken
        assertEquals(25L, pts.get(pts.size() - 5));  // four hours ago, before the deposit

        Map<String, Object> act = api.activity(s, null, null, null, null, Long.MAX_VALUE, 50);
        List<Map<String, Object>> events = (List<Map<String, Object>>) act.get("events");
        assertEquals(2, events.size());
        assertEquals("Other", events.get(0).get("player"));
        assertEquals(2, ((List<?>) events.get(1).get("items")).size());

        List<?> deposits = (List<?>) api.activity(s, null, null, null, "in", Long.MAX_VALUE, 50).get("events");
        assertEquals(1, deposits.size());
        List<?> byPlayer = (List<?>) api.activity(s, null, null, "other", null, Long.MAX_VALUE, 50).get("events");
        assertEquals(1, byPlayer.size());
    }

    @Test
    void loginCodesWorkOnceAndBlockGuessing() {
        AuthManager auth = new AuthManager(dir.resolve("sessions.json"), LOG);
        String code = auth.createCode(OWNER, "Owner", false);
        String token = auth.verify(code, "1.2.3.4");
        assertNotNull(token);
        assertEquals("Owner", auth.authenticate(token).name);
        assertNull(auth.verify(code, "1.2.3.4"), "a code works only once");

        for (int i = 0; i < 8; i++) {
            auth.verify("000000", "6.6.6.6");
        }
        assertTrue(auth.isBlocked("6.6.6.6"));
        String fresh = auth.createCode(OTHER, "Other", false);
        assertNull(auth.verify(fresh, "6.6.6.6"), "blocked address cannot log in even with a good code");
        assertNotNull(auth.verify(fresh, "7.7.7.7"));

        auth.logout(token);
        assertNull(auth.authenticate(token));
    }
}
