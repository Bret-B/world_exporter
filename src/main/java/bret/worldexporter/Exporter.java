package bret.worldexporter;

import bret.worldexporter.legacylwjgl.Vector2f;
import bret.worldexporter.legacylwjgl.Vector3f;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RegionRenderCacheBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.fluid.IFluidState;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.model.ModelDataManager;
import net.minecraftforge.client.model.data.IModelData;
import org.apache.commons.lang3.tuple.ImmutablePair;
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
    protected static final Logger logger = LogManager.getLogger(WorldExporter.MODID);
    protected final Minecraft mc = Minecraft.getInstance();
    protected final CustomBlockRendererDispatcher blockRenderer = new CustomBlockRendererDispatcher(mc.getBlockRendererDispatcher().getBlockModelShapes(), mc.getBlockColors());
    protected final RegionRenderCacheBuilder renderCacheBuilder = new RegionRenderCacheBuilder();
    protected final boolean[] startedBufferBuilders = new boolean[BlockRenderLayer.values().length];
    protected final BufferedImage atlasImage;
    protected final Map<BlockRenderLayer, Map<BlockPos, Pair<Integer, Integer>>> layerPosVerticesMap = new HashMap<>();
    protected final Map<BlockPos, ArrayList<Quad>> blockQuadsMap = new HashMap<>();
    private final int lowerHeightLimit;
    private final int upperHeightLimit;
    private final int playerX;
    private final int playerZ;
    private final BlockPos startPos;
    private final BlockPos endPos;
    private final World world;
    private int currentX;
    private int currentZ;

    public Exporter(ClientPlayerEntity player, int radius, int lower, int upper) {
        // Create entire atlas image as a BufferedImage to be used when exporting
        int textureId = mc.getTextureMap().getGlTextureId();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        int atlasWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int atlasHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        int size = atlasWidth * atlasHeight;
        atlasImage = new BufferedImage(atlasWidth, atlasHeight, TYPE_INT_ARGB);
        IntBuffer buffer = BufferUtils.createIntBuffer(size);
        int[] data = new int[size];
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        buffer.get(data);
        atlasImage.setRGB(0, 0, atlasWidth, atlasHeight, data, 0, atlasWidth);

        lowerHeightLimit = lower;
        upperHeightLimit = upper;
        playerX = (int) player.posX;
        playerZ = (int) player.posZ;
        startPos = new BlockPos(playerX + radius, upperHeightLimit, playerZ + radius);
        endPos = new BlockPos(playerX - radius, lowerHeightLimit, playerZ - radius);
        world = player.getEntityWorld();
        currentX = startPos.getX();
        currentZ = startPos.getZ();
    }

    protected void addBuilderData(RegionRenderCacheBuilder renderCacheBuilder) {
        for (int blockRenderLayerId = 0; blockRenderLayerId < BlockRenderLayer.values().length; ++blockRenderLayerId) {
            BufferBuilder bufferBuilder = renderCacheBuilder.getBuilder(blockRenderLayerId);
            if (bufferBuilder.getVertexCount() == 0 || bufferBuilder.getDrawMode() != GL11.GL_QUADS) {
                continue;
            }
            VertexFormat vertexFormat = bufferBuilder.getVertexFormat();
            int vertexByteSize = vertexFormat.getIntegerSize() * 4;
            ByteBuffer bytebuffer = bufferBuilder.getByteBuffer();
            List<VertexFormatElement> list = vertexFormat.getElements();

            for (BlockPos pos : layerPosVerticesMap.get(BlockRenderLayer.values()[blockRenderLayerId]).keySet()) {
                Pair<Integer, Integer> verticesPosCount = layerPosVerticesMap.get(BlockRenderLayer.values()[blockRenderLayerId]).get(pos);
                int firstVertexBytePos = verticesPosCount.getLeft() * vertexByteSize;
                int vertexCount = verticesPosCount.getRight();

                if (vertexCount == 0) {
                    continue;
                }

                Quad quad = new Quad(BlockRenderLayer.values()[blockRenderLayerId]);
                boolean skipQuad = false;
                bytebuffer.position(firstVertexBytePos);
                for (int vertexNum = 0; vertexNum < vertexCount; ++vertexNum) {
                    if (skipQuad) {
                        vertexNum += 4 - (vertexNum - 1) % 4;
                        if (vertexNum >= vertexCount) break;
                        bytebuffer.position(bytebuffer.position() + (4 - ((vertexNum - 1) % 4)));
                        quad = new Quad(BlockRenderLayer.values()[blockRenderLayerId]);
                        skipQuad = false;
                    } else if (quad.getCount() == 4) {
                        blockQuadsMap.computeIfAbsent(pos, k -> new ArrayList<>()).add(quad);
                        quad = new Quad(BlockRenderLayer.values()[blockRenderLayerId]);
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
                                    logger.warn("Vertex position element had no supported type, skipping.");
                                    continue;
                                }
                                break;
                            case COLOR:
                                if (vertexFormatElement.getType() == VertexFormatElement.Type.UBYTE) {
                                    vertex.setColor(bytebuffer.getInt());
                                } else {
                                    bytebuffer.position(bytebuffer.position() + vertexFormatElement.getSize());
                                    logger.warn("Vertex color element had no supported type, skipping.");
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
                                            logger.warn("Quad being skipped since a vertex had a UV coordinate of NaN.");
                                            break;
                                        }

                                        if (u > 1.0f || u < 0 || v > 1.0f || v < 0) {
                                            skipQuad = true;
                                            logger.warn("Quad being skipped because UV was out of bounds on add.");
                                            break;
                                        }

                                        vertex.setUv(new Vector2f(u, v));
                                        break;
                                    case SHORT:
                                        // TODO: ensure value / 16 / (2^16 - 1) gives proper 0-1 float range
                                        //  Minecraft.getMinecraft().getTextureManager().getTexture(new ResourceLocation( "minecraft", "dynamic/lightmap_1"))
                                        //  Discard first short (sky light) and only use second (block light) when implementing emissive lighting?
                                        vertex.setUvlight(new Vector2f(bytebuffer.getShort() / 65520.0f, bytebuffer.getShort() / 65520.0f));
                                        break;
                                    default:
                                        bytebuffer.position(bytebuffer.position() + vertexFormatElement.getSize());
                                        logger.warn("Vertex UV element had no supported type, skipping.");
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
                    blockQuadsMap.computeIfAbsent(pos, k -> new ArrayList<>()).add(quad);
                }
            }
        }
    }

    // Resets bufferBuilders, offsets, and all internal quad lists
    protected void resetBuilders() {
        Arrays.fill(startedBufferBuilders, false);
        for (BlockRenderLayer blockRenderLayer : BlockRenderLayer.values()) {
            int blockRenderLayerId = blockRenderLayer.ordinal();
            BufferBuilder bufferBuilder = renderCacheBuilder.getBuilder(blockRenderLayerId);
            bufferBuilder.setTranslation(-playerX, 0, -playerZ);
            bufferBuilder.reset();
        }

        layerPosVerticesMap.clear();
        blockQuadsMap.clear();
    }

    public boolean getNextChunkData() {
        if (currentX < endPos.getX() || currentZ < endPos.getZ()) {
            return false;
        }

        resetBuilders();

        // ((a % b) + b) % b gives true modulus instead of just remainder
        int chunkXOffset = ((currentX % 16) + 16) % 16;
        int chunkZOffset = ((currentZ % 16) + 16) % 16;
        BlockPos thisChunkStart = new BlockPos(currentX, upperHeightLimit, currentZ);
        BlockPos thisChunkEnd = new BlockPos(Math.max(currentX - chunkXOffset, endPos.getX()), lowerHeightLimit, Math.max(currentZ - chunkZOffset, endPos.getZ()));
        Random random = new Random();
        for (BlockPos pos : BlockPos.getAllInBoxMutable(thisChunkStart, thisChunkEnd)) {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock().isAir(state, world, pos)) {
                continue;
            }

            IFluidState ifluidstate = world.getFluidState(pos);
            IModelData modelData = ModelDataManager.getModelData(world, pos);
            for (BlockRenderLayer blockrenderlayer1 : BlockRenderLayer.values()) {
                if (!ifluidstate.canRenderInLayer(blockrenderlayer1) && !state.canRenderInLayer(blockrenderlayer1)) {
                    continue;
                }

                int blockRenderLayerId = blockrenderlayer1.ordinal();
                net.minecraftforge.client.ForgeHooksClient.setRenderLayer(blockrenderlayer1);
                BufferBuilder bufferbuilder = renderCacheBuilder.getBuilder(blockRenderLayerId);
                int vertexCountPre = bufferbuilder.getVertexCount();

                if (!ifluidstate.isEmpty() && ifluidstate.canRenderInLayer(blockrenderlayer1)) {
                    if (!startedBufferBuilders[blockRenderLayerId]) {
                        startedBufferBuilders[blockRenderLayerId] = true;
                        bufferbuilder.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                    }

                    try {
                        blockRenderer.renderFluid(pos, world, bufferbuilder, ifluidstate);
                    } catch (Exception exception) {
                        logger.warn("Unable to render block: " + state.getBlock() + "      with position: " + pos + "\n" + exception);
                    }
                }

                // note: may need to check if modelData == null in the future, but it is unused at the moment
                if (state.getRenderType() != BlockRenderType.INVISIBLE && state.canRenderInLayer(blockrenderlayer1)) {
                    if (!startedBufferBuilders[blockRenderLayerId]) {
                        startedBufferBuilders[blockRenderLayerId] = true;
                        bufferbuilder.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                    }

                    try {
                        blockRenderer.renderBlock(state, pos, world, bufferbuilder, random, modelData);
                    } catch (Exception exception) {
                        logger.warn("Unable to render block: " + state.getBlock() + "      with position: " + pos + "\n" + exception);
                    }
                }

                int addedVertexCount = bufferbuilder.getVertexCount() - vertexCountPre;
                if (addedVertexCount > 0) {
                    layerPosVerticesMap.computeIfAbsent(blockrenderlayer1, k -> new HashMap<>()).put(pos.toImmutable(), new ImmutablePair<>(vertexCountPre, addedVertexCount));
                }
            }
            ForgeHooksClient.setRenderLayer(null);
        }

        for (int blockRenderLayerId = 0; blockRenderLayerId < startedBufferBuilders.length; ++blockRenderLayerId) {
            if (!startedBufferBuilders[blockRenderLayerId]) {
                continue;
            }
            renderCacheBuilder.getBuilder(blockRenderLayerId).finishDrawing();
        }

        addBuilderData(renderCacheBuilder);

        // Update the current position to be the starting position of the next chunk export (which may be
        // outside the selected boundary, accounted for at the beginning of the function call).
        currentX -= (thisChunkStart.getX() - thisChunkEnd.getX() + 1);
        if (currentX < endPos.getX()) {
            currentX = startPos.getX();
            currentZ -= (thisChunkStart.getZ() - thisChunkEnd.getZ() + 1);
        }

        return true;
    }
}
