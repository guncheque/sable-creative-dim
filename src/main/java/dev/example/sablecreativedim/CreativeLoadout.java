package dev.example.sablecreativedim;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * A player's creative-mode inventory setup, saved the moment they leave
 * the creative dimension (right before the anti-cheat wipe that already
 * happens on /creativedim leave) and restored automatically the next time
 * they enter -- so builders don't have to re-gather their palette/tools
 * every single visit.
 *
 * This is intentionally separate from PlayerSnapshot: PlayerSnapshot is
 * "what to give back on leave" (their survival state), CreativeLoadout is
 * "what to give on enter" (their remembered creative setup).
 *
 * Curios ARE now part of this (added after initial release, following a
 * real user request) -- captured non-destructively via
 * CuriosIntegration#saveOnly right alongside the main inventory capture,
 * and restored via CuriosIntegration#restore right after the main
 * inventory loadout is applied. Safe to restore directly without an
 * extra clearAll() first at that point in enter()'s flow, since
 * stashAndClear() already emptied every Curios slot earlier in the same
 * call, for the (separate) survival-snapshot purpose.
 */
public record CreativeLoadout(ItemStack[] mainInventory, ItemStack[] armor, ItemStack offhand, ListTag curios) {

    public CompoundTag toNbt(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("MainInventory", ItemStackNbtUtil.itemsToList(mainInventory, registries));
        tag.put("Armor", ItemStackNbtUtil.itemsToList(armor, registries));
        if (!offhand.isEmpty()) {
            tag.put("Offhand", offhand.save(registries));
        }
        if (curios != null) {
            tag.put("Curios", curios);
        }
        return tag;
    }

    public static CreativeLoadout fromNbt(CompoundTag tag, HolderLookup.Provider registries) {
        ItemStack[] main = ItemStackNbtUtil.itemsFromList(tag.getList("MainInventory", Tag.TAG_COMPOUND), registries, 36);
        ItemStack[] armor = ItemStackNbtUtil.itemsFromList(tag.getList("Armor", Tag.TAG_COMPOUND), registries, 4);
        ItemStack offhand = tag.contains("Offhand")
                ? ItemStack.parseOptional(registries, tag.getCompound("Offhand"))
                : ItemStack.EMPTY;
        ListTag curios = tag.contains("Curios") ? tag.getList("Curios", Tag.TAG_COMPOUND) : null;
        return new CreativeLoadout(main, armor, offhand, curios);
    }
}
