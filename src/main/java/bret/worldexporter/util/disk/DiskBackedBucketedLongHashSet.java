package bret.worldexporter.util.disk;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.nio.file.Paths;
import java.util.function.Function;

public class DiskBackedBucketedLongHashSet implements SimpleSet<Long> {
    private static final String SUBDIR = "longset";
    private final DiskBackedBuckets<LongOpenHashSet> buckets;
    private final Function<Long, Long> bucketFunction;

    public DiskBackedBucketedLongHashSet(int bucketCacheSize, String baseCacheDir, Function<Long, Long> bucketFunction) {
        buckets = new DiskBackedBuckets<>(
                bucketCacheSize,
                Paths.get(baseCacheDir, SUBDIR).toString(),
                LongOpenHashSet::new);
        this.bucketFunction = bucketFunction;
    }

    public boolean contains(long element) {
        long bucketKey = bucketFunction.apply(element);
        LongOpenHashSet bucket = buckets.getBucket(bucketKey);
        if (bucket == null) {
            return false;
        }

        return bucket.contains(element);
    }

    public boolean add(long element) {
        long bucketKey = bucketFunction.apply(element);
        LongOpenHashSet bucket = buckets.getBucket(bucketKey, true);
        if (bucket.add(element)) {
            buckets.setDirty(bucketKey);
            return true;
        }

        return false;
    }

    // returns true if the provided element was removed from the set
    public boolean remove(long element) {
        long bucketKey = bucketFunction.apply(element);
        LongOpenHashSet bucket = buckets.getBucket(bucketKey);
        if (bucket == null) {
            return false;
        }

        if (bucket.remove(element)) {
            buckets.setDirty(bucketKey);
            return true;
        }

        if (bucket.isEmpty()) {
            buckets.removeBucket(bucketKey);
        }

        return false;
    }

    public boolean contains(Long element) {
        return contains(element.longValue());
    }

    public boolean add(Long element) {
        return add(element.longValue());
    }

    public boolean remove(Long element) {
        return remove(element.longValue());
    }

    public boolean isEmpty() {
        return buckets.isEmpty();
    }

    public void clear() {
        buckets.clear();
    }
}
