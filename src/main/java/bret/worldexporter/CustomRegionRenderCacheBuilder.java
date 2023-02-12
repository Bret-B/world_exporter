package bret.worldexporter;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.BlockRenderLayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class CustomRegionRenderCacheBuilder
{
    private final BufferBuilder[] worldRenderers = new BufferBuilder[BlockRenderLayer.values().length];

    public CustomRegionRenderCacheBuilder(int radius)
    {
        // Try and estimate required allocation amounts in advance: diameter^2 * height
        int multiplier = (radius * 2) * (radius * 2) * 120;
        this.worldRenderers[BlockRenderLayer.SOLID.ordinal()] = new BufferBuilder(16 * multiplier);
        this.worldRenderers[BlockRenderLayer.CUTOUT.ordinal()] = new BufferBuilder(multiplier);
        this.worldRenderers[BlockRenderLayer.CUTOUT_MIPPED.ordinal()] = new BufferBuilder(multiplier);
        this.worldRenderers[BlockRenderLayer.TRANSLUCENT.ordinal()] = new BufferBuilder(2 * multiplier);
    }

    public BufferBuilder getWorldRendererByLayer(BlockRenderLayer layer)
    {
        return this.worldRenderers[layer.ordinal()];
    }

    public BufferBuilder getWorldRendererByLayerId(int id)
    {
        return this.worldRenderers[id];
    }
}
