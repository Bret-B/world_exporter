package bret.worldexporter;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.model.ModelBakery;
import net.minecraft.util.Util;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;

@OnlyIn(Dist.CLIENT)
public class CustomImpl implements IRenderTypeBuffer {
    public final BufferBuilder builder;
    public final Map<RenderType, BufferBuilder> fixedBuffers;
    protected final Set<BufferBuilder> startedBuffers = Sets.newHashSet();
    private final Exporter exporter;
    private final ExporterRunnable thread;
    public Optional<RenderType> lastState = Optional.empty();
    // keeps track of vertexCounts for used buffers (updated when a new buffer is pulled from getBuffer)
    private Map<RenderType, Integer> typeUsedVertices;

    public CustomImpl(Exporter exporter, ExporterRunnable thread) {
        this.builder = new BufferBuilder(2097152);
        this.exporter = exporter;
        this.thread = thread;
        RegionRenderCacheBuilder basicBuilder = new RegionRenderCacheBuilder();
        RegionRenderCacheBuilder basicBuilder2 = new RegionRenderCacheBuilder();
        this.fixedBuffers = Util.make(new Object2ObjectLinkedOpenHashMap<>(), (map) -> {
            map.put(Atlases.solidBlockSheet(), basicBuilder.builder(RenderType.solid()));
            map.put(Atlases.cutoutBlockSheet(), basicBuilder.builder(RenderType.cutout()));
            map.put(Atlases.bannerSheet(), basicBuilder.builder(RenderType.cutoutMipped()));
            map.put(Atlases.translucentCullBlockSheet(), basicBuilder.builder(RenderType.translucent()));

            // Add basic renderTypes aliases
            map.put(RenderType.solid(), basicBuilder2.builder(RenderType.solid()));
            map.put(RenderType.cutout(), basicBuilder2.builder(RenderType.cutout()));
            map.put(RenderType.cutoutMipped(), basicBuilder2.builder(RenderType.cutoutMipped()));
            map.put(RenderType.translucent(), basicBuilder2.builder(RenderType.translucent()));

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
        typeUsedVertices = new HashMap<>(fixedBuffers.size());
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
                    thread.setLastFallbackInfo();
                }
            }

            this.lastState = optional;
        }

        // Update pre vertices count for previously uncounted buffer. Does nothing if it already has a count
        // fallback builder does not require counts to be updated since it uses the DrawState count
        if (bufferbuilder != this.builder) {
            typeUsedVertices.putIfAbsent(p_getBuffer_1_, bufferbuilder.vertices);
        }

        return bufferbuilder;
    }

    // returns map of a RenderType to the pre- and post-counts of vertices for that RenderType since the last call to this function
    public Map<RenderType, Pair<Integer, Integer>> getClearVerticesCounts() {
        Map<RenderType, Pair<Integer, Integer>> counts = new HashMap<>();
        for (RenderType type : typeUsedVertices.keySet()) {
            int preCount = typeUsedVertices.get(type);
            int count = getBuilderRaw(type).vertices - preCount;
            if (count == 0) continue;
            counts.put(type, Pair.of(preCount, count));
        }
        typeUsedVertices.clear();
        return counts;
    }

    public void clearVerticesCounts() {
        typeUsedVertices.clear();
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
                        renderState.textureState.texture.ifPresent(resourceLocation ->
                                thread.putResource(pRenderType, resourceLocation)
                        );
                    }

                    if (bufferbuilder == this.builder) {
                        // If the buffer being finished is the fallback buffer, the data needs to be processed immediately before it is overwritten/finished
                        // The callback function should call finishDrawing on the buffer before consuming the data
                        thread.fallbackBufferCallback();
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