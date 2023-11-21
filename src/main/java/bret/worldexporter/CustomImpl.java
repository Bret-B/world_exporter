package bret.worldexporter;

import bret.worldexporter.util.RenderTypeFinder;
import com.google.common.collect.Sets;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.model.ModelBakery;
import net.minecraft.util.Util;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import java.util.*;

import static bret.worldexporter.Exporter.LOGGER;

@OnlyIn(Dist.CLIENT)
public class CustomImpl implements IRenderTypeBuffer {
    public final BufferBuilder builder;
    public final Map<RenderType, BufferBuilder> fixedBuffers;
    protected final Set<BufferBuilder> startedBuffers = Sets.newHashSet();
    private final ExporterRunnable thread;
    // keeps track of vertexCounts for used buffers (updated when a new buffer is pulled from getBuffer)
    private final Map<RenderType, Integer> typeUsedVertices;
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public Optional<RenderType> lastState = Optional.empty();

    public CustomImpl(ExporterRunnable thread) {
        this.builder = new BufferBuilder(2097152);
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

    @Nonnull
    public BufferBuilder getBuffer(RenderType p_getBuffer_1_) {
        Optional<RenderType> optional = p_getBuffer_1_.asOptional();
        BufferBuilder bufferbuilder = this.getBuilderRaw(p_getBuffer_1_);
        if (!Objects.equals(this.lastState, optional)) {
            if (this.lastState.isPresent()) {
                // If the last state was using the fallback buffer and this getBuffer call is not using the
                // fallback buffer with the same render type, end the fallback buffer
                RenderType rendertype = this.lastState.get();
                if (!this.fixedBuffers.containsKey(rendertype)) {
                    this.endBatch(rendertype);
                }
            }

            if (this.startedBuffers.add(bufferbuilder)) {
                bufferbuilder.begin(p_getBuffer_1_.mode(), p_getBuffer_1_.format());
                if (bufferbuilder == this.builder) {
                    this.thread.setLastFallbackInfo();
                }
            }

            this.lastState = optional;
        }

        // Update pre vertices count for previously uncounted buffer. Does nothing if it already has a count
        // fallback builder does not require counts to be updated since it uses the DrawState count
        if (bufferbuilder != this.builder) {
            this.typeUsedVertices.putIfAbsent(p_getBuffer_1_, bufferbuilder.vertices);
        }

        return bufferbuilder;
    }

    // returns map of a RenderType to the pre- and post-counts of vertices for that RenderType since the last call to this function
    public Map<RenderType, Pair<Integer, Integer>> getClearVerticesCounts() {
        Map<RenderType, Pair<Integer, Integer>> counts = new HashMap<>();
        for (RenderType type : this.typeUsedVertices.keySet()) {
            int preCount = this.typeUsedVertices.get(type);
            int count = getBuilderRaw(type).vertices - preCount;
            if (count == 0) continue;
            counts.put(type, Pair.of(preCount, count));
        }
        this.typeUsedVertices.clear();
        return counts;
    }

    public void clearVerticesCounts() {
        this.typeUsedVertices.clear();
    }

    public BufferBuilder getBuilderRaw(RenderType pRenderType) {
        return this.fixedBuffers.getOrDefault(pRenderType, this.builder);
    }

    public void endBatch() {
        this.lastState.ifPresent((renderType) -> {
            IVertexBuilder ivertexbuilder = this.getBuffer(renderType);
            if (ivertexbuilder == this.builder) {
                this.endBatch(renderType);  // end the fallback buffer if needed
            }
        });

        this.fixedBuffers.keySet().forEach(this::endBatch);  // end all fixed buffers
    }

    public void endBatch(RenderType pRenderType) {
        BufferBuilder bufferbuilder = this.getBuilderRaw(pRenderType);
        boolean endingLastUsedType = Objects.equals(this.lastState, pRenderType == null ? Optional.empty() : pRenderType.asOptional());
        if (endingLastUsedType || bufferbuilder != this.builder) {
            if (this.startedBuffers.remove(bufferbuilder)) {
                // replace typical RenderType.end() behavior
                // pRenderType.end(bufferbuilder, 0, 0, 0);

                if (bufferbuilder.building()) {
                    RenderType.Type renderTypeExtended;
                    if (pRenderType instanceof RenderType.Type) {
                        renderTypeExtended = (RenderType.Type) pRenderType;
                    } else {
                        // some modded subclasses of RenderType include their RenderType.Type in another field
                        // (or potentially not at all), so try to find it in some nested field
                        renderTypeExtended = RenderTypeFinder.findNestedType(pRenderType, 4);
                    }

                    if (renderTypeExtended != null) {
                        RenderType.State renderState = renderTypeExtended.state;
                        renderState.textureState.texture.ifPresent(resourceLocation ->
                                this.thread.putResource(pRenderType, resourceLocation)
                        );
                    } else {
                        LOGGER.warn("Could not find associated texture ResourceLocation for RenderType class: " +
                                (pRenderType == null ? "null" : pRenderType.getClass()));
                    }

                    // Both the fallback and fixed buffers process their data immediately when the batch is ended
                    // Both callbacks should call end on the buffer before consuming the data
                    if (bufferbuilder == this.builder) {
                        this.thread.fallbackBufferCallback();
                    } else {
                        this.thread.fixedBufferCallback(pRenderType);
                        this.typeUsedVertices.remove(pRenderType);
                        this.thread.layerPosVertexCountsMap.remove(pRenderType);
                        this.thread.layerUUIDVertexCountsMap.remove(pRenderType);
                    }
                }

                if (endingLastUsedType) {
                    this.lastState = Optional.empty();
                }
            }
        }
    }

    public void resetAll() {
        endBatch();
        this.builder.discard();
        this.fixedBuffers.keySet().forEach((RenderType type) -> this.getBuilderRaw(type).discard());
        this.startedBuffers.clear();  // just in case
    }
}