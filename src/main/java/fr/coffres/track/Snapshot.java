package fr.coffres.track;

import fr.coffres.model.SlotItem;
import fr.coffres.store.DataStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Turns Bukkit item stacks into the plain data the site uses. */
public final class Snapshot {

    private Snapshot() {
    }

    public static String id(ItemStack it) {
        return it.getType().name().toLowerCase(Locale.ROOT);
    }

    public static List<SlotItem> read(ItemStack[] contents, boolean nested, DataStore store) {
        List<SlotItem> out = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            SlotItem s = toSlot(i, contents[i], nested ? 2 : 0, store);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private static SlotItem toSlot(int slot, ItemStack it, int depth, DataStore store) {
        if (it == null || it.isEmpty()) {
            return null;
        }
        SlotItem s = new SlotItem(slot, id(it), it.getAmount());
        if (store != null) {
            store.noteStackSize(s.id, it.getMaxStackSize());
        }
        if (!it.hasItemMeta()) {
            return s;
        }
        ItemMeta meta = it.getItemMeta();
        if (meta.hasDisplayName()) {
            Component name = meta.displayName();
            if (name != null) {
                String plain = PlainTextComponentSerializer.plainText().serialize(name);
                s.name = plain.length() > 50 ? plain.substring(0, 50) : plain;
            }
        }
        s.e = meta.hasEnchants() || (meta instanceof EnchantmentStorageMeta esm && esm.hasStoredEnchants());
        if (depth > 0) {
            if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof ShulkerBox box) {
                ItemStack[] inner = box.getInventory().getContents();
                List<SlotItem> list = new ArrayList<>();
                for (int i = 0; i < inner.length; i++) {
                    SlotItem x = toSlot(i, inner[i], depth - 1, store);
                    if (x != null) {
                        list.add(x);
                    }
                }
                if (!list.isEmpty()) {
                    s.in = list;
                }
            } else if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
                List<SlotItem> list = new ArrayList<>();
                int i = 0;
                for (ItemStack inner : bundle.getItems()) {
                    SlotItem x = toSlot(i++, inner, depth - 1, store);
                    if (x != null) {
                        list.add(x);
                    }
                }
                if (!list.isEmpty()) {
                    s.in = list;
                }
            }
        }
        return s;
    }
}
