package bret.worldexporter.util.disk;

public class BucketFunctions {
    private static final long RING_WIDTH = 128L;

    public static long xzLocalityBucket(int x, int z, int sqrtChunksPerBucket) {
        long result = (long)((z >> 4) / sqrtChunksPerBucket) & 0xFFFFFFFFL;
        return result | (((long)((x >> 4) / sqrtChunksPerBucket) & 0xFFFFFFFFL) << 32);
    }

    public static long centerBasedRingBucket(int centerX, int centerZ, int x, int z, int chunksPerBucket) {
        long absX = Math.abs(centerX - x);
        long absZ = Math.abs(centerZ - z);
        long maxCardinalFromCenter = Math.max(absX, absZ);
        long ringsFromCenter = maxCardinalFromCenter / RING_WIDTH;
        long ringArea = squareHollowRingArea(ringsFromCenter);
        long bucketsInRing = ringArea / chunksPerBucket / 256L;
        long bucketsPerSide = Math.max(1, bucketsInRing / 4);
        long increment = Math.max(1, ringsFromCenter / bucketsPerSide);
        long xHash = (2 * absX) / increment;
        long zHash = (2 * absZ) / increment;
        return ((0xFFFFFL & xHash) << 40) | ((0xFFFFFL & zHash) << 20) | (0xFFFFFL & ringsFromCenter);
    }

    public static long squareHollowRingArea(long numRingsFromCenter) {
        long wholeArea = RING_WIDTH * (numRingsFromCenter + 1) * 2;
        long hollowArea = RING_WIDTH * numRingsFromCenter * 2;
        wholeArea *= wholeArea;
        hollowArea *= hollowArea;
        return wholeArea - hollowArea;
    }
}
