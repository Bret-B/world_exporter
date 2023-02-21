package bret.worldexporter;

import org.lwjgl.util.vector.Matrix3f;
import org.lwjgl.util.vector.Vector3f;

public class VectorUtils {
    // https://stackoverflow.com/questions/9423621/3d-rotations-of-a-plane
    // https://en.wikipedia.org/wiki/Rotation_matrix#Axis_and_angle
    public static Matrix3f getRotationMatrix(Vector3f from, Vector3f to) {
        float c = Vector3f.dot(from, to) / from.length() / to.length();  // cosine of theta
        Vector3f a = Vector3f.cross(from, to, null).normalise(null);  // the axis
        a.x = a.x != a.x ? 0 : a.x;
        a.y = a.y != a.y ? 0 : a.y;
        a.z = a.z != a.z ? 0 : a.z;
        float s = (float) Math.sqrt(1 - c * c);
        float C = 1 - c;
        float x = a.x;
        float y = a.y;
        float z = a.z;

        Matrix3f rotationMatrix = new Matrix3f();
        rotationMatrix.m00 = x * x * C + c;
        rotationMatrix.m01 = x * y * C - z * s;
        rotationMatrix.m02 = x * z * C + y * s;
        rotationMatrix.m10 = y * x * C + z * s;
        rotationMatrix.m11 = y * y * C + c;
        rotationMatrix.m12 = y * z * C - x * s;
        rotationMatrix.m20 = z * x * C - y * s;
        rotationMatrix.m21 = z * y * C + x * s;
        rotationMatrix.m22 = z * z * C + c;
        return rotationMatrix;
    }
}
