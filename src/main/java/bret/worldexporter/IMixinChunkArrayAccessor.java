package bret.worldexporter;

import net.minecraft.world.chunk.Chunk;

public interface IMixinChunkArrayAccessor {
    public int worldexporter$createChunkIndex(int pX, int pZ);
    public void worldexporter$addChunk(int index, Chunk chunk);
    public void worldexporter$removeChunkCustom(int pX, int pZ);
    public void worldexporter$removeChunkCustom(int index);
    public void worldexporter$clear();
}
