package bret.worldexporter;

import bret.worldexporter.legacylwjgl.Vector2f;
import bret.worldexporter.legacylwjgl.Vector3f;

import java.util.Objects;

public class Vertex {
    private Vector3f position;
    private Vector2f uv;
    private int color = -1;

    public Vertex() {
    }

    public Vertex(Vertex other) {
        this.position = other.position == null ? null : new Vector3f(other.position);
        this.uv = other.uv == null ? null : new Vector2f(other.uv);
        this.color = other.color;
    }

    public Vector3f getPosition() {
        return position;
    }

    public void setPosition(Vector3f position) {
        this.position = position;
    }

    public void setPosition(float x, float y, float z) {
        position.x = x;
        position.y = y;
        position.z = z;
    }

    public Vector2f getUv() {
        return uv;
    }

    public void setUv(Vector2f uv) {
        this.uv = uv;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public boolean hasUv() {
        return uv != null;
    }

    public boolean hasColor() {
        return color != -1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Vertex vertex = (Vertex) o;
        return color == vertex.color && position.equals(vertex.position) && uv.equals(vertex.uv);
    }

    @Override
    public int hashCode() {
        return Objects.hash(position, uv, color);
    }
}
