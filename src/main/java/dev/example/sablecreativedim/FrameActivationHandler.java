package dev.example.sablecreativedim;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Right-clicking an Amethyst Block with Flint and Steel attempts to light
 * a portal, same activation gesture as a vanilla Nether portal.
 *
 * Lighting a frame in the OVERWORLD (or any dimension other than the
 * creative test dimension) always succeeds if the frame shape is valid,
 * and registers + auto-builds the matching analogous portal at the same
 * (x, z) inside the creative dimension (see PortalLinkRegistry).
 *
 * Lighting a frame INSIDE the creative dimension only succeeds if its
 * origin is already a registered, valid analogous position -- i.e.
 * someone built the real overworld portal first. This is deliberate: the
 * creative dimension can't spawn portals that were never anchored to a
 * real place in the overworld.
 */
public class FrameActivationHandler {

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockPos pos = event.getPos();
        if (!level.getBlockState(pos).is(Blocks.AMETHYST_BLOCK)) {
            return;
        }

        ItemStack held = event.getItemStack();
        if (!held.is(Items.FLINT_AND_STEEL)) {
            return;
        }

        boolean isCreativeDim = serverLevel.dimension().equals(SableCreativeDimMod.CREATIVE_TESTING);

        if (isCreativeDim) {
            handleCreativeDimActivation(serverLevel, pos, event, held);
        } else {
            handleOverworldActivation(serverLevel, pos, event, held);
        }
    }

    private void handleOverworldActivation(ServerLevel serverLevel, BlockPos pos, PlayerInteractEvent.RightClickBlock event, ItemStack held) {
        AmethystFrameHelper.ActivationResult activation = AmethystFrameHelper.tryActivate(serverLevel, pos);
        if (activation == null) {
            return;
        }

        consumeItem(event, held);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);

        MinecraftServer server = serverLevel.getServer();
        PortalLinkRegistry.registerPortal(server, serverLevel, activation);

        ServerLevel creativeLevel = server.getLevel(SableCreativeDimMod.CREATIVE_TESTING);
        if (creativeLevel != null) {
            int scaledX = PortalLinkRegistry.toCreativeCoord(activation.interiorOrigin().getX());
            int scaledZ = PortalLinkRegistry.toCreativeCoord(activation.interiorOrigin().getZ());
            AmethystFrameHelper.buildAnalogousPortal(creativeLevel, scaledX, scaledZ);

            long overworldOriginKey = PortalLinkRegistry.key(
                    activation.interiorOrigin().getX(), activation.interiorOrigin().getZ());
            PortalLinkRegistry.registerCreativeSideCells(server, scaledX, scaledZ, overworldOriginKey);

            // Console log keeps the full technical detail regardless --
            // that's the actual diagnostic value, and it's dev/operator
            // territory anyway (server log, not chat).
            String detailedMsg = "Portal lit and linked: overworld " + activation.interiorOrigin().toShortString()
                    + " -> creative-dim (" + scaledX + ", 1, " + scaledZ + ")";
            System.out.println("[sablecreativedim] " + detailedMsg);

            // Chat stays simple for regular players -- raw coordinates
            // read as a debug dump, not a polished confirmation. Operators
            // get the technical detail inline since it's genuinely useful
            // to them; everyone else just gets a clean confirmation.
            var lighter = event.getEntity();
            if (lighter.hasPermissions(2)) {
                lighter.sendSystemMessage(Component.literal("Portal linked. " + detailedMsg));
            } else {
                lighter.sendSystemMessage(Component.literal("Portal linked to the creative dimension."));
            }
        }
    }

    private void handleCreativeDimActivation(ServerLevel serverLevel, BlockPos pos, PlayerInteractEvent.RightClickBlock event, ItemStack held) {
        // Peek at whether this WOULD be a valid frame shape before actually
        // lighting it, so we can tell the difference between "not a real
        // frame" (silent, same as vanilla clicking random obsidian) and
        // "valid frame shape, but no overworld anchor" (worth telling the
        // player about, since it looks like it should have worked).
        AmethystFrameHelper.ActivationResult wouldBe = AmethystFrameHelper.tryActivate(serverLevel, pos);
        if (wouldBe == null) {
            return;
        }

        Long overworldOriginKey = PortalLinkRegistry.originForCreativeCell(
                wouldBe.interiorOrigin().getX(), wouldBe.interiorOrigin().getZ());
        boolean anchored = overworldOriginKey != null;

        if (!anchored) {
            // tryActivate already filled the interior with portal blocks --
            // undo that since this frame isn't allowed to light.
            revertFill(serverLevel, wouldBe);
            Player player = event.getEntity();
            player.sendSystemMessage(Component.literal(
                    "This portal has no matching anchor in the overworld -- "
                            + "build and light a portal there first."));
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }

        consumeItem(event, held);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    /** Reverts a frame's interior back to air -- used when a creative-dim frame technically lit but isn't allowed to stay lit. Mirrors AmethystFrameHelper's own fill() iteration exactly, rather than trying to reconstruct positions from the (x, z)-only cell list. */
    private void revertFill(ServerLevel level, AmethystFrameHelper.ActivationResult activation) {
        for (int a = 0; a < activation.width(); a++) {
            for (int b = 0; b < activation.height(); b++) {
                BlockPos pos = activation.axis() == net.minecraft.core.Direction.Axis.X
                        ? activation.interiorOrigin().offset(a, b, 0)
                        : activation.interiorOrigin().offset(0, b, a);
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private void consumeItem(PlayerInteractEvent.RightClickBlock event, ItemStack held) {
        Player player = event.getEntity();
        if (!player.getAbilities().instabuild) {
            held.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }
    }
}
