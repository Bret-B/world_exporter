package bret.worldexporter.util.disk;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import java.nio.file.Paths;

public class DiskBackedBucketedLongFIFOQueue {
    private static final String SUBDIR = "longqueue";
    private final DiskBackedBuckets<LongArrayFIFOQueue> buckets;
    private final int sizePerBucket;
    // bucket indices do not wrap and are technically finite
    private long enqueueBucket = 0;
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
        LongArrayFIFOQueue currentBucket = buckets.getBucket(enqueueBucket, true);
        if (currentBucket.size() >= sizePerBucket) {
            currentBucket = buckets.getBucket(++enqueueBucket, true);
        }
        currentBucket.enqueue(element);
        buckets.setDirty(enqueueBucket);
    }

    public long dequeueLong() {
        LongArrayFIFOQueue bucket = buckets.getBucket(dequeueBucket);
        long result = bucket.dequeueLong();
        buckets.setDirty(dequeueBucket);
        // if we've exhausted this queue and the next one exists, use it for the next dequeue and delete the old queue
        if (bucket.isEmpty() && buckets.bucketExists(dequeueBucket + 1)) {
            buckets.removeBucket(dequeueBucket++);
        }
        return result;
    }

    public boolean isEmpty() {
        if (!buckets.bucketExists(dequeueBucket)) {
            return true;  // bucket doesnt exist to dequeue from: empty
        } else {
            // check the bucket that would be dequeued from
            return buckets.getBucket(dequeueBucket).isEmpty();
        }
    }

    public void clear() {
        buckets.clear();
    }
}
