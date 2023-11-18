package bret.worldexporter.util;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.PixelGrabber;
import java.util.Arrays;

public class ImgUtils {
    @Nullable
    public static BufferedImage tintImage(BufferedImage image, int color) {
        if (color == -1 || image == null) return image;

        // https://forge.gemwire.uk/wiki/Tinted_Textures
        int[] imagePixels;
        try {
            imagePixels = getPixelData(image);
        } catch (InterruptedException e) {
            return image;
        }

        int aFactor = color >>> 24;
        int bFactor = ((color >>> 16) & 255);
        int gFactor = ((color >>> 8) & 255);
        int rFactor = color & 255;
        for (int i = 0; i < imagePixels.length; ++i) {
            int originalPixel = imagePixels[i];
            int newPixel = 0;
            newPixel |= ((int) ((float) ((originalPixel & 0xFF000000) >>> 24) * aFactor / 255)) << 24;
            newPixel |= ((int) ((float) ((originalPixel & 0xFF0000) >>> 16) * rFactor / 255)) << 16;
            newPixel |= ((int) ((float) ((originalPixel & 0xFF00) >>> 8) * gFactor / 255)) << 8;
            newPixel |= (int) ((float) (originalPixel & 0xFF) * bFactor / 255);
            imagePixels[i] = newPixel;
        }

        BufferedImage tintedImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        tintedImage.setRGB(0, 0, image.getWidth(), image.getHeight(), imagePixels, 0, image.getWidth());
        return tintedImage;
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
            average += (pixel & 0xFF000000) >>> 24;
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
