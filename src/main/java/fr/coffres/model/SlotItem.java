package fr.coffres.model;

import java.util.List;

/** One occupied slot of a container. Field names are short because they are stored and sent as JSON. */
public final class SlotItem {
    /** Slot index in the container. */
    public int s;
    /** Item id without namespace, e.g. "diamond". */
    public String id;
    /** Stack size. */
    public int n;
    /** Custom (renamed) name, or null. */
    public String name;
    /** Enchanted (shows the glint on the site). */
    public boolean e;
    /** Contents of a shulker box / bundle, or null. */
    public List<SlotItem> in;

    public SlotItem() {
    }

    public SlotItem(int s, String id, int n) {
        this.s = s;
        this.id = id;
        this.n = n;
    }
}
