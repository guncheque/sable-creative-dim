package dev.example.sablecreativedim;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

/**
 * Shared helpers for serializing an ItemStack[] to/from NBT, used by both
 * PlayerSnapshot (survival inventory, restored on /creativedim leave) and
 * CreativeLoadout (creative inventory, restored on /creativedim enter).
 *
 * ItemStack#save(...) throws IllegalStateException on an empty stack --
 * it can't be encoded to NBT at all, only real items can. Most inventory
 * slots ARE empty most of the time, so these only write non-empty slots
 * (tagged with their original index) rather than saving every slot
 * uniformly.
 */
final class ItemStackNbtUtil {

    private ItemStackNbtUtil() {}

    static ListTag itemsToList(ItemStack[] items, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (int i = 0; i < items.length; i++) {
            ItemStack stack = items[i];
            if (stack.isEmpty()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", i);
            entry.put("Item", stack.save(registries));
            list.add(entry);
        }
        return list;
    }

    /** Starts every slot as empty, then fills in only the slots that were actually saved. */
    static ItemStack[] itemsFromList(ListTag list, HolderLookup.Provider registries, int expectedSize) {
        ItemStack[] out = new ItemStack[expectedSize];
        for (int i = 0; i < out.length; i++) {
            out[i] = ItemStack.EMPTY;
        }
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int slot = entry.getInt("Slot");
            if (slot >= 0 && slot < out.length) {
                out[slot] = ItemStack.parseOptional(registries, entry.getCompound("Item"));
            }
        }
        return out;
    }
}
