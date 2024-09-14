package bret.worldexporter.util;


public class Pairing {
    // https://en.wikipedia.org/wiki/Pairing_function
//    public static long fromPair(int x, int y) {
//        return x < y ? ((long) y) * y + x : ((long) x) * x + x + y;
//    }

    // https://stackoverflow.com/questions/58979713/interleave-2-32-bit-integers-into-64-integer
    public static long fromPair(int a, int b) {
        return (spaceOut(a) << 1) | spaceOut(b);
    }

    public static long spaceOut(int a) {
        long x = a & 0x00000000FFFFFFFFL;
        x = (x | (x << 16)) & 0x0000FFFF0000FFFFL;
        x = (x | (x << 8)) & 0x00FF00FF00FF00FFL;
        x = (x | (x << 4)) & 0x0F0F0F0F0F0F0F0FL;
        x = (x | (x << 2)) & 0x3333333333333333L;
        x = (x | (x << 1)) & 0x5555555555555555L;
        return x;
    }
}
