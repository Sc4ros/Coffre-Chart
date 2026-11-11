package fr.coffres.model;

/**
 * One line of history: an item count change in one container.
 * Entries written by the same action share the same timestamp, chest, player and kind,
 * which is how the site groups them back into one event.
 */
public final class LogEntry {
    /** Player moved items by hand. */
    public static final String PLAYER = "p";
    /** Automatic change (hoppers, copper golems, other plugins…). */
    public static final String AUTO = "a";
    /** Container added to Coffres (its contents start being counted). */
    public static final String LINK = "l";
    /** Container removed from Coffres. */
    public static final String UNLINK = "u";
    /** Container broken or blown up. */
    public static final String BREAK = "b";

    /** Time in epoch millis. */
    public long t;
    /** Chest id. */
    public String c;
    /** Player name, or a label such as "Explosion", or null for automatic changes. */
    public String p;
    /** Player UUID, or null. */
    public String u;
    /** Kind, see constants. */
    public String k;
    /** Item id, or null for an event about the chest itself. */
    public String m;
    /** Count change (positive = added). */
    public long d;

    public LogEntry() {
    }

    public LogEntry(long t, String c, String p, String u, String k, String m, long d) {
        this.t = t;
        this.c = c;
        this.p = p;
        this.u = u;
        this.k = k;
        this.m = m;
        this.d = d;
    }
}
