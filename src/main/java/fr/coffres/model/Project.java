package fr.coffres.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A building project: the items a plan (.litematic, .schem…) needs, and the containers that hold its materials.
 * Only the counts are kept, not the file. Collections are replaced, never changed in place, so a reader holding
 * a reference always sees a consistent list.
 */
public final class Project {
    public String id;
    public String name;
    public String owner;
    public String ownerName;
    /** Visible to every player, who can also link their containers to it. Otherwise only the owner and admins see it. */
    public boolean shared;
    public long created;
    public long updated;

    /** Original file name, e.g. "chateau.litematic". */
    public String file;
    /** "litematic", "schem", "schematic" or "nbt". */
    public String format;
    /** Name and author written in the file, or null. */
    public String schemName;
    public String author;
    public int sx;
    public int sy;
    public int sz;
    /** Total number of items needed. */
    public long blocks;
    /** Item id → count needed, most needed first. */
    public Map<String, Long> materials = new LinkedHashMap<>();
    /** Linked container ids. */
    public List<String> chests = new ArrayList<>();
}
