package bret.worldexporter;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.model.ModelBakery;
import net.minecraft.inventory.container.PlayerContainer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public class CustomImpl implements IRenderTypeBuffer {
    public final BufferBuilder buffer;
    public final Map<RenderType, BufferBuilder> fixedBuffers;
    protected final Set<BufferBuilder> startedBuffers = Sets.newHashSet();
    private final Map<RenderType, ResourceLocation> renderResourceLocationMap;
    protected Optional<RenderType> lastRenderType = Optional.empty();
    private Runnable fallbackBufferCallback;

    public CustomImpl(Map<RenderType, ResourceLocation> renderResourceLocationMapIn) {
        this.buffer = new BufferBuilder(2097152);
        RegionRenderCacheBuilder tempBuilder = new RegionRenderCacheBuilder();
        RegionRenderCacheBuilder tempBuilder2 = new RegionRenderCacheBuilder();  // FIXME: slightly hacky
        this.fixedBuffers = Util.make(new Object2ObjectLinkedOpenHashMap<>(), (p_228485_1_) -> {
            p_228485_1_.put(Atlases.getSolidBlockType(), tempBuilder.getBuilder(RenderType.getSolid()));
            p_228485_1_.put(Atlases.getCutoutBlockType(), tempBuilder.getBuilder(RenderType.getCutout()));
            p_228485_1_.put(Atlases.getBannerType(), tempBuilder.getBuilder(RenderType.getCutoutMipped()));
            p_228485_1_.put(Atlases.getTranslucentBlockType(), tempBuilder.getBuilder(RenderType.getTranslucent()));

            // Add basic renderTypes aliases
            p_228485_1_.put(RenderType.getSolid(), tempBuilder2.getBuilder(RenderType.getSolid()));
            p_228485_1_.put(RenderType.getCutout(), tempBuilder2.getBuilder(RenderType.getCutout()));
            p_228485_1_.put(RenderType.getCutoutMipped(), tempBuilder2.getBuilder(RenderType.getCutoutMipped()));
            p_228485_1_.put(RenderType.getTranslucent(), tempBuilder2.getBuilder(RenderType.getTranslucent()));

            put(p_228485_1_, Atlases.getShieldType());
            put(p_228485_1_, Atlases.getBedType());
            put(p_228485_1_, Atlases.getShulkerBoxType());
            put(p_228485_1_, Atlases.getSignType());
            put(p_228485_1_, Atlases.getChestType());
            put(p_228485_1_, RenderType.getTranslucentNoCrumbling());
            put(p_228485_1_, RenderType.getGlint());
            put(p_228485_1_, RenderType.getEntityGlint());
            put(p_228485_1_, RenderType.getWaterMask());
            ModelBakery.DESTROY_RENDER_TYPES.forEach((p_228488_1_) -> {
                put(p_228485_1_, p_228488_1_);
            });
        });
        this.renderResourceLocationMap = renderResourceLocationMapIn;
    }

    private static void put(Object2ObjectLinkedOpenHashMap<RenderType, BufferBuilder> mapBuildersIn, RenderType renderTypeIn) {
        mapBuildersIn.put(renderTypeIn, new BufferBuilder(renderTypeIn.getBufferSize()));
    }

    public BufferBuilder getBuffer(RenderType p_getBuffer_1_) {
        Optional<RenderType> lvt_2_1_ = p_getBuffer_1_.func_230169_u_();
        BufferBuilder lvt_3_1_ = this.getBufferRaw(p_getBuffer_1_);
        if (!Objects.equals(this.lastRenderType, lvt_2_1_)) {
            if (this.lastRenderType.isPresent()) {
                RenderType lvt_4_1_ = (RenderType) this.lastRenderType.get();
                if (!this.fixedBuffers.containsKey(lvt_4_1_)) {
                    this.finish(lvt_4_1_);
                }
            }

            if (this.startedBuffers.add(lvt_3_1_)) {
                lvt_3_1_.begin(p_getBuffer_1_.getDrawMode(), p_getBuffer_1_.getVertexFormat());
            }

            this.lastRenderType = lvt_2_1_;
        }

        return lvt_3_1_;
    }

    public void finish() {
        this.lastRenderType.ifPresent((p_228464_1_) -> {
            IVertexBuilder lvt_2_1_ = this.getBuffer(p_228464_1_);
            if (lvt_2_1_ == this.buffer) {
                this.finish(p_228464_1_);
            }

        });

        for (RenderType lvt_2_1_ : this.fixedBuffers.keySet()) {
            this.finish(lvt_2_1_);
        }
    }

    public void finish(RenderType p_228462_1_) {
        BufferBuilder lvt_2_1_ = this.getBufferRaw(p_228462_1_);
        boolean lvt_3_1_ = Objects.equals(this.lastRenderType, p_228462_1_.func_230169_u_());
        if (lvt_3_1_ || lvt_2_1_ != this.buffer) {
            if (this.startedBuffers.remove(lvt_2_1_)) {
//                p_228462_1_.finish(lvt_2_1_, 0, 0, 0);
                if (lvt_2_1_.isDrawing()) {
                    if (p_228462_1_ instanceof RenderType.Type) {
                        RenderType.Type renderTypeExtended = (RenderType.Type) p_228462_1_;
                        RenderType.State renderState = renderTypeExtended.renderState;
                        renderState.texture.texture.ifPresent(resourceLocation -> {
                            if (resourceLocation != PlayerContainer.LOCATION_BLOCKS_TEXTURE) {
                                renderResourceLocationMap.put(p_228462_1_, resourceLocation);
                            }
                        });
                    }

                    if (lvt_2_1_ == this.buffer) {
                        // If the buffer being finished is the fallback buffer, the data needs to be processed immediately before it is overwritten/finished
                        // The function should also call finishDrawing on the buffer before consuming the data
                        fallbackBufferCallback.run();
                    } else {
                        lvt_2_1_.finishDrawing();
                    }
                }

                if (lvt_3_1_) {
                    this.lastRenderType = Optional.empty();
                }
            }
        }
    }

    public void setFallbackBufferCallback(Runnable fallbackBufferCallback) {
        this.fallbackBufferCallback = fallbackBufferCallback;
    }

    public BufferBuilder getBufferRaw(RenderType p_228463_1_) {
        return (BufferBuilder) this.fixedBuffers.getOrDefault(p_228463_1_, this.buffer);
    }
}