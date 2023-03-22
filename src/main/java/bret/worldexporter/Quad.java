package bret.worldexporter;

import bret.worldexporter.legacylwjgl.Matrix3f;
import bret.worldexporter.legacylwjgl.Vector2f;
import bret.worldexporter.legacylwjgl.Vector3f;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ResourceLocation;

import java.util.Arrays;
import java.util.Objects;

public class Quad {
    private static final float TOLERANCE = 0.001f;
    private static final Vector3f DESIRED_NORM = new Vector3f(0, 0, 1);
    private final Vertex[] vertices = new Vertex[4];
    private final RenderType type;
    private ResourceLocation resource;
    private int count = 0;
    private UVBounds uvBounds;
    private TextureAtlasSprite texture;

    public Quad(RenderType renderType, ResourceLocation resource) {
        this.type = renderType;
        this.resource = resource;
    }

    public Quad(Quad other) {
        for (int i = 0; i < other.count; ++i) {
            this.vertices[i] = new Vertex(other.vertices[i]);
        }
        this.count = other.count;
        this.type = other.type;
        this.uvBounds = new UVBounds(other.uvBounds);
        this.resource = other.resource;
        this.texture = other.texture;
    }

    public boolean overlaps(Quad second) {
        // rotate a copy of both quads in the plane such that their x and y coordinates can be compared
        // (such that they lie flat along a xy plane (the z coordinate will vary))
        Quad thisCopy = new Quad(this);
        Quad secondCopy = new Quad(second);

        Vector3f thisNorm = thisCopy.getNormal();
        Vector3f secondNorm = secondCopy.getNormal();

        Matrix3f rotateByThis = VectorUtils.getRotationMatrix(thisNorm, DESIRED_NORM);
        Matrix3f rotateBySecond = VectorUtils.getRotationMatrix(secondNorm, DESIRED_NORM);
        for (int i = 0; i < 4; ++i) {
            Vector3f vertPosThis = thisCopy.getVertices()[i].getPosition();
            Vector3f vertPosSecond = secondCopy.getVertices()[i].getPosition();

            Matrix3f.transform(rotateByThis, vertPosThis, vertPosThis);
            Matrix3f.transform(rotateBySecond, vertPosSecond, vertPosSecond);
        }

        Vector3f[] minMaxPos = thisCopy.minMaxPositions();
        Vector3f[] minMaxPosSecond = secondCopy.minMaxPositions();

        // if the planes are not close enough that they should be considered overlapping, return false
        if (Math.abs(minMaxPos[1].z - minMaxPosSecond[1].z) > TOLERANCE) {
            return false;
        }

        return (minMaxPos[1].x - minMaxPosSecond[0].x > TOLERANCE) && (minMaxPos[0].x - minMaxPosSecond[1].x < -TOLERANCE)
                && (minMaxPos[1].y - minMaxPosSecond[0].y > TOLERANCE) && (minMaxPos[0].y - minMaxPosSecond[1].y) < -TOLERANCE;
    }

    public Vector3f[] minMaxPositions() {
        validate();

        Vector3f min = new Vector3f(vertices[0].getPosition());
        Vector3f max = new Vector3f(vertices[0].getPosition());
        for (int i = 1; i < vertices.length; ++i) {
            Vector3f vertexPos = vertices[i].getPosition();
            if (vertexPos.x < min.x) min.x = vertexPos.x;
            if (vertexPos.y < min.y) min.y = vertexPos.y;
            if (vertexPos.z < min.z) min.z = vertexPos.z;
            if (vertexPos.x > max.x) max.x = vertexPos.x;
            if (vertexPos.y > max.y) max.y = vertexPos.y;
            if (vertexPos.z > max.z) max.z = vertexPos.z;
        }
        return new Vector3f[]{min, max};
    }

    public void addVertex(Vertex vertex) {
        if (count >= 4) {
            throw new IllegalStateException("Quad already has 4 vertices");
        }

        vertices[count++] = vertex;

        if (count == 4) {
            updateUvBounds();
        }
    }

    public Vertex[] getVertices() {
        return vertices;
    }

    public int getCount() {
        return count;
    }

    public UVBounds getUvBounds() {
        validate();

        return uvBounds;
    }

    // Returns the vertex color of the first vertex
    public int getColor() {
        validate();

        return vertices[0].getColor();
    }

    public ResourceLocation getResource() {
        return resource;
    }

    public void setResource(ResourceLocation resource) {
        this.resource = resource;
    }

    public TextureAtlasSprite getTexture() {
        return texture;
    }

    public void setTexture(TextureAtlasSprite texture) {
        this.texture = texture;
    }

    public RenderType getType() {
        return type;
    }

    public Vector3f getNormal() {
        validate();

        Vector3f direction = Vector3f.cross(
                Vector3f.sub(vertices[1].getPosition(), vertices[0].getPosition(), null),
                Vector3f.sub(vertices[2].getPosition(), vertices[0].getPosition(), null),
                null);
        return direction.normalise(null);
    }

    public void translate(Vector3f translate) {
        validate();

        for (Vertex vertex : vertices) {
            vertex.getPosition().translate(translate.x, translate.y, translate.z);
        }
    }

    public boolean isEquivalentTo(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Quad quad = (Quad) o;
        if (count != quad.count) return false;
        if (type != quad.type) return false;
        for (int i = 0; i < count; ++i) {
            boolean hasEquivalence = false;
            boolean hasUvEquivalence = false;
            Vertex v1 = vertices[i];
            for (int j = 0; j < quad.count; ++j) {
                if (v1.getPosition().equals(quad.vertices[j].getPosition())
                        && v1.getColor() == quad.vertices[j].getColor()
                        && v1.getUvlight().equals(quad.vertices[j].getUvlight())) {
                    hasEquivalence = true;
                }

                if (v1.getUv().equals(quad.vertices[j].getUv())) {
                    hasUvEquivalence = true;
                }
            }
            if (!hasEquivalence) return false;
            if (!hasUvEquivalence) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Quad quad = (Quad) o;
        return count == quad.count && type == quad.type && Arrays.equals(vertices, quad.vertices);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(count, type);
        result = 31 * result + Arrays.hashCode(vertices);
        return result;
    }

    public void updateUvBounds() {
        validate();

        uvBounds = new UVBounds();
        Vector2f uv = vertices[0].getUv();
        uvBounds.uMin = uv.x;
        uvBounds.uMax = uv.x;
        uvBounds.vMin = uv.y;
        uvBounds.vMax = uv.y;
        for (int i = 1; i < vertices.length; ++i) {
            uv = vertices[i].getUv();
            if (uv.x < uvBounds.uMin) uvBounds.uMin = uv.x;
            if (uv.x > uvBounds.uMax) uvBounds.uMax = uv.x;
            if (uv.y < uvBounds.vMin) uvBounds.vMin = uv.y;
            if (uv.y > uvBounds.vMax) uvBounds.vMax = uv.y;
        }
    }

    private void validate() {
        if (count < 4) {
            throw new IllegalStateException();
        }
    }
}
