package bret.worldexporter.mixinsadditional;

import net.minecraft.world.chunk.Chunk;

public interface IMixinChunkArrayAccessor {
    public int worldexporter$addChunkCustom(int pX, int pZ, Chunk chunk);
    public void worldexporter$removeChunkCustom(int index);
    public void worldexporter$clear();
}
