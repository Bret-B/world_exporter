package bret.worldexporter.util;

import bret.worldexporter.NormalData;
import bret.worldexporter.SpecularData;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;

public class LABPBRParser {
    private static final int METAL_LOW = 230;
    private static final int METAL_HIGH = 255;
    private static final int NO_NORMAL = (127 << 16) | (127 << 8) | 255;  // 127, 127, 255 is the default RGB for no normal
    private static final int NO_HEIGHT = 0x000000FF;  // grayscale
    private static final int NO_AO = 0x000000FF;  // grayscale
    private static final int NO_METAL = 0;
    private static final int NO_ROUGHNESS = 0x000000FF;  // grayscale
    private static final int NO_EMISSIVE = 0;
    private static final int DEFAULT_NORMAL_PIX = 0xFF000000 | NO_NORMAL;  // packed ARGB; -8421377
    private static final int DEFAULT_SPECULAR_PIX = 0;  // packed ARGB

    // https://github.com/rre36/lab-pbr/wiki/Specular-Texture-Details
    // If the entire image has no meaningful data, return null
    @Nullable
    public static SpecularData parseSpecular(BufferedImage specularBase, boolean perceptualRoughness) {
        int[] specular;
        try {
            specular = ImgUtils.getPixelData(specularBase);
        } catch (InterruptedException e) {
            return null;
        }

        if (allPixelsMatch(specular, DEFAULT_SPECULAR_PIX, 0xFFFFFFFF)) {
            return null;
        }

        SpecularData sd = new SpecularData(specularBase.getWidth(), specularBase.getHeight());
        for (int i = 0; i < specular.length; ++i) {
            int pixel = specular[i];  // packed ARGB format
            int a = (pixel & 0xFF000000) >>> 24;  // must use >>> in order to avoid sign extending for a negative value
            int r = (pixel & 0x00FF0000) >>> 16;
            int g = (pixel & 0x0000FF00) >>> 8;
            int b = pixel & 0x000000FF;

            sd.emissiveness[i] = a == 255 ? NO_EMISSIVE : a / 254.0f;  // 0-254 -> 0-100% emissiveness. 255 is 0%
            sd.roughness[i] = perceptualRoughness ? 1.0f - (r / 255.0f) : (float) Math.pow(1 - (r / 255.0f), 2);
            // not sure how to handle the custom preset metal values
            // currently, treat metal values >= 230 as 100% metal (as done by shaders without custom metal support)
            if (isMetal(g)) {
                sd.metallic[i] = 1.0f;
                sd.porosity[i] = 1.0f;
                sd.sss[i] = 0.0f;
            } else {
                sd.metallic[i] = g / 255.0f;
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
    // If the entire image has no meaningful data, return null
    @Nullable
    public static NormalData parseNormal(BufferedImage normalBase, boolean outputOpenGLNormals) {
        int[] normal;
        try {
            normal = ImgUtils.getPixelData(normalBase);
        } catch (InterruptedException e) {
            return null;
        }

        if (allPixelsMatch(normal, DEFAULT_NORMAL_PIX, 0xFFFFFFFF)) {
            return null;
        }

        NormalData nd = new NormalData(normalBase.getWidth(), normalBase.getHeight());
        for (int i = 0; i < normal.length; ++i) {
            int pixel = normal[i];  // packed ARGB format
            nd.height[i] = ((pixel & 0xFF000000) >>> 24) / 255.0f;  // height is stored in the alpha map
            float r = ((pixel & 0x00FF0000) >>> 16) / 255.0f;
            float g = ((pixel & 0x0000FF00) >>> 8) / 255.0f;
            float b = (pixel & 0x000000FF) / 255.0f;
            float r2 = r * 2 - 1;  // r [0, 1] converted to [-1, 1]
            float g2 = g * 2 - 1;  // g [0, 1] converted to [-1, 1]
            nd.ao[i] = b;
            float r2Sqr = r2 * r2;
            float g2Sqr = g2 * g2;
            // the Z value may be "imaginary" (though always positive due to abs) if r2Sqr + g2Sqr > 1.
            // the x, y, z values are then normalized by the magnitude using this potentially imaginary Z component
            float imaginaryZ = (float) Math.sqrt(Math.abs(1 - (r2Sqr + g2Sqr)));
            float magnitude = (float) Math.sqrt(r2Sqr + g2Sqr + imaginaryZ * imaginaryZ);
            r2 /= magnitude;
            g2 /= magnitude;
            // convert x, y, z back to [0, 1] and save
            nd.x[i] = (r2 + 1.0f) / 2.0f;
            float y = (g2 + 1.0f) / 2.0f;
            nd.y[i] = outputOpenGLNormals ? y : 1.0f - y;
            // since in actual usage the z value is in the range [0, -1], it is stored in the image in the range [128, 255]
            // the real Z value, mapped from [0, 1] to [.5, 1]
            nd.z[i] = (((float) Math.sqrt(Math.abs(clamp(1 - (r2 * r2 + g2 * g2))))) + 1.0f) / 2.0f;
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
        return !allPixelsMatch(image, NO_HEIGHT, 0x00000FF);
    }

    public static BufferedImage getHeightmapImage(float[] heightData, int width) {
        return getGrayscaleBufferedImage(heightData, width);
    }

    public static boolean hasAO(BufferedImage image) {
        return !allPixelsMatch(image, NO_AO, 0x000000FF);
    }

    public static BufferedImage getAOImage(float[] aoData, int width) {
        return getGrayscaleBufferedImage(aoData, width);
    }

    public static boolean hasMetal(BufferedImage image) {
        return !allPixelsMatch(image, NO_METAL, 0x000000FF);
    }

    public static BufferedImage getMetalImage(float[] metalData, int width) {
        return getGrayscaleBufferedImage(metalData, width);
    }

    public static boolean hasRoughness(BufferedImage image) {
        return !allPixelsMatch(image, NO_ROUGHNESS, 0x000000FF);
    }

    public static BufferedImage getRoughnessImage(float[] roughnessData, int width) {
        return getGrayscaleBufferedImage(roughnessData, width);
    }

    public static boolean hasEmissiveGrayscale(BufferedImage image) {
        return !allPixelsMatch(image, NO_EMISSIVE, 0x000000FF);
    }

    public static boolean hasEmissive(float[] data) {
        for (float v : data) {
            if (v != NO_EMISSIVE) {
                return true;
            }
        }
        return false;
    }

    // input data values are effectively clamped into the range [0, 1], resulting image is grayscale: TYPE_BYTE_GRAY
    private static BufferedImage getGrayscaleBufferedImage(float[] data, int width) {
        BufferedImage image = new BufferedImage(width, data.length / width, BufferedImage.TYPE_BYTE_GRAY);
        int[] grayscaleData = new int[data.length];
        for (int i = 0; i < data.length; ++i) {
            int grayValue = clamp(Math.round(data[i] * 255.0f));
            grayscaleData[i] = (byte) grayValue;
        }
        image.getRaster().setPixels(0, 0, width, data.length / width, grayscaleData);
        return image;
    }

    private static boolean allPixelsMatch(BufferedImage image, int pixelValue, int mask) {
        int[] pixels;
        try {
            pixels = ImgUtils.getPixelData(image);
        } catch (InterruptedException e) {
            return false;
        }

        return allPixelsMatch(pixels, pixelValue, mask);
    }

    private static boolean allPixelsMatch(int[] data, int pixelValue, int mask) {
        for (int pixel : data) {
            if ((pixel & mask) != pixelValue) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMetal(int value) {
        return value >= METAL_LOW && value <= METAL_HIGH;
    }

    public static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    public static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
