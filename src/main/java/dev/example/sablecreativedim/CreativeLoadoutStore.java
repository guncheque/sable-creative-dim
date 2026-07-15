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
 * Persists each player's remembered creative-mode inventory setup to a
 * file inside the world save folder
 * (world/sablecreativedim/creativedim_loadouts.dat), so it survives a
 * server restart the same way snapshots and portal links already do.
 *
 * Mirrors SnapshotStore's write-through pattern exactly.
 */
public final class CreativeLoadoutStore {

    private static final String FILE_NAME = "creativedim_loadouts.dat";

    private CreativeLoadoutStore() {}

    public static void save(MinecraftServer server, Map<UUID, CreativeLoadout> loadouts) {
        CompoundTag root = new CompoundTag();
        for (Map.Entry<UUID, CreativeLoadout> entry : loadouts.entrySet()) {
            root.put(entry.getKey().toString(), entry.getValue().toNbt(server.registryAccess()));
        }
        Path path = filePath(server);
        try {
            Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(root, path);
        } catch (IOException e) {
            System.err.println("[sablecreativedim] Failed to save creative loadouts: " + e);
        }
    }

    public static Map<UUID, CreativeLoadout> load(MinecraftServer server) {
        Map<UUID, CreativeLoadout> result = new HashMap<>();
        Path path = filePath(server);
        if (!Files.exists(path)) {
            return result;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            for (String key : root.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(key);
                    CreativeLoadout loadout = CreativeLoadout.fromNbt(root.getCompound(key), server.registryAccess());
                    result.put(uuid, loadout);
                } catch (IllegalArgumentException ignored) {
                    // Skip a malformed key rather than fail the whole load.
                }
            }
        } catch (IOException e) {
            System.err.println("[sablecreativedim] Failed to load creative loadouts: " + e);
        }
        return result;
    }

    private static Path filePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("sablecreativedim").resolve(FILE_NAME);
    }
}
