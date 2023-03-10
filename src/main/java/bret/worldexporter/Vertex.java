package bret.worldexporter;

import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;

import java.util.Objects;

public class Vertex {
    private Vector3f position;
    private Vector2f uv;
    private Vector2f uvlight;
    private int color = -1;

    public Vertex() {
    }

    public Vertex(Vertex other) {
        this.position = new Vector3f(other.position);
        this.uv = new Vector2f(other.uv);
        this.uvlight = new Vector2f(other.uvlight);
        this.color = other.color;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Vertex vertex = (Vertex) o;
        return color == vertex.color && position.equals(vertex.position) && uv.equals(vertex.uv) && uvlight.equals(vertex.uvlight);
    }

    @Override
    public int hashCode() {
        return Objects.hash(position, uv, uvlight, color);
    }
}
