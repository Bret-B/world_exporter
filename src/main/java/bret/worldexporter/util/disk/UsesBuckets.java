package bret.worldexporter.util.disk;

import java.io.Serializable;

public abstract class UsesBuckets<T extends Serializable> {
    DiskBackedBuckets<T> buckets;

    // Do not use this instance again after calling
    public void dispose() {
        if (buckets != null) buckets.dispose();
    }
}
