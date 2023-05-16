package bret.worldexporter;

import bret.worldexporter.legacylwjgl.Vector2f;
import bret.worldexporter.legacylwjgl.Vector3f;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderTypeLookup;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.renderer.tileentity.TileEntityRenderer;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.inventory.container.PlayerContainer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.client.model.ModelDataManager;
import net.minecraftforge.client.model.data.IModelData;
import org.apache.commons.lang3.tuple.Pair;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static bret.worldexporter.Exporter.LOGGER;

class ExporterRunnable implements Runnable {
    private final Map<RenderType, Map<BlockPos, Pair<Integer, Integer>>> layerPosVerticesMap = new HashMap<>();
    private final Map<BlockPos, ArrayList<Quad>> blockQuadsMap = new HashMap<>();
    private final Map<RenderType, Map<UUID, Pair<Integer, Integer>>> layerUUIDVerticesMap = new HashMap<>();
    private final Map<UUID, ArrayList<Quad>> entityUUIDQuadsMap = new HashMap<>();
    private final Map<BlockPos, Integer> blockLightValuesMap = new HashMap<>();
    private final Map<RenderType, ResourceLocation> renderResourceLocationMap = new HashMap<>();
    private final CustomImpl impl;
    private final Collection<Pair<BlockPos, BlockPos>> chunkBoundaries;
    private final boolean threaded;
    private final Exporter exporter;
    private final int chunksPerConsume;
    private final Consumer<ArrayList<ExportChunk>> chunkConsumer;
    private ArrayList<ExportChunk> resultChunks = new ArrayList<>();
    private BlockPos lastFixedBlock;
    private UUID lastFixedEntityUUID;
    private boolean lastFixedIsBlock;
    private BlockPos lastFallbackBlock;
    private UUID lastFallbackEntityUUID;
    private boolean lastFallbackIsBlock;

    public ExporterRunnable(Exporter exporter, Collection<Pair<BlockPos, BlockPos>> chunkBoundaries,
                            boolean threaded, Consumer<ArrayList<ExportChunk>> chunkConsumer, int chunksPerConsume) {
        this.exporter = exporter;
        this.chunkBoundaries = chunkBoundaries;
        this.threaded = threaded;
        this.chunkConsumer = chunkConsumer;
        this.chunksPerConsume = chunksPerConsume;
        impl = new CustomImpl(exporter, this);
    }

    @Override
    public void run() {
        try {
            int processedChunks = 0;
            for (Pair<BlockPos, BlockPos> startEnd : chunkBoundaries) {
                ArrayList<Quad> chunkQuads = getNextChunkData(startEnd.getLeft(), startEnd.getRight());
                int chunkX = startEnd.getLeft().getX() >> 4;
                int chunkZ = startEnd.getLeft().getZ() >> 4;
                this.resultChunks.add(new ExportChunk(chunkQuads, chunkX, chunkZ));
                if (++processedChunks == chunksPerConsume) {
                    processedChunks = 0;
                    consumeChunks();
                }
            }

            if (processedChunks > 0) {
                consumeChunks();
            }
        } catch (Throwable e) {
            LOGGER.error("ExporterRunnable crashed while exporting: ", e);
        }
    }

    private void consumeChunks() {
        ArrayList<ExportChunk> toConsume = resultChunks;
        resultChunks = new ArrayList<>();
        try {
            if (threaded) {
                exporter.addTask(() -> chunkConsumer.accept(toConsume));
            } else {
                chunkConsumer.accept(toConsume);
            }
        } catch (Exception e) {
            LOGGER.warn("Unable to handle list of ExportChunks in ExporterRunnable: ", e);
        }
    }

    private ArrayList<Quad> getNextChunkData(BlockPos start, BlockPos end) {
        resetBuilders();
        Chunk chunk = exporter.world.getChunkAt(start);
        Random random = new Random();
        MatrixStack matrixStack = new MatrixStack();
        ArrayList<Quad> quads = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(start, end)) {
            BlockState state = chunk.getBlockState(pos);
            if (state.getBlock().isAir(state, exporter.world, pos)) {
                continue;
            }

            BlockPos immutablePos = pos.immutable();
            preBlock(immutablePos);
            int light = state.getLightValue(exporter.world, immutablePos);
            if (light != 0) {
                blockLightValuesMap.put(immutablePos, light);
            }

            if (state.hasTileEntity()) {
                TileEntity tileentity = exporter.world.getChunkAt(pos).getBlockEntity(pos);
                if (tileentity != null) {
                    TileEntityRenderer<TileEntity> tileEntityRenderer = TileEntityRendererDispatcher.instance.getRenderer(tileentity);
                    int i = WorldRenderer.getLightColor(exporter.world, tileentity.getBlockPos());
                    if (tileEntityRenderer != null) {
                        matrixStack.pushPose();
                        matrixStack.translate(pos.getX(), pos.getY(), pos.getZ());
                        float partialTicks = 0;

                        RunnableFuture<Boolean> renderTileEntity = new FutureTask<>(() -> {
                            tileEntityRenderer.render(tileentity, partialTicks, matrixStack, impl, i, OverlayTexture.NO_OVERLAY);
                            return true;
                        });

                        try {
                            // This may crash on non-main threads, fallback to main thread if so
                            renderTileEntity.run();
                        } catch (Exception e) {
                            try {
                                exporter.addTask(renderTileEntity);
                                if (!renderTileEntity.get())
                                    throw new RuntimeException("Unknown error while exporting tile entity on main thread.");
                            } catch (Exception e2) {
                                LOGGER.error("Unable to export tile entity: " + tileentity + "\nDue to multiple exceptions: ", e);
                                LOGGER.error(e2);
                            }
                        }

                        matrixStack.popPose();
                    }
                }
            }

            // The rendering logic is roughly taken from ChunkRenderDispatcher.compile with multiple tweaks
            FluidState fluidState = exporter.world.getFluidState(pos);
            IModelData modelData = ModelDataManager.getModelData(exporter.world, pos);
            for (RenderType rendertype : RenderType.chunkBufferLayers()) {
                if (!fluidState.isEmpty() && RenderTypeLookup.canRenderInLayer(fluidState, rendertype)) {
                    BitSet forceRender = exporter.getForcedDirections(pos);
                    BufferBuilder bufferbuilder = impl.getBuffer(rendertype);  // automatically starts buffer
                    exporter.blockRendererDispatcher.renderLiquid(pos, exporter.world, bufferbuilder, fluidState, 0, 0, forceRender);
                }
                if (state.getRenderShape() != BlockRenderType.INVISIBLE && RenderTypeLookup.canRenderInLayer(state, rendertype)) {
                    matrixStack.pushPose();
                    matrixStack.translate(pos.getX(), pos.getY(), pos.getZ());
                    BitSet forceRender = exporter.getForcedDirections(pos);
                    BufferBuilder bufferbuilder = impl.getBuffer(rendertype);   // automatically starts buffer
                    exporter.blockRendererDispatcher.renderModel(state, pos, exporter.world, matrixStack, bufferbuilder, forceRender, random, modelData, exporter.randomize);

                    // TODO: useful?
//                    if (state.getRenderShape() == BlockRenderType.ENTITYBLOCK_ANIMATED && modelData != null) {
//                        int packedLight = 15 << 20 | 15 << 4;  // .lightmap(240, 240) is full-bright
//                        exporter.blockRendererDispatcher.renderBlock(state, matrixStack, impl, packedLight, OverlayTexture.NO_OVERLAY, modelData);
//                    }

                    matrixStack.popPose();
                }
            }
            postCountLayerVertices();
        }

        // TODO: option for enabling/disabling entities, also option for LivingEntity
        // export all entities within chunk
        for (Entity entity : exporter.world.getEntities(null, new AxisAlignedBB(start, end))) {
            preEntity(entity.getUUID());
            matrixStack.pushPose();
            float partialTicks = 0;
            int packedLight = 15 << 20 | 15 << 4;  // .lightmap(240, 240) is full-bright
            RunnableFuture<Boolean> renderEntity = new FutureTask<>(() -> {
                exporter.mc.getEntityRenderDispatcher().render(entity, entity.getX(), entity.getY(), entity.getZ(), entity.yRot,
                        partialTicks, matrixStack, impl, packedLight);
                return true;
            });
            try {
                // optifine can cause this to crash because it calls parts of RenderSystem which check the thread it is running on
                // causing a crash on threads other than the render thread
                // So far, I have seen this occur with leashed entities.
                // If this happens, fallback to main thread
                renderEntity.run();
            } catch (Exception e) {
                try {
                    exporter.addTask(renderEntity);
                    if (!renderEntity.get())
                        throw new RuntimeException("Unknown error while exporting entity on main thread.");
                } catch (Exception e2) {
                    LOGGER.error("Unable to export entity: " + entity + "\nDue to multiple exceptions: ", e);
                    LOGGER.error(e2);
                }
            }
            matrixStack.popPose();
            postCountLayerVertices();
        }

        // finish all builders that were drawing, then add the data
        impl.endBatch();
        if (impl.builder.building()) {
            fallbackBufferCallback();
        }
        addAllFinishedData();
        updateQuadTextures();

        // update light values for quads that originate from a block
        for (BlockPos pos : blockQuadsMap.keySet()) {
            ArrayList<Quad> quadsForBlock = blockQuadsMap.get(pos);
            quadsForBlock.forEach(quad -> quad.setLightValue(blockLightValuesMap.getOrDefault(pos, 0)));
        }

        // Minecraft uses a flipped V coordinate
        blockQuadsMap.values().forEach(Exporter::flipV);
        entityUUIDQuadsMap.values().forEach(Exporter::flipV);

        // fix quad data issues such as overlapping/duplicate faces
        fixOverlaps(blockQuadsMap.values());
        fixOverlaps(entityUUIDQuadsMap.values());
        blockQuadsMap.values().forEach(quads::addAll);
        entityUUIDQuadsMap.values().forEach(quads::addAll);

        if (exporter.optimizeMesh) {
            MeshOptimizer meshOptimizer = new MeshOptimizer();
            quads = meshOptimizer.optimize(quads);
        }

        // translate quads so that the player x,z are the origin
        exporter.translateQuads(quads);

        return quads;
    }

    private void resetBuilders() {
        for (RenderType type : layerPosVerticesMap.keySet()) {
            BufferBuilder buf = impl.getBuilderRaw(type);
            if (buf.building()) buf.end();
            buf.discard();
        }

        for (RenderType type : layerUUIDVerticesMap.keySet()) {
            BufferBuilder buf = impl.getBuilderRaw(type);
            if (buf.building()) buf.end();
            buf.discard();
        }

        if (impl.builder.building()) impl.builder.end();
        impl.builder.discard();

        impl.endBatch();

        layerPosVerticesMap.clear();
        blockQuadsMap.clear();
        layerUUIDVerticesMap.clear();
        entityUUIDQuadsMap.clear();
        impl.clearVerticesCounts();
        blockLightValuesMap.clear();
    }

    private void addAllFinishedData() {
        Set<RenderType> allTypes = new HashSet<>(layerPosVerticesMap.keySet());
        allTypes.addAll(layerUUIDVerticesMap.keySet());

        for (RenderType type : allTypes) {
            BufferBuilder bufferBuilder = impl.getBuilderRaw(type);
            com.mojang.datafixers.util.Pair<BufferBuilder.DrawState, ByteBuffer> stateBufferPair = bufferBuilder.popNextBuffer();
            BufferBuilder.DrawState drawState = stateBufferPair.getFirst();
            VertexFormat format = type.format();
            if (drawState.vertexCount() == 0 || drawState.mode() != GL11.GL_QUADS || !Exporter.supportedVertexFormat(format)) {
                continue;
            }

            ByteBuffer bytebuffer = stateBufferPair.getSecond();
            for (BlockPos pos : layerPosVerticesMap.getOrDefault(type, Collections.emptyMap()).keySet()) {
                Pair<Integer, Integer> verticesPosCount = layerPosVerticesMap.get(type).get(pos);
                int firstVertexBytePos = verticesPosCount.getLeft() * type.format().getVertexSize();
                int vertexCount = verticesPosCount.getRight();
                ArrayList<Quad> quadsList = blockQuadsMap.computeIfAbsent(pos, k -> new ArrayList<>());
                addVertices(bytebuffer, type.format().getElements(), quadsList, type, firstVertexBytePos, vertexCount);
            }

            for (UUID uuid : layerUUIDVerticesMap.getOrDefault(type, Collections.emptyMap()).keySet()) {
                Pair<Integer, Integer> verticesPosCount = layerUUIDVerticesMap.get(type).get(uuid);
                int firstVertexBytePos = verticesPosCount.getLeft() * type.format().getVertexSize();
                int vertexCount = verticesPosCount.getRight();
                ArrayList<Quad> quadsList = entityUUIDQuadsMap.computeIfAbsent(uuid, k -> new ArrayList<>());
                addVertices(bytebuffer, type.format().getElements(), quadsList, type, firstVertexBytePos, vertexCount);
            }
        }
    }

    private void addVertices(ByteBuffer bytebuffer, List<VertexFormatElement> list, ArrayList<Quad> quadsList, RenderType type, int vertexStartIndex, int vertexCount) {
        ResourceLocation resource = renderResourceLocationMap.getOrDefault(type, PlayerContainer.BLOCK_ATLAS);
        Quad quad = new Quad(type, resource);
        boolean skipQuad = false;
        bytebuffer.position(vertexStartIndex);
        for (int vertexNum = 0; vertexNum < vertexCount; ++vertexNum) {
            if (skipQuad) {
                vertexNum += 3 - (vertexNum - 1) % 4;
                if (vertexNum >= vertexCount) break;
                bytebuffer.position(bytebuffer.position() + (4 - ((vertexNum - 1) % 4)));
                quad = new Quad(type, resource);
                skipQuad = false;
            } else if (quad.getCount() == 4) {
                quadsList.add(quad);
                quad = new Quad(type, resource);
            }

            Vertex vertex = new Vertex();
            for (VertexFormatElement vertexFormatElement : list) {
                VertexFormatElement.Usage vertexElementEnumUsage = vertexFormatElement.getUsage();
                switch (vertexElementEnumUsage) {
                    case POSITION:
                        if (vertexFormatElement.getType() == VertexFormatElement.Type.FLOAT) {
                            vertex.setPosition(new Vector3f(bytebuffer.getFloat(), bytebuffer.getFloat(), bytebuffer.getFloat()));
                        } else {
                            bytebuffer.position(bytebuffer.position() + vertexFormatElement.getByteSize());
                            LOGGER.warn("Vertex position element had no supported type, skipping.");
                        }
                        break;
                    case COLOR:
                        if (vertexFormatElement.getType() == VertexFormatElement.Type.UBYTE) {
                            vertex.setColor(bytebuffer.getInt());
                        } else {
                            bytebuffer.position(bytebuffer.position() + vertexFormatElement.getByteSize());
                            LOGGER.warn("Vertex color element had no supported type, skipping.");
                        }
                        break;
                    case UV:
                        switch (vertexFormatElement.getIndex()) {
                            case 0:  // ELEMENT_UV0 - texture coordinates - 2 float elements
                                float u = bytebuffer.getFloat();
                                float v = bytebuffer.getFloat();
                                // Check for NaNs
                                if (u != u || v != v) {
                                    skipQuad = true;
                                    LOGGER.warn("Quad being skipped since a vertex had a UV coordinate of NaN.");
                                    break;
                                }

                                vertex.setUv(new Vector2f(u, v));
                                break;
                            case 2:  // ELEMENT_UV2 - lightmap coordinates - 2 short elements
                                // TODO: ensure value / 16 / (2^16 - 1) gives proper 0-1 float range
//                              //  Minecraft.getMinecraft().getTextureManager().getTexture(new ResourceLocation( "minecraft", "dynamic/lightmap_1"))
//                              //  Discard first short (sky light) and only use second (block light) when implementing emissive lighting?
//                                vertex.setUvlight(new Vector2f(bytebuffer.getShort() / 65520.0f, bytebuffer.getShort() / 65520.0f));
//                                break;
                            default:
                                // case 1: ELEMENT_UV1 - not currently used, appears in formats like ENTITY
                                bytebuffer.position(bytebuffer.position() + vertexFormatElement.getByteSize());
                        }
                        break;
                    case PADDING:
                    case NORMAL:
                    default:
                        bytebuffer.position(bytebuffer.position() + vertexFormatElement.getByteSize());
                }
            }

            if (!skipQuad) {
                quad.addVertex(vertex);
            }
        }

        // add the last quad
        if (quad.getCount() == 4 && !skipQuad) {
            quadsList.add(quad);
        }
    }

    private void postCountLayerVertices() {
        // store the pre- and post-count of used vertices for each renderType for the relevant block/entity, except for fallback buffer types
        for (Map.Entry<RenderType, Pair<Integer, Integer>> entry : impl.getClearVerticesCounts().entrySet()) {
            RenderType type = entry.getKey();

            if (lastFixedIsBlock) {
                layerPosVerticesMap.computeIfAbsent(type, k -> new HashMap<>()).put(lastFixedBlock, entry.getValue());
            } else {
                layerUUIDVerticesMap.computeIfAbsent(type, k -> new HashMap<>()).put(lastFixedEntityUUID, entry.getValue());
            }
        }
    }

    private void preEntity(UUID entityUUID) {
        lastFixedIsBlock = false;
        lastFixedEntityUUID = entityUUID;
    }

    private void preBlock(BlockPos pos) {
        lastFixedIsBlock = true;
        lastFixedBlock = pos;
    }

    protected void setLastFallbackInfo() {
        lastFallbackIsBlock = lastFixedIsBlock;
        lastFallbackBlock = lastFixedBlock;
        lastFallbackEntityUUID = lastFixedEntityUUID;
    }

    protected void fallbackBufferCallback() {
        impl.builder.end();
        com.mojang.datafixers.util.Pair<BufferBuilder.DrawState, ByteBuffer> stateBufferPair = impl.builder.popNextBuffer();
        impl.builder.discard();
        BufferBuilder.DrawState drawState = stateBufferPair.getFirst();
        ByteBuffer bytebuffer = stateBufferPair.getSecond();

        if (drawState.vertexCount() == 0 || drawState.mode() != GL11.GL_QUADS || !Exporter.supportedVertexFormat(drawState.format())) {
            return;
        }

        ArrayList<Quad> quadList;
        if (lastFallbackIsBlock && lastFallbackBlock != null) {
            quadList = blockQuadsMap.computeIfAbsent(lastFallbackBlock, k -> new ArrayList<>());
        } else if (lastFallbackEntityUUID != null) {
            quadList = entityUUIDQuadsMap.computeIfAbsent(lastFallbackEntityUUID, k -> new ArrayList<>());
        } else {
            return;
        }

        addVertices(bytebuffer, impl.builder.getVertexFormat().getElements(), quadList, impl.lastState.orElse(null), 0, drawState.vertexCount());
    }

    // For every quad: if its texture refers to an atlasImage, update its texture resourceLocation, add a reference to its sprite,
    // and update its UV coordinates respectively (in place)
    private void updateQuadTextures() {
        List<Quad> quads = blockQuadsMap.values().stream().flatMap(Collection::stream).collect(Collectors.toList());
        quads.addAll(entityUUIDQuadsMap.values().stream().flatMap(Collection::stream).collect(Collectors.toList()));
        for (Quad quad : quads) {
            Texture baseTexture = exporter.getTexture(quad.getResource(), threaded);
            quad.setTexture(baseTexture);
            boolean didModifyUV = false;
            // allowed error is very small by default
            float allowableErrorU = 0.00001f;
            float allowableErrorV = 0.00001f;
            if (baseTexture instanceof AtlasTexture) {
                Pair<ResourceLocation, TextureAtlasSprite> nameAndTexture = exporter.getTextureFromAtlas(quad.getResource(), quad.getUvBounds(), threaded);
                if (nameAndTexture != null) {
                    quad.setResource(nameAndTexture.getLeft());
                    TextureAtlasSprite sprite = nameAndTexture.getRight();
                    // recalculate the allowable error based on the sprite's size so that any rounding does not change
                    // the sprite's display on a sprite-pixel level
                    allowableErrorU = 1.0F / sprite.getWidth() / 2.0F;
                    allowableErrorV = 1.0F / sprite.getHeight() / 2.0F;
                    for (Vertex vertex : quad.getVertices()) {
                        Vector2f uv = vertex.getUv();
                        uv.x = (uv.x - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
                        uv.y = (uv.y - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
                    }
                    quad.setSprite(sprite);
                    didModifyUV = true;
                }
            } else if (baseTexture instanceof DynamicTexture) {
                NativeImage quadImage = ((DynamicTexture) baseTexture).getPixels();
                if (quadImage != null) {
                    allowableErrorU = 1.0F / quadImage.getWidth() / 2.0F;
                    allowableErrorV = 1.0F / quadImage.getHeight() / 2.0F;
                }
            }

            // round the UV coordinates to a 0 or 1 if they are close enough based on an allowable error amount
            for (Vertex vertex : quad.getVertices()) {
                Vector2f uv = vertex.getUv();
                float roundedU = Math.round(uv.x);
                float roundedV = Math.round(uv.y);
                boolean shouldRoundU = Math.abs(roundedU - uv.x) < allowableErrorU;
                boolean shouldRoundV = Math.abs(roundedV - uv.y) < allowableErrorV;
                uv.x = shouldRoundU ? roundedU : uv.x;
                uv.y = shouldRoundV ? roundedV : uv.y;
                didModifyUV |= (shouldRoundU || shouldRoundV);
            }
            if (didModifyUV) quad.updateUvBounds();
        }
    }

    // update any quads that overlap by translating by a small multiple of their normal
    protected void fixOverlaps(Collection<ArrayList<Quad>> quadsArrays) {
        Exporter.removeDuplicateQuads(quadsArrays);

        boolean fallbackSort;
        if (threaded) {
            // Delegates the task of sorting the quads to the main thread, which can generate the image data required for accurate sorting
            RunnableFuture<Boolean> task = new FutureTask<>(() -> {
                quadsArrays.forEach(quads -> exporter.sortQuads(quads, false));
                return true;
            });
            try {
                exporter.addTask(task);
                fallbackSort = !task.get();
            } catch (InterruptedException | ExecutionException e) {
                LOGGER.warn("Unable to sort quads on main thread, falling back to threaded sort");
                fallbackSort = true;
            }
        } else {
            fallbackSort = true;
        }
        if (fallbackSort) {
            quadsArrays.forEach(quads -> exporter.sortQuads(quads, threaded));
        }

        for (ArrayList<Quad> quads : quadsArrays) {
            Set<Integer> toCheck = new HashSet<>();
            for (int i = 0, size = quads.size(); i < size; ++i) {
                toCheck.add(i);
            }
            boolean reCheck = !toCheck.isEmpty();
            while (reCheck) {
                Set<Integer> newToCheck = new HashSet<>();
                for (int quadIndex : toCheck) {
                    Quad first = quads.get(quadIndex);
                    ArrayList<Integer> overlapsWithFirst = new ArrayList<>();
                    for (int j = quadIndex + 1; j < quads.size(); ++j) {
                        if (first.overlaps(quads.get(j))) {
                            overlapsWithFirst.add(j);
                        }
                    }

                    if (overlapsWithFirst.isEmpty()) {
                        continue;
                    }

                    Vector3f posTranslate = (Vector3f) first.getNormal().scale(0.00075f);
                    for (int overlapQuad : overlapsWithFirst) {
                        quads.get(overlapQuad).translate(posTranslate);
                        newToCheck.add(overlapQuad);
                    }
                }

                reCheck = !newToCheck.isEmpty();
                toCheck = newToCheck;
            }
        }
    }

    protected void putResource(RenderType type, ResourceLocation location) {
        renderResourceLocationMap.put(type, location);
    }
}
