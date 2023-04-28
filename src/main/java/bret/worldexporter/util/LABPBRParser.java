package bret.worldexporter.util;

import bret.worldexporter.NormalData;
import bret.worldexporter.SpecularData;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;

public class LABPBRParser {
    private static final int METAL_LOW = 230;
    private static final int METAL_HIGH = 255;
    private static final int NO_NORMAL = (127 << 16) | (127 << 8) | 181;  // 127, 127, 181 is the default rgb for no normal
    private static final int NO_HEIGHT = 0x00FFFFFF;
    private static final int NO_AO = 0x00FFFFFF;
    private static final int NO_METAL = 0;
    private static final int NO_ROUGHNESS = 0x00FFFFFF;
    private static final int NO_EMISSIVE = 0;

    // https://github.com/rre36/lab-pbr/wiki/Specular-Texture-Details
    @Nullable
    public static SpecularData parseSpecular(BufferedImage specularBase) {
        int[] specular;
        try {
            specular = ImgUtils.getPixelData(specularBase);
        } catch (InterruptedException e) {
            return null;
        }

        SpecularData sd = new SpecularData(specularBase.getWidth(), specularBase.getHeight());
        for (int i = 0; i < specular.length; ++i) {
            int pixel = specular[i];  // packed ARGB format
            int a = (pixel & 0xFF000000) >>> 24;  // must use >>> in order to avoid sign extending for a negative value
            int r = (pixel & 0x00FF0000) >> 16;
            int g = (pixel & 0x0000FF00) >> 8;
            int b = pixel & 0x000000FF;

            sd.emissiveness[i] = a == 255 ? 0.0f : a / 254.0f;  // 0-254 -> 0-100% emissiveness. 255 is 0%
            sd.roughness[i] = (float) Math.pow(1 - (r / 255.0f), 2);
            // not sure how to handle the custom preset metal values
            sd.metallic[i] = g / 255.0f;
            if (isMetal(g)) {
                sd.porosity[i] = 1.0f;
                sd.sss[i] = 0.0f;
            } else {
                if (b >= 65) {  // b represents a subsurface scattering value
                    sd.porosity[i] = 1.0f;
                    sd.sss[i] = (b - 65) / 190.0f;
                } else {  // b represents a porosity value
                    sd.porosity[i] = b / 64.0f;
                    sd.sss[i] = 0.0f;
                }
            }
        }
        return sd;
    }

    // https://github.com/rre36/lab-pbr/wiki/Normal-Texture-Details
    @Nullable
    public static NormalData parseNormal(BufferedImage normalBase) {
        int[] normal;
        try {
            normal = ImgUtils.getPixelData(normalBase);
        } catch (InterruptedException e) {
            return null;
        }

        NormalData nd = new NormalData(normalBase.getWidth(), normalBase.getHeight());
        for (int i = 0; i < normal.length; ++i) {
            int pixel = normal[i];  // packed ARGB format
            float r = ((pixel & 0x00FF0000) >> 16) / 255.0f;
            float g = ((pixel & 0x0000FF00) >> 8) / 255.0f;
            float b = (pixel & 0x000000FF) / 255.0f;
            nd.height[i] = ((pixel & 0xFF000000) >>> 24)  / 255.0f;  // height is stored in the alpha map
            nd.ao[i] = b;
            nd.x[i] = r;
            nd.y[i] = g;
            nd.z[i] = (float) Math.sqrt(1 - (r * r + g * g));
        }
        return nd;
    }

    public static boolean hasNonDefaultNormal(BufferedImage image) {
        return !allPixelsMatch(image, NO_NORMAL, 0x00FFFFFF);
    }

    public static BufferedImage getNormalImage(float[] normalX, float[] normalY, float[] normalZ, int width) {
        BufferedImage image = new BufferedImage(width, normalX.length / width, BufferedImage.TYPE_INT_RGB);
        int[] packedNormal = new int[normalX.length];
        for (int i = 0; i < packedNormal.length; ++i) {
            int pixel = clamp(Math.round(normalX[i] * 255.0f)) << 16;  // r
            pixel |= clamp(Math.round(normalY[i] * 255.0f)) << 8;      // g
            pixel |= clamp(Math.round(normalZ[i] * 255.0f));           // b
            packedNormal[i] = pixel;
        }

        image.setRGB(0, 0, width, normalX.length / width, packedNormal, 0, width);
        return image;
    }

    public static boolean hasHeight(BufferedImage image) {
        return !allPixelsMatch(image, NO_HEIGHT, 0x00FFFFFF);
    }

    public static BufferedImage getHeightmapImage(float[] heightData, int width) {
        return getGrayscaleBufferedImage(heightData, width);
    }

    public static boolean hasAO(BufferedImage image) {
        return !allPixelsMatch(image, NO_AO, 0x00FFFFFF);
    }

    public static BufferedImage getAOImage(float[] aoData, int width) {
        return getGrayscaleBufferedImage(aoData, width);
    }

    public static boolean hasMetal(BufferedImage image) {
        return !allPixelsMatch(image, NO_METAL, 0x00FFFFFF);
    }

    public static BufferedImage getMetalImage(float[] metalData, int width) {
        return getGrayscaleBufferedImage(metalData, width);
    }

    public static boolean hasRoughness(BufferedImage image) {
        return !allPixelsMatch(image, NO_ROUGHNESS, 0x00FFFFFF);
    }

    public static BufferedImage getRoughnessImage(float[] roughnessData, int width) {
        return getGrayscaleBufferedImage(roughnessData, width);
    }

    public static boolean hasEmissive(BufferedImage image) {
        return !allPixelsMatch(image, NO_EMISSIVE, 0x00FFFFFF);
    }

    public static BufferedImage getEmissiveImage(float[] emissiveData, int width) {
        return getGrayscaleBufferedImage(emissiveData, width);
    }

    private static BufferedImage getGrayscaleBufferedImage(float[] aoData, int width) {
//        BufferedImage image = new BufferedImage(width, aoData.length / width, BufferedImage.TYPE_BYTE_GRAY);
        BufferedImage image = new BufferedImage(width, aoData.length / width, BufferedImage.TYPE_INT_RGB);
        int[] grayscaleData = new int[aoData.length];
        for (int i = 0; i < aoData.length; ++i) {
            int grayValue = clamp(Math.round(aoData[i] * 255.0f));
            grayscaleData[i] = 0xFF000000 | (grayValue << 16) | (grayValue << 8) | grayValue;
        }
        image.setRGB(0, 0, width, aoData.length / width, grayscaleData, 0, width);
        return image;
    }

    private static boolean allPixelsMatch(BufferedImage image, int pixelValue, int mask) {
        int[] pixels;
        try {
            pixels = ImgUtils.getPixelData(image);
        } catch (InterruptedException e) {
            return false;
        }

        for (int pixel : pixels) {
            if ((pixel & mask) != pixelValue) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMetal(int value) {
        return value >= METAL_LOW && value <= METAL_HIGH;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
