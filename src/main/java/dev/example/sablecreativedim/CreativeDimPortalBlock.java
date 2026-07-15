package dev.example.sablecreativedim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The block that fills a lit amethyst frame. Non-solid (walk/fly straight
 * through), glows like a Nether portal, and teleports any player who
 * touches it -- into the creative dimension from anywhere else, or back out
 * if touched from inside it.
 *
 * Reuses vanilla's own portal-cooldown mechanism (Entity#isOnPortalCooldown /
 * setPortalCooldown) to avoid re-triggering every tick while someone is
 * standing inside the portal -- the same mechanism Nether/End portals use.
 *
 * Rendering reuses the vanilla Nether portal's existing model/texture via
 * the blockstate JSON (src/main/resources/assets/.../blockstates/creative_dim_portal.json)
 * rather than shipping new art -- amethyst's own purple tone happens to
 * read reasonably close to the portal shimmer's purple already.
 *
 * neighborChanged triggers AmethystFrameHelper.checkAndClearIfBroken,
 * mirroring vanilla's collapsing-portal behavior when the frame is
 * broken. NOTE: neighborChanged's exact signature has been stable across
 * a long stretch of Minecraft versions, so this is lower-risk than some
 * of the earlier additions, but hasn't been javap-verified against this
 * specific build the way the ItemStack/networking APIs were.
 */
public class CreativeDimPortalBlock extends Block {

    public static final EnumProperty<Direction.Axis> AXIS = EnumProperty.create(
            "axis", Direction.Axis.class, Direction.Axis.X, Direction.Axis.Z);

    private static final VoxelShape X_SHAPE = Shapes.box(0.0, 0.0, 0.375, 1.0, 1.0, 0.625);
    private static final VoxelShape Z_SHAPE = Shapes.box(0.375, 0.0, 0.0, 0.625, 1.0, 1.0);

    public CreativeDimPortalBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X));
    }

    public static BlockBehaviour.Properties portalProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .noCollission()
                .randomTicks()
                .strength(-1.0F, 3600000.0F)
                .lightLevel(state -> 11)
                .sound(SoundType.GLASS)
                .pushReaction(PushReaction.BLOCK)
                .noLootTable();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(AXIS) == Direction.Axis.Z ? Z_SHAPE : X_SHAPE;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level instanceof ServerLevel serverLevel) {
            AmethystFrameHelper.checkAndClearIfBroken(serverLevel, pos);
        }
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide() || !(entity instanceof ServerPlayer player)) {
            return;
        }
        if (CreativeDimTeleporter.isInTeleportGracePeriod(player)) {
            return;
        }
        if (player.isOnPortalCooldown()) {
            return;
        }

        ServerLevel serverLevel = (ServerLevel) level;

        // Mirror vanilla's entry delay: charge for a few ticks before
        // actually teleporting (instant in creative, ~4s in survival),
        // sending progress to the client each tick so it can render the
        // shimmer. Stepping out before finishing resets the charge --
        // PortalChargeTracker handles that naturally since it just stops
        // being called.
        int currentTick = serverLevel.getServer().getTickCount();
        boolean readyToTeleport = PortalChargeTracker.tick(player, currentTick);
        PacketDistributor.sendToPlayer(player, new PortalChargePayload(PortalChargeTracker.progress(player)));

        if (!readyToTeleport) {
            return;
        }

        PortalChargeTracker.reset(player.getUUID());
        PacketDistributor.sendToPlayer(player, new PortalChargePayload(0f));

        CreativeDimTeleporter.Result result;
        if (serverLevel.dimension().equals(SableCreativeDimMod.CREATIVE_TESTING)) {
            // Leaving -- resolve which specific overworld portal this
            // creative-dim cell was built as the counterpart of, and send
            // the player back to THAT portal's real position, not
            // wherever they originally entered from. Falls back to the
            // old snapshot-based leave() if this cell isn't a registered
            // analogous portal (e.g. touching the fixed admin/testing
            // anchor, which has no overworld counterpart).
            Long overworldOriginKey = PortalLinkRegistry.originForCreativeCell(pos.getX(), pos.getZ());
            PortalLinkRegistry.PortalOrigin origin = overworldOriginKey != null
                    ? PortalLinkRegistry.originData(overworldOriginKey)
                    : null;
            result = origin != null
                    ? CreativeDimTeleporter.leaveAt(player, origin.dimension(), origin.pos())
                    : CreativeDimTeleporter.leave(player);
        } else {
            // Entering from outside -- resolve which registered portal
            // this cell belongs to, and land at that portal's analogous
            // position inside the creative dimension. If for some reason
            // this cell isn't registered (shouldn't happen -- lighting a
            // portal always registers every interior cell), fall back to
            // the fixed admin anchor rather than failing silently.
            Long originKey = PortalLinkRegistry.originForCell(pos.getX(), pos.getZ());
            BlockPos analogousOrigin;
            if (originKey != null) {
                analogousOrigin = new BlockPos(
                        PortalLinkRegistry.toCreativeCoord(PortalLinkRegistry.keyX(originKey)),
                        1,
                        PortalLinkRegistry.toCreativeCoord(PortalLinkRegistry.keyZ(originKey)));
            } else {
                // This shouldn't happen for a properly-lit portal -- flag
                // it loudly rather than silently falling back, since this
                // exact fallback is what was causing multiple overworld
                // portals to all land in the same spot.
                System.out.println("[sablecreativedim] WARNING: entered portal at " + pos
                        + " has no registered origin -- falling back to admin anchor. "
                        + "This portal's registration may be stale or missing; try breaking "
                        + "and relighting it.");
                analogousOrigin = new BlockPos(4, 1, 0);
            }
            result = CreativeDimTeleporter.enterAt(player, analogousOrigin);
        }

        if (result == CreativeDimTeleporter.Result.OK) {
            player.setPortalCooldown();
            level.playSound(null, pos, SoundEvents.PORTAL_TRAVEL, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        // ALREADY_INSIDE / NOT_INSIDE shouldn't normally happen here since
        // we pick enter vs leave based on which dimension the player is
        // physically standing in, but if it does (e.g. a stale snapshot),
        // we simply don't teleport and let them walk through harmlessly.
    }
}
