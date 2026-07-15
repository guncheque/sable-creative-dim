package dev.example.sablecreativedim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The actual enter/leave mechanics, factored out so both /creativedim
 * enter|leave and the amethyst portal block trigger the exact same logic
 * (snapshot, inventory swap, gamemode swap, persistence) rather than two
 * copies drifting apart.
 *
 * Two separate inventory concepts, easy to confuse: PlayerSnapshot is
 * "what to give back on leave" (their survival state, captured on enter).
 * CreativeLoadout is "what to give on enter" (their remembered creative
 * setup, captured on leave, right before the anti-cheat wipe that
 * discards whatever they built up). Enter restores a loadout if one
 * exists; leave always saves the current creative inventory as the new
 * loadout before wiping it.
 */
public final class CreativeDimTeleporter {

    static final Map<UUID, PlayerSnapshot> SNAPSHOTS = new HashMap<>();
    static final Map<UUID, CreativeLoadout> LOADOUTS = new HashMap<>();

    // Floor block sits at y=0 (void air fills min_y..-1), so y=1 is the first walkable space above it.
    private static final BlockPos ENTRY_POS = new BlockPos(0, 1, 0);

    // Our own explicit "just teleported, ignore portal touches" window,
    // independent of vanilla's Entity#setPortalCooldown -- that cooldown
    // is applied by the caller *after* teleportTo() already runs, and
    // whether it reliably survives a same-tick cross-dimension jump landing
    // the player directly inside another portal block was the actual
    // cause of an instant round-trip bounce. Tracking it ourselves removes
    // that uncertainty entirely.
    private static final Map<UUID, Integer> TELEPORT_GRACE_EXPIRY = new HashMap<>();
    // Matches vanilla's real post-arrival Nether portal cooldown: 300 ticks
    // (15 seconds) during which an entity can't be pulled back through any
    // portal, giving plenty of time to just stand there without being
    // bounced. (Vanilla also has a separate *entry* delay -- 80 ticks/4s in
    // survival, 1 tick in creative -- before a portal actually teleports
    // you in the first place; our portal currently triggers instantly on
    // touch instead, so that half isn't mirrored yet. Say the word if you
    // want that too.)
    private static final int TELEPORT_GRACE_TICKS = 300;

    public static boolean isInTeleportGracePeriod(ServerPlayer player) {
        Integer expiryTick = TELEPORT_GRACE_EXPIRY.get(player.getUUID());
        if (expiryTick == null) {
            return false;
        }
        MinecraftServer server = player.getServer();
        return server != null && server.getTickCount() < expiryTick;
    }

    private static void startTeleportGracePeriod(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            TELEPORT_GRACE_EXPIRY.put(player.getUUID(), server.getTickCount() + TELEPORT_GRACE_TICKS);
        }
    }

    private CreativeDimTeleporter() {}

    public enum Result {
        OK,
        ALREADY_INSIDE,
        NOT_INSIDE,
        DIMENSION_UNAVAILABLE
    }

    public static Result enter(ServerPlayer player) {
        return enter(player, ENTRY_POS, true);
    }

    /**
     * Portal-triggered entry: teleports to the analogous position inside
     * the creative dimension (same x, z as the overworld portal that was
     * touched), rather than the fixed admin anchor. Doesn't call
     * ensureReturnPortal/buildAnalogousPortal -- FrameActivationHandler
     * already built that portal at lighting time, so it should already be
     * there by the time anyone walks through.
     */
    public static Result enterAt(ServerPlayer player, BlockPos analogousInteriorOrigin) {
        return enter(player, analogousInteriorOrigin, false);
    }

    private static Result enter(ServerPlayer player, BlockPos targetPos, boolean ensurePortalExists) {
        if (SNAPSHOTS.containsKey(player.getUUID())) {
            return Result.ALREADY_INSIDE;
        }

        MinecraftServer server = player.getServer();
        ServerLevel target = server != null ? server.getLevel(SableCreativeDimMod.CREATIVE_TESTING) : null;
        if (target == null) {
            return Result.DIMENSION_UNAVAILABLE;
        }

        Inventory inv = player.getInventory();
        var curiosData = CuriosIntegration.stashAndClear(player);
        PlayerSnapshot snapshot = new PlayerSnapshot(
                player.level().dimension(),
                player.position(),
                player.getYRot(),
                player.getXRot(),
                player.gameMode.getGameModeForPlayer(),
                copyOf(inv.items),
                copyOf(inv.armor),
                inv.offhand.get(0).copy(),
                curiosData
        );
        SNAPSHOTS.put(player.getUUID(), snapshot);
        SnapshotStore.save(server, SNAPSHOTS);

        // Empty the inventory before teleporting so nothing rides along.
        inv.clearContent();

        // Restore whatever creative loadout they had from their last
        // visit, if any -- QoL so builders don't have to re-gather their
        // palette/tools every time. Applied to the now-empty inventory.
        CreativeLoadout loadout = LOADOUTS.get(player.getUUID());
        if (loadout != null) {
            applyLoadout(inv, loadout);
        }

        if (ensurePortalExists) {
            // Only the fixed admin anchor needs this -- portal-triggered
            // entry already has its analogous portal built at light-time.
            AmethystFrameHelper.ensureReturnPortal(target);
        }

        player.teleportTo(target, targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5,
                player.getYRot(), player.getXRot());

        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.onUpdateAbilities();

        player.sendSystemMessage(Component.literal(loadout != null
                ? "Entered the creative test dimension. Your previous creative loadout has been restored."
                : "Entered the creative test dimension. Your inventory is stashed until you leave."));
        startTeleportGracePeriod(player);
        return Result.OK;
    }

    public static Result leave(ServerPlayer player) {
        return leave(player, null, null);
    }

    /**
     * Portal-triggered leave: sends the player to THIS SPECIFIC portal's
     * real overworld position, rather than wherever they originally
     * entered from. Inventory and gamemode still come from the snapshot
     * (those aren't portal-specific) -- only the destination position and
     * dimension are overridden.
     */
    public static Result leaveAt(ServerPlayer player, ResourceKey<Level> targetDimension, BlockPos targetPos) {
        return leave(player, targetDimension, targetPos);
    }

    private static Result leave(ServerPlayer player, ResourceKey<Level> overrideDimension, BlockPos overridePos) {
        PlayerSnapshot snapshot = SNAPSHOTS.remove(player.getUUID());
        if (snapshot == null) {
            return Result.NOT_INSIDE;
        }

        MinecraftServer server = player.getServer();
        SnapshotStore.save(server, SNAPSHOTS);

        // Remember this creative inventory setup for next time, BEFORE
        // the anti-cheat wipe below discards it -- this is the actual QoL
        // feature: builders get their palette/tools back automatically on
        // their next /creativedim enter instead of starting from scratch.
        Inventory currentInv = player.getInventory();
        CreativeLoadout loadout = new CreativeLoadout(
                copyOf(currentInv.items),
                copyOf(currentInv.armor),
                currentInv.offhand.get(0).copy()
        );
        LOADOUTS.put(player.getUUID(), loadout);
        if (server != null) {
            CreativeLoadoutStore.save(server, LOADOUTS);
        }

        // Discard anything picked up/spawned while in creative -- this is
        // the anti-cheat step: nothing crosses back except what was there
        // before entering.
        player.getInventory().clearContent();

        ResourceKey<Level> destDimension = overrideDimension != null ? overrideDimension : snapshot.dimension();
        ServerLevel target = server != null ? server.getLevel(destDimension) : null;
        if (target == null && server != null) {
            // Saved/target dimension is gone for some reason -- fall back
            // to overworld rather than strand the player.
            target = server.overworld();
        }
        if (target == null) {
            return Result.DIMENSION_UNAVAILABLE;
        }

        if (overridePos != null) {
            player.teleportTo(target, overridePos.getX() + 0.5, overridePos.getY(), overridePos.getZ() + 0.5,
                    player.getYRot(), player.getXRot());
        } else {
            player.teleportTo(target, snapshot.position().x, snapshot.position().y, snapshot.position().z,
                    snapshot.yRot(), snapshot.xRot());
        }

        player.setGameMode(snapshot.gameMode());
        boolean canFly = snapshot.gameMode() == GameType.CREATIVE || snapshot.gameMode() == GameType.SPECTATOR;
        player.getAbilities().mayfly = canFly;
        player.getAbilities().flying = false;
        player.onUpdateAbilities();

        restoreInventory(player.getInventory(), snapshot);
        CuriosIntegration.restore(player, snapshot.curios());

        player.sendSystemMessage(Component.literal(
                "Left the creative test dimension. Your inventory has been restored."));
        startTeleportGracePeriod(player);
        return Result.OK;
    }

    public static boolean isInside(UUID playerId) {
        return SNAPSHOTS.containsKey(playerId);
    }

    private static ItemStack[] copyOf(NonNullList<ItemStack> list) {
        ItemStack[] out = new ItemStack[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i).copy();
        }
        return out;
    }

    private static void applyLoadout(Inventory inv, CreativeLoadout loadout) {
        for (int i = 0; i < loadout.mainInventory().length && i < inv.items.size(); i++) {
            inv.items.set(i, loadout.mainInventory()[i]);
        }
        for (int i = 0; i < loadout.armor().length && i < inv.armor.size(); i++) {
            inv.armor.set(i, loadout.armor()[i]);
        }
        inv.offhand.set(0, loadout.offhand());
    }

    private static void restoreInventory(Inventory inv, PlayerSnapshot snapshot) {
        for (int i = 0; i < snapshot.mainInventory().length && i < inv.items.size(); i++) {
            inv.items.set(i, snapshot.mainInventory()[i]);
        }
        for (int i = 0; i < snapshot.armor().length && i < inv.armor.size(); i++) {
            inv.armor.set(i, snapshot.armor()[i]);
        }
        inv.offhand.set(0, snapshot.offhand());
    }
}
