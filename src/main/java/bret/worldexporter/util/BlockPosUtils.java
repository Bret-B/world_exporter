package bret.worldexporter.util;

import net.minecraft.util.math.BlockPos;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Arrays;
import java.util.Collection;

public class BlockPosUtils {
    // Returns all BlockPos included in the (inclusive) cuboid defined via corners start and end
    public static Collection<BlockPos> cuboidFaces(BlockPos start, BlockPos end) {
        BlockPos high = new BlockPos(Math.max(start.getX(), end.getX()), Math.max(start.getY(), end.getY()), Math.max(start.getZ(), end.getZ()));
        BlockPos low = new BlockPos(Math.min(start.getX(), end.getX()), Math.min(start.getY(), end.getY()), Math.min(start.getZ(), end.getZ()));

        // number of cubes included in the top and bottom of the cuboid
        int topAndBottomArea = (high.getY() == low.getY() ? 1 : 2) * ((high.getX() - low.getX() + 1) * (high.getZ() - low.getZ() + 1));
        // height of the sides which exclude the top and bottom faces (+1 for exclusive, -2 for top/bottom = -1), may be 0
        int sidesHeight = Math.max(0, high.getY() - low.getY() - 1);
        // area of the 2 sides along the +-X axis
        // there is 1 distinct face if the z axis only spans 1 block in width (equal value), 2 otherwise
        int firstTwoSidesArea = (high.getZ() == low.getZ() ? 1 : 2) * sidesHeight * (high.getX() - low.getX() + 1);
        int finalSidesWidth = Math.max(0, high.getZ() - low.getZ() - 1);  // may be 0
        int finalSidesArea = (high.getX() == low.getX() ? 1 : 2) * sidesHeight * finalSidesWidth;
        int count = topAndBottomArea + firstTwoSidesArea + finalSidesArea;
        BlockPos[] faceBlocks = new BlockPos[count];
        int index = 0;

        // top face of the cuboid (fixed y)
        int y;
        int x;
        int z;
        y = high.getY();
        for (x = low.getX(); x <= high.getX(); ++x) {
            for (z = low.getZ(); z <= high.getZ(); ++z) {
                faceBlocks[index++] = new BlockPos(x, y, z);
            }
        }

        // bottom face of the cuboid (fixed y) (if not the same as the top)
        y = low.getY();
        if (y != high.getY()) {
            for (x = low.getX(); x <= high.getX(); ++x) {
                for (z = low.getZ(); z <= high.getZ(); ++z) {
                    faceBlocks[index++] = new BlockPos(x, y, z);
                }
            }
        }

        // first side face (fixed x), excluding top/bottom y levels
        x = high.getX();
        for (y = low.getY() + 1; y < high.getY(); ++y) {
            for (z = low.getZ(); z <= high.getZ(); ++z) {
                faceBlocks[index++] = new BlockPos(x, y, z);
            }
        }

        // second side face (fixed x) (if not the same as the first), excluding top/bottom y levels
        x = low.getX();
        if (x != high.getX()) {
            for (y = low.getY() + 1; y < high.getY(); ++y) {
                for (z = low.getZ(); z <= high.getZ(); ++z) {
                    faceBlocks[index++] = new BlockPos(x, y, z);
                }
            }
        }

        // third side face (fixed z), excluding top/bottom y levels and first 2 side x values
        z = high.getZ();
        for (y = low.getY() + 1; y < high.getY(); ++y) {
            for (x = low.getX() + 1; x < high.getX(); ++x) {
                faceBlocks[index++] = new BlockPos(x, y, z);
            }
        }

        // fourth side face (fixed z), excluding top/bottom y levels and first 2 side x values
        z = low.getZ();
        if (z != high.getZ()) {
            for (y = low.getY() + 1; y < high.getY(); ++y) {
                for (x = low.getX() + 1; x < high.getX(); ++x) {
                    faceBlocks[index++] = new BlockPos(x, y, z);
                }
            }
        }

        return Arrays.asList(faceBlocks);
    }

    // Returns all long-encoded BlockPos included in the (inclusive) cuboid defined via corners start and end
    public static Collection<Long> cuboidFacesLong(BlockPos start, BlockPos end) {
        Collection<BlockPos> blockPositions = cuboidFaces(start, end);
        Long[] positions = new Long[blockPositions.size()];
        int i = 0;
        for (BlockPos pos : blockPositions) {
            positions[i++] = pos.asLong();
        }
        return Arrays.asList(positions);
    }

    public static int lowestCoordinateInChunk(int coordinate) {
        return (coordinate >> 4) << 4;
    }

    public static int highestCoordinateInChunk(int coordinate) {
        return ((coordinate >> 4) << 4) + 15;
    }

    // Takes the min/max of provided BlockPos coordinates and returns a Pair(A, B) of BlockPos such that
    // A contains values <= B
    public static Pair<BlockPos, BlockPos> blockPosMinMax(BlockPos a, BlockPos b) {
        BlockPos lower = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos higher = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        return Pair.of(lower, higher);
    }

    // Grows the bbox of a and b in cardinal directions such that the new bounding box returned is increased outwards by
    // the provided number of chunks on each side where the coordinates extend as far as possible without leaving those chunks
    // For example, if called with chunkDistance=2 and both a and b are in the same chunk, a BBOX returning a 5x5 chunk area
    // with the chunk at a and b in the center will be returned.
    // Returns Pair(low, high) where all values in low are <= high
    public static Pair<BlockPos, BlockPos> extendChunks(BlockPos a, BlockPos b, int chunkDistance) {
        chunkDistance = Math.abs(chunkDistance);
        Pair<BlockPos, BlockPos> lowHigh = BlockPosUtils.blockPosMinMax(a, b);
        BlockPos low = lowHigh.getLeft();
        BlockPos high = lowHigh.getRight();
        int lowX = lowestCoordinateInChunk(low.getX() - (16 * chunkDistance));
        int lowZ = lowestCoordinateInChunk(low.getZ() - (16 * chunkDistance));
        int highX = highestCoordinateInChunk(high.getX() + (16 * chunkDistance));
        int highZ = highestCoordinateInChunk(high.getZ() + (16 * chunkDistance));
        return Pair.of(
                new BlockPos(lowX, low.getY(), lowZ),
                new BlockPos(highX, high.getY(), highZ)
        );
    }

    public static long blockVolume(BlockPos a, BlockPos b) {
        long xWidth = Math.abs(a.getX() - b.getX()) + 1;
        long zWidth = Math.abs(a.getZ() - b.getZ()) + 1;
        long height = Math.abs(a.getY() - b.getY()) + 1;
        return xWidth * zWidth * height;
    }
}
