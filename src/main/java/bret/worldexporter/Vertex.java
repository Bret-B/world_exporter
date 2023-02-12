package bret.worldexporter;

import javax.vecmath.Vector2f;
import javax.vecmath.Vector3f;

public class Vertex {
    private Vector3f position;
    private Vector2f uv;
    private Vector2f uvlight;
    private int color = -1;

    public Vertex() {
    }

    public void setPosition(Vector3f position) {
        this.position = position;
    }

    public void setUv(Vector2f uv) {
        this.uv = uv;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public void setUvlight(Vector2f uvlight) {
        this.uvlight = uvlight;
    }

    public Vector3f getPosition() {
        return position;
    }

    public Vector2f getUv() {
        return uv;
    }

    public int getColor() {
        return color;
    }

    public Vector2f getUvlight() {
        return uvlight;
    }

    public boolean hasUv() {
        return uv != null;
    }

    public boolean hasColor() {
        return color != -1;
    }

    public boolean hasUvlight() {
        return uvlight != null;
    }
}
