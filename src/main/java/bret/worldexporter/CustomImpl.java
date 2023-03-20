package bret.worldexporter;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.model.ModelBakery;
import net.minecraft.inventory.container.PlayerContainer;
import net.minecraft.util.Util;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public class CustomImpl implements IRenderTypeBuffer {
    public final BufferBuilder builder;
    public final Map<RenderType, BufferBuilder> fixedBuffers;
    protected final Set<BufferBuilder> startedBuffers = Sets.newHashSet();
    private final Exporter exporter;
    public Optional<RenderType> lastState = Optional.empty();

    public CustomImpl(Exporter exporter) {
        this.builder = new BufferBuilder(2097152);
        RegionRenderCacheBuilder tempBuilder = new RegionRenderCacheBuilder();
        RegionRenderCacheBuilder tempBuilder2 = new RegionRenderCacheBuilder();  // FIXME: slightly hacky - requires some extra memory
        this.fixedBuffers = Util.make(new Object2ObjectLinkedOpenHashMap<>(), (map) -> {
            map.put(Atlases.solidBlockSheet(), tempBuilder.builder(RenderType.solid()));
            map.put(Atlases.cutoutBlockSheet(), tempBuilder.builder(RenderType.cutout()));
            map.put(Atlases.bannerSheet(), tempBuilder.builder(RenderType.cutoutMipped()));
            map.put(Atlases.translucentCullBlockSheet(), tempBuilder.builder(RenderType.translucent()));

            // Add basic renderTypes aliases
            map.put(RenderType.solid(), tempBuilder2.builder(RenderType.solid()));
            map.put(RenderType.cutout(), tempBuilder2.builder(RenderType.cutout()));
            map.put(RenderType.cutoutMipped(), tempBuilder2.builder(RenderType.cutoutMipped()));
            map.put(RenderType.translucent(), tempBuilder2.builder(RenderType.translucent()));

            put(map, Atlases.shieldSheet());
            put(map, Atlases.bedSheet());
            put(map, Atlases.shulkerBoxSheet());
            put(map, Atlases.signSheet());
            put(map, Atlases.chestSheet());
            put(map, RenderType.translucentNoCrumbling());
            put(map, RenderType.armorGlint());
            put(map, RenderType.armorEntityGlint());
            put(map, RenderType.glint());
            put(map, RenderType.glintDirect());
            put(map, RenderType.glintTranslucent());
            put(map, RenderType.entityGlint());
            put(map, RenderType.entityGlintDirect());
            put(map, RenderType.waterMask());
            ModelBakery.DESTROY_TYPES.forEach((type) -> {
                put(map, type);
            });
        });
        this.exporter = exporter;
    }

    private static void put(Object2ObjectLinkedOpenHashMap<RenderType, BufferBuilder> pMapBuilders, RenderType pRenderType) {
        pMapBuilders.put(pRenderType, new BufferBuilder(pRenderType.bufferSize()));
    }

    public BufferBuilder getBuffer(RenderType p_getBuffer_1_) {
        Optional<RenderType> optional = p_getBuffer_1_.asOptional();
        BufferBuilder bufferbuilder = this.getBuilderRaw(p_getBuffer_1_);
        if (!Objects.equals(this.lastState, optional)) {
            if (this.lastState.isPresent()) {
                RenderType rendertype = this.lastState.get();
                if (!this.fixedBuffers.containsKey(rendertype)) {
                    this.endBatch(rendertype);
                }
            }

            if (this.startedBuffers.add(bufferbuilder)) {
                bufferbuilder.begin(p_getBuffer_1_.mode(), p_getBuffer_1_.format());
                if (bufferbuilder == this.builder) {
                    exporter.setLastFallbackInfo();
                }
            }

            this.lastState = optional;
        }

        return bufferbuilder;
    }

    public BufferBuilder getBuilderRaw(RenderType pRenderType) {
        return this.fixedBuffers.getOrDefault(pRenderType, this.builder);
    }

    public void endBatch() {
        this.lastState.ifPresent((renderType) -> {
            IVertexBuilder ivertexbuilder = this.getBuffer(renderType);
            if (ivertexbuilder == this.builder) {
                this.endBatch(renderType);
            }
        });

        for (RenderType rendertype : this.fixedBuffers.keySet()) {
            this.endBatch(rendertype);
        }
    }

    public void endBatch(RenderType pRenderType) {
        BufferBuilder bufferbuilder = this.getBuilderRaw(pRenderType);
        boolean flag = Objects.equals(this.lastState, pRenderType.asOptional());
        if (flag || bufferbuilder != this.builder) {
            if (this.startedBuffers.remove(bufferbuilder)) {
                // replace typical RenderType.end() behavior
                // pRenderType.end(bufferbuilder, 0, 0, 0);

                if (bufferbuilder.building()) {
                    if (pRenderType instanceof RenderType.Type) {
                        RenderType.Type renderTypeExtended = (RenderType.Type) pRenderType;
                        RenderType.State renderState = renderTypeExtended.state;
                        renderState.textureState.texture.ifPresent(resourceLocation -> {
                            if (resourceLocation != PlayerContainer.BLOCK_ATLAS) {
                                exporter.renderResourceLocationMap.put(pRenderType, resourceLocation);
                            }
                        });
                    }

                    if (bufferbuilder == this.builder) {
                        // If the buffer being finished is the fallback buffer, the data needs to be processed immediately before it is overwritten/finished
                        // The callback function should call finishDrawing on the buffer before consuming the data
                        exporter.fallbackBufferCallback();
                    } else {
                        bufferbuilder.end();
                    }
                }

                if (flag) {
                    this.lastState = Optional.empty();
                }
            }
        }
    }
}