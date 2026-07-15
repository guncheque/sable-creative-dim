package dev.example.sablecreativedim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persists the enter/leave snapshot map to a file inside the world save
 * folder (world/sablecreativedim/creativedim_snapshots.dat), so a server
 * crash or restart while someone is mid-visit doesn't lose their stashed
 * survival inventory.
 *
 * SableTestCommand calls save() after every enter/leave (write-through),
 * not just on shutdown -- that way even an ungraceful crash only loses
 * whatever happened in the last few milliseconds, not the whole session.
 */
public final class SnapshotStore {

    private static final String FILE_NAME = "creativedim_snapshots.dat";

    private SnapshotStore() {}

    public static void save(MinecraftServer server, Map<UUID, PlayerSnapshot> snapshots) {
        CompoundTag root = new CompoundTag();
        for (Map.Entry<UUID, PlayerSnapshot> entry : snapshots.entrySet()) {
            root.put(entry.getKey().toString(), entry.getValue().toNbt(server.registryAccess()));
        }
        Path path = filePath(server);
        try {
            Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(root, path);
        } catch (IOException e) {
            // Don't crash the server over a failed save -- log and move on.
            // A failed save here means the *next* successful save will still
            // catch up, since we always write the full current map.
            System.err.println("[sablecreativedim] Failed to save snapshots: " + e);
        }
    }

    public static Map<UUID, PlayerSnapshot> load(MinecraftServer server) {
        Map<UUID, PlayerSnapshot> result = new HashMap<>();
        Path path = filePath(server);
        if (!Files.exists(path)) {
            return result;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            for (String key : root.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(key);
                    PlayerSnapshot snapshot = PlayerSnapshot.fromNbt(root.getCompound(key), server.registryAccess());
                    result.put(uuid, snapshot);
                } catch (IllegalArgumentException ignored) {
                    // Skip a malformed key rather than fail the whole load.
                }
            }
        } catch (IOException e) {
            System.err.println("[sablecreativedim] Failed to load snapshots: " + e);
        }
        return result;
    }

    private static Path filePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("sablecreativedim").resolve(FILE_NAME);
    }
}
