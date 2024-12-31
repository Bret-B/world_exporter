package bret.worldexporter.util;

import com.google.common.collect.AbstractIterator;
import net.minecraft.util.math.BlockPos;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;

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

    public static long blockVolume(int pX1, int pY1, int pZ1, int pX2, int pY2, int pZ2) {
        long xWidth = Math.abs(pX1 - pX2) + 1;
        long zWidth = Math.abs(pZ1 - pZ2) + 1;
        long height = Math.abs(pY1 - pY2) + 1;
        return xWidth * zWidth * height;
    }

    public static long blockVolume(BlockPos a, BlockPos b) {
        return blockVolume(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    public static Iterable<BlockPos> betweenClosedXZY(BlockPos low, BlockPos high) {
        return betweenClosedXZY(low.getX(), low.getY(), low.getZ(), high.getX(), high.getY(), high.getZ());
    }

    public static Iterable<BlockPos> betweenClosedXZY(int pX1, int pY1, int pZ1, int pX2, int pY2, int pZ2) {
        int i = pX2 - pX1 + 1;
        int j = pY2 - pY1 + 1;
        int k = pZ2 - pZ1 + 1;
        int l = i * j * k;
        return () -> new AbstractIterator<BlockPos>() {
            private final BlockPos.Mutable cursor = new BlockPos.Mutable();
            private int index;

            protected BlockPos computeNext() {
                if (this.index == l) {
                    return this.endOfData();
                } else {
                    int i1 = this.index % i;
                    int j1 = this.index / i;
                    int l1 = j1 % k;
                    int k1 = j1 / k;
                    ++this.index;
                    return this.cursor.set(pX1 + i1, pY1 + k1, pZ1 + l1);
                }
            }
        };
    }

    public static Iterable<BlockPos> betweenClosedYXZ(BlockPos low, BlockPos high) {
        return betweenClosedYXZ(low.getX(), low.getY(), low.getZ(), high.getX(), high.getY(), high.getZ());
    }

    public static Iterable<BlockPos> betweenClosedYXZ(int pX1, int pY1, int pZ1, int pX2, int pY2, int pZ2) {
        int i = pX2 - pX1 + 1;
        int j = pY2 - pY1 + 1;
        int k = pZ2 - pZ1 + 1;
        int l = i * j * k;
        return () -> new AbstractIterator<BlockPos>() {
            private final BlockPos.Mutable cursor = new BlockPos.Mutable();
            private int index;

            protected BlockPos computeNext() {
                if (this.index == l) {
                    return this.endOfData();
                } else {
                    int i1 = this.index % j;
                    int j1 = this.index / j;
                    int l1 = j1 % i;
                    int k1 = j1 / i;
                    ++this.index;
                    return this.cursor.set(pX1 + l1, pY1 + i1, pZ1 + k1);
                }
            }
        };
    }

    public static Iterable<BlockPos> betweenClosedChunkOrder(BlockPos low, BlockPos high) {
        return betweenClosedChunkOrder(low.getX(), low.getY(), low.getZ(), high.getX(), high.getY(), high.getZ());
    }

    public static Iterable<BlockPos> betweenClosedChunkOrder(int pX1, int pY1, int pZ1, int pX2, int pY2, int pZ2) {
        return () -> new AbstractIterator<BlockPos>() {
            private int currentX = pX2;
            private int currentZ = pZ2;
            private Iterator<BlockPos> thisChunkIter = getNextChunkIter();

            private Iterator<BlockPos> getNextChunkIter() {
                int chunkXOffset = ((currentX % 16) + 16) % 16;
                int chunkZOffset = ((currentZ % 16) + 16) % 16;
                BlockPos thisChunkStart = new BlockPos(currentX, pY2, currentZ);
                BlockPos thisChunkEnd = new BlockPos(
                        Math.max(currentX - chunkXOffset, pX1),
                        pY1,
                        Math.max(currentZ - chunkZOffset, pZ1));

                currentX -= (thisChunkStart.getX() - thisChunkEnd.getX() + 1);
                if (currentX < pX1) {
                    currentX = pX2;
                    currentZ -= (thisChunkStart.getZ() - thisChunkEnd.getZ() + 1);
                }

                return betweenClosedXZY(thisChunkEnd, thisChunkStart).iterator();
            }

            protected BlockPos computeNext() {
                if (thisChunkIter.hasNext()) {
                    return thisChunkIter.next();
                }

                if (currentX < pX1 || currentZ < pZ1) {
                    return this.endOfData();
                }

                thisChunkIter = getNextChunkIter();
                return thisChunkIter.next();
            }
        };
    }
}
