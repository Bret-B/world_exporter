package bret.worldexporter.util;


import net.minecraft.util.math.ChunkPos;

public class Pairing {
    public static long fromPair(int a, int b) {
        return ChunkPos.asLong(a, b);
    }
}
