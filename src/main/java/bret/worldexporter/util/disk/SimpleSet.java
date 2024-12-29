package bret.worldexporter.util.disk;

public interface SimpleSet<T> {
    public boolean contains(T element);
    // returns true if the provided element was not already in the set
    public boolean add(T element);
    // returns true if the provided element was removed from the set
    public boolean remove(T element);
    public boolean isEmpty();
    public void clear();
}
