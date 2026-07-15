package dev.example.sablecreativedim;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
 * "what to give on enter" (their remembered creative setup). Curios slots
 * are deliberately NOT part of this -- the loadout only covers main
 * inventory, armor, and offhand, matching what's actually meant by
 * "creative inventory setup" here rather than accessory items.
 */
public record CreativeLoadout(ItemStack[] mainInventory, ItemStack[] armor, ItemStack offhand) {

    public CompoundTag toNbt(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("MainInventory", ItemStackNbtUtil.itemsToList(mainInventory, registries));
        tag.put("Armor", ItemStackNbtUtil.itemsToList(armor, registries));
        if (!offhand.isEmpty()) {
            tag.put("Offhand", offhand.save(registries));
        }
        return tag;
    }

    public static CreativeLoadout fromNbt(CompoundTag tag, HolderLookup.Provider registries) {
        ItemStack[] main = ItemStackNbtUtil.itemsFromList(tag.getList("MainInventory", Tag.TAG_COMPOUND), registries, 36);
        ItemStack[] armor = ItemStackNbtUtil.itemsFromList(tag.getList("Armor", Tag.TAG_COMPOUND), registries, 4);
        ItemStack offhand = tag.contains("Offhand")
                ? ItemStack.parseOptional(registries, tag.getCompound("Offhand"))
                : ItemStack.EMPTY;
        return new CreativeLoadout(main, armor, offhand);
    }
}
