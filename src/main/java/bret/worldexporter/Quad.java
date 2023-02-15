package bret.worldexporter;

import javax.vecmath.Vector2f;

public class Quad {

    private final Vertex[] vertices = new Vertex[4];
    private int count = 0;

    public Quad() {
    }

    public void addVertex(Vertex vertex) {
        if (count >= 4) {
            throw new IllegalStateException("Quad already has 4 vertices");
        }
        vertices[count++] = vertex;
    }

    public Vertex[] getVertices() {
        return vertices;
    }

    public int getCount() {
        return count;
    }

    public UVBounds getUvBounds() {
        if (count < 4) {
            throw new IllegalStateException("Quad does not yet have 4 vertices");
        }

        UVBounds bounds = new UVBounds();
        Vector2f uv = vertices[0].getUv();
        bounds.uMin = uv.x;
        bounds.uMax = uv.x;
        bounds.vMin = uv.y;
        bounds.vMax = uv.y;
        for (int i = 1; i < 4; ++i) {
            uv = vertices[i].getUv();
            if (uv.x < bounds.uMin) {
                bounds.uMin = uv.x;
            }
            if (uv.x > bounds.uMax) {
                bounds.uMax = uv.x;
            }
            if (uv.y < bounds.vMin) {
                bounds.vMin = uv.y;
            }
            if (uv.y > bounds.vMax) {
                bounds.vMax = uv.y;
            }
        }
        return bounds;
    }

    // Returns the vertex color of the first vertex
    public int getColor() {
        if (count < 4) {
            throw new IllegalStateException();
        }

//        int redAvg = 0;
//        int greenAvg = 0;
//        int blueAvg = 0;
//        for (int i = 0; i < 4; ++i) {
//            int color = vertices[i].getColor();
//            redAvg += (color >> 16) & 255;
//            greenAvg += (color >> 8) & 255;
//            blueAvg += color & 255;
//        }
//        redAvg /= 4;
//        greenAvg /= 4;
//        blueAvg /= 4;
//
//        int colorResult = 0xFF000000;  // Alpha component always 255
//        colorResult |= (redAvg << 16) & 0x00FF0000;
//        colorResult |= (greenAvg << 8) & 0x0000FF00;
//        colorResult |= blueAvg & 0x000000FF;
//         -16777216 | blue << 16 | green << 8 | red
//
//        return colorResult;
        return vertices[0].getColor();
    }
}
