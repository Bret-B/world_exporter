package bret.worldexporter.util;

import javax.annotation.Nullable;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.PixelGrabber;
import java.awt.image.RescaleOp;
import java.util.Arrays;

public class ImgUtils {
    @Nullable
    public static BufferedImage tintImage(BufferedImage image, int color) {
        if (color == -1 || image == null) return image;

        // https://forge.gemwire.uk/wiki/Tinted_Textures
        float[] offsets = new float[]{0, 0, 0, 0};
        float[] rgbaFactors = new float[4];
        rgbaFactors[0] = (color & 255) / 255.0f;
        rgbaFactors[1] = ((color >> 8) & 255) / 255.0f;
        rgbaFactors[2] = ((color >> 16) & 255) / 255.0f;
        rgbaFactors[3] = ((color >> 24) & 255) / 255.0f;
        RenderingHints hints = new RenderingHints(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        hints.add(new RenderingHints(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE));
        return new RescaleOp(rgbaFactors, offsets, hints).filter(image, null);
    }

    // Returns the provided image with alpha values multiplied by the [0, 1] values in transparencyData, per-pixel.
    // The first image should have format BufferedImage.TYPE_INT_ARGB;
    @Nullable
    public static BufferedImage applyTransparency(BufferedImage image, float[] transparencyData) {
        if (image.getType() != BufferedImage.TYPE_INT_ARGB) {
            throw new RuntimeException("mergeTransparency requires the first argument to be an image of TYPE_INT_ARGB");
        }
        if (image.getWidth() * image.getHeight() != transparencyData.length) {
            throw new RuntimeException("mergeTransparency requires pixel count of image to equal length of transparencyData");
        }

        int[] imagePixels;
        try {
            imagePixels = getPixelData(image);
        } catch (InterruptedException e) {
            return null;
        }

        int[] merged = new int[imagePixels.length];
        for (int i = 0; i < imagePixels.length; ++i) {
            int newAlpha = (imagePixels[i] & 0xFF000000) >>> 24;
            newAlpha = Math.max(0, Math.min(255, Math.round(newAlpha * transparencyData[i])));
            merged[i] = (newAlpha << 24) | (imagePixels[i] & 0x00FFFFFF);
        }

        BufferedImage mergedImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        mergedImage.setRGB(0, 0, image.getWidth(), image.getHeight(), merged, 0, image.getWidth());
        return mergedImage;
    }

    public static boolean imageHasTransparency(BufferedImage image) {
        int[] pixels;
        try {
            pixels = getPixelData(image);
        } catch (InterruptedException e) {
            return true;
        }
        for (int pixel : pixels) {
            if ((pixel & 0xFF000000) != 0xFF000000) {
                return true;
            }
        }
        return false;
    }

    public static int countTransparentPixels(BufferedImage image) {
        int count = 0;
        int[] pixels;
        try {
            pixels = getPixelData(image);
        } catch (InterruptedException interruptedException) {
            return 0;
        }

        for (int pixel : pixels) {
            if ((pixel & 0xFF000000) != 0xFF000000) {
                ++count;
            }
        }
        return count;
    }

    public static float averageTransparencyValue(BufferedImage image) {
        if (image == null) return 0;

        float average = 0;
        int[] pixels;
        try {
            pixels = getPixelData(image);
        } catch (InterruptedException interruptedException) {
            return 0;
        }

        for (int pixel : pixels) {
            average += (pixel & 0xFF000000) >> 24;
        }
        return average / (image.getWidth() * image.getHeight());
    }

    public static boolean isCompletelyTransparent(BufferedImage image) {
        int[] pixels;
        try {
            pixels = getPixelData(image);
        } catch (InterruptedException interruptedException) {
            return false;
        }

        for (int pixel : pixels) {
            if ((pixel & 0xFF000000) != 0) {
                return false;
            }
        }
        return true;
    }

    public static BufferedImage newImageWithFormat(BufferedImage image) {
        if (image.getColorModel() instanceof IndexColorModel) {
            return new BufferedImage(image.getWidth(), image.getHeight(), image.getType(), (IndexColorModel) image.getColorModel());
        } else {
            return new BufferedImage(image.getWidth(), image.getHeight(), image.getType());
        }
    }

    public static int[] getPixelData(BufferedImage image) throws InterruptedException {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[image.getWidth() * image.getHeight()];
        PixelGrabber pixelGrabber = new PixelGrabber(image, 0, 0, width, height, pixels, 0, width);
        pixelGrabber.grabPixels();
        return pixels;
    }

    // Returns true if two images have equivalent data
    public static boolean compareImages(BufferedImage first, BufferedImage second) {
        if (first == second) {
            return true;
        }
        if ((first == null) != (second == null)) {
            return false;
        }
        if ((first.getWidth() != second.getWidth()) || (first.getHeight() != second.getHeight())) {
            return false;
        }
        if (first.getColorModel() != second.getColorModel()) {
            return false;
        }

        int[] firstData;
        int[] secondData;
        try {
            firstData = getPixelData(first);
            secondData = getPixelData(second);
        } catch (InterruptedException e) {
            return false;
        }

        return Arrays.equals(firstData, secondData);
    }
}
