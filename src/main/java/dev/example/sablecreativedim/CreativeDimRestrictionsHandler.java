package dev.example.sablecreativedim;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Ender Chest contents are tied to the player, not the block/location --
 * completely independent of our inventory snapshot/restore system. That
 * means opening one while standing in the creative dimension gives full
 * access to a player's real survival storage, bypassing the whole point
 * of the inventory swap entirely. Confirmed as a real, working exploit
 * (not just theoretical) before this fix went in.
 *
 * The fix is blocking the INTERACTION, not touching the block/inventory
 * itself -- unlike Curios (which needed active stash/clear/restore
 * because slot contents travel with the player), an Ender Chest block
 * placed in the creative dimension never enters the player's inventory at
 * all, so there's nothing to snapshot. Simply preventing the GUI from
 * opening while inside closes the exploit completely.
 *
 * Worth keeping in mind for the future: this is really "any storage
 * mechanism that's tied to the player/globally rather than to a
 * location," and Ender Chest just happens to be the concrete case found
 * so far. If this modpack has other genuinely global storage (an
 * ender-linked backpack upgrade, a cross-dimension ME network terminal,
 * etc.), the same category of exploit could apply there too -- this
 * fix doesn't cover those, since no report of them being an issue exists
 * yet.
 */
public class CreativeDimRestrictionsHandler {

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide() || !level.dimension().equals(SableCreativeDimMod.CREATIVE_TESTING)) {
            return;
        }
        if (!level.getBlockState(event.getPos()).is(Blocks.ENDER_CHEST)) {
            return;
        }

        event.setCanceled(true);
        Player player = event.getEntity();
        player.sendSystemMessage(Component.literal(
                "Ender Chests can't be opened inside the creative test dimension."));
    }
}
