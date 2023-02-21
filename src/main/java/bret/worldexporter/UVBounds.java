package bret.worldexporter;

import java.util.Objects;

public class UVBounds {
    public float uMin;
    public float uMax;
    public float vMin;
    public float vMax;

    public UVBounds() {
    }

    public UVBounds(UVBounds other) {
        this.uMin = other.uMin;
        this.uMax = other.uMax;
        this.vMin = other.vMin;
        this.vMax = other.vMax;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UVBounds uvBounds = (UVBounds) o;
        return Float.compare(uvBounds.uMin, uMin) == 0 &&
                Float.compare(uvBounds.uMax, uMax) == 0 &&
                Float.compare(uvBounds.vMin, vMin) == 0 &&
                Float.compare(uvBounds.vMax, vMax) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(uMin, uMax, vMin, vMax);
    }

    public float uDist() {
        return uMax - uMin;
    }

    public float vDist() {
        return vMax - vMin;
    }
}
