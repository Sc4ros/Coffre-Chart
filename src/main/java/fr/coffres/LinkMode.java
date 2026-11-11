package fr.coffres;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Mode ajout": while it is on, right-clicking a container adds it to Coffres (or removes it).
 * The same mode can target a building project: right-clicking then links the container to that project
 * (adding it to Coffres first if needed), or unlinks it.
 */
public final class LinkMode {

    private record Target(String id, String name) {
    }

    private final Map<UUID, Long> active = new HashMap<>();
    private final Map<UUID, Target> projects = new HashMap<>();
    private long timeoutMillis = 180_000;

    public void setTimeoutSeconds(int s) {
        timeoutMillis = Math.max(10, s) * 1000L;
    }

    public boolean isActive(Player p) {
        Long until = active.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    /** Project targeted by the mode, or null for the normal "add to Coffres" mode. */
    public String projectOf(Player p) {
        Target t = isActive(p) ? projects.get(p.getUniqueId()) : null;
        return t == null ? null : t.id();
    }

    /** Turns the normal mode on, or stops whichever mode is on. Returns the new state. */
    public boolean toggle(Player p) {
        if (isActive(p)) {
            stop(p, false);
            return false;
        }
        projects.remove(p.getUniqueId());
        active.put(p.getUniqueId(), System.currentTimeMillis() + timeoutMillis);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.4f);
        Msg.send(p, "<aqua>Mode ajout activé.</aqua> <gray>Fais un <white>clic droit</white> sur un coffre, un tonneau ou une shulker pour l'ajouter. Reclique dessus pour le retirer.</gray>");
        Msg.raw(p, "  <click:run_command:'/coffres ajouter'><hover:show_text:'Arrêter le mode ajout'><red>[Arrêter]</red></hover></click>");
        return true;
    }

    /** Turns the project mode on (or off when it already targets this project). Returns the new state. */
    public boolean toggleProject(Player p, String projectId, String projectName) {
        Target current = isActive(p) ? projects.get(p.getUniqueId()) : null;
        if (current != null && current.id().equals(projectId)) {
            stop(p, false);
            return false;
        }
        projects.put(p.getUniqueId(), new Target(projectId, projectName));
        active.put(p.getUniqueId(), System.currentTimeMillis() + timeoutMillis);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.4f);
        Msg.send(p, "<light_purple>Liaison au projet</light_purple> <white><project></white> <gray>: fais un <white>clic droit</white> sur les coffres qui contiennent ses matériaux. Reclique pour en délier un.</gray>",
                Placeholder.unparsed("project", projectName));
        Msg.raw(p, "  <click:run_command:'/coffres ajouter'><hover:show_text:'Arrêter la liaison'><red>[Arrêter]</red></hover></click>");
        return true;
    }

    public void stop(Player p, boolean expired) {
        Target t = projects.remove(p.getUniqueId());
        if (active.remove(p.getUniqueId()) != null) {
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.6f, 1.2f);
            p.sendActionBar(net.kyori.adventure.text.Component.empty());
            String what = t == null ? "Mode ajout" : "Liaison au projet";
            Msg.send(p, expired ? "<gray>" + what + " terminé" + (t == null ? "" : "e") + " (inactif depuis un moment).</gray>"
                    : "<gray>" + what + " désactivé" + (t == null ? "" : "e") + ".</gray>");
        }
    }

    /** Refreshes the action bar hint and ends expired modes. Every second, main thread. */
    public void tick() {
        long now = System.currentTimeMillis();
        List<Player> expired = new ArrayList<>();
        Iterator<Map.Entry<UUID, Long>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                projects.remove(e.getKey());
            } else if (e.getValue() <= now) {
                expired.add(p);
            } else {
                Target t = projects.get(e.getKey());
                if (t == null) {
                    Msg.actionBar(p, "<aqua>✚ Mode ajout</aqua> <gray>· clic droit sur un coffre pour l'ajouter ou le retirer</gray>");
                } else {
                    Msg.actionBar(p, "<light_purple>⛏ Projet</light_purple> <white><project></white> <gray>· clic droit sur un coffre pour le lier ou le délier</gray>",
                            Placeholder.unparsed("project", t.name()));
                }
            }
        }
        for (Player p : expired) {
            stop(p, true);
        }
    }

    /** Pushes back the timeout after each use. */
    public void touch(Player p) {
        if (isActive(p)) {
            active.put(p.getUniqueId(), System.currentTimeMillis() + timeoutMillis);
        }
    }

    public void forget(UUID uuid) {
        active.remove(uuid);
        projects.remove(uuid);
    }
}
