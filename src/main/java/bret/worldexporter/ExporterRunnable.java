package bret.worldexporter;

import bret.worldexporter.config.WorldExporterConfig;
import bret.worldexporter.legacylwjgl.Vector2f;
import bret.worldexporter.legacylwjgl.Vector3f;
import bret.worldexporter.util.BlockPosUtils;
import bret.worldexporter.util.LightConnectedPathfinder;
import bret.worldexporter.util.ReflectionHandler;
import bret.worldexporter.util.disk.SimpleSet;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.Minecraft;
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
import net.minecraft.entity.LivingEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.model.ModelDataManager;
import net.minecraftforge.client.model.data.EmptyModelData;
import net.minecraftforge.client.model.data.IModelData;
import org.apache.commons.lang3.tuple.Pair;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static bret.worldexporter.Exporter.WORLD_HEIGHT_LIMIT;
import static bret.worldexporter.Exporter.WORLD_LOWER_HEIGHT_LIMIT;
import static bret.worldexporter.WorldExporter.LOGGER;

// Instances of this class should not be run on the main thread.
//  If thread safety is desired, threadSafe must be true and tasks must be added to the main thread with
//  exporter.addMainThreadTask to run code on the main thread
class ExporterRunnable implements Runnable {
    protected final Map<RenderType, Map<BlockPos, Pair<Integer, Integer>>> layerPosVertexCountsMap = new HashMap<>();
    protected final Map<RenderType, Map<UUID, Pair<Integer, Integer>>> layerUUIDVertexCountsMap = new HashMap<>();
    private final Map<BlockPos, ArrayList<Quad>> blockQuadsMap = new HashMap<>();
    private final Map<UUID, ArrayList<Quad>> entityUUIDQuadsMap = new HashMap<>();
    private final Map<BlockPos, Integer> blockLightValuesMap = new HashMap<>();
    private final Map<RenderType, ResourceLocation> renderResourceLocationMap = new HashMap<>();
    private final CustomImpl impl;
    private final Collection<Pair<BlockPos, BlockPos>> chunkBoundaries;
    private final Exporter exporter;
    private final boolean threadSafe;
    private final int chunksPerConsume;
    private final Consumer<ArrayList<ExportChunk>> chunkConsumer;
    private final boolean renderCutout;
    private final Map<net.minecraftforge.registries.IRegistryDelegate<Block>, java.util.function.Predicate<RenderType>> blockRenderChecks;
    private final Map<net.minecraftforge.registries.IRegistryDelegate<Fluid>, java.util.function.Predicate<RenderType>> fluidRenderChecks;
    private ArrayList<ExportChunk> resultChunks = new ArrayList<>();
    private BlockPos lastFixedBlock;
    private UUID lastFixedEntityUUID;
    private boolean lastFixedIsBlock;
    private BlockPos lastFallbackBlock;
    private UUID lastFallbackEntityUUID;
    private boolean lastFallbackIsBlock;
    private SimpleSet<Long> lightConnected;

    @SuppressWarnings("unchecked")
    public ExporterRunnable(Exporter exporter, Collection<Pair<BlockPos, BlockPos>> chunkBoundaries,
                            boolean threadSafe, Consumer<ArrayList<ExportChunk>> chunkConsumer, int chunksPerConsume, @Nullable SimpleSet<Long> lightConnected) {
        this.exporter = exporter;
        this.chunkBoundaries = chunkBoundaries;
        this.threadSafe = threadSafe;
        this.chunkConsumer = chunkConsumer;
        this.chunksPerConsume = chunksPerConsume;
        this.lightConnected = lightConnected;
        impl = new CustomImpl(this);
        renderCutout = Minecraft.useFancyGraphics();

        try {
            Field fluidChecks = Objects.requireNonNull(ReflectionHandler.getField(RenderTypeLookup.class, "fluidRenderChecks"));
            Map<net.minecraftforge.registries.IRegistryDelegate<Fluid>, java.util.function.Predicate<RenderType>> mapFluid =
                    (Map<net.minecraftforge.registries.IRegistryDelegate<Fluid>, java.util.function.Predicate<RenderType>>) fluidChecks.get(RenderTypeLookup.class);
            fluidRenderChecks = new HashMap<>(mapFluid);

            Field blockChecks = Objects.requireNonNull(ReflectionHandler.getField(RenderTypeLookup.class, "blockRenderChecks"));
            Map<net.minecraftforge.registries.IRegistryDelegate<Block>, java.util.function.Predicate<RenderType>> mapBlock =
                    (Map<net.minecraftforge.registries.IRegistryDelegate<Block>, java.util.function.Predicate<RenderType>>) blockChecks.get(RenderTypeLookup.class);
            blockRenderChecks = new HashMap<>(mapBlock);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private boolean runThreadSafe(Runnable task) throws InterruptedException, ExecutionException {
        boolean result;
        if (threadSafe) {
            RunnableFuture<Boolean> futureTask = new FutureTask<>(() -> {
                try {
                    task.run();
                    return true;
                } catch (Throwable e) {
                    LOGGER.warn("Failed to run a task on the main thread: ", e);
                    return false;
                }
            });
            ChunkThreadSyncManager.release();
            exporter.addMainThreadTask(futureTask);
            result = futureTask.get();
            ChunkThreadSyncManager.waitForThreadsReady();
            ChunkThreadSyncManager.acquire();
        } else {
            try {
                task.run();
                result = true;
            } catch (Throwable e) {
                LOGGER.warn("Failed to run a task on thread: ", e);
                result = false;
            }
        }

        return result;
    }

    @Override
    public void run() {
        try {
            int processedChunks = 0;
            for (Pair<BlockPos, BlockPos> startEnd : chunkBoundaries) {
                ChunkThreadSyncManager.threadCheckpoint();
                ArrayList<Quad> chunkQuads = getNextChunkData(startEnd.getLeft(), startEnd.getRight());
                int chunkX = startEnd.getLeft().getX() >> 4;
                int chunkZ = startEnd.getLeft().getZ() >> 4;

                if (WorldExporterConfig.CLIENT.relativeCoordinates.get()) {
                    chunkX -= exporter.playerX >> 4;
                    chunkZ -= exporter.playerZ >> 4;
                }

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
            e.printStackTrace();
        }

        // The thread is done: release it from the semaphore so that it doesn't block other threads in a finished state
        ChunkThreadSyncManager.release();
    }

    private void consumeChunks() {
        ArrayList<ExportChunk> toConsume = resultChunks;
        resultChunks = new ArrayList<>();
        try {
            exporter.addMainThreadTask(() ->  {
                try {
                    chunkConsumer.accept(toConsume);
                } catch (Throwable e) {
                    LOGGER.warn("Unable to handle list of ExportChunks in ExporterRunnable consumeChunks task: ", e);
                }
            });
        } catch (InterruptedException e) {
            throw new RuntimeException("Error while trying to add exporter task in consumeChunks: ", e);
        }
    }

    private boolean canRenderInLayer(FluidState fluid, RenderType type) {
        java.util.function.Predicate<RenderType> rendertype;
        rendertype = fluidRenderChecks.get(fluid.getType().delegate);
        return rendertype != null ? rendertype.test(type) : type == RenderType.solid();
    }

    private boolean canRenderInLayer(BlockState state, RenderType type) {
        Block block = state.getBlock();
        if (block instanceof LeavesBlock) {
            return renderCutout ? type == RenderType.cutoutMipped() : type == RenderType.solid();
        } else {
            java.util.function.Predicate<RenderType> rendertype;
            rendertype = blockRenderChecks.get(block.delegate);
            return rendertype != null ? rendertype.test(type) : type == RenderType.solid();
        }
    }

    private void ensureEmptyMatrixStack(MatrixStack matrixStack) {
        while (!matrixStack.clear()) matrixStack.popPose();
    }

    private ArrayList<Quad> getNextChunkData(BlockPos low, BlockPos high) throws InterruptedException, ExecutionException {
        reset();
        ArrayList<Quad> quads = new ArrayList<>();
        Chunk chunk = exporter.world.getChunkAt(low);
        if (chunk.isEmpty()) {
            return quads;
        }

        boolean useLightConnected = WorldExporterConfig.CLIENT.exportVisibleExteriorOnly.get();
        boolean segmentLightConnectionBuilding = WorldExporterConfig.CLIENT.segmentedExteriorPathfinding.get();
        boolean lightNeedsSync = true;
        if (useLightConnected && segmentLightConnectionBuilding) {
            int segmentChunkDistance = WorldExporterConfig.CLIENT.segmentChunkRadius.get();
            Pair<BlockPos, BlockPos> segment = BlockPosUtils.extendChunks(low, high, segmentChunkDistance);
            LightConnectedPathfinder lightFinder = new LightConnectedPathfinder(exporter, exporter.world, segment.getLeft(), segment.getRight());
            lightConnected = lightFinder.lightConnectedBlockSet(WorldExporterConfig.CLIENT.maxVisibilityPathLength.get(), false);
            lightNeedsSync = false;
        }

        final SimpleSet<Long> lightConnectedSet = lightConnected;
        Random random = new Random();
        MatrixStack matrixStack = new MatrixStack();
        float partialTicks = Minecraft.getInstance().getFrameTime();
        BlockPos lowClampedHeight = new BlockPos(low.getX(),
                Math.max(WORLD_LOWER_HEIGHT_LIMIT, Math.min(WORLD_HEIGHT_LIMIT, low.getY())), low.getZ());
        BlockPos highClampedHeight = new BlockPos(high.getX(),
                Math.max(WORLD_LOWER_HEIGHT_LIMIT, Math.min(WORLD_HEIGHT_LIMIT, high.getY())), high.getZ());
        for (BlockPos pos : BlockPosUtils.betweenClosedXZY(lowClampedHeight, highClampedHeight)) {
            if (useLightConnected) {
                if (lightNeedsSync) {
                    synchronized (lightConnectedSet) {
                        if (!lightConnected.contains(pos.asLong())) {
                            continue;
                        }
                    }
                } else if (!lightConnected.contains(pos.asLong())) {
                    continue;
                }
            }

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
                TileEntity tileentity = chunk.getBlockEntity(pos, Chunk.CreateEntityType.CHECK);
                if (tileentity != null) {
                    TileEntityRenderer<TileEntity> tileEntityRenderer = TileEntityRendererDispatcher.instance.getRenderer(tileentity);
                    int i = WorldRenderer.getLightColor(exporter.world, tileentity.getBlockPos());
                    if (tileEntityRenderer != null) {
                        matrixStack.pushPose();
                        matrixStack.translate(pos.getX() - exporter.playerXOffset, pos.getY(), pos.getZ() - exporter.playerZOffset);
                        Runnable renderTileEntity = () -> tileEntityRenderer.render(tileentity, partialTicks, matrixStack, impl, i, OverlayTexture.NO_OVERLAY);
                        // This may crash on non-main threads
                        if (!runThreadSafe(renderTileEntity)) {
                            LOGGER.error("Error while exporting tile entity: " + tileentity + '\n');
                        }
                        ensureEmptyMatrixStack(matrixStack);
                    }
                }
            }

            // The rendering logic is roughly taken from ChunkRenderDispatcher.compile with multiple tweaks
            FluidState fluidState = chunk.getFluidState(pos);
            // TODO should check if this could cause threading issues with
            //  null model data being returned when not on main thread
            IModelData modelData = ModelDataManager.getModelData(exporter.world, pos);
            // This more accurately matches the base ChunkRenderDispatcher code, since getModelData can return null
            modelData = modelData == null ? EmptyModelData.INSTANCE : modelData;
            for (RenderType rendertype : RenderType.chunkBufferLayers()) {
                // It appears some mods use MinecraftForgeClient.getRenderLayer() for branching rendering behavior,
                // so it needs to be set properly before rendering here
                // The map this updates is ThreadLocal
                ForgeHooksClient.setRenderLayer(rendertype);

                if (!fluidState.isEmpty() && canRenderInLayer(fluidState, rendertype)) {
                    BitSet forceRender = exporter.getForcedDirections(pos);
                    BufferBuilder bufferbuilder = impl.getBuffer(rendertype);  // automatically starts buffer
                    if (!runThreadSafe(() -> exporter.blockRendererDispatcher.renderLiquid(pos, exporter.world, bufferbuilder, fluidState, exporter.playerXOffset, exporter.playerZOffset, forceRender))) {
                        LOGGER.error("Unable to render fluid block '" + fluidState.getType().getBucket() + "' at " + pos);
                    }
                }

                if (state.getRenderShape() != BlockRenderType.INVISIBLE && canRenderInLayer(state, rendertype)) {
                    matrixStack.pushPose();
                    matrixStack.translate(pos.getX() - exporter.playerXOffset, pos.getY(), pos.getZ() - exporter.playerZOffset);
                    BitSet forceRender = exporter.getForcedDirections(pos);
                    BufferBuilder bufferbuilder = impl.getBuffer(rendertype);   // automatically starts buffer
                    IModelData finalModelData = modelData;
                    if (!runThreadSafe(() -> exporter.blockRendererDispatcher.renderModel(state, pos, exporter.world, matrixStack, bufferbuilder, forceRender, random, finalModelData, exporter.randomize))) {
                        LOGGER.error("Unable to render block '" + state + "' at " + pos);
                    }

                    ensureEmptyMatrixStack(matrixStack);
                }
            }
            ForgeHooksClient.setRenderLayer(null);
            postCountLayerVertices();
        }

        // export all entities within chunk
        if (WorldExporterConfig.CLIENT.enableEntities.get()) {
            boolean skipLiving = !WorldExporterConfig.CLIENT.enableLivingEntities.get();
            for (Entity entity : exporter.world.getEntities(null, new AxisAlignedBB(low, high))) {
                if (skipLiving && entity instanceof LivingEntity) continue;

                preEntity(entity.getUUID());
                matrixStack.pushPose();
                int packedLight = 15 << 20 | 15 << 4;  // .lightmap(240, 240) is full-bright
                Runnable renderEntity = () -> exporter.mc.getEntityRenderDispatcher().render(entity, entity.getX() - exporter.playerXOffset, entity.getY(),
                        entity.getZ() - exporter.playerZOffset, entity.yRot, partialTicks, matrixStack, impl, packedLight);
                // optifine can cause this to crash because it calls parts of RenderSystem which verify execution on the render/main thread
                // causing a crash on threads other than the render thread
                // So far, I have seen this occur with leashed entities.
                // If this happens, fallback to main thread
                if (!runThreadSafe(renderEntity)) {
                    if (!threadSafe) {
                        ensureEmptyMatrixStack(matrixStack);
                        matrixStack.pushPose();
                        RunnableFuture<Boolean> entityFuture = new FutureTask<>(() -> {
                            try {
                                renderEntity.run();
                                return true;
                            } catch (Throwable e) {
                                LOGGER.error("Unknown error while exporting entity on main thread: " + entity + '\n', e);
                                return false;
                            }
                        });
                        ChunkThreadSyncManager.release();
                        exporter.addMainThreadTask(entityFuture);
                        entityFuture.get();
                        ChunkThreadSyncManager.waitForThreadsReady();
                        ChunkThreadSyncManager.acquire();
                    } else {
                        // the failure was not threading related, so there's nothing that can be done
                        LOGGER.error("Unknown error while exporting entity on main thread: " + entity + '\n');
                    }
                }
                ensureEmptyMatrixStack(matrixStack);
                postCountLayerVertices();
            }
        }

        // finish all builders that were drawing and add the remaining data, if any
        impl.endBatch();
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

        return quads;
    }

    private void reset() {
        impl.resetAll();
        layerPosVertexCountsMap.clear();
        blockQuadsMap.clear();
        layerUUIDVertexCountsMap.clear();
        entityUUIDQuadsMap.clear();
        impl.clearVerticesCounts();
        blockLightValuesMap.clear();
    }

    private void addVertices(ByteBuffer bytebuffer, List<VertexFormatElement> list, ArrayList<Quad> quadsList, RenderType type, int vertexStartIndex, int vertexCount) {
        ResourceLocation resource = renderResourceLocationMap.getOrDefault(type, MissingTextureSprite.getLocation());
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
                layerPosVertexCountsMap.computeIfAbsent(type, k -> new HashMap<>()).put(lastFixedBlock, entry.getValue());
            } else {
                layerUUIDVertexCountsMap.computeIfAbsent(type, k -> new HashMap<>()).put(lastFixedEntityUUID, entry.getValue());
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

    // This must not be used with a non-fixed RenderType (fallback type).
    // This will be called on every fixed buffer RenderType when endBatch() is called, but this
    // exists just in case a fixed buffer is ended at some point during rendering
    protected void fixedBufferCallback(RenderType type) {
        BufferBuilder bufferBuilder = impl.getBuilderRaw(type);
        bufferBuilder.end();
        com.mojang.datafixers.util.Pair<BufferBuilder.DrawState, ByteBuffer> stateBufferPair = bufferBuilder.popNextBuffer();
        BufferBuilder.DrawState drawState = stateBufferPair.getFirst();
        VertexFormat format = drawState.format();
        if (drawState.vertexCount() == 0) {
            return;
        }
        if (drawState.mode() != GL11.GL_QUADS || !Exporter.supportedVertexFormat(format)) {
            LOGGER.warn("A rendertype VertexFormat was not supported: " + type + '\n' + format + '\n' + format.getElements()
                    + '\n' + "Using GL drawstate ID: " + drawState.mode());
            return;
        }

        ByteBuffer bytebuffer = stateBufferPair.getSecond();
        for (BlockPos pos : layerPosVertexCountsMap.getOrDefault(type, Collections.emptyMap()).keySet()) {
            Pair<Integer, Integer> verticesPosCount = layerPosVertexCountsMap.get(type).get(pos);
            int firstVertexBytePos = verticesPosCount.getLeft() * type.format().getVertexSize();
            int vertexCount = verticesPosCount.getRight();
            ArrayList<Quad> quadsList = blockQuadsMap.computeIfAbsent(pos, k -> new ArrayList<>());
            addVertices(bytebuffer, type.format().getElements(), quadsList, type, firstVertexBytePos, vertexCount);
        }

        for (UUID uuid : layerUUIDVertexCountsMap.getOrDefault(type, Collections.emptyMap()).keySet()) {
            Pair<Integer, Integer> verticesPosCount = layerUUIDVertexCountsMap.get(type).get(uuid);
            int firstVertexBytePos = verticesPosCount.getLeft() * type.format().getVertexSize();
            int vertexCount = verticesPosCount.getRight();
            ArrayList<Quad> quadsList = entityUUIDQuadsMap.computeIfAbsent(uuid, k -> new ArrayList<>());
            addVertices(bytebuffer, type.format().getElements(), quadsList, type, firstVertexBytePos, vertexCount);
        }

        bufferBuilder.discard();
    }

    protected void fallbackBufferCallback() {
        impl.builder.end();
        com.mojang.datafixers.util.Pair<BufferBuilder.DrawState, ByteBuffer> stateBufferPair = impl.builder.popNextBuffer();
        BufferBuilder.DrawState drawState = stateBufferPair.getFirst();
        ByteBuffer bytebuffer = stateBufferPair.getSecond();

        if (drawState.vertexCount() == 0) {
            return;
        }
        if (drawState.mode() != GL11.GL_QUADS || !Exporter.supportedVertexFormat(drawState.format())) {
            LOGGER.warn("A rendertype VertexFormat was not supported: " + drawState.format() + '\n' + drawState.format().getElements()
                    + '\n' + "Using GL drawstate ID: " + drawState.mode());
            return;
        }
        if (drawState.vertexCount() != (bytebuffer.limit() / drawState.format().getVertexSize())) {
            throw new RuntimeException(String.format(
                    "Mismatch between drawState vertex count (%d) and number of vertices contained in bytebuffer (%d) \n" +
                            "Bytebuffer has limit (%d) and capacity (%d)",
                    drawState.vertexCount(),
                    bytebuffer.limit() / drawState.format().getVertexSize(),
                    bytebuffer.limit(),
                    bytebuffer.capacity()));
        }

        ArrayList<Quad> quadList;
        if (lastFallbackIsBlock && lastFallbackBlock != null) {
            quadList = blockQuadsMap.computeIfAbsent(lastFallbackBlock, k -> new ArrayList<>());
        } else if (!lastFallbackIsBlock && lastFallbackEntityUUID != null) {
            quadList = entityUUIDQuadsMap.computeIfAbsent(lastFallbackEntityUUID, k -> new ArrayList<>());
        } else {
            LOGGER.warn("fallbackBufferCallback called, but no block or entity could be matched with the quads.");
            return;
        }

        addVertices(bytebuffer, drawState.format().getElements(), quadList, impl.lastState.orElse(null), 0, drawState.vertexCount());
        impl.builder.discard();
    }

    // For every quad: if its texture refers to an atlasImage, update its texture resourceLocation, add a reference to its sprite,
    // and update its UV coordinates respectively (in place)
    private void updateQuadTextures() {
        List<Quad> quads = blockQuadsMap.values().stream().flatMap(Collection::stream).collect(Collectors.toList());
        quads.addAll(entityUUIDQuadsMap.values().stream().flatMap(Collection::stream).collect(Collectors.toList()));
        for (Quad quad : quads) {
            if (!quad.hasUV()) continue;

            Texture baseTexture = exporter.getTexture(quad.getResource());
            quad.setTexture(baseTexture);
            boolean didModifyUV = false;
            // allowed error is very small by default
            float allowableErrorU = 0.0001f;
            float allowableErrorV = 0.0001f;
            if (baseTexture instanceof AtlasTexture) {
                Pair<ResourceLocation, TextureAtlasSprite> nameAndTexture = exporter.getTextureFromAtlas(quad.getResource(), quad.getUvBounds());
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
                } else {
                    LOGGER.warn("Unable to determine where on atlas " + ((AtlasTexture) baseTexture).location() +
                            " the texture is with the following UVs: " + quad.getUvBounds() +
                            ", derived from from ResourceLocation " + quad.getResource() +
                            ", at world position " + quad.getVertices()[0].getPosition());
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
    private void fixOverlaps(Collection<ArrayList<Quad>> quadsArrays) {
        Exporter.removeDuplicateQuads(quadsArrays);
        Collection<ArrayList<Quad>> toSort = quadsArrays.stream().filter(quads -> quads.size() >= 1).collect(Collectors.toList());

        boolean fallbackSort;
        // Delegates the task of sorting the quads to the main thread, which can generate the image data required for accurate sorting
        RunnableFuture<Boolean> task = new FutureTask<>(() -> {
            toSort.forEach(exporter::sortQuads);
            return true;
        });
        try {
            exporter.addMainThreadTask(task);
            fallbackSort = !task.get();
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.warn("Unable to sort quads on main thread, falling back to threaded sort", e);
            fallbackSort = true;
        }
        if (fallbackSort) {
            // sort on thread instead, with less accuracy
            toSort.forEach(exporter::sortQuads);
        }

        for (ArrayList<Quad> quads : quadsArrays) {
            if (quads.size() <= 1) {
                continue;
            }

            Set<Integer> toCheck = new HashSet<>();
            for (int i = 0, size = quads.size(); i < size; ++i) {
                toCheck.add(i);
            }
            boolean reCheck = !toCheck.isEmpty();
            while (reCheck) {
                Set<Integer> newToCheck = new HashSet<>();
                for (int quadIndex : toCheck) {
                    Quad first = quads.get(quadIndex);
                    ArrayList<Pair<Integer, Float>> overlapsWithFirst = new ArrayList<>();
                    for (int j = quadIndex + 1; j < quads.size(); ++j) {
                        float overlapDistance = first.overlaps(quads.get(j));
                        if (overlapDistance != Float.POSITIVE_INFINITY) {
                            overlapsWithFirst.add(Pair.of(j, overlapDistance));
                        }
                    }

                    if (overlapsWithFirst.isEmpty()) {
                        continue;
                    }

                    for (Pair<Integer, Float> quadIndexDistance : overlapsWithFirst) {
                        int overlapQuad = quadIndexDistance.getLeft();
                        float distance = quadIndexDistance.getRight();
                        Quad toOffset = quads.get(overlapQuad);
                        // scale the toOffset quad such that it is overlapDistance away from the other quad
                        float scaleDistance = WorldExporterConfig.CLIENT.overlapDistance.get().floatValue() - distance;
                        Vector3f posTranslate = (Vector3f) toOffset.getNormal().scale(scaleDistance);
                        toOffset.translate(posTranslate);
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
