package bret.worldexporter.util.disk;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.nio.file.Paths;
import java.util.function.Function;

public class DiskBackedBucketedLong2IntHashMap extends UsesBuckets<Long2IntOpenHashMap> {
    private static final String SUBDIR = "long2intmap";
    private final DiskBackedBuckets<Long2IntOpenHashMap> buckets;
    private final Function<Long, Long> bucketFunction;
    private final int defaultReturnValue;

    public DiskBackedBucketedLong2IntHashMap(int bucketCacheSize, String baseCacheDir, Function<Long, Long> bucketFunction, int defaultReturnValue) {
        buckets = new DiskBackedBuckets<>(
                bucketCacheSize,
                Paths.get(baseCacheDir, SUBDIR).toString(),
                () -> {
                    Long2IntOpenHashMap map = new Long2IntOpenHashMap();
                    map.defaultReturnValue(defaultReturnValue);
                    return map;
                });
        this.bucketFunction = bucketFunction;
        this.defaultReturnValue = defaultReturnValue;
    }

    public int get(long key) {
        long bucketKey = bucketFunction.apply(key);
        Long2IntOpenHashMap bucket = buckets.getBucket(bucketKey);
        if (bucket == null) {
            return defaultReturnValue;
        }

        return bucket.get(key);
    }

    public void put(long key, int value) {
        long bucketKey = bucketFunction.apply(key);
        Long2IntOpenHashMap bucket = buckets.getBucket(bucketKey, true);
        bucket.put(key, value);
        buckets.setDirty(bucketKey);
    }

    public boolean isEmpty() {
        return buckets.isEmpty();
    }

    public void clear() {
        buckets.clear();
    }
}
