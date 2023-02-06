package bret.worldexporter;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.math.Vec3i;

import javax.annotation.Nullable;
import javax.vecmath.Vector2f;
import javax.vecmath.Vector3f;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;

import static java.util.Arrays.copyOfRange;


public class Quad {
    private final Vector3f[] vertices  = new Vector3f[4];  // obj format for vertex: v x y z
    private final Vector2f[] uvpairs = new Vector2f[4];;  // obj format for texture coordinates: vt u v
    private static final String f = "#.#####";
    private static final DecimalFormat df = new DecimalFormat(f);


    public Quad(int[] vertexData, TextureAtlasSprite sprite, @Nullable Vec3i offsets) {
        df.setRoundingMode(RoundingMode.HALF_UP);
        float uDist = sprite.getMaxU() - sprite.getMinU();
        float vDist = sprite.getMaxV() - sprite.getMinV();

        for (int i = 0; i < 4; ++i) {
            vertices[i] = new Vector3f();
            uvpairs[i] = new Vector2f();

            int offset = i * 7;
            vertices[i].x = Float.intBitsToFloat(vertexData[offset]);
            vertices[i].y = Float.intBitsToFloat(vertexData[offset + 1]);
            vertices[i].z = Float.intBitsToFloat(vertexData[offset + 2]);
            if (offsets != null) {
                vertices[i].x += offsets.getX();
                vertices[i].y += offsets.getY();
                vertices[i].z += offsets.getZ();
            }

            uvpairs[i].x = (Float.intBitsToFloat(vertexData[offset + 4]) - sprite.getMinU()) / uDist;  // U
            uvpairs[i].y = 1 - (Float.intBitsToFloat(vertexData[offset + 5]) - sprite.getMinV()) / vDist;  // V
        }
    }

    static public ArrayList<Quad> getQuads(int[] vertexData, TextureAtlasSprite sprite, int vertexCount, @Nullable Vec3i offsets) {
        ArrayList<Quad> quadsList = new ArrayList<>();
        for (int i = 0; i < vertexCount / 4; ++i) {
            quadsList.add(new Quad(copyOfRange(vertexData, i * 28, i * 28 + 28), sprite, offsets));
        }
        return quadsList;
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
