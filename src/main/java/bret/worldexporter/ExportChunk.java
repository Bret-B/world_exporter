package bret.worldexporter;

import java.util.ArrayList;

public class ExportChunk {
    public final ArrayList<Quad> quads;
    public final int xChunkPos;
    public final int zChunkPos;

    public ExportChunk(ArrayList<Quad> quads, int xChunkPos, int zChunkPos) {
        this.quads = quads;
        this.xChunkPos = xChunkPos;
        this.zChunkPos = zChunkPos;
    }
}
