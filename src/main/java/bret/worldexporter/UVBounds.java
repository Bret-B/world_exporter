package bret.worldexporter;

import java.util.Objects;

public class UVBounds {
    public float uMin;
    public float uMax;
    public float vMin;
    public float vMax;

    public UVBounds() {
    }

    public UVBounds(float uMin, float uMax, float vMin, float vMax) {
        this.uMin = uMin;
        this.uMax = uMax;
        this.vMin = vMin;
        this.vMax = vMax;
    }

    public UVBounds(UVBounds other) {
        this.uMin = other.uMin;
        this.uMax = other.uMax;
        this.vMin = other.vMin;
        this.vMax = other.vMax;
    }

    public UVBounds clamped() {
        UVBounds clampedBounds = new UVBounds();
        clampedBounds.uMin = Math.max(0.0F, this.uMin);
        clampedBounds.uMax = Math.min(1.0F, this.uMax);
        clampedBounds.vMin = Math.max(0.0F, this.vMin);
        clampedBounds.vMax = Math.min(1.0F, this.vMax);

        return clampedBounds;
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
