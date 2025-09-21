package bret.worldexporter.util.disk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.io.Serializable;
import java.nio.file.Paths;
import java.util.function.Function;

public class DiskBackedBucketedLong2ObjectHashMap<T extends Serializable> {
    private static final String SUBDIR = "long2objectmap";
    private final DiskBackedBuckets<Long2ObjectOpenHashMap<T>> buckets;
    private final Function<Long, Long> bucketFunction;

    public DiskBackedBucketedLong2ObjectHashMap(int bucketCacheSize, String baseCacheDir, Function<Long, Long> bucketFunction) {
        buckets = new DiskBackedBuckets<>(
                bucketCacheSize,
                Paths.get(baseCacheDir, SUBDIR).toString(),
                Long2ObjectOpenHashMap::new);
        this.bucketFunction = bucketFunction;
    }

    public T get(long key) {
        long bucketKey = bucketFunction.apply(key);
        Long2ObjectOpenHashMap<T> bucket = buckets.getBucket(bucketKey);
        return bucket.get(key);
    }

    public void put(long key, T value) {
        long bucketKey = bucketFunction.apply(key);
        Long2ObjectOpenHashMap<T> bucket = buckets.getBucket(bucketKey, true);
        bucket.put(key, value);
        buckets.setDirty(bucketKey);
    }

    public boolean containsKey(long key) {
        long bucketKey = bucketFunction.apply(key);
        Long2ObjectOpenHashMap<T> bucket = buckets.getBucket(bucketKey);
        return bucket != null && bucket.containsKey(key);
    }

    public boolean isEmpty() {
        return buckets.isEmpty();
    }

    public void clear() {
        buckets.clear();
    }
}
