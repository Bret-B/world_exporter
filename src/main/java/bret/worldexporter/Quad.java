package bret.worldexporter;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.util.math.BlockPos;

import javax.vecmath.Vector2f;
import javax.vecmath.Vector3f;
import java.math.RoundingMode;
import java.text.DecimalFormat;


public class Quad {
    private final Vector3f[] vertices  = new Vector3f[4];  // obj format for vertex: v x y z
    private final Vector2f[] uvpairs = new Vector2f[4];;  // obj format for texture coordinates: vt u v
    private static final String f = "#.#####";
    private static final DecimalFormat df = new DecimalFormat(f);

    public Quad(BakedQuad quadIn, BlockPos pos) {
        df.setRoundingMode(RoundingMode.HALF_UP);
        int[] vertexData = quadIn.getVertexData();
        float uDist = quadIn.getSprite().getMaxU() - quadIn.getSprite().getMinU();
        float vDist = quadIn.getSprite().getMaxV() - quadIn.getSprite().getMinV();
        for (int i = 0; i < 4; ++i) {
            vertices[i] = new Vector3f();
            uvpairs[i] = new Vector2f();

            int offset = i * 7;
            vertices[i].x = Float.intBitsToFloat(vertexData[offset]) + pos.getX();
            vertices[i].y = Float.intBitsToFloat(vertexData[offset + 1]) + pos.getY();
            vertices[i].z = Float.intBitsToFloat(vertexData[offset + 2]) + pos.getZ();

            uvpairs[i].x = (Float.intBitsToFloat(vertexData[offset + 4]) - quadIn.getSprite().getMinU()) / uDist;  // U
            uvpairs[i].y = 1 - (Float.intBitsToFloat(vertexData[offset + 5]) - quadIn.getSprite().getMinV()) / vDist;  // V
        }
    }

    // returns a String of relevant .obj file lines
    public String toObj(int verts, int uvs) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 4; ++i) {
            result.append("v ").append(df.format(vertices[i].x)).append(' ').append(df.format(vertices[i].y)).append(' ').append(df.format(vertices[i].z)).append('\n');
            result.append("vt ").append(df.format(uvpairs[i].x)).append(' ').append(df.format(uvpairs[i].y)).append('\n');
        }

        result.append("f ").append(verts).append('/').append(uvs).append(' ');
        result.append(verts + 1).append('/').append(uvs + 1).append(' ');
        result.append(verts + 2).append('/').append(uvs + 2).append(' ');
        result.append(verts + 3).append('/').append(uvs + 3).append("\n\n");

        return result.toString();
    }
}
