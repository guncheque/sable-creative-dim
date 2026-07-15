package dev.example.sablecreativedim;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks how many consecutive ticks each player has been standing inside
 * ANY of our portal blocks, mirroring vanilla's real Nether portal entry
 * delay: 80 ticks (4 seconds) in survival, 1 tick in creative, before the
 * portal actually teleports them. Stepping out resets the count, matching
 * vanilla's "step out to abort" behavior.
 *
 * Hardcoded to vanilla's default values rather than reading the
 * corresponding gamerules (playersNetherPortalDefaultDelay /
 * playersNetherPortalCreativeDelay) -- simpler and lower-risk than
 * guessing at those gamerule keys' exact registered names.
 */
public final class PortalChargeTracker {

    private static final int SURVIVAL_DELAY_TICKS = 80;
    private static final int CREATIVE_DELAY_TICKS = 1;

    private static final Map<UUID, Integer> CONSECUTIVE_TICKS = new HashMap<>();
    private static final Map<UUID, Integer> LAST_TICK_SEEN = new HashMap<>();

    private PortalChargeTracker() {}

    /** Call once per tick a player is standing inside a portal block. Returns true once they've charged long enough to actually teleport. */
    public static boolean tick(ServerPlayer player, int currentServerTick) {
        UUID id = player.getUUID();
        Integer lastSeen = LAST_TICK_SEEN.get(id);

        // A standing player's hitbox is ~1.8 blocks tall, taller than a
        // single portal cell -- entityInside fires once per overlapping
        // cell, so this gets called more than once in the SAME tick. Only
        // the first call of a given tick should advance the counter;
        // later calls this same tick just report the current state back.
        if (lastSeen != null && lastSeen == currentServerTick) {
            int consecutiveSoFar = CONSECUTIVE_TICKS.getOrDefault(id, 0);
            int thresholdSoFar = player.isCreative() ? CREATIVE_DELAY_TICKS : SURVIVAL_DELAY_TICKS;
            return consecutiveSoFar >= thresholdSoFar;
        }

        int consecutive = (lastSeen != null && lastSeen == currentServerTick - 1)
                ? CONSECUTIVE_TICKS.getOrDefault(id, 0) + 1
                : 1;
        CONSECUTIVE_TICKS.put(id, consecutive);
        LAST_TICK_SEEN.put(id, currentServerTick);

        int threshold = player.isCreative() ? CREATIVE_DELAY_TICKS : SURVIVAL_DELAY_TICKS;
        return consecutive >= threshold;
    }

    /** 0.0 to 1.0 charge progress, for the client-side shimmer overlay. */
    public static float progress(ServerPlayer player) {
        int consecutive = CONSECUTIVE_TICKS.getOrDefault(player.getUUID(), 0);
        int threshold = player.isCreative() ? CREATIVE_DELAY_TICKS : SURVIVAL_DELAY_TICKS;
        return Math.min(1.0f, consecutive / (float) threshold);
    }

    public static void reset(UUID playerId) {
        CONSECUTIVE_TICKS.remove(playerId);
        LAST_TICK_SEEN.remove(playerId);
    }
}
