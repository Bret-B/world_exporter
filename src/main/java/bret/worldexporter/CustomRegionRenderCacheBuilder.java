package bret.worldexporter;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.BlockRenderLayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.math.BigInteger;

@SideOnly(Side.CLIENT)
public class CustomRegionRenderCacheBuilder {
    private final BufferBuilder[] worldRenderers = new BufferBuilder[BlockRenderLayer.values().length];

    public CustomRegionRenderCacheBuilder(int radius) {
        // Try and estimate required allocation amounts in advance: diameter^2 * height
        BigInteger multiplier = BigInteger.valueOf((radius * 2L) * (radius * 2L) * 120L);

        BigInteger solidSize = multiplier.multiply(BigInteger.valueOf(16));
        int solidSizeInt = solidSize.compareTo(BigInteger.valueOf(Integer.MAX_VALUE / 4)) > 0 ? Integer.MAX_VALUE / 4 : solidSize.intValue();
        this.worldRenderers[BlockRenderLayer.SOLID.ordinal()] = new BufferBuilder(solidSizeInt);

        BigInteger cutoutSize = multiplier.multiply(BigInteger.valueOf(1));
        int cutoutSizeInt = cutoutSize.compareTo(BigInteger.valueOf(Integer.MAX_VALUE / 4)) > 0 ? Integer.MAX_VALUE / 4 : cutoutSize.intValue();
        this.worldRenderers[BlockRenderLayer.CUTOUT.ordinal()] = new BufferBuilder(cutoutSizeInt);

        BigInteger mippedSize = multiplier.multiply(BigInteger.valueOf(1));
        int mippedSizeInt = mippedSize.compareTo(BigInteger.valueOf(Integer.MAX_VALUE / 4)) > 0 ? Integer.MAX_VALUE / 4 : mippedSize.intValue();
        this.worldRenderers[BlockRenderLayer.CUTOUT_MIPPED.ordinal()] = new BufferBuilder(mippedSizeInt);

        BigInteger translucentSize = multiplier.multiply(BigInteger.valueOf(4));
        int translucentSizeInt = translucentSize.compareTo(BigInteger.valueOf(Integer.MAX_VALUE / 4)) > 0 ? Integer.MAX_VALUE / 4 : translucentSize.intValue();
        this.worldRenderers[BlockRenderLayer.TRANSLUCENT.ordinal()] = new BufferBuilder(translucentSizeInt);
    }

    public BufferBuilder getWorldRendererByLayer(BlockRenderLayer layer) {
        return this.worldRenderers[layer.ordinal()];
    }

    public BufferBuilder getWorldRendererByLayerId(int id) {
        return this.worldRenderers[id];
    }
}
