package fr.coffres.track;

import fr.coffres.CoffresPlugin;
import fr.coffres.Msg;
import fr.coffres.model.LogEntry;
import fr.coffres.model.SlotItem;
import fr.coffres.model.StoredChest;
import fr.coffres.store.DataStore;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Follows every registered container.
 * <ul>
 *   <li>A player click in a container marks it "dirty"; next tick its contents are compared with the last
 *       snapshot and the difference is credited to that player (grouped until they close it).</li>
 *   <li>Hoppers mark it dirty without a player: the change is recorded as automatic.</li>
 *   <li>Every tick a few containers are re-checked in turn, which catches anything else
 *       (copper golems, other plugins, changes made while the server was off…).</li>
 * </ul>
 * Main thread only.
 */
public final class ChestTracker implements Listener {

    public record Actor(UUID uuid, String name, String kind) {
    }

    private static final class Session {
        final String chest;
        final UUID uuid;
        final String name;
        final Map<String, Long> deltas = new HashMap<>();
        long last;

        Session(String chest, UUID uuid, String name) {
            this.chest = chest;
            this.uuid = uuid;
            this.name = name;
        }
    }

    private final CoffresPlugin plugin;
    private final DataStore store;
    private final Containers containers;
    private final Map<String, ItemStack[]> cache = new HashMap<>();
    private final LinkedHashSet<String> dirty = new LinkedHashSet<>();
    private final Map<String, Actor> actors = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Map<String, Long>> auto = new HashMap<>();
    private final Map<UUID, String> lastLinked = new HashMap<>();
    private List<String> roundRobin = List.of();
    private int rrIndex;
    private long lastAutoFlush = System.currentTimeMillis();
    private long lastSessionCheck;

    private int perTick = 10;
    private long autoGroupMillis = 60_000;

    public ChestTracker(CoffresPlugin plugin, DataStore store, Containers containers) {
        this.plugin = plugin;
        this.store = store;
        this.containers = containers;
    }

    public void configure(int perTick, int autoGroupSeconds) {
        this.perTick = Math.max(1, perTick);
        this.autoGroupMillis = Math.max(5, autoGroupSeconds) * 1000L;
    }

    // ------------------------------------------------------------------ tick

    public void tick() {
        if (!dirty.isEmpty()) {
            List<String> ids = new ArrayList<>(dirty);
            dirty.clear();
            for (String id : ids) {
                refresh(id, actors.remove(id));
            }
        }
        if (rrIndex >= roundRobin.size()) {
            roundRobin = store.ids();
            rrIndex = 0;
        }
        for (int i = 0; i < perTick && rrIndex < roundRobin.size(); i++) {
            refresh(roundRobin.get(rrIndex++), null);
        }
        long now = System.currentTimeMillis();
        if (now - lastSessionCheck > 1000) {
            lastSessionCheck = now;
            Iterator<Session> it = sessions.values().iterator();
            while (it.hasNext()) {
                Session s = it.next();
                if (now - s.last > 30_000) {
                    writeSession(s);
                    it.remove();
                }
            }
        }
        if (now - lastAutoFlush > autoGroupMillis) {
            flushAuto();
        }
    }

    /** Writes everything that is still being grouped (on shutdown). */
    public void flushAll() {
        for (Session s : sessions.values()) {
            writeSession(s);
        }
        sessions.clear();
        flushAuto();
    }

    // ------------------------------------------------------------------ core

    /** Compares a container with its last snapshot and records the difference. */
    public void refresh(String id, Actor actor) {
        StoredChest c = store.get(id);
        if (c == null) {
            cache.remove(id);
            return;
        }
        World w = Bukkit.getWorld(c.world);
        if (w == null || !w.isChunkLoaded(c.x >> 4, c.z >> 4)) {
            return;
        }
        Block b = w.getBlockAt(c.x, c.y, c.z);
        Inventory inv = containers.inventoryOf(b);
        if (inv == null) {
            onMissing(c, actor);
            return;
        }
        ItemStack[] contents = inv.getContents();
        ItemStack[] prev = cache.get(id);
        if (prev != null && same(prev, contents)) {
            return;
        }
        cache.put(id, copy(contents));
        List<SlotItem> slots = Snapshot.read(contents, store.countNested(), store);
        Map<String, Long> delta = store.updateContents(id, slots, inv.getSize(), inv instanceof DoubleChestInventory);
        if (!delta.isEmpty()) {
            record(c, actor, delta);
        }
    }

    private void record(StoredChest c, Actor actor, Map<String, Long> delta) {
        if (actor == null) {
            Map<String, Long> acc = auto.computeIfAbsent(c.id, k -> new HashMap<>());
            delta.forEach((k, v) -> acc.merge(k, v, Long::sum));
            return;
        }
        if (LogEntry.PLAYER.equals(actor.kind())) {
            Session s = sessions.computeIfAbsent(c.id + '|' + actor.uuid(), k -> new Session(c.id, actor.uuid(), actor.name()));
            delta.forEach((k, v) -> s.deltas.merge(k, v, Long::sum));
            s.last = System.currentTimeMillis();
            store.setLastPlayer(c.id, actor.name());
            return;
        }
        long now = System.currentTimeMillis();
        List<LogEntry> entries = new ArrayList<>();
        String uuid = actor.uuid() == null ? null : actor.uuid().toString();
        delta.forEach((k, v) -> entries.add(new LogEntry(now, c.id, actor.name(), uuid, actor.kind(), k, v)));
        store.append(entries);
    }

    private void onMissing(StoredChest c, Actor actor) {
        flushChest(c.id);
        cache.remove(c.id);
        String name = actor == null ? null : actor.name();
        String uuid = actor == null || actor.uuid() == null ? null : actor.uuid().toString();
        store.unregister(c.id, LogEntry.BREAK, name, uuid);
        plugin.getLogger().info("Coffre « " + c.name + " » retiré : le bloc n'existe plus (" + c.world + " " + c.x + " " + c.y + " " + c.z + ").");
    }

    private void writeSession(Session s) {
        long now = System.currentTimeMillis();
        List<LogEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Long> e : s.deltas.entrySet()) {
            if (e.getValue() != 0) {
                entries.add(new LogEntry(now, s.chest, s.name, s.uuid.toString(), LogEntry.PLAYER, e.getKey(), e.getValue()));
            }
        }
        store.append(entries);
    }

    private void flushAuto() {
        lastAutoFlush = System.currentTimeMillis();
        if (auto.isEmpty()) {
            return;
        }
        long now = lastAutoFlush;
        List<LogEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> c : auto.entrySet()) {
            for (Map.Entry<String, Long> e : c.getValue().entrySet()) {
                if (e.getValue() != 0) {
                    entries.add(new LogEntry(now, c.getKey(), null, null, LogEntry.AUTO, e.getKey(), e.getValue()));
                }
            }
        }
        auto.clear();
        store.append(entries);
    }

    private void flushChest(String id) {
        Iterator<Session> it = sessions.values().iterator();
        while (it.hasNext()) {
            Session s = it.next();
            if (s.chest.equals(id)) {
                writeSession(s);
                it.remove();
            }
        }
        Map<String, Long> acc = auto.remove(id);
        if (acc != null) {
            long now = System.currentTimeMillis();
            List<LogEntry> entries = new ArrayList<>();
            acc.forEach((k, v) -> {
                if (v != 0) {
                    entries.add(new LogEntry(now, id, null, null, LogEntry.AUTO, k, v));
                }
            });
            store.append(entries);
        }
    }

    private void flushPlayer(UUID uuid) {
        Iterator<Session> it = sessions.values().iterator();
        while (it.hasNext()) {
            Session s = it.next();
            if (s.uuid.equals(uuid)) {
                writeSession(s);
                it.remove();
            }
        }
    }

    private static boolean same(ItemStack[] a, ItemStack[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            ItemStack x = a[i];
            ItemStack y = b[i];
            boolean ex = x == null || x.isEmpty();
            boolean ey = y == null || y.isEmpty();
            if (ex != ey) {
                return false;
            }
            if (!ex && !x.equals(y)) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack[] copy(ItemStack[] in) {
        ItemStack[] out = new ItemStack[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[i] == null || in[i].isEmpty() ? null : in[i].clone();
        }
        return out;
    }

    // ------------------------------------------------------------------ lookups

    /** The registered container this inventory belongs to, or null. */
    public StoredChest chestOf(Inventory inv) {
        if (inv == null) {
            return null;
        }
        InventoryHolder h = inv.getHolder(false);
        if (!(h instanceof Container) && !(h instanceof DoubleChest)) {
            return null;
        }
        Location l = inv.getLocation();
        if (l == null || l.getWorld() == null) {
            return null;
        }
        String world = l.getWorld().getName();
        for (int[] p : Containers.positionsOf(inv)) {
            StoredChest c = store.atLocation(world, p[0], p[1], p[2]);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    /** The registered container at this block (either half of a double chest), or null. */
    public StoredChest chestOf(Block b) {
        if (b == null || !containers.isTracked(b.getType())) {
            return null;
        }
        for (Block h : containers.halves(b)) {
            StoredChest c = store.atLocation(h.getWorld().getName(), h.getX(), h.getY(), h.getZ());
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    public String lastLinked(UUID player) {
        return lastLinked.get(player);
    }

    private static Actor player(HumanEntity p) {
        return new Actor(p.getUniqueId(), p.getName(), LogEntry.PLAYER);
    }

    // ------------------------------------------------------------------ add / remove

    public boolean canManage(Player p, StoredChest c) {
        return p.getUniqueId().toString().equals(c.owner) || p.hasPermission("coffres.admin");
    }

    /** Adds the container to Coffres, or removes it if it is already there. */
    public void toggle(Player p, Block b) {
        StoredChest existing = chestOf(b);
        if (existing != null) {
            remove(p, existing);
            return;
        }
        add(p, b);
    }

    /** Adds the container to Coffres and returns it (null when the block has no inventory). */
    public StoredChest add(Player p, Block b) {
        Inventory inv = containers.inventoryOf(b);
        if (inv == null) {
            return null;
        }
        Block anchor = b;
        if (inv instanceof DoubleChestInventory dci && dci.getLeftSide().getLocation() != null) {
            anchor = dci.getLeftSide().getLocation().getBlock();
        }
        long now = System.currentTimeMillis();
        StoredChest c = new StoredChest();
        c.id = DataStore.newId();
        c.world = anchor.getWorld().getName();
        c.dim = Containers.dimension(anchor.getWorld());
        c.x = anchor.getX();
        c.y = anchor.getY();
        c.z = anchor.getZ();
        c.type = anchor.getType().name().toLowerCase(Locale.ROOT);
        c.dbl = inv instanceof DoubleChestInventory;
        c.size = inv.getSize();
        c.name = Containers.defaultName(anchor.getType()) + " n°" + store.nextNumber();
        c.zone = store.nearestZone(c.world, c.x, c.y, c.z, 32);
        c.owner = p.getUniqueId().toString();
        c.ownerName = p.getName();
        c.pub = false; // always private when added: only its owner (and admins) can see it until they make it shared
        c.created = now;
        c.lastPlayer = p.getName();
        ItemStack[] contents = inv.getContents();
        List<SlotItem> slots = Snapshot.read(contents, store.countNested(), store);
        store.register(c, slots, p.getName(), p.getUniqueId().toString());
        cache.put(c.id, copy(contents));
        lastLinked.put(p.getUniqueId(), c.id);

        Location center = anchor.getLocation().add(0.5, 0.6, 0.5);
        p.spawnParticle(Particle.HAPPY_VILLAGER, center, 14, 0.35, 0.3, 0.35, 0);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.6f);
        long items = c.itemCount();
        Msg.send(p, "<green>✔</green> <white><name></white> ajouté"
                        + (c.zone != null ? " <gray>(zone <zone>)</gray>" : "")
                        + " <dark_gray>·</dark_gray> <gray><count> objet(s) comptés</gray>",
                Placeholder.unparsed("name", c.name),
                Placeholder.unparsed("zone", c.zone == null ? "" : c.zone),
                Placeholder.unparsed("count", Msg.number(items)));
        Msg.send(p, "  <click:suggest_command:'/coffres nom '><hover:show_text:'Donner un nom à ce coffre'><aqua>[Renommer]</aqua></hover></click> "
                + "<click:suggest_command:'/coffres zone '><hover:show_text:'Ranger ce coffre dans une zone (Base, Ferme…)'><aqua>[Zone]</aqua></hover></click>");
        Msg.raw(p, "  <gold>🔒</gold> <gray>Privé : toi seul le vois. Pour le partager, rends-le <white>commun</white> depuis sa page sur le site.</gray>");
        return c;
    }

    public void remove(Player p, StoredChest c) {
        if (!canManage(p, c)) {
            Msg.send(p, "<red>Ce coffre a été ajouté par <owner>. Seul lui ou un admin peut le retirer.</red>",
                    Placeholder.unparsed("owner", c.ownerName == null ? "un autre joueur" : c.ownerName));
            return;
        }
        flushChest(c.id);
        cache.remove(c.id);
        store.unregister(c.id, LogEntry.UNLINK, p.getName(), p.getUniqueId().toString());
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.8f);
        Msg.send(p, "<yellow>✖</yellow> <white><name></white> <gray>n'est plus suivi.</gray>", Placeholder.unparsed("name", c.name));
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player p = e.getPlayer();
        if (!plugin.linkMode().isActive(p)) {
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null || !containers.isTracked(b.getType())) {
            return;
        }
        if (e.getHand() != EquipmentSlot.HAND) {
            e.setCancelled(true);
            return;
        }
        if (e.useInteractedBlock() == Event.Result.DENY) {
            Msg.send(p, "<red>Tu n'as pas accès à ce coffre (zone protégée).</red>");
            return;
        }
        e.setCancelled(true);
        plugin.linkMode().touch(p);
        String project = plugin.linkMode().projectOf(p);
        if (project != null) {
            plugin.projectsGame().linkAt(p, b, project);
        } else {
            toggle(p, b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        StoredChest c = chestOf(e.getInventory());
        if (c != null) {
            dirty.remove(c.id);
            refresh(c.id, actors.remove(c.id));
            if (e.getPlayer() instanceof Player p) {
                plugin.locator().opened(p, c.id);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        StoredChest c = chestOf(e.getView().getTopInventory());
        if (c != null) {
            actors.put(c.id, player(e.getWhoClicked()));
            dirty.add(c.id);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        StoredChest c = chestOf(e.getView().getTopInventory());
        if (c != null) {
            actors.put(c.id, player(e.getWhoClicked()));
            dirty.add(c.id);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        StoredChest c = chestOf(e.getInventory());
        if (c == null) {
            return;
        }
        Actor a = player(e.getPlayer());
        dirty.remove(c.id);
        actors.remove(c.id);
        refresh(c.id, a);
        Session s = sessions.remove(c.id + '|' + a.uuid());
        if (s != null) {
            writeSession(s);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        StoredChest a = chestOf(e.getSource());
        if (a != null) {
            dirty.add(a.id);
        }
        StoredChest b = chestOf(e.getDestination());
        if (b != null) {
            dirty.add(b.id);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        handleRemoval(e.getBlock(), new Actor(p.getUniqueId(), p.getName(), LogEntry.BREAK));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) {
            handleRemoval(b, new Actor(null, "Explosion", LogEntry.BREAK));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) {
            handleRemoval(b, new Actor(null, "Explosion", LogEntry.BREAK));
        }
    }

    private void handleRemoval(Block b, Actor actor) {
        if (!containers.isTracked(b.getType())) {
            return;
        }
        List<Block> halves = containers.halves(b);
        StoredChest c = null;
        for (Block h : halves) {
            c = store.atLocation(h.getWorld().getName(), h.getX(), h.getY(), h.getZ());
            if (c != null) {
                break;
            }
        }
        if (c == null) {
            return;
        }
        String id = c.id;
        boolean registeredHere = c.world.equals(b.getWorld().getName()) && c.x == b.getX() && c.y == b.getY() && c.z == b.getZ();
        Block other = halves.size() > 1 ? halves.get(1) : null;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (store.get(id) == null) {
                return;
            }
            if (registeredHere && other != null && containers.isTracked(other.getType())) {
                store.move(id, other.getWorld().getName(), other.getX(), other.getY(), other.getZ());
            }
            refresh(id, actor);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        flushPlayer(e.getPlayer().getUniqueId());
        lastLinked.remove(e.getPlayer().getUniqueId());
    }
}
