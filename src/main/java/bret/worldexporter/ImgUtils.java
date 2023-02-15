package bret.worldexporter;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.PixelGrabber;
import java.awt.image.RescaleOp;

public class ImgUtils {
    public static BufferedImage tintImage(BufferedImage image, int color) {
        // https://forge.gemwire.uk/wiki/Tinted_Textures
        float[] offsets = new float[]{0, 0, 0, 0};
        float[] rgbaFactors = new float[4];
        rgbaFactors[0] = (color & 255) / 255.0f;
        rgbaFactors[1] = ((color >> 8) & 255) / 255.0f;
        rgbaFactors[2] = ((color >> 16) & 255) / 255.0f;
        rgbaFactors[3] = 1.0f;
        RenderingHints hints = new RenderingHints(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        hints.add(new RenderingHints(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE));
        return new RescaleOp(rgbaFactors, offsets, hints).filter(image, null);
    }

    public static boolean imageHasTransparency(BufferedImage image) throws InterruptedException {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[image.getWidth() * image.getHeight()];
        PixelGrabber pixelGrabber = new PixelGrabber(image, 0, 0, width, height, pixels, 0, width);
        pixelGrabber.grabPixels();
        for (int pixel : pixels) {
            if ((pixel & 0xFF000000) != 0xFF000000) {
                return true;
            }
        }
        return false;
    }
}
