package bret.worldexporter.util;

import bret.worldexporter.Exporter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThreadUtils {
    public static ExecutorService threadPoolWithModClassLoader(int threadCount) {
        CountDownLatch latch = new CountDownLatch(threadCount);
        Runnable startup = () -> {
            Exporter.getInstance().useClassLoaderOnThisThread();

            latch.countDown();
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; ++i) {
            pool.submit(startup);
        }

        return pool;
    }
}
