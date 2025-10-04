package bret.worldexporter.util.disk;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.io.Serializable;
import java.nio.file.Paths;
import java.util.function.Function;

public class DiskBackedBucketedObject2IntHashMap<T extends Serializable> extends UsesBuckets<Object2IntOpenHashMap<T>> {
    private static final String SUBDIR = "object2intmap";
    private final DiskBackedBuckets<Object2IntOpenHashMap<T>> buckets;
    private final Function<T, Long> bucketFunction;

    public DiskBackedBucketedObject2IntHashMap(int bucketCacheSize,
                                               String baseCacheDir,
                                               Function<T, Long> bucketFunction,
                                               CompressionType compressionType) {
        buckets = new DiskBackedBuckets<>(
                bucketCacheSize,
                Paths.get(baseCacheDir, SUBDIR).toString(),
                Object2IntOpenHashMap::new,
                compressionType);
        this.bucketFunction = bucketFunction;
    }

    public int get(T key) {
        long bucketKey = bucketFunction.apply(key);
        Object2IntOpenHashMap<T> bucket = buckets.getBucket(bucketKey);
        return bucket.getInt(key);
    }

    public void put(T key, int value) {
        long bucketKey = bucketFunction.apply(key);
        Object2IntOpenHashMap<T> bucket = buckets.getBucket(bucketKey, true);
        bucket.put(key, value);
        buckets.setDirty(bucketKey);
    }

    public boolean containsKey(T key) {
        long bucketKey = bucketFunction.apply(key);
        Object2IntOpenHashMap<T> bucket = buckets.getBucket(bucketKey);
        return bucket != null && bucket.containsKey(key);
    }

    public boolean isEmpty() {
        return buckets.isEmpty();
    }

    public void clear() {
        buckets.clear();
    }
}
