package bret.worldexporter.util.disk;

public interface SimpleSet<T> {
    boolean contains(T element);
    // returns true if the provided element was not already in the set
    boolean add(T element);
    // returns true if the provided element was removed from the set
    boolean remove(T element);
//    public boolean isEmpty();
//    public void clear();

    void dispose();
}
