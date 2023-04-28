package bret.worldexporter;

public class SpecularData {
    public float[] emissiveness;  // 0-1 value representing % emissiveness
    public float[] roughness;  // converted from perceptual smoothness
    public float[] metallic;  // "reflectance", f0
    public float[] porosity;  // 0-1, 0 is not porous at all, 1 is the most porous
    public float[] sss;  // subsurface scattering, 0-1
    public final int cols_width;
    public final int rows_height;

    public SpecularData(int cols_width, int rows_height) {
        this.cols_width = cols_width;
        this.rows_height = rows_height;
        int size = cols_width * rows_height;
        emissiveness = new float[size];
        roughness = new float[size];
        metallic = new float[size];
        porosity = new float[size];
        sss = new float[size];
    }
}
