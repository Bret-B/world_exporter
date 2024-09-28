package bret.worldexporter;

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.ChunkPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class ChunkThreadSyncManager {
    private final static LinkedBlockingQueue<Runnable> threadSyncRequiredTasks = new LinkedBlockingQueue<>();
    private final static Semaphore threadSyncSemaphore = new Semaphore(0);
    private final static Object threadsReadyEvent = new Object();
    private final static AtomicBoolean needsSync = new AtomicBoolean(false);
    // uses ChunkPos <-> long functions for mapping
    private final static ConcurrentHashMap<Long, AtomicBoolean> chunkEvents = new ConcurrentHashMap<>();
    private static int threads = 0;

    private ChunkThreadSyncManager() {
    }

    public static void reset(int threadCount) {
        threadSyncSemaphore.drainPermits();
        threadSyncRequiredTasks.clear();
        threadSyncSemaphore.release(threadCount);
        chunkEvents.clear();
        threads = threadCount;
    }

    public static void add(Runnable task) {
        synchronized (threadSyncRequiredTasks) {
            threadSyncRequiredTasks.add(task);
            needsSync.set(true);
        }
    }

    public static boolean threadsNeedSync() {
        return needsSync.get();
//        return !threadSyncRequiredTasks.isEmpty();
    }

    public static boolean isEmpty() {
        return threadSyncRequiredTasks.isEmpty();
    }

    public static boolean shouldSyncNow() {
        return threadsNeedSync() && threadSyncSemaphore.availablePermits() == threads;
    }

    public static void notifyChunkReceived(int x, int z) {
        long pos = ChunkPos.asLong(x, z);
        AtomicBoolean chunkReceived = chunkEvents.computeIfAbsent(pos, k -> new AtomicBoolean(false));
        chunkReceived.set(true);
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (chunkReceived) {
            chunkReceived.notifyAll();
        }
    }

    // these should only be called on external threads
    // --------------------------------------------------------------------------------------------
    public static void blockUntilChunk(int x, int z) {
        release();
        long pos = ChunkPos.asLong(x, z);
        AtomicBoolean chunkReceived = chunkEvents.computeIfAbsent(pos, k -> new AtomicBoolean(false));
        while (!chunkReceived.get()) {
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized (chunkReceived) {
                try {
                    chunkReceived.wait();
                } catch (InterruptedException ignored) {}
            }
        }
        waitForThreadsReady();
        acquire();
    }

    // should be periodically called by the threads to see if they need to pause execution
    public static void threadCheckpoint() {
        if (threadsNeedSync()) {
            release();
            waitForThreadsReady();
            acquire();
        }
    }
    // --------------------------------------------------------------------------------------------

    public static void waitForThreadsReady() {
        while (threadsNeedSync()) {
            synchronized (threadsReadyEvent) {
                try {
                    threadsReadyEvent.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    public static void notifyThreadsResume() {
        // Continues notifying threads until they all either re-acquire their permits or until they need to sync again,
        //  whichever happens earlier. The reason for this is that it might be possible for
        //  the resume notification to be sent before a thread finishes acquiring the lock and waiting
        if (threadsNeedSync()) return;

        while (threadSyncSemaphore.availablePermits() > 0) {
            synchronized (threadsReadyEvent) {
                try {
                    threadsReadyEvent.notifyAll();
                    //noinspection BusyWait
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                }
            }
            if (threadsNeedSync()) return;
        }

//        threadsReadyEvent.notifyAll();
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

    public static void mainThreadEventLoopOrReturn(BooleanSupplier hasFinished, boolean returnIfCannotSync) {
        if (!isMainThread()) {
            return;
        }
        if (returnIfCannotSync && !shouldSyncNow()) {
            return;
        }

        while (!hasFinished.getAsBoolean()) {
            // wait until threads can sync
            while (!shouldSyncNow()) {
                try {
                    //noinspection BusyWait
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {}
            }

            completeAllTasks();

            // prevent any tasks from being added while ensuring any tasks are fully completed
            synchronized (threadSyncRequiredTasks) {
                completeAllTasks();
                needsSync.set(false);
                boolean finished = hasFinished.getAsBoolean();
                notifyThreadsResume();
                if (finished) {
                    return;
                }
            }
        }
    }
}
