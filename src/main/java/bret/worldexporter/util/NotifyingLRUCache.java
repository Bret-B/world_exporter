package bret.worldexporter.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public class NotifyingLRUCache<K, V> extends LinkedHashMap<K, V> {
    final BiConsumer<K, V> removeCallback;
    final int maxEntries;

    public NotifyingLRUCache(int cacheSize, BiConsumer<K, V> removeCallback) {
        super(cacheSize, 0.75f, true);  // set accessOrder to false for FIFO cache
        this.removeCallback = removeCallback;
        this.maxEntries = cacheSize;
    }

    @Override
    protected boolean removeEldestEntry(final Map.Entry<K, V> eldest) {
        boolean willRemove = super.size() > maxEntries;
        if (willRemove) {
            removeCallback.accept(eldest.getKey(), eldest.getValue());
        }
        return willRemove;
    }
}