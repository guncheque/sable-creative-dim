package dev.example.sablecreativedim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks which overworld positions have a real, player-built amethyst
 * portal -- and therefore a matching analogous portal built at the same
 * (x, z) inside the creative dimension.
 *
 * Only lighting a portal in the OVERWORLD (or any non-creative dimension)
 * registers anything and auto-builds the creative-dim counterpart.
 * Lighting a frame directly inside the creative dimension only succeeds
 * if its origin is already a registered, valid analogous position --
 * otherwise it just doesn't light.
 *
 * Two separate reverse-lookup maps, for two different directions of travel:
 * - CELL_TO_ORIGIN: an OVERWORLD cell -> that portal's overworld origin.
 *   Used when entering (touching an overworld portal), so we know which
 *   analogous creative-dim spot to send the player to.
 * - CREATIVE_CELL_TO_ORIGIN: a CREATIVE-DIM cell -> the overworld origin
 *   key it was built from. Used when leaving (touching a creative-dim
 *   portal), so the player returns to THAT portal's real overworld
 *   position specifically, not wherever they originally entered from.
 *   This can't just be "divide the touched cell's coordinates by the
 *   scale factor" -- our auto-built analogous frames are always a fixed
 *   2-wide shape regardless of scale, so only some of their cells land on
 *   an exact multiple of CREATIVE_DIM_SCALE. Recording the mapping
 *   directly at build time sidesteps that arithmetic entirely.
 */
public final class PortalLinkRegistry {

    private static final String FILE_NAME = "creativedim_portal_links.dat";

    /**
     * Inverse of the Nether's 1:8 rule -- 1 block in the overworld = this
     * many blocks in the creative dimension, so portals built close
     * together in the overworld still end up with real distance between
     * their private creative-dim areas, without needing to travel far in
     * the overworld to get that spread.
     */
    public static final int CREATIVE_DIM_SCALE = 8;

    /** Overworld coordinate -> creative dimension coordinate. */
    public static int toCreativeCoord(int overworldCoord) {
        return overworldCoord * CREATIVE_DIM_SCALE;
    }

    public record PortalOrigin(ResourceKey<Level> dimension, BlockPos pos) {}

    static final Map<Long, PortalOrigin> ORIGIN_DATA = new HashMap<>();
    static final Map<Long, Long> CELL_TO_ORIGIN = new HashMap<>();
    static final Map<Long, Long> CREATIVE_CELL_TO_ORIGIN = new HashMap<>();

    private PortalLinkRegistry() {}

    public static long key(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    public static boolean isValidOrigin(int x, int z) {
        return ORIGIN_DATA.containsKey(key(x, z));
    }

    /** Returns the packed origin key for the OVERWORLD portal this overworld cell belongs to, or null if not part of any registered portal. */
    public static Long originForCell(int x, int z) {
        return CELL_TO_ORIGIN.get(key(x, z));
    }

    /** Returns the packed origin key for the overworld portal that this CREATIVE-DIM cell was built as the analogous copy of, or null. */
    public static Long originForCreativeCell(int x, int z) {
        return CREATIVE_CELL_TO_ORIGIN.get(key(x, z));
    }

    public static PortalOrigin originData(long originKey) {
        return ORIGIN_DATA.get(originKey);
    }

    /**
     * Wipes all portal link data, in memory and on disk. Used by
     * /creativedim resetlinks -- a clean-slate recovery option for when
     * registry data looks wrong/stale (e.g. portals registered under an
     * earlier version of this system before the creative-side reverse
     * mapping existed). Does NOT touch any actual portal blocks in the
     * world -- existing lit portals will need to be broken and relit to
     * re-register correctly after this.
     */
    public static void resetAll(MinecraftServer server) {
        ORIGIN_DATA.clear();
        CELL_TO_ORIGIN.clear();
        CREATIVE_CELL_TO_ORIGIN.clear();
        save(server);
    }

    /**
     * Registers a newly-lit overworld portal: every interior cell maps
     * back to the origin, and the origin's real dimension + full position
     * (including y) is recorded so a later "leave" can send the player
     * back to the exact right spot.
     */
    public static void registerPortal(MinecraftServer server, ServerLevel sourceLevel, AmethystFrameHelper.ActivationResult activation) {
        long originKey = key(activation.interiorOrigin().getX(), activation.interiorOrigin().getZ());
        ORIGIN_DATA.put(originKey, new PortalOrigin(sourceLevel.dimension(), activation.interiorOrigin()));
        for (var cell : activation.interiorCellsXZ()) {
            CELL_TO_ORIGIN.put(key(cell[0], cell[1]), originKey);
        }
        save(server);
    }

    /**
     * Records that the fixed 2-wide analogous frame built at
     * (creativeOriginX, creativeOriginZ) inside the creative dimension is
     * the counterpart of the given overworld origin -- called right after
     * AmethystFrameHelper.buildAnalogousPortal succeeds for a real
     * overworld portal (never for the fixed admin/testing anchor, which
     * has no overworld counterpart to return to).
     */
    public static void registerCreativeSideCells(MinecraftServer server, int creativeOriginX, int creativeOriginZ, long overworldOriginKey) {
        // Matches the fixed 2-wide x 3-tall shape buildAnalogousPortal
        // always uses, axis=X: 2 distinct (x, z) pairs (z fixed, x varies).
        CREATIVE_CELL_TO_ORIGIN.put(key(creativeOriginX, creativeOriginZ), overworldOriginKey);
        CREATIVE_CELL_TO_ORIGIN.put(key(creativeOriginX + 1, creativeOriginZ), overworldOriginKey);
        save(server);
    }

    public static void save(MinecraftServer server) {
        ListTag origins = new ListTag();
        for (Map.Entry<Long, PortalOrigin> e : ORIGIN_DATA.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Key", e.getKey());
            entry.putString("Dimension", e.getValue().dimension().location().toString());
            entry.putInt("X", e.getValue().pos().getX());
            entry.putInt("Y", e.getValue().pos().getY());
            entry.putInt("Z", e.getValue().pos().getZ());
            origins.add(entry);
        }
        ListTag cells = new ListTag();
        for (Map.Entry<Long, Long> e : CELL_TO_ORIGIN.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Cell", e.getKey());
            entry.putLong("Origin", e.getValue());
            cells.add(entry);
        }
        ListTag creativeCells = new ListTag();
        for (Map.Entry<Long, Long> e : CREATIVE_CELL_TO_ORIGIN.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Cell", e.getKey());
            entry.putLong("Origin", e.getValue());
            creativeCells.add(entry);
        }
        CompoundTag root = new CompoundTag();
        root.put("Origins", origins);
        root.put("Cells", cells);
        root.put("CreativeCells", creativeCells);

        Path path = filePath(server);
        try {
            Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(root, path);
        } catch (IOException e) {
            System.err.println("[sablecreativedim] Failed to save portal links: " + e);
        }
    }

    public static void load(MinecraftServer server) {
        ORIGIN_DATA.clear();
        CELL_TO_ORIGIN.clear();
        CREATIVE_CELL_TO_ORIGIN.clear();
        Path path = filePath(server);
        if (!Files.exists(path)) {
            return;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            ListTag origins = root.getList("Origins", Tag.TAG_COMPOUND);
            for (int i = 0; i < origins.size(); i++) {
                CompoundTag entry = origins.getCompound(i);
                ResourceKey<Level> dimension = ResourceKey.create(
                        Registries.DIMENSION, ResourceLocation.parse(entry.getString("Dimension")));
                BlockPos pos = new BlockPos(entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z"));
                ORIGIN_DATA.put(entry.getLong("Key"), new PortalOrigin(dimension, pos));
            }
            ListTag cells = root.getList("Cells", Tag.TAG_COMPOUND);
            for (int i = 0; i < cells.size(); i++) {
                CompoundTag entry = cells.getCompound(i);
                CELL_TO_ORIGIN.put(entry.getLong("Cell"), entry.getLong("Origin"));
            }
            ListTag creativeCells = root.getList("CreativeCells", Tag.TAG_COMPOUND);
            for (int i = 0; i < creativeCells.size(); i++) {
                CompoundTag entry = creativeCells.getCompound(i);
                CREATIVE_CELL_TO_ORIGIN.put(entry.getLong("Cell"), entry.getLong("Origin"));
            }
        } catch (IOException e) {
            System.err.println("[sablecreativedim] Failed to load portal links: " + e);
        }
    }

    private static Path filePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("sablecreativedim").resolve(FILE_NAME);
    }
}
