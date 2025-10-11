package bret.worldexporter.util;

import bret.worldexporter.ChunkThreadSyncManager;
import bret.worldexporter.Exporter;
import bret.worldexporter.WorldExporterClient;
import bret.worldexporter.config.WorldExporterConfig;
import bret.worldexporter.util.disk.*;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.shapes.IBooleanFunction;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.world.LightType;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Collection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

import static bret.worldexporter.WorldExporter.LOGGER;

public class LightConnectedPathfinder {
    private final static int BASE_BUCKETS = 4096;
    private final static int THREAD_CHECKPOINT_ITERATIONS = 10_000;
    private final Exporter exporter;
    private final ClientWorld world;
    private final BlockPos volumeLow;
    private final BlockPos volumeHigh;
    private final int centerX;
    private final int centerZ;
    private final boolean sidesAreForced;
    private final int maxThreads;
    private final boolean segmentedPathfinding = WorldExporterConfig.CLIENT.segmentedExteriorPathfinding.get();
    private final int segmentChunkRadius = WorldExporterConfig.CLIENT.segmentChunkRadius.get();


    public LightConnectedPathfinder(Exporter exporter, ClientWorld world, int maxThreads) {
        this.exporter = exporter;
        this.world = world;
        Pair<BlockPos, BlockPos> lowHigh = BlockPosUtils.blockPosMinMax(
                exporter.getLowPosClampedHeight(),
                exporter.getHighPosClampedHeight());
        this.volumeLow = lowHigh.getLeft();
        this.volumeHigh = lowHigh.getRight();
        this.centerX = (volumeHigh.getX() + volumeLow.getX()) / 2;
        this.centerZ = (volumeHigh.getZ() + volumeLow.getZ()) / 2;
        this.sidesAreForced = exporter.sidesAreForced;
        this.maxThreads = maxThreads;
    }

    public LightConnectedPathfinder(Exporter exporter, ClientWorld world, BlockPos segmentStart, BlockPos segmentEnd, int maxThreads) {
        this.exporter = exporter;
        this.world = world;
        Pair<BlockPos, BlockPos> lowHigh = BlockPosUtils.blockPosMinMax(
                segmentStart,
                segmentEnd);
        this.volumeLow = lowHigh.getLeft();
        this.volumeHigh = lowHigh.getRight();
        this.centerX = (volumeHigh.getX() + volumeLow.getX()) / 2;
        this.centerZ = (volumeHigh.getZ() + volumeLow.getZ()) / 2;
        this.sidesAreForced = exporter.sidesAreForced;
        this.maxThreads = maxThreads;
    }

    private boolean outOfSegmentRange(BlockPos pos, BlockPos low, BlockPos high) {
        return low.getX() > pos.getX() || pos.getX() > high.getX()
                || low.getY() > pos.getY() || pos.getY() > high.getY()
                || low.getZ() > pos.getZ() || pos.getZ() > high.getZ();
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

    private void getNeighbors(BlockPos pos,
                              SimpleSet<Long> seen,
                              SimpleSet<Long> alreadyAdded,
                              Collection<Long> hasLight,
                              Collection<Long> sideLit,
                              BlockPos segmentLow,
                              BlockPos segmentHigh) {
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
            if (alreadyAdded.contains(toCheckLong)
                    || seen.contains(toCheckLong)
                    || outOfSegmentRange(toCheck, segmentLow, segmentHigh)) {
                continue;
            }

            VoxelShape posExitShape = stateAtPos.getFaceOcclusionShape(world, pos, dir);
            boolean lightExitsPosWithDir = !stateAtPos.canOcclude() ||
                    VoxelShapes.joinIsNotEmpty(
                            VoxelShapes.block(),
                            posExitShape,
                            IBooleanFunction.ONLY_FIRST);
            if (!lightExitsPosWithDir) {
                continue;
            }

            BlockState stateAtCheck = world.getBlockState(toCheck);
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

    private static long xzBucket(long pos) {
        return BucketFunctions.xzLocalityBucket(BlockPos.getX(pos), BlockPos.getZ(pos), 16);
    }

    private long ringBucket(long pos) {
        return BucketFunctions.centerBasedRingBucket(centerX, centerZ, BlockPos.getX(pos), BlockPos.getZ(pos), 256);
    }

    // Should only be called on the main thread
    public SimpleSet<Long> lightConnectedBlockSet(int maxRange) throws ExecutionException, InterruptedException {
        AtomicLong dequeueTotal = new AtomicLong(0);
        if (segmentedPathfinding) {
            LinkedBlockingQueue<DiskBackedVolumeSet> segmentSets = new LinkedBlockingQueue<>();
            Collection<Pair<BlockPos, BlockPos>> stripes = BlockPosUtils.divideStripesXZ(volumeLow, volumeHigh, maxThreads);
            ExecutorService threads = ThreadUtils.threadPoolWithModClassLoader(stripes.size());
            AtomicInteger remainingCount = new AtomicInteger(stripes.size());
            ChunkThreadSyncManager.reset(stripes.size());
            for (Pair<BlockPos, BlockPos> stripe : stripes) {
                threads.submit(() -> {
                    try {
                        Pair<BlockPos, BlockPos> extended = BlockPosUtils.extendChunks(
                                stripe.getLeft(),
                                stripe.getRight(),
                                segmentChunkRadius
                        );
                        segmentSets.put(threadedSliceLightConnectedBlockSet(
                                maxRange,
                                BASE_BUCKETS / stripes.size(),
                                extended.getLeft(),
                                extended.getRight(),
                                dequeueTotal)
                        );
                    } catch (Throwable e) {
                        LOGGER.error("Thread error when running light connected set", e);
                    } finally {
                        ChunkThreadSyncManager.release();
                        remainingCount.decrementAndGet();
                    }
                });
            }
            ChunkThreadSyncManager.mainThreadEventLoop(() -> remainingCount.get() == 0, null, false);
            LOGGER.info(String.format("Explored %d blocks while building light connected set", dequeueTotal.get()));

            if (segmentSets.isEmpty()) {
                return null;
            } else {
                DiskBackedVolumeSet merged = DiskBackedVolumeSet.fromMerged(WorldExporterClient.getCacheDirectory(), volumeLow, volumeHigh, segmentSets);
                while (!segmentSets.isEmpty()) {
                    segmentSets.poll().dispose();
                }
                return merged;
            }
        } else {
            RunnableFuture<SimpleSet<Long>> task = new FutureTask<>(() -> {
                try {
                    exporter.useClassLoaderOnThisThread();
                    return threadedSliceLightConnectedBlockSet(maxRange, BASE_BUCKETS, volumeLow, volumeHigh, dequeueTotal);
                } catch (Throwable e) {
                    LOGGER.error("Thread error when running light connected set", e);
                    return null;
                }
            });
            ChunkThreadSyncManager.reset(1);
            (new Thread(task)).start();
            ChunkThreadSyncManager.mainThreadEventLoop(task::isDone, null, false);
            LOGGER.info(String.format("Explored %d blocks while building light connected set", dequeueTotal.get()));
            return task.get();
        }
    }

    private DiskBackedVolumeSet threadedSliceLightConnectedBlockSet(int maxRange,
                                                                    int baseBuckets,
                                                                    BlockPos segmentLow,
                                                                    BlockPos segmentHigh,
                                                                    AtomicLong dequeueTotal) {
        // Note: to have blocks have 0 cost for light transfer instead of 1, it would be sufficient to
        //  use two queues and always remove from queue 1 first if possible instead of using a priority queue structure
        final boolean canRequestChunks = WorldExporterClient.canRequestChunks();
        long dequeueCount = 0;
        long iterCount = 0;
        String cacheBase = WorldExporterClient.getCacheDirectory();
        DiskBackedVolumeSet allLightConnected = new DiskBackedVolumeSet(cacheBase, segmentLow, segmentHigh);
        DiskBackedVolumeSet seen = new DiskBackedVolumeSet(cacheBase, segmentLow, segmentHigh);
        DiskBackedVolumeSet inUnexplored = new DiskBackedVolumeSet(cacheBase, segmentLow, segmentHigh);
        DiskBackedBucketedLongFIFOQueue unexplored = new DiskBackedBucketedLongFIFOQueue(2,
                4096 * baseBuckets, cacheBase);

        // A default return value of 0 allows block positions with skylight to not be added (massively saves resources).
        // Since all blocks with skylight are added to the queue at the start, this is fine.
        DiskBackedBucketedLong2IntHashMap distanceToSkylight = new DiskBackedBucketedLong2IntHashMap(baseBuckets / 16,
                cacheBase, LightConnectedPathfinder::xzBucket, 0);

        LongArrayList hasLightReusable = new LongArrayList(6);
        LongArrayList sideLitReusable = new LongArrayList(6);

        // Only blocks that have a connection to skylight need to be added.
        // Therefore, start checking at only blocks that have skylight since they will always
        // have some path to all blocks we are interested in adding to allLightConnected
        long lastTouchedChunk = new ChunkPos(Integer.MAX_VALUE, Integer.MAX_VALUE).toLong();
        for (BlockPos blockPos : BlockPosUtils.betweenClosedChunkOrder(segmentLow, segmentHigh)) {
            if (canRequestChunks) {
                long thisChunk = ChunkPos.asLong(blockPos.getX() >> 4, blockPos.getZ() >> 4);
                // "touch" the chunk to request and update its light data
                if (lastTouchedChunk != thisChunk) {
                    lastTouchedChunk = thisChunk;
                    world.getChunkAt(blockPos);
                }

                if (++iterCount == THREAD_CHECKPOINT_ITERATIONS) {
                    iterCount = 0;
                    ChunkThreadSyncManager.threadCheckpoint();
                }
            }

            if (hasSkyLight(blockPos) || blockPos.getY() >= Exporter.WORLD_HEIGHT_LIMIT) {
                long pos = blockPos.asLong();
                unexplored.enqueue(pos);
                inUnexplored.add(pos);
            }
        }

        while (!unexplored.isEmpty()) {
            if (++iterCount == THREAD_CHECKPOINT_ITERATIONS) {
                iterCount = 0;
                ChunkThreadSyncManager.threadCheckpoint();
            }

            long unexploredLong = unexplored.dequeueLong();
            ++dequeueCount;

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

            getNeighbors(unexploredPos, seen, inUnexplored, hasLightReusable, sideLitReusable, segmentLow, segmentHigh);
            for (long hasLightNeighbor : hasLightReusable) {
                unexplored.enqueue(hasLightNeighbor);
                inUnexplored.add(hasLightNeighbor);
                distanceToSkylight.put(hasLightNeighbor, distance + 1);
            }
            for (long sideLitNeighbor : sideLitReusable) {
                allLightConnected.add(sideLitNeighbor);
            }
        }

        seen.dispose();
        inUnexplored.dispose();
        unexplored.dispose();
        distanceToSkylight.dispose();

        dequeueTotal.addAndGet(dequeueCount);

        return allLightConnected;
    }
}
