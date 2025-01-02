package bret.worldexporter.util.disk;

import bret.worldexporter.WorldExporter;
import bret.worldexporter.util.NotifyingLRUCache;
import it.unimi.dsi.fastutil.io.BinIO;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.function.Supplier;

class DiskBackedBuckets<BucketValue extends Serializable> {
    private final NotifyingLRUCache<Long, BucketValue> memoryBuckets;
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<String> diskBuckets = new Long2ObjectOpenHashMap<>();
    private final Supplier<BucketValue> bucketValueSupplier;
    private final Path directory;

    public DiskBackedBuckets(int bucketCacheSize, String baseCacheDir, Supplier<BucketValue> bucketValueSupplier) {
        final String cacheID = UUID.randomUUID().toString();
        memoryBuckets = new NotifyingLRUCache<>(bucketCacheSize, this::onMemoryRemove);
        directory = Paths.get(baseCacheDir, cacheID);
        this.bucketValueSupplier = bucketValueSupplier;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isEmpty() {
        return memoryBuckets.isEmpty() && diskBuckets.isEmpty();
    }

    public void clear() {
        memoryBuckets.clear();
        diskBuckets.clear();
        dirty.clear();
    }

    public BucketValue getBucket(long bucketKey) {
        return getBucket(bucketKey, false);
    }

    // returns the bucket associated with the provided bucket key, loading as necessary and creating if desired
    public BucketValue getBucket(long bucketKey, boolean createBucket) {
        BucketValue result;
        result = memoryBuckets.get(bucketKey);
        if (result != null) {
            return result;
        }

        if (diskBuckets.containsKey(bucketKey)) {
            // load the bucket from disk and move it to memory - not dirty yet
            try {
                //noinspection unchecked
                result = (BucketValue) BinIO.loadObject(new File(bucketPath(bucketKey).toString()));
            } catch (IOException | ClassNotFoundException | ClassCastException e) {
                throw new RuntimeException(e);
            }
            diskBuckets.remove(bucketKey);
            memoryBuckets.put(bucketKey, result);
        } else if (createBucket) {
            // create and register the bucket
            result = bucketValueSupplier.get();
            memoryBuckets.put(bucketKey, result);
            dirty.add(bucketKey);
        }

        return result;
    }

    public void removeBucket(long bucketKey) {
        memoryBuckets.remove(bucketKey);
        diskBuckets.remove(bucketKey);
        dirty.remove(bucketKey);
    }

    public void setDirty(long bucketKey) {
        dirty.add(bucketKey);
    }

    // called when the bucket is being removed from memory: save to disk if dirty
    private void onMemoryRemove(long bucketKey, BucketValue bucket) {
        String pathToBucket = bucketPath(bucketKey).toString();
        diskBuckets.put(bucketKey, pathToBucket);

        // skip the write if it hasn't changed
        if (dirty.contains(bucketKey)) {
            try {
                BinIO.storeObject(bucket, new File(pathToBucket));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            dirty.remove(bucketKey);
        }
    }

    private Path bucketPath(long bucketKey) {
        return Paths.get(directory.toString(), String.valueOf(bucketKey));
    }
}