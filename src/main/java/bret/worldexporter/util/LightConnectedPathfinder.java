package bret.worldexporter.util;

import bret.worldexporter.Exporter;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.shapes.IBooleanFunction;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.world.LightType;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Collection;
import java.util.Set;

import static bret.worldexporter.util.BlockPosUtils.blockVolume;

public class LightConnectedPathfinder {
    private final Exporter exporter;
    private final ClientWorld world;
    private final BlockPos segmentLow;
    private final BlockPos segmentHigh;
    private final boolean sidesAreForced;

    public LightConnectedPathfinder(Exporter exporter, ClientWorld world) {
        this.exporter = exporter;
        this.world = world;
        Pair<BlockPos, BlockPos> lowHigh = BlockPosUtils.blockPosMinMax(
                exporter.getEndPosClampedHeight(),
                exporter.getStartPosClampedHeight());
        this.segmentLow = lowHigh.getLeft();
        this.segmentHigh = lowHigh.getRight();
        this.sidesAreForced = exporter.sidesAreForced;
    }

    public LightConnectedPathfinder(Exporter exporter, ClientWorld world, BlockPos segmentStart, BlockPos segmentEnd) {
        this.exporter = exporter;
        this.world = world;
        Pair<BlockPos, BlockPos> lowHigh = BlockPosUtils.blockPosMinMax(
                segmentStart,
                segmentEnd);
        this.segmentLow = lowHigh.getLeft();
        this.segmentHigh = lowHigh.getRight();
        this.sidesAreForced = exporter.sidesAreForced;
    }

    private boolean OutOfSegmentRange(BlockPos pos) {
        return segmentLow.getX() > pos.getX() || pos.getX() > segmentHigh.getX()
                || segmentLow.getY() > pos.getY() || pos.getY() > segmentHigh.getY()
                || segmentLow.getZ() > pos.getZ() || pos.getZ() > segmentHigh.getZ();
    }

    // Blocks in chunks without data will (probably) be an outer face on the edge of the export and so are given light.
    // Blocks that fall on the edge of the export boundary are also given light
    private boolean hasSkyLight(BlockPos pos) {
        if (sidesAreForced && (exporter.isOnExportEdge(pos) || world.getChunkAt(pos).isEmpty())) {
            return true;
        } else {
            return world.getBrightness(LightType.SKY, pos) > 0;
        }
    }

    private void getNeighbors(BlockPos pos, Set<Long> seen, Set<Long> alreadyAdded, Collection<Long> hasLight, Collection<Long> sideLit) {
        BlockState stateAtPos = world.getBlockState(pos);
        hasLight.clear();
        sideLit.clear();

        // early exit: if it can occlude and is a full solid shape it will have 0 neighbors
        if (stateAtPos.canOcclude() && stateAtPos.isSolidRender(world, pos)) {
            return;
        }

        for (Direction dir : Direction.values()) {
            BlockPos toCheck = pos.relative(dir);
            long toCheckLong = toCheck.asLong();
            if (alreadyAdded.contains(toCheckLong) || seen.contains(toCheckLong) || OutOfSegmentRange(toCheck)) {
                continue;
            }

            VoxelShape posExitShape = stateAtPos.getFaceOcclusionShape(world, pos, dir);
            BlockState stateAtCheck = world.getBlockState(toCheck);
            boolean lightExitsPosWithDir = !stateAtPos.canOcclude() ||
                    VoxelShapes.joinIsNotEmpty(
                            VoxelShapes.block(),
                            posExitShape,
                            IBooleanFunction.ONLY_FIRST);
            if (!lightExitsPosWithDir) {
                continue;
            }

            boolean lightEntersCheck = !stateAtCheck.canOcclude() ||
                    VoxelShapes.joinIsNotEmpty(
                            VoxelShapes.block(),
                            VoxelShapes.joinUnoptimized(
                                    posExitShape,
                                    stateAtCheck.getFaceOcclusionShape(world, toCheck, dir.getOpposite()),
                                    IBooleanFunction.OR),
                            IBooleanFunction.ONLY_FIRST);
            if (lightEntersCheck) {
                hasLight.add(toCheckLong);
            } else {
                sideLit.add(toCheckLong);
            }
        }
    }

    public Set<Long> lightConnectedBlockSet(int maxRange) {
        LongOpenHashSet allLightConnected = new LongOpenHashSet();
        double blockVolumeLowEstimate = blockVolume(segmentLow, segmentHigh) * 0.1;
        int blockVolume = blockVolumeLowEstimate > Integer.MAX_VALUE ? Integer.MAX_VALUE / 2 : (int) blockVolumeLowEstimate;
        // Note: to have blocks have 0 cost for light transfer instead of 1, it would be sufficient to
        //  use two queues and always remove from queue 1 first if possible instead of using a priority queue structure
        LongArrayFIFOQueue unexplored = new LongArrayFIFOQueue(blockVolume);
        LongOpenHashSet seen = new LongOpenHashSet(blockVolume);
        LongOpenHashSet inUnexplored = new LongOpenHashSet();
        Long2IntOpenHashMap distanceToSkylight = new Long2IntOpenHashMap();
        LongArrayList hasLightReusable = new LongArrayList(6);
        LongArrayList sideLitReusable = new LongArrayList(6);

        // Only blocks that have a connection to skylight need to be added.
        // Therefore, start checking at only blocks that have skylight since they will always
        // have some path to all blocks we are interested in adding to allLightConnected
        for (BlockPos blockPos : BlockPos.betweenClosed(segmentLow, segmentHigh)) {
            if (hasSkyLight(blockPos)) {
                long pos = blockPos.asLong();
                unexplored.enqueue(pos);
                inUnexplored.add(pos);
            }
        }

        // A default return value of 0 allows block positions with skylight to not be added.
        // Since all blocks with skylight are added to the queue at the start, this is fine
        distanceToSkylight.defaultReturnValue(0);

        while (!unexplored.isEmpty()) {
            long unexploredLong = unexplored.dequeueLong();
            inUnexplored.remove(unexploredLong);
            if (seen.contains(unexploredLong)) {
                continue;
            }

            BlockPos unexploredPos = BlockPos.of(unexploredLong);
            seen.add(unexploredLong);
            int distance = distanceToSkylight.get(unexploredLong);
            if (distance > maxRange) {
                continue;
            }
            allLightConnected.add(unexploredLong);

            getNeighbors(unexploredPos, seen, inUnexplored, hasLightReusable, sideLitReusable);
            for (long hasLightNeighbor : hasLightReusable) {
                unexplored.enqueue(hasLightNeighbor);
                inUnexplored.add(hasLightNeighbor);
                distanceToSkylight.put(hasLightNeighbor, distance + 1);
            }
            for (long sideLitNeighbor : sideLitReusable) {
                allLightConnected.add(sideLitNeighbor);
            }
        }

        return allLightConnected;
    }
}
