package dev.example.sablecreativedim;

import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

import java.util.Optional;

/**
 * Snapshot/restore for Curios accessory slots (rings, belts, bags, etc.)
 * -- these live in a completely separate inventory system from vanilla's
 * main/armor/offhand slots, so the base inventory swap never touched them
 * at all. That was a real exploit: a player could stash items in a Curios
 * slot (e.g. a bag) before entering, then pick up MORE items into that
 * bag while inside the creative dimension, and walk out with them since
 * nothing ever cleared or restored Curios contents.
 *
 * Uses saveInventory(false) (a guaranteed plain save, no side effects)
 * followed by manually zeroing every slot via the standard
 * IItemHandlerModifiable interface, rather than relying on
 * saveInventory(boolean)'s undocumented boolean parameter -- couldn't
 * find a definitive answer on what that flag actually does (whether it
 * also clears, filters, or something else), and
 * IItemHandlerModifiable#setStackInSlot is long-stable, well-documented
 * API, so this sidesteps the ambiguity entirely instead of gambling on a
 * flag whose exact behavior isn't confirmed.
 */
public final class CuriosIntegration {

    private CuriosIntegration() {}

    /**
     * Saves the player's current Curios inventory and empties every slot.
     * Returns null if the player has no Curios handler at all (shouldn't
     * normally happen since Curios is a required dependency here, but
     * this fails safe rather than throwing if something's unusual).
     */
    public static ListTag stashAndClear(ServerPlayer player) {
        Optional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
        if (handlerOpt.isEmpty()) {
            return null;
        }
        ICuriosItemHandler handler = handlerOpt.get();
        ListTag saved = handler.saveInventory(false);

        IItemHandlerModifiable equipped = handler.getEquippedCurios();
        for (int i = 0; i < equipped.getSlots(); i++) {
            equipped.setStackInSlot(i, ItemStack.EMPTY);
        }
        return saved;
    }

    /** Restores a previously-saved Curios inventory. No-op if data is null or the player has no Curios handler. */
    public static void restore(ServerPlayer player, ListTag data) {
        if (data == null) {
            return;
        }
        CuriosApi.getCuriosInventory(player).ifPresent(handler -> handler.loadInventory(data));
    }
}
