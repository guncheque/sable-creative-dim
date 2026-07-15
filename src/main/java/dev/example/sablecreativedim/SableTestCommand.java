package dev.example.sablecreativedim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.util.Map;

/**
 * /creativedim enter          -- OPERATOR ONLY (level 2). Admin testing
 *                               shortcut: snapshots position/gamemode/
 *                               inventory, empties inventory, teleports to
 *                               the fixed admin anchor inside the creative
 *                               dimension, switches to creative. Regular
 *                               players enter by building and lighting an
 *                               amethyst portal in the overworld instead.
 * /creativedim leave          -- discards whatever they're currently holding
 *                               (so nothing built/spawned in creative can be
 *                               smuggled back), teleports them to where they
 *                               were, and restores their original gamemode +
 *                               inventory. Open to any player.
 * /creativedim restore <player> -- admin safety net: force-runs the same
 *                               restore logic as "leave" on someone else's
 *                               behalf (or your own), for cases where a
 *                               player got stuck, disconnected mid-visit, or
 *                               something otherwise went wrong. Requires
 *                               operator permission (level 2). Only works on
 *                               an online player -- their live inventory
 *                               only exists in memory while connected, so an
 *                               offline player needs to reconnect first.
 * /creativedim debug          -- OPERATOR ONLY. Dumps the raw
 *                               PortalLinkRegistry state to chat: every
 *                               registered overworld origin and its real
 *                               position, and every creative-dim cell's
 *                               reverse mapping back to its overworld
 *                               origin. Ground truth for diagnosing
 *                               portal-routing bugs.
 * /creativedim resetlinks      -- OPERATOR ONLY. Wipes all portal link
 *                               data in memory and on disk. Recovery
 *                               option for stale/corrupt registry data --
 *                               existing portals need breaking and
 *                               relighting afterward to re-register.
 *
 * The actual enter/leave mechanics live in CreativeDimTeleporter, shared
 * with the amethyst portal block -- this class is just the command-line
 * entry point into that same logic.
 *
 * enter and restore require operator permission (level 2). leave requires
 * no permission node (level 0), so any player can always get their own
 * stuff back regardless of rank.
 */
public class SableTestCommand {

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        CreativeDimTeleporter.SNAPSHOTS.clear();
        CreativeDimTeleporter.SNAPSHOTS.putAll(SnapshotStore.load(event.getServer()));
        CreativeDimTeleporter.LOADOUTS.clear();
        CreativeDimTeleporter.LOADOUTS.putAll(CreativeLoadoutStore.load(event.getServer()));
        PortalLinkRegistry.load(event.getServer());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("creativedim")
                        .then(Commands.literal("enter")
                                .requires(src -> src.hasPermission(2))
                                .executes(this::enter))
                        .then(Commands.literal("leave").executes(this::leave))
                        .then(Commands.literal("restore")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(this::restore)))
                        .then(Commands.literal("debug")
                                .requires(src -> src.hasPermission(2))
                                .executes(this::debug))
                        .then(Commands.literal("resetlinks")
                                .requires(src -> src.hasPermission(2))
                                .executes(this::resetLinks))
        );
    }

    private int enter(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CreativeDimTeleporter.Result result = CreativeDimTeleporter.enter(player);
        return reportResult(ctx, result, null);
    }

    private int leave(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CreativeDimTeleporter.Result result = CreativeDimTeleporter.leave(player);
        return reportResult(ctx, result, null);
    }

    private int restore(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        CreativeDimTeleporter.Result result = CreativeDimTeleporter.leave(target);
        if (result == CreativeDimTeleporter.Result.OK) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Restored " + target.getName().getString() + "'s inventory."), true);
            return 1;
        }
        return reportResult(ctx, result, target);
    }

    /**
     * Dumps the raw PortalLinkRegistry contents to chat -- ground truth
     * for diagnosing the "multiple portals land in the same place" bug,
     * without needing to relight anything or interpret log timing. Shows
     * every registered overworld origin, how many of its own interior
     * cells are mapped to it, and every creative-dim cell -> overworld
     * origin reverse mapping used for the "leave" direction.
     */
    private int debug(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("=== Portal Link Registry ==="), false);

        if (PortalLinkRegistry.ORIGIN_DATA.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No registered overworld portals."), false);
        }
        for (Map.Entry<Long, PortalLinkRegistry.PortalOrigin> entry : PortalLinkRegistry.ORIGIN_DATA.entrySet()) {
            long key = entry.getKey();
            PortalLinkRegistry.PortalOrigin origin = entry.getValue();
            int x = PortalLinkRegistry.keyX(key);
            int z = PortalLinkRegistry.keyZ(key);
            long cellCount = PortalLinkRegistry.CELL_TO_ORIGIN.values().stream().filter(v -> v == key).count();
            source.sendSuccess(() -> Component.literal(
                    "Origin key(" + x + "," + z + ") -> dimension=" + origin.dimension().location()
                            + " realPos=" + origin.pos().toShortString()
                            + " registeredOverworldCells=" + cellCount), false);
        }

        int creativeCellCount = PortalLinkRegistry.CREATIVE_CELL_TO_ORIGIN.size();
        source.sendSuccess(() -> Component.literal("Creative-side reverse mappings (" + creativeCellCount + "):"), false);
        for (Map.Entry<Long, Long> entry : PortalLinkRegistry.CREATIVE_CELL_TO_ORIGIN.entrySet()) {
            int cx = PortalLinkRegistry.keyX(entry.getKey());
            int cz = PortalLinkRegistry.keyZ(entry.getKey());
            int ox = PortalLinkRegistry.keyX(entry.getValue());
            int oz = PortalLinkRegistry.keyZ(entry.getValue());
            source.sendSuccess(() -> Component.literal(
                    "  creative(" + cx + "," + cz + ") -> overworldOrigin(" + ox + "," + oz + ")"), false);
        }
        return 1;
    }

    private int resetLinks(CommandContext<CommandSourceStack> ctx) {
        PortalLinkRegistry.resetAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Portal link registry wiped. Existing portals need to be broken and relit to re-register."), true);
        return 1;
    }

    private int reportResult(CommandContext<CommandSourceStack> ctx, CreativeDimTeleporter.Result result, ServerPlayer restoreTarget) {
        switch (result) {
            case OK:
                // CreativeDimTeleporter already messaged the player directly.
                return 1;
            case ALREADY_INSIDE:
                ctx.getSource().sendFailure(Component.literal(
                        "You're already in the creative test dimension. Use /creativedim leave first."));
                return 0;
            case NOT_INSIDE:
                if (restoreTarget != null) {
                    ctx.getSource().sendFailure(Component.literal(
                            restoreTarget.getName().getString() + " has no stashed inventory to restore."));
                } else {
                    ctx.getSource().sendFailure(Component.literal(
                            "You're not currently in the creative test dimension."));
                }
                return 0;
            case DIMENSION_UNAVAILABLE:
                ctx.getSource().sendFailure(Component.literal(
                        "Creative test dimension isn't loaded on this server."));
                return 0;
            default:
                return 0;
        }
    }
}
