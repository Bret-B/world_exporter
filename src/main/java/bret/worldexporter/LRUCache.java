package bret.worldexporter;

import java.util.LinkedHashMap;
import java.util.Map;

public class LRUCache<K, V> extends LinkedHashMap<K, V> {
    private final int maxEntries;

    public LRUCache(int cacheSize) {
        super(cacheSize, 0.75f, true);  // set accessOrder to false for FIFO cache
        this.maxEntries = cacheSize;
    }

    @Override
    protected boolean removeEldestEntry(final Map.Entry<K, V> eldest) {
        return super.size() > maxEntries;
    }
}
