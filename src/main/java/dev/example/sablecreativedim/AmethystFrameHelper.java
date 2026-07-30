package dev.example.sablecreativedim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects a rectangular ring of Amethyst Block (like a Nether portal frame,
 * but amethyst instead of obsidian) and fills the hollow interior with
 * CreativeDimPortalBlock when lit.
 *
 * DETECTION APPROACH: rather than replicate vanilla's exact frame-walking
 * algorithm (PortalShape), this flood-fills connected Amethyst Blocks in a
 * single plane from the clicked block, then checks whether that connected
 * set forms a clean hollow rectangle (frame on the border, air inside,
 * within vanilla-portal-like size limits). Simpler to reason about than a
 * faithful port, and produces the same end result for a normal rectangular
 * frame -- but it means oddly-shaped amethyst clusters near a real frame
 * could confuse the flood fill. Good enough for a hand-built frame; revisit
 * if players report false negatives on unusual shapes.
 *
 * CORNERS ARE OPTIONAL, matching vanilla Nether portal behavior -- the 4
 * exact corner cells of the frame are never checked at all (can be air,
 * can be anything), only the straight edges actually need to be Amethyst
 * Block. This required two coordinated changes to work at all: the flood
 * fill traverses 8 directions (orthogonal AND diagonal) rather than just
 * 4, since two edges meeting at a missing corner only touch diagonally;
 * and the verification loop (both in findFrame and in the breakage-check
 * checkAndClearIfBroken) explicitly skips the 4 corner positions rather
 * than requiring them. These two pieces must stay in sync -- one enables
 * finding a corner-less shape as a single connected region, the other
 * enables accepting it once found.
 */
public final class AmethystFrameHelper {

    private static final int MIN_INTERIOR_WIDTH = 2;
    private static final int MAX_INTERIOR_WIDTH = 21;
    private static final int MIN_INTERIOR_HEIGHT = 3;
    private static final int MAX_INTERIOR_HEIGHT = 21;

    private AmethystFrameHelper() {}

    /** Details of a successfully-lit frame, enough for PortalLinkRegistry to register every interior cell. */
    public record ActivationResult(BlockPos interiorOrigin, int width, int height, Direction.Axis axis) {
        /** Every interior cell's (x, z) world coordinates, matching how fill() iterates. */
        public List<int[]> interiorCellsXZ() {
            List<int[]> cells = new ArrayList<>();
            for (int a = 0; a < width; a++) {
                for (int b = 0; b < height; b++) {
                    BlockPos pos = axis == Direction.Axis.X
                            ? interiorOrigin.offset(a, b, 0)
                            : interiorOrigin.offset(0, b, a);
                    cells.add(new int[]{pos.getX(), pos.getZ()});
                }
            }
            return cells;
        }
    }

    /**
     * Called when a player right-clicks an Amethyst Block with Flint and
     * Steel. Tries both possible orientations. Returns details of the lit
     * frame, or null if no valid frame was found at this position.
     */
    public static ActivationResult tryActivate(ServerLevel level, BlockPos clickedPos) {
        for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            Rect rect = findFrame(level, clickedPos, axis);
            if (rect != null) {
                fill(level, rect, axis);
                return new ActivationResult(rect.interiorMin(), rect.width(), rect.height(), axis);
            }
        }
        return null;
    }

    /** Builds and lights a standard small frame at the given analogous (x, z) inside the creative dimension, if one isn't already there. Used both for the fixed admin/testing anchor and for per-overworld-portal analogous portals. */
    public static void buildAnalogousPortal(ServerLevel creativeLevel, int x, int z) {
        BlockPos interiorOrigin = new BlockPos(x, 1, z);
        int width = 2;
        int height = 3;

        if (creativeLevel.getBlockState(interiorOrigin).is(ModBlocks.CREATIVE_DIM_PORTAL.get())) {
            return; // already built and lit
        }

        for (int a = -1; a <= width; a++) {
            for (int b = -1; b <= height; b++) {
                boolean isBorder = (a == -1 || a == width || b == -1 || b == height);
                boolean isInterior = !isBorder && a >= 0 && a < width && b >= 0 && b < height;
                BlockPos pos = interiorOrigin.offset(a, b, 0);
                if (isBorder) {
                    creativeLevel.setBlock(pos, Blocks.AMETHYST_BLOCK.defaultBlockState(), 2);
                } else if (isInterior) {
                    creativeLevel.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }

        fill(creativeLevel, new Rect(interiorOrigin, width, height), Direction.Axis.X);
    }

    /** The one fixed anchor used by the op-only /creativedim enter command -- not tied to any overworld portal. */
    public static void ensureReturnPortal(ServerLevel creativeLevel) {
        buildAnalogousPortal(creativeLevel, 4, 0);
    }

    /**
     * Called whenever a block adjacent to a portal block changes (see
     * CreativeDimPortalBlock#neighborChanged). Flood-fills the connected
     * portal blocks this cell belongs to, checks whether the ring of
     * Amethyst Block surrounding them is still fully intact, and clears
     * the whole interior back to air if it isn't -- mirrors vanilla's
     * behavior of a Nether portal collapsing when its obsidian frame gets
     * broken.
     */
    public static void checkAndClearIfBroken(ServerLevel level, BlockPos triggerPos) {
        BlockState triggerState = level.getBlockState(triggerPos);
        if (!triggerState.is(ModBlocks.CREATIVE_DIM_PORTAL.get())) {
            return;
        }
        Direction.Axis axis = triggerState.getValue(CreativeDimPortalBlock.AXIS);

        Set<BlockPos> portalCells = floodFillPortal(level, triggerPos, axis);
        if (portalCells.isEmpty()) {
            return;
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int fixedCoord = axis == Direction.Axis.X ? triggerPos.getZ() : triggerPos.getX();

        for (BlockPos pos : portalCells) {
            int a = axis == Direction.Axis.X ? pos.getX() : pos.getZ();
            int b = pos.getY();
            minX = Math.min(minX, a);
            maxX = Math.max(maxX, a);
            minY = Math.min(minY, b);
            maxY = Math.max(maxY, b);
        }

        // The border ring is exactly one block outside the portal-cell
        // bounding box on every side -- check the straight edges are
        // still Amethyst. Corners are deliberately skipped here too,
        // matching findFrame's corner-optional behavior -- a frame that
        // was validly lit without corners must not be treated as
        // "broken" just because its corners were never amethyst in the
        // first place.
        boolean intact = true;
        for (int a = minX - 1; a <= maxX + 1 && intact; a++) {
            for (int b = minY - 1; b <= maxY + 1 && intact; b++) {
                boolean isEdge = (a == minX - 1 || a == maxX + 1 || b == minY - 1 || b == maxY + 1);
                boolean isCorner = (a == minX - 1 || a == maxX + 1) && (b == minY - 1 || b == maxY + 1);
                if (!isEdge || isCorner) {
                    continue;
                }
                BlockPos pos = axis == Direction.Axis.X
                        ? new BlockPos(a, b, fixedCoord)
                        : new BlockPos(fixedCoord, b, a);
                if (!level.getBlockState(pos).is(Blocks.AMETHYST_BLOCK)) {
                    intact = false;
                }
            }
        }

        if (!intact) {
            for (BlockPos pos : portalCells) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static Set<BlockPos> floodFillPortal(ServerLevel level, BlockPos seed, Direction.Axis axis) {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        Direction[] inPlane = axis == Direction.Axis.X
                ? new Direction[]{Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST}
                : new Direction[]{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH};

        int limit = (MAX_INTERIOR_WIDTH) * (MAX_INTERIOR_HEIGHT) * 4;

        while (!queue.isEmpty() && visited.size() < limit) {
            BlockPos current = queue.poll();
            if (visited.contains(current)) {
                continue;
            }
            BlockState state = level.getBlockState(current);
            if (!state.is(ModBlocks.CREATIVE_DIM_PORTAL.get()) || state.getValue(CreativeDimPortalBlock.AXIS) != axis) {
                continue;
            }
            visited.add(current);
            for (Direction dir : inPlane) {
                BlockPos next = current.relative(dir);
                if (!visited.contains(next)) {
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    private record Rect(BlockPos interiorMin, int width, int height) {}

    private static Rect findFrame(ServerLevel level, BlockPos start, Direction.Axis axis) {
        if (!level.getBlockState(start).is(Blocks.AMETHYST_BLOCK)
                && !isAmethystNeighbor(level, start, axis)) {
            return null;
        }

        BlockPos seed = level.getBlockState(start).is(Blocks.AMETHYST_BLOCK)
                ? start
                : findAdjacentAmethyst(level, start, axis);
        if (seed == null) {
            return null;
        }

        Set<BlockPos> connected = floodFillAmethyst(level, seed, axis);
        if (connected.isEmpty()) {
            return null;
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int fixedCoord = axis == Direction.Axis.X ? seed.getZ() : seed.getX();

        for (BlockPos pos : connected) {
            int a = axis == Direction.Axis.X ? pos.getX() : pos.getZ();
            int b = pos.getY();
            minX = Math.min(minX, a);
            maxX = Math.max(maxX, a);
            minY = Math.min(minY, b);
            maxY = Math.max(maxY, b);
        }

        int outerWidth = maxX - minX + 1;
        int outerHeight = maxY - minY + 1;
        int interiorWidth = outerWidth - 2;
        int interiorHeight = outerHeight - 2;

        if (interiorWidth < MIN_INTERIOR_WIDTH || interiorWidth > MAX_INTERIOR_WIDTH
                || interiorHeight < MIN_INTERIOR_HEIGHT || interiorHeight > MAX_INTERIOR_HEIGHT) {
            return null;
        }

        for (int a = minX; a <= maxX; a++) {
            for (int b = minY; b <= maxY; b++) {
                boolean isEdge = (a == minX || a == maxX || b == minY || b == maxY);
                // Matches vanilla Nether portal behavior: the 4 exact
                // corners aren't checked at all -- can be air, can be
                // anything. Only the straight edges (isEdge but NOT a
                // corner) actually need to be Amethyst Block. Without
                // this exception, floodFillAmethyst wouldn't even be
                // ABLE to find a corner-less frame as one connected shape
                // in the first place (see its own updated comment) --
                // this check and that traversal change have to agree
                // with each other for corner-optional frames to work.
                boolean isCorner = (a == minX || a == maxX) && (b == minY || b == maxY);
                if (isCorner) {
                    continue;
                }
                BlockPos pos = axis == Direction.Axis.X
                        ? new BlockPos(a, b, fixedCoord)
                        : new BlockPos(fixedCoord, b, a);
                BlockState state = level.getBlockState(pos);
                if (isEdge) {
                    if (!state.is(Blocks.AMETHYST_BLOCK)) {
                        return null;
                    }
                } else {
                    if (!state.isAir()) {
                        return null;
                    }
                }
            }
        }

        BlockPos interiorMin = axis == Direction.Axis.X
                ? new BlockPos(minX + 1, minY + 1, fixedCoord)
                : new BlockPos(fixedCoord, minY + 1, minX + 1);
        return new Rect(interiorMin, interiorWidth, interiorHeight);
    }

    private static boolean isAmethystNeighbor(ServerLevel level, BlockPos pos, Direction.Axis axis) {
        return findAdjacentAmethyst(level, pos, axis) != null;
    }

    private static BlockPos findAdjacentAmethyst(ServerLevel level, BlockPos pos, Direction.Axis axis) {
        Direction[] toCheck = axis == Direction.Axis.X
                ? new Direction[]{Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST}
                : new Direction[]{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH};
        for (Direction dir : toCheck) {
            BlockPos neighbor = pos.relative(dir);
            if (level.getBlockState(neighbor).is(Blocks.AMETHYST_BLOCK)) {
                return neighbor;
            }
        }
        return null;
    }

    /**
     * Flood-fills connected Amethyst Block cells in a single plane,
     * starting from seed. Uses 8-directional traversal (orthogonal AND
     * diagonal) rather than just the 4 orthogonal directions -- this is
     * what actually makes corner-optional frames possible at all: two
     * edges that meet at a missing corner only touch DIAGONALLY (the
     * corner cell between them is empty), so purely-orthogonal traversal
     * could never discover them as one connected shape in the first
     * place. This has to stay in sync with findFrame's corner exception
     * in the verification loop -- one enables finding the shape, the
     * other enables accepting it once found.
     */
    private static Set<BlockPos> floodFillAmethyst(ServerLevel level, BlockPos seed, Direction.Axis axis) {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);

        // All 8 offsets in-plane: 4 orthogonal + 4 diagonal. (da, db) are
        // expressed in the plane's own (a, b) coordinates -- a maps to
        // X for axis=X or Z for axis=Z, b always maps to Y.
        int[][] offsets = {
                {0, 1}, {0, -1}, {1, 0}, {-1, 0},   // orthogonal
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}  // diagonal
        };

        int limit = (MAX_INTERIOR_WIDTH + 2) * (MAX_INTERIOR_HEIGHT + 2) * 4;

        while (!queue.isEmpty() && visited.size() < limit) {
            BlockPos current = queue.poll();
            if (visited.contains(current)) {
                continue;
            }
            if (!level.getBlockState(current).is(Blocks.AMETHYST_BLOCK)) {
                continue;
            }
            visited.add(current);
            for (int[] offset : offsets) {
                BlockPos next = axis == Direction.Axis.X
                        ? current.offset(offset[0], offset[1], 0)
                        : current.offset(0, offset[1], offset[0]);
                if (!visited.contains(next)) {
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    private static void fill(ServerLevel level, Rect rect, Direction.Axis axis) {
        List<BlockPos> toFill = new ArrayList<>();
        for (int a = 0; a < rect.width(); a++) {
            for (int b = 0; b < rect.height(); b++) {
                BlockPos pos = axis == Direction.Axis.X
                        ? rect.interiorMin().offset(a, b, 0)
                        : rect.interiorMin().offset(0, b, a);
                toFill.add(pos);
            }
        }
        BlockState portalState = ModBlocks.CREATIVE_DIM_PORTAL.get().defaultBlockState()
                .setValue(CreativeDimPortalBlock.AXIS, axis);
        for (BlockPos pos : toFill) {
            // Flag 2 = update clients only, skip neighbor notification.
            // Using setBlockAndUpdate here would notify each
            // already-placed portal cell as later cells go in, and mid-
            // fill the interior is still partially air -- our new
            // breakage check would see an incomplete shape and clear
            // what was just placed, fighting the fill loop itself.
            level.setBlock(pos, portalState, 2);
        }
    }
}
