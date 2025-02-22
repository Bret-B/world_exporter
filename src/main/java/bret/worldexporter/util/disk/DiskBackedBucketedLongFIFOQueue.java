package bret.worldexporter.util.disk;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import java.nio.file.Paths;

public class DiskBackedBucketedLongFIFOQueue {
    private static final String SUBDIR = "queue";
    private final DiskBackedBuckets<LongArrayFIFOQueue> buckets;
    private final int sizePerBucket;
    // bucket indices do not wrap and are technically finite
    private long queueBucket = 0;
    private long dequeueBucket = 0;

    public DiskBackedBucketedLongFIFOQueue(int bucketCacheSize, int sizePerBucket, String baseCacheDir, CompressionType compressionType) {
        buckets = new DiskBackedBuckets<>(
                bucketCacheSize,
                Paths.get(baseCacheDir, SUBDIR).toString(),
                () -> new LongArrayFIFOQueue(sizePerBucket),
                compressionType);
        this.sizePerBucket = sizePerBucket;
    }

    public void enqueue(long element) {
        LongArrayFIFOQueue currentBucket = buckets.getBucket(queueBucket, true);
        if (currentBucket.size() >= sizePerBucket) {
            currentBucket = buckets.getBucket(++queueBucket, true);
        }
        currentBucket.enqueue(element);
        buckets.setDirty(queueBucket);
    }

    public long dequeueLong() {
        LongArrayFIFOQueue bucket = buckets.getBucket(dequeueBucket);
        long result = bucket.dequeueLong();
        buckets.setDirty(dequeueBucket);
        if (bucket.isEmpty()) {
            buckets.removeBucket(dequeueBucket++);
        }
        return result;
    }

    public boolean isEmpty() {
        return buckets.isEmpty();
    }

    public void clear() {
        buckets.clear();
    }
}
