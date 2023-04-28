package bret.worldexporter;

public class NormalData {
    public final float[] height;  // 0 = 25% depth into the block, 1 = no depth
    public final float[] ao;  // ambient occlusion, 0 = 100%, 1 = 0%
    public final float[] x;  // normal x component - corresponds to r
    public final float[] y;  // normal y component - corresponds to g
    public final float[] z;  // normal z component - corresponds to b
    public final int cols_width;
    public final int rows_height;

    public NormalData(int cols_width, int rows_height) {
        this.cols_width = cols_width;
        this.rows_height = rows_height;
        int size = cols_width * rows_height;
        height = new float[size];
        ao = new float[size];
        x = new float[size];
        y = new float[size];
        z = new float[size];
    }
}
