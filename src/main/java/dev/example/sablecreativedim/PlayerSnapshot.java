package dev.example.sablecreativedim;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Everything needed to put a player back exactly how they were before
 * /creativedim enter: where they were, what gamemode they were in, and
 * their full inventory.
 *
 * Item (de)serialization lives in ItemStackNbtUtil, shared with
 * CreativeLoadout (the reverse: what to give the player when they
 * *enter* the creative dimension, restored from their last visit).
 *
 * NOTE: ItemStack's NBT save/load API is component-based as of 1.20.5+.
 * `ItemStack#save(HolderLookup.Provider)` / `ItemStack.parseOptional(...)`
 * are confirmed correct against the real 1.21.1 ItemStack class (verified
 * via javap against the actual game jar).
 */
public record PlayerSnapshot(
        ResourceKey<Level> dimension,
        Vec3 position,
        float yRot,
        float xRot,
        GameType gameMode,
        ItemStack[] mainInventory,
        ItemStack[] armor,
        ItemStack offhand,
        ListTag curios
) {

    public CompoundTag toNbt(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimension.location().toString());
        tag.putDouble("X", position.x);
        tag.putDouble("Y", position.y);
        tag.putDouble("Z", position.z);
        tag.putFloat("YRot", yRot);
        tag.putFloat("XRot", xRot);
        tag.putString("GameMode", gameMode.getName());
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

    public static PlayerSnapshot fromNbt(CompoundTag tag, HolderLookup.Provider registries) {
        ResourceKey<Level> dim = ResourceKey.create(
                Registries.DIMENSION,
                ResourceLocation.parse(tag.getString("Dimension")));
        Vec3 pos = new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
        GameType gameMode = GameType.byName(tag.getString("GameMode"), GameType.SURVIVAL);
        ItemStack[] main = ItemStackNbtUtil.itemsFromList(tag.getList("MainInventory", Tag.TAG_COMPOUND), registries, 36);
        ItemStack[] armor = ItemStackNbtUtil.itemsFromList(tag.getList("Armor", Tag.TAG_COMPOUND), registries, 4);
        ItemStack offhand = tag.contains("Offhand")
                ? ItemStack.parseOptional(registries, tag.getCompound("Offhand"))
                : ItemStack.EMPTY;
        ListTag curios = tag.contains("Curios") ? tag.getList("Curios", Tag.TAG_COMPOUND) : null;
        return new PlayerSnapshot(dim, pos, tag.getFloat("YRot"), tag.getFloat("XRot"),
                gameMode, main, armor, offhand, curios);
    }
}
