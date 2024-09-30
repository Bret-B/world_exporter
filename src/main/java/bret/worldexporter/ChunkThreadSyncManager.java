package bret.worldexporter;

import bret.worldexporter.networking.packets.PacketHandler;
import bret.worldexporter.networking.packets.ReceivedChunkEnum;
import bret.worldexporter.networking.packets.clientout.CRequestChunkPacket;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

@SuppressWarnings("BusyWait")
public class ChunkThreadSyncManager {
    private final static LinkedBlockingQueue<Runnable> threadSyncRequiredTasks = new LinkedBlockingQueue<>();
    private final static Semaphore threadSyncSemaphore = new Semaphore(0);
    private final static AtomicBoolean threadsShouldResume = new AtomicBoolean(false);
    private final static AtomicBoolean requestsDisabled = new AtomicBoolean(false);
    // uses ChunkPos <-> long functions for mapping
    private final static ConcurrentHashMap.KeySetView<Long, Boolean> pendingChunks = ConcurrentHashMap.newKeySet();
    private final static Long2ObjectOpenHashMap<boolean[]> chunkPartsReceived = new Long2ObjectOpenHashMap<>();
    private final static ConcurrentHashMap<Long, AtomicBoolean> chunkEvents = new ConcurrentHashMap<>();
    private static int threads = 0;

    public static void reset(int threadCount) {
        // semaphore starts with 0 permits because threads are working when they start
        threadSyncRequiredTasks.clear();
        threadSyncSemaphore.drainPermits();
        threadsShouldResume.set(false);
        requestsDisabled.set(false);
        pendingChunks.clear();
        chunkPartsReceived.clear();
        chunkEvents.clear();
        threads = threadCount;
    }

    public static void add(Runnable task) {
        threadSyncRequiredTasks.add(task);
    }

    public static boolean threadsNeedSync() {
        return !threadSyncRequiredTasks.isEmpty();
    }

    public static boolean isEmpty() {
        return threadSyncRequiredTasks.isEmpty();
    }

    public static boolean readyToSync() {
        return threadsNeedSync() && threadSyncSemaphore.availablePermits() == threads;
    }

    public static AtomicBoolean getChunkEvent(int x, int z) {
        long pos = ChunkPos.asLong(x, z);
        return chunkEvents.computeIfAbsent(pos, k -> new AtomicBoolean(false));
    }

    // must be run on the main thread since when all parts are received the light manager needs to run updates
    public static void notifyChunkReceived(int x, int z, ReceivedChunkEnum partType) {
        long pos = ChunkPos.asLong(x, z);
        boolean[] parts = chunkPartsReceived.computeIfAbsent(pos, k -> new boolean[ReceivedChunkEnum.values().length]);
        parts[partType.ordinal()] = true;
        boolean allReceived = true;
        for (boolean part : parts) {
            if (!part) {
                allReceived = false;
                break;
            }
        }

        if (allReceived) {
            // WorldExporter.LOGGER.info(String.format("Chunk all received: x:%d, z:%d", x, z));
            requestsDisabled.set(true);
            // how accurate is the data if it cannot request true data from nearby chunks?
            Objects.requireNonNull(Minecraft.getInstance().level).getChunkSource()
                    .getLightEngine().runUpdates(Integer.MAX_VALUE, true, true);
            requestsDisabled.set(false);
            AtomicBoolean chunkReceived = getChunkEvent(x, z);
            chunkReceived.set(true);
            pendingChunks.remove(pos);
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized (chunkReceived) {
                chunkReceived.notifyAll();
            }
        }
    }

    // these should only be called on external threads
    // --------------------------------------------------------------------------------------------
    public static void blockUntilChunk(int x, int z) {
        release();
        AtomicBoolean chunkReceived = getChunkEvent(x, z);
        while (!chunkReceived.get()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
            }
        }
        waitForThreadsReady();
        acquire();
    }

    // should be periodically called by the threads to see if they need to pause execution
    public static void threadCheckpoint() {
        if (threadsNeedSync()) {
            // WorldExporter.LOGGER.info("Thread pausing at checkpoint");
            release();
            waitForThreadsReady();
            acquire();
            // WorldExporter.LOGGER.info("Thread checkpoint resume");
        }
    }
    // --------------------------------------------------------------------------------------------


    public static void waitForThreadsReady() {
        while (!threadsShouldResume.get()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // wait and notifyAll works, but the threads were excruciatingly slow to wake and I have no idea why, so threads
    //  busy wait on an atomic boolean instead
    public static void notifyThreadsResume() {
        threadsShouldResume.set(true);
    }

    public static void release() {
        threadSyncSemaphore.release();
    }

    public static void acquire() {
        try {
            threadSyncSemaphore.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isMainThread() {
        return Minecraft.getInstance().isSameThread();
    }

    public static void completeAllTasks() {
        while (!threadSyncRequiredTasks.isEmpty()) {
            Runnable task = threadSyncRequiredTasks.poll();
            if (task != null) task.run();
        }
    }

    public static void mainThreadEventLoop(BooleanSupplier hasFinished, @Nullable Queue<Runnable> secondaryQueue, boolean returnIfCannotSync) {
        if (!isMainThread()) {
            throw new RuntimeException("mainThreadEventLoop() must be ran on the main thread!");
        }
        threadsShouldResume.set(false);

        if (returnIfCannotSync && !readyToSync()) {
            return;
        }

        while (!hasFinished.getAsBoolean()) {
            // wait until threads can sync
            while (!readyToSync()) {
                if (hasFinished.getAsBoolean()) {
                    return;
                }

                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                }

                // other main thread tasks may block external threads and prevent them from releasing their semaphore
                while (secondaryQueue != null && !secondaryQueue.isEmpty()) {
                    Runnable task = secondaryQueue.poll();
                    if (task != null) task.run();
                }
            }

            completeAllTasks();
            notifyThreadsResume();
        }
    }

    public static boolean isPending(int x, int z) {
        return pendingChunks.contains(ChunkPos.asLong(x, z));
    }

    public static void requestChunk(int pChunkX, int pChunkZ) {
        long pos = ChunkPos.asLong(pChunkX, pChunkZ);
        if (!isPending(pChunkX, pChunkZ)) {
            pendingChunks.add(pos);
            // WorldExporter.LOGGER.info(String.format("Req chunk: x:%d, z:%d", pChunkX, pChunkZ));
            PacketHandler.INSTANCE.sendToServer(new CRequestChunkPacket(pChunkX, pChunkZ));
        }

        if (ChunkThreadSyncManager.isMainThread()) {
            ChunkThreadSyncManager.mainThreadEventLoop(
                    ChunkThreadSyncManager.getChunkEvent(pChunkX, pChunkZ)::get,
                    Exporter.getInstance().getMainThreadTasks(),
                    false);
        } else {
            ChunkThreadSyncManager.blockUntilChunk(pChunkX, pChunkZ);
        }
    }

    public static boolean requestsDisabled() {
        return requestsDisabled.get();
    }
}
