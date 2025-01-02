package bret.worldexporter.util.disk;

import bret.worldexporter.util.BlockPosUtils;
import net.minecraft.util.math.BlockPos;
import org.apache.commons.lang3.tuple.Pair;
import sun.nio.ch.DirectBuffer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

public class DiskBackedVolumeSet implements SimpleSet<Long> {
    private static final String SUBDIR = "volumeset";
    private static final int MAP_SIZE = Integer.MAX_VALUE;
    private static final long CHUNK_LAYER = 256;
    private final MappedByteBuffer[] maps;
    private final int lowX;
    private final int lowZ;
    private final int lowY;
    private final int widthX;
    private final int widthZ;
    private final int widthY;
    private final long blocksPerChunk;
    private final long numXChunks;

    public DiskBackedVolumeSet(String baseCacheDir, BlockPos start, BlockPos end) {
        Pair<BlockPos, BlockPos> lowHigh = BlockPosUtils.blockPosMinMax(start, end);
        BlockPos low = lowHigh.getLeft();
        BlockPos high = lowHigh.getRight();
        lowX = BlockPosUtils.lowestCoordinateInChunk(low.getX());
        lowZ = BlockPosUtils.lowestCoordinateInChunk(low.getZ());
        lowY = low.getY();
        int highX = BlockPosUtils.highestCoordinateInChunk(high.getX());
        int highZ = BlockPosUtils.highestCoordinateInChunk(high.getZ());
        int highY = high.getY();
        widthX = highX - lowX + 1;
        widthZ = highZ - lowZ + 1;
        widthY = highY - lowY + 1;
        long blockVolume = ((long) widthX) * widthZ * widthY;
        blocksPerChunk = widthY * CHUNK_LAYER;
        numXChunks = (widthX >> 4) + (widthX % 16 == 0 ? 0 : 1);

        try {
            Files.createDirectories(Paths.get(baseCacheDir, SUBDIR));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        long numBytes = (blockVolume >> 3) + (blockVolume % 8 == 0 ? 0 : 1);
        int numMaps = (int) ((numBytes / MAP_SIZE) + (numBytes % MAP_SIZE == 0 ? 0 : 1));
        maps = new MappedByteBuffer[numMaps];
        for (int i = 0; i < numMaps; ++i) {
            Path file = Paths.get(baseCacheDir, SUBDIR, UUID.randomUUID().toString());
            try {
                FileChannel fileChannel = new RandomAccessFile(file.toString(), "rw").getChannel();
                long mapSize = i == numMaps - 1 ? (numBytes % MAP_SIZE) : MAP_SIZE;
                maps[i] = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, mapSize);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void set(int map, long bit, boolean val) {
        int rawIndex = (int) (bit / 8);
        int rawOffset = (int) (bit % 8);
        byte raw = maps[map].get(rawIndex);
        int removeMask = ~(0b10000000 >> rawOffset);
        byte newRaw = (byte) ((raw & removeMask) | ((val ? 0b10000000 : 0) >> rawOffset));
        maps[map].put(rawIndex, newRaw);
    }

    private boolean isSet(int map, long bit) {
        byte raw = maps[map].get((int) (bit / 8));
        return (raw & (0b10000000 >> (bit % 8))) > 0;
    }

    // the bit offset of the position, treating the storage as one contiguous array
    private long rawOffset(long pos) {
        int x = BlockPos.getX(pos);
        int y = BlockPos.getY(pos);
        int z = BlockPos.getZ(pos);
        int relativeX = x - lowX;
        int relativeY = y - lowY;
        int relativeZ = z - lowZ;
        if (relativeX < 0 || relativeY < 0 || relativeZ < 0 ||
                relativeX >= widthX || relativeY >= widthY || relativeZ >= widthZ) return -1L;

        int offsetX = ((relativeX % 16) + 16) % 16;
        int offsetZ = ((relativeZ % 16) + 16) % 16;
        long chunkX = relativeX >> 4;
        long chunkZ = relativeZ >> 4;
        long chunkIndex = (chunkZ * numXChunks) + chunkX;
        return chunkIndex * blocksPerChunk + (relativeY * CHUNK_LAYER + offsetZ * 16 + offsetX);
    }

    // -1 denotes an invalid map for the provided offset
    private int mapIndex(long rawOffset) {
        if (rawOffset < 0) return -1;
        int map = (int) (rawOffset / (MAP_SIZE * 8L));
        if (map >= maps.length) return -1;
        return map;
    }

    // -1 denotes an invalid single-map offset for the provided raw offset
    private long bitOffset(long rawOffset) {
        if (rawOffset < 0) return -1L;
        return rawOffset % (MAP_SIZE * 8L);
    }

    public boolean contains(long pos) {
        long rawOffset = rawOffset(pos);
        int map = mapIndex(rawOffset);
        if (map < 0) return false;
        long bitOffset = bitOffset(rawOffset);
        if (bitOffset < 0) return false;

        return isSet(map, bitOffset);
    }

    private boolean addOrRemove(long pos, boolean val) {
        long rawOffset = rawOffset(pos);
        int map = mapIndex(rawOffset);
        if (map < 0) return false;
        long bitOffset = bitOffset(rawOffset);
        if (bitOffset < 0) return false;

        boolean wasPresent = isSet(map, bitOffset);
        set(map, bitOffset, val);
        return wasPresent != val;
    }

    // returns true if the set was modified
    public boolean add(long pos) {
        return addOrRemove(pos, true);
    }

    // returns true if the set was modified
    public boolean remove(long pos) {
        return addOrRemove(pos, false);
    }

    @Override
    public boolean contains(Long pos) {
        return contains(pos.longValue());
    }

    @Override
    public boolean add(Long pos) {
        return add(pos.longValue());
    }

    @Override
    public boolean remove(Long pos) {
        return remove(pos.longValue());
    }
}
