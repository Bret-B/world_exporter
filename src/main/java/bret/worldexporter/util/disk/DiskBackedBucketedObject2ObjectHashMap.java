package bret.worldexporter.util.disk;

import java.io.Serializable;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.function.Function;

public class DiskBackedBucketedObject2ObjectHashMap<T extends Serializable, V extends Serializable> extends UsesBuckets<HashMap<T, V>> {
    private static final String SUBDIR = "object2objectmap";
    private final DiskBackedBuckets<HashMap<T, V>> buckets;
    private final Function<T, Long> bucketFunction;

    public DiskBackedBucketedObject2ObjectHashMap(int bucketCacheSize,
                                                  String baseCacheDir,
                                                  Function<T, Long> bucketFunction) {
        buckets = new DiskBackedBuckets<>(
                bucketCacheSize,
                Paths.get(baseCacheDir, SUBDIR).toString(),
                HashMap::new);
        this.bucketFunction = bucketFunction;
    }

    public V get(T key) {
        long bucketKey = bucketFunction.apply(key);
        HashMap<T, V> bucket = buckets.getBucket(bucketKey);
        return bucket.get(key);
    }

    public void put(T key, V value) {
        long bucketKey = bucketFunction.apply(key);
        HashMap<T, V> bucket = buckets.getBucket(bucketKey, true);
        bucket.put(key, value);
        buckets.setDirty(bucketKey);
    }

    public boolean containsKey(T key) {
        long bucketKey = bucketFunction.apply(key);
        HashMap<T, V> bucket = buckets.getBucket(bucketKey);
        return bucket != null && bucket.containsKey(key);
    }

    public boolean isEmpty() {
        return buckets.isEmpty();
    }

    public void clear() {
        buckets.clear();
    }
}
