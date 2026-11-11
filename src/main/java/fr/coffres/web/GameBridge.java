package fr.coffres.web;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What the site needs from the Minecraft server. The plugin implements it with the Bukkit API
 * (always switching to the main thread); the dev server implements it with fake data.
 * Every method may be called from a web thread.
 */
public interface GameBridge {

    record PlayerRef(UUID uuid, String name, boolean admin) {
    }

    record Position(String world, String dim, double x, double y, double z, float yaw) {
    }

    /** Finds an online player by name and shows them a fresh login code in chat. Completes with null when not online. */
    CompletableFuture<PlayerRef> sendLoginCode(String name, AuthManager auth);

    /** Highlights these containers in game for this player. Completes with a short French message for the site. */
    CompletableFuture<LocateResult> locate(UUID player, List<String> chestIds);

    /** Last known position of an online player, or null. */
    Position position(UUID player);

    List<String> onlinePlayers();

    String minecraftVersion();

    record LocateResult(boolean ok, String message) {
    }
}
