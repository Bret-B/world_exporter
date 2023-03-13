package bret.worldexporter;

import bret.worldexporter.legacylwjgl.Vector2f;
import bret.worldexporter.legacylwjgl.Vector3f;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderTypeLookup;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.tileentity.TileEntityRenderer;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.client.settings.AmbientOcclusionStatus;
import net.minecraft.fluid.IFluidState;
import net.minecraft.inventory.container.PlayerContainer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.model.ModelDataManager;
import net.minecraftforge.client.model.data.IModelData;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;

import static java.awt.image.BufferedImage.TYPE_INT_ARGB;

public class Exporter {
    protected static final Logger LOGGER = LogManager.getLogger(WorldExporter.MODID);
    protected final Minecraft mc = Minecraft.getInstance();
    protected final CustomBlockRendererDispatcher blockRendererDispatcher = new CustomBlockRendererDispatcher(mc.getBlockRendererDispatcher().getBlockModelShapes(), mc.getBlockColors());
    protected final Map<ResourceLocation, BufferedImage> atlasCacheMap = new HashMap<>();
    protected final Map<RenderType, Map<BlockPos, Pair<Integer, Integer>>> layerPosVerticesMap = new HashMap<>();
    protected final Map<BlockPos, ArrayList<Quad>> blockQuadsMap = new HashMap<>();
    protected final Map<RenderType, Map<UUID, Pair<Integer, Integer>>> layerUUIDVerticesMap = new HashMap<>();
    protected final Map<UUID, ArrayList<Quad>> entityUUIDQuadsMap = new HashMap<>();
    protected final Map<RenderType, Integer> preCountVertices = new HashMap<>();
    protected final Map<RenderType, ResourceLocation> renderResourceLocationMap = new HashMap<>();
    protected final CustomImpl impl;
    private final int lowerHeightLimit;
    private final int upperHeightLimit;
    private final int playerX;
    private final int playerZ;
    private final BlockPos startPos;
    private final BlockPos endPos;
    private final World world;
    private int currentX;
    private int currentZ;
    private BlockPos lastBlock;
    private UUID lastEntityUUID;
    private boolean lastIsBlock;

    public Exporter(ClientPlayerEntity player, int radius, int lower, int upper) {
        atlasCacheMap.put(null, computeImage(PlayerContainer.LOCATION_BLOCKS_TEXTURE));
        lowerHeightLimit = lower;
        upperHeightLimit = upper;
        playerX = (int) player.getPosX();
        playerZ = (int) player.getPosZ();
        startPos = new BlockPos(playerX + radius, upperHeightLimit, playerZ + radius);
        endPos = new BlockPos(playerX - radius, lowerHeightLimit, playerZ - radius);
        world = player.getEntityWorld();
        currentX = startPos.getX();
        currentZ = startPos.getZ();

        impl = new CustomImpl(renderResourceLocationMap);
        impl.setFallbackBufferCallback(() -> {
            int fallbackCount = impl.buffer.vertexCount - preCountVertices.getOrDefault(null, 0);
            preCountVertices.remove(null);
            impl.buffer.finishDrawing();
            ArrayList<Quad> quadList = null;
            if (lastIsBlock && lastBlock != null) {
                quadList = blockQuadsMap.computeIfAbsent(lastBlock, k -> new ArrayList<>());
            } else if (lastEntityUUID != null) {
                quadList = entityUUIDQuadsMap.computeIfAbsent(lastEntityUUID, k -> new ArrayList<>());
            }

            if (quadList != null && fallbackCount > 0) {
                com.mojang.datafixers.util.Pair<BufferBuilder.DrawState, ByteBuffer> stateBufferPair = impl.buffer.getNextBuffer();
                BufferBuilder.DrawState drawState = stateBufferPair.getFirst();
                ByteBuffer bytebuffer = stateBufferPair.getSecond();

                if (drawState.getVertexCount() == 0 || drawState.getDrawMode() != GL11.GL_QUADS || !supportedVertexFormat(drawState.getFormat())) {
                    return;
                }

                addVertices(bytebuffer, impl.buffer.getVertexFormat().getElements(), quadList, impl.lastRenderType.orElse(null), 0, drawState.getVertexCount());
            }
        });
    }

    public static BufferedImage computeImage(ResourceLocation resource) {
        int textureId = Objects.requireNonNull(Minecraft.getInstance().getTextureManager().getTexture(resource)).getGlTextureId();
//        int textureId = Objects.requireNonNull(Minecraft.getInstance().getModelManager().getAtlasTexture(resource)).getGlTextureId();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        int atlasWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int atlasHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        int size = atlasWidth * atlasHeight;
        BufferedImage atlasImage = new BufferedImage(atlasWidth, atlasHeight, TYPE_INT_ARGB);
        IntBuffer buffer = BufferUtils.createIntBuffer(size);
        int[] data = new int[size];
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        buffer.get(data);
        atlasImage.setRGB(0, 0, atlasWidth, atlasHeight, data, 0, atlasWidth);
        return atlasImage;
    }

    protected void addAllFinishedData() {
        Set<RenderType> allTypes = new HashSet<>(layerPosVerticesMap.keySet());
        allTypes.addAll(layerUUIDVerticesMap.keySet());

        for (RenderType type : allTypes) {
            BufferBuilder bufferBuilder = impl.getBufferRaw(type);
            com.mojang.datafixers.util.Pair<BufferBuilder.DrawState, ByteBuffer> stateBufferPair = bufferBuilder.getNextBuffer();
            BufferBuilder.DrawState drawState = stateBufferPair.getFirst();
            VertexFormat format = type.getVertexFormat();
            if (drawState.getVertexCount() == 0 || drawState.getDrawMode() != GL11.GL_QUADS || !supportedVertexFormat(format)) {
                continue;
            }

            ByteBuffer bytebuffer = stateBufferPair.getSecond();
            for (BlockPos pos : layerPosVerticesMap.getOrDefault(type, Collections.emptyMap()).keySet()) {
                Pair<Integer, Integer> verticesPosCount = layerPosVerticesMap.get(type).get(pos);
                int firstVertexBytePos = verticesPosCount.getLeft() * type.getVertexFormat().getSize();
                int vertexCount = verticesPosCount.getRight();
                ArrayList<Quad> quadsList = blockQuadsMap.computeIfAbsent(pos, k -> new ArrayList<>());
                addVertices(bytebuffer, type.getVertexFormat().getElements(), quadsList, type, firstVertexBytePos, vertexCount);
            }

            for (UUID uuid : layerUUIDVerticesMap.getOrDefault(type, Collections.emptyMap()).keySet()) {
                Pair<Integer, Integer> verticesPosCount = layerUUIDVerticesMap.get(type).get(uuid);
                int firstVertexBytePos = verticesPosCount.getLeft() * type.getVertexFormat().getSize();
                int vertexCount = verticesPosCount.getRight();
                ArrayList<Quad> quadsList = entityUUIDQuadsMap.computeIfAbsent(uuid, k -> new ArrayList<>());
                addVertices(bytebuffer, type.getVertexFormat().getElements(), quadsList, type, firstVertexBytePos, vertexCount);
            }
        }
    }

    protected void addVertices(ByteBuffer bytebuffer, List<VertexFormatElement> list, ArrayList<Quad> quadsList, RenderType type, int vertexStartIndex, int vertexCount) {
        ResourceLocation resource = renderResourceLocationMap.getOrDefault(type, null);
        Quad quad = new Quad(type, resource);
        boolean skipQuad = false;
        bytebuffer.position(vertexStartIndex);
        for (int vertexNum = 0; vertexNum < vertexCount; ++vertexNum) {
            if (skipQuad) {
                vertexNum += 4 - (vertexNum - 1) % 4;  // TODO: test this
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
                            bytebuffer.position(bytebuffer.position() + vertexFormatElement.getSize());
                            LOGGER.warn("Vertex position element had no supported type, skipping.");
                            continue;
                        }
                        break;
                    case COLOR:
                        if (vertexFormatElement.getType() == VertexFormatElement.Type.UBYTE) {
                            vertex.setColor(bytebuffer.getInt());
                        } else {
                            bytebuffer.position(bytebuffer.position() + vertexFormatElement.getSize());
                            LOGGER.warn("Vertex color element had no supported type, skipping.");
                            continue;
                        }
                        break;
                    case UV:
                        switch (vertexFormatElement.getType()) {
                            case FLOAT:
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
                            case SHORT:
                                // TODO: ensure value / 16 / (2^16 - 1) gives proper 0-1 float range
                                //  Minecraft.getMinecraft().getTextureManager().getTexture(new ResourceLocation( "minecraft", "dynamic/lightmap_1"))
                                //  Discard first short (sky light) and only use second (block light) when implementing emissive lighting?
                                if (vertexFormatElement.getIndex() == DefaultVertexFormats.TEX_2SB.getIndex()) {
                                    vertex.setUvlight(new Vector2f(bytebuffer.getShort() / 65520.0f, bytebuffer.getShort() / 65520.0f));
                                }
//                               else if (vertexFormatElement.getIndex() == DefaultVertexFormats.TEX_2S.getIndex()) {  // TODO: determine what index 1 (TEX_2S) is used for in entity rendering
//
//                               }
                                else {
                                    bytebuffer.position(bytebuffer.position() + vertexFormatElement.getSize());
                                    continue;
                                }
                                break;
                            default:
                                bytebuffer.position(bytebuffer.position() + vertexFormatElement.getSize());
                                LOGGER.warn("Vertex UV element had no supported type, skipping.");
                                continue;
                        }
                        break;
                    case PADDING:
                    case NORMAL:
                    default:
                        bytebuffer.position(bytebuffer.position() + vertexFormatElement.getSize());
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

    // Resets bufferBuilders, offsets, and all internal quad lists
    protected void resetBuilders() {
        for (RenderType type : layerPosVerticesMap.keySet()) {
            BufferBuilder buf = impl.getBufferRaw(type);
            if (buf.isDrawing()) buf.finishDrawing();
            buf.discard();
        }

        for (RenderType type : layerUUIDVerticesMap.keySet()) {
            BufferBuilder buf = impl.getBufferRaw(type);
            if (buf.isDrawing()) buf.finishDrawing();
            buf.discard();
        }

        impl.finish();

        layerPosVerticesMap.clear();
        blockQuadsMap.clear();
        layerUUIDVerticesMap.clear();
        entityUUIDQuadsMap.clear();
        preCountVertices.clear();
    }

    protected void preCountLayerVertices() {
        preCountVertices.clear();

        // fallback buffer count, key of null, since it has no type
        preCountVertices.put(null, impl.buffer.vertexCount);  // null refers to the fallback bufferBuilder, which has no type

        // loop over Impl renderTypes, get buffer, get vertex count from that, and store it
        for (RenderType type : impl.fixedBuffers.keySet()) {
            preCountVertices.put(type, impl.getBufferRaw(type).vertexCount);
        }

    }

    protected void postCountLayerVertices() {
        // post counting and data handling for fallback buffer is handled with callback in CustomImpl.finish()

        // loop over Impl renderTypes, get buffer, get vertex count from that, and store the difference from pre count
        for (RenderType type : impl.fixedBuffers.keySet()) {
            int prevCount = preCountVertices.get(type);
            int difference = impl.getBufferRaw(type).vertexCount - prevCount;
            if (difference == 0) continue;

            if (lastIsBlock) {
                layerPosVerticesMap.computeIfAbsent(type, k -> new HashMap<>()).put(lastBlock, Pair.of(prevCount, difference));
            } else {
                layerUUIDVerticesMap.computeIfAbsent(type, k -> new HashMap<>()).put(lastEntityUUID, Pair.of(prevCount, difference));
            }
        }
    }

    private void preEntity(UUID entityUUID) {
        lastIsBlock = false;
        lastEntityUUID = entityUUID;
    }

    private void preBlock(BlockPos pos) {
        lastIsBlock = true;
        lastBlock = pos;
    }

    private boolean supportedVertexFormat(VertexFormat format) {
        return format == DefaultVertexFormats.BLOCK || format == DefaultVertexFormats.ENTITY;
    }

    public boolean getNextChunkData() {
        if (currentX < endPos.getX() || currentZ < endPos.getZ()) {
            return false;
        }

        resetBuilders();
        AmbientOcclusionStatus pre = mc.gameSettings.ambientOcclusionStatus;
        mc.gameSettings.ambientOcclusionStatus = AmbientOcclusionStatus.OFF;

        // ((a % b) + b) % b gives true modulus instead of just remainder
        int chunkXOffset = ((currentX % 16) + 16) % 16;
        int chunkZOffset = ((currentZ % 16) + 16) % 16;
        BlockPos thisChunkStart = new BlockPos(currentX, upperHeightLimit, currentZ);
        BlockPos thisChunkEnd = new BlockPos(Math.max(currentX - chunkXOffset, endPos.getX()), lowerHeightLimit, Math.max(currentZ - chunkZOffset, endPos.getZ()));
        Random random = new Random();
        MatrixStack matrixStack = new MatrixStack();
        matrixStack.translate(-playerX, 0, -playerZ);

        for (BlockPos pos : BlockPos.getAllInBoxMutable(thisChunkStart, thisChunkEnd)) {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock().isAir(state, world, pos)) {
                continue;
            }

            preCountLayerVertices();
            preBlock(pos.toImmutable());

            if (state.hasTileEntity()) {
                TileEntity tileentity = world.getChunkAt(pos).getTileEntity(pos);
                if (tileentity != null) {
                    TileEntityRenderer<TileEntity> tileEntityRenderer = TileEntityRendererDispatcher.instance.getRenderer(tileentity);
                    int i = WorldRenderer.getCombinedLight(world, tileentity.getPos());
                    if (tileEntityRenderer != null) {
                        matrixStack.push();
                        matrixStack.translate(pos.getX(), pos.getY(), pos.getZ());
                        float partialTicks = 0;
                        tileEntityRenderer.render(tileentity, partialTicks, matrixStack, impl, i, OverlayTexture.NO_OVERLAY);
                        matrixStack.pop();
                    }
                }
            }

            IFluidState ifluidstate = world.getFluidState(pos);
            IModelData modelData = ModelDataManager.getModelData(world, pos);
            for (RenderType rendertype : RenderType.getBlockRenderTypes()) {
                if (!ifluidstate.isEmpty() && RenderTypeLookup.canRenderInLayer(ifluidstate, rendertype)) {
                    BufferBuilder bufferbuilder = impl.getBuffer(rendertype);  // automatically starts buffer
                    blockRendererDispatcher.renderFluid(pos, world, bufferbuilder, ifluidstate, playerX, playerZ);
                }

                if (state.getRenderType() != BlockRenderType.INVISIBLE && RenderTypeLookup.canRenderInLayer(state, rendertype)) {
                    BufferBuilder bufferbuilder2 = impl.getBuffer(rendertype);   // automatically starts buffer
                    matrixStack.push();
                    matrixStack.translate(pos.getX(), pos.getY(), pos.getZ());
                    blockRendererDispatcher.renderModel(state, pos, world, matrixStack, bufferbuilder2, true, random, modelData);
                    matrixStack.pop();
                }

            }

            postCountLayerVertices();
        }

        // TODO: loop over world.getAllEntities() here


        // finish all builders that were drawing, then add the data
        impl.finish();

        // TODO: need to call impl.buffer callback here if drawing or not?
//        if (impl.buffer.isDrawing())
        if (impl.buffer.vertexCount > 0) {
            throw new IllegalStateException();
        }

        addAllFinishedData();
        mc.gameSettings.ambientOcclusionStatus = pre;

        // Update the current position to be the starting position of the next chunk export (which may be
        // outside the selected boundary, accounted for at the beginning of the function call).
        currentX -= (thisChunkStart.getX() - thisChunkEnd.getX() + 1);
        if (currentX < endPos.getX()) {
            currentX = startPos.getX();
            currentZ -= (thisChunkStart.getZ() - thisChunkEnd.getZ() + 1);
        }

        return true;
    }

    public BufferedImage getAtlasImage(ResourceLocation resource) {
        return atlasCacheMap.computeIfAbsent(resource, Exporter::computeImage);
    }
}
