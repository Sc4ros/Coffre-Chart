package fr.coffres;

import fr.coffres.model.Group;
import fr.coffres.model.Project;
import fr.coffres.store.DataStore;
import fr.coffres.store.GroupStore;
import fr.coffres.store.ProjectStore;
import fr.coffres.web.Api;
import fr.coffres.web.AuthManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Groups of friends: who is in them, and who sees a group project. */
class GroupTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final String STEVE = UUID.randomUUID().toString();
    private static final String ALEX = UUID.randomUUID().toString();
    private static final String LEA = UUID.randomUUID().toString();

    @TempDir
    Path dir;

    private static AuthManager.Session session(String uuid, boolean admin) {
        AuthManager.Session s = new AuthManager.Session();
        s.uuid = uuid;
        s.name = "x";
        s.admin = admin;
        return s;
    }

    @Test
    void groupsRememberPlayersAndMembers() {
        GroupStore groups = new GroupStore(dir.resolve("groups.json"), LOG, null);
        groups.seen(STEVE, "Steve");
        groups.seen(ALEX, "Alex");
        assertEquals(ALEX, groups.findPlayer("alex"), "names are found whatever the case");
        assertNull(groups.findPlayer("Inconnu"));

        Group g = groups.create("Les potes", STEVE, "Steve");
        assertTrue(groups.add(g.id, ALEX, "Alex"));
        assertFalse(groups.add(g.id, ALEX, "Alex"), "already in the group");
        assertFalse(groups.add(g.id, STEVE, "Steve"), "the creator is already in it");
        assertTrue(groups.isMember(g.id, STEVE));
        assertTrue(groups.isMember(g.id, ALEX));
        assertFalse(groups.isMember(g.id, LEA));

        groups.seen(ALEX, "Alex_v2");
        groups.save();
        GroupStore again = new GroupStore(dir.resolve("groups.json"), LOG, null);
        again.load();
        Group loaded = again.get(g.id);
        assertNotNull(loaded);
        assertEquals("Alex_v2", loaded.members.getFirst().name, "a new name is followed in the groups");
        assertEquals(List.of("Alex_v2", "Steve"), again.knownNames());

        assertTrue(again.remove(g.id, ALEX));
        assertFalse(again.isMember(g.id, ALEX));
    }

    @Test
    void groupProjectsAreSeenOnlyByTheGroup() {
        DataStore store = new DataStore(dir.resolve("store"), LOG);
        ProjectStore projects = new ProjectStore(dir.resolve("projects.json"), LOG, null);
        GroupStore groups = new GroupStore(dir.resolve("groups.json"), LOG, null);
        Api api = new Api(store, projects, null, groups);

        Group g = groups.create("Les potes", STEVE, "Steve");
        groups.add(g.id, ALEX, "Alex");
        Project p = new Project();
        p.id = "ferme123";
        p.name = "Ferme";
        p.owner = STEVE;
        p.ownerName = "Steve";
        p.materials.put("stone", 10L);
        projects.put(p);
        projects.setVisibility(p.id, false, g.id);

        assertNotNull(api.project(session(STEVE, false), p.id));
        Map<String, Object> seenByAlex = api.project(session(ALEX, false), p.id);
        assertNotNull(seenByAlex, "a member sees the group project");
        assertEquals("group", seenByAlex.get("visibility"));
        assertEquals(true, seenByAlex.get("canLink"), "a member can link chests and check the build");
        assertEquals(false, seenByAlex.get("canEdit"), "only the creator edits it");
        assertNull(api.project(session(LEA, false), p.id), "someone outside the group does not see it");
        assertNotNull(api.project(session(LEA, true), p.id), "admins see everything");
        assertTrue(api.projects(session(LEA, false)).isEmpty());

        // Deleting the group makes its projects solo.
        groups.delete(g.id);
        assertEquals(1, projects.clearGroup(g.id));
        assertNull(api.project(session(ALEX, false), p.id));
        assertEquals("solo", api.project(session(STEVE, false), p.id).get("visibility"));

        // Making it shared forgets the group.
        projects.setVisibility(p.id, true, "whatever");
        assertNull(projects.get(p.id).group);
        assertEquals("all", api.project(session(LEA, false), p.id).get("visibility"));
    }

    @Test
    void inventoriesCountEvenWhenTheProjectIsNotPlaced() {
        DataStore store = new DataStore(dir.resolve("store"), LOG);
        ProjectStore projects = new ProjectStore(dir.resolve("projects.json"), LOG, null);
        fr.coffres.store.PlanStore plans = new fr.coffres.store.PlanStore(dir.resolve("plans"), LOG);
        Api api = new Api(store, projects, plans, new GroupStore(dir.resolve("groups.json"), LOG, null));
        Project p = new Project();
        p.id = "maison1";
        p.name = "Maison";
        p.owner = STEVE;
        p.ownerName = "Steve";
        p.shared = true;
        p.materials.put("terracotta", 100L);
        projects.put(p);

        assertTrue(plans.setCarried(p.id, Map.of("terracotta", 30L), List.of(new fr.coffres.project.Build.Carrier("Arthur", 30))));
        assertFalse(plans.setCarried(p.id, Map.of("terracotta", 30L), List.of(new fr.coffres.project.Build.Carrier("Arthur", 30))), "nothing changed");
        Map<String, Object> seen = api.project(session(LEA, false), p.id);
        assertEquals(30L, seen.get("carried"), "not placed in the world, but Arthur's terracotta counts");
        assertEquals(30, seen.get("carriedPercent"));
        assertEquals(70L, seen.get("missing"));
        assertEquals(List.of(Map.of("name", "Arthur", "n", 30L)), seen.get("carriers"));

        assertTrue(plans.setCarried(p.id, Map.of(), List.of()));
        assertEquals(0L, api.project(session(LEA, false), p.id).get("carried"));
    }
}
