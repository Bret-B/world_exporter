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
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.Texture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.tileentity.TileEntityRenderer;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.client.settings.AmbientOcclusionStatus;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.inventory.container.PlayerContainer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
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
import java.util.stream.Collectors;

import static java.awt.image.BufferedImage.TYPE_INT_ARGB;

public class Exporter {
    protected static final Logger LOGGER = LogManager.getLogger(WorldExporter.MODID);
    protected final Minecraft mc = Minecraft.getInstance();
    protected final CustomBlockRendererDispatcher blockRendererDispatcher = new CustomBlockRendererDispatcher(mc.getBlockRenderer().getBlockModelShaper(), mc.getBlockColors());
    protected final Map<ResourceLocation, BufferedImage> atlasCacheMap = new HashMap<>();
    protected final Map<RenderType, Map<BlockPos, Pair<Integer, Integer>>> layerPosVerticesMap = new HashMap<>();
    protected final Map<BlockPos, ArrayList<Quad>> blockQuadsMap = new HashMap<>();
    protected final Map<RenderType, Map<UUID, Pair<Integer, Integer>>> layerUUIDVerticesMap = new HashMap<>();
    protected final Map<UUID, ArrayList<Quad>> entityUUIDQuadsMap = new HashMap<>();
    protected final Map<RenderType, Integer> preCountVertices = new HashMap<>();
    protected final Map<RenderType, ResourceLocation> renderResourceLocationMap = new HashMap<>();
    protected final CustomImpl impl;
    private final Map<Pair<String, UVBounds>, Pair<ResourceLocation, TextureAtlasSprite>> atlasUVToSpriteCache = new HashMap<>();
    private final ClientWorld world = Objects.requireNonNull(mc.level);
    private final int lowerHeightLimit;
    private final int upperHeightLimit;
    private final int playerX;
    private final int playerZ;
    private final boolean randomize;
    private final BlockPos startPos;  // higher values
    private final BlockPos endPos;  // lower values
    private int currentX;
    private int currentZ;
    private BlockPos lastFixedBlock;
    private UUID lastFixedEntityUUID;
    private boolean lastFixedIsBlock;
    private BlockPos lastFallbackBlock;
    private UUID lastFallbackEntityUUID;
    private boolean lastFallbackIsBlock;

    public Exporter(ClientPlayerEntity player, int radius, int lower, int upper, boolean randomize) {
        this.randomize = randomize;
        lowerHeightLimit = lower;
        upperHeightLimit = upper;
        playerX = (int) player.getX();
        playerZ = (int) player.getZ();
        startPos = new BlockPos(playerX + radius, upperHeightLimit, playerZ + radius);
        endPos = new BlockPos(playerX - radius, lowerHeightLimit, playerZ - radius);
        currentX = startPos.getX();
        currentZ = startPos.getZ();
        impl = new CustomImpl(this);
    }

    // only ResourceLocations with an associated Texture should be used
    public static BufferedImage computeImage(ResourceLocation resource) {
        Texture texture = Minecraft.getInstance().getTextureManager().getTexture(resource);
        if (texture == null) return null;

        int textureId = texture.getId();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        int size = width * height;
        BufferedImage image = new BufferedImage(width, height, TYPE_INT_ARGB);
        IntBuffer buffer = BufferUtils.createIntBuffer(size);
        int[] data = new int[size];
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        buffer.get(data);
        image.setRGB(0, 0, width, height, data, 0, width);
        return image;
    }

    // return true if the bit in a bitset for a given direction is set
    public static boolean isForced(BitSet bitSet, Direction direction) {
        return bitSet.get(direction.get3DDataValue());
    }

    // Flips quad V values
    protected static void flipV(List<Quad> quads) {
        for (Quad quad : quads) {
            for (Vertex vertex : quad.getVertices()) {
                vertex.getUv().y = 1 - vertex.getUv().y;
            }
        }
    }

    protected void addAllFinishedData() {
        Set<RenderType> allTypes = new HashSet<>(layerPosVerticesMap.keySet());
        allTypes.addAll(layerUUIDVerticesMap.keySet());

        for (RenderType type : allTypes) {
            BufferBuilder bufferBuilder = impl.getBuilderRaw(type);
            com.mojang.datafixers.util.Pair<BufferBuilder.DrawState, ByteBuffer> stateBufferPair = bufferBuilder.popNextBuffer();
            BufferBuilder.DrawState drawState = stateBufferPair.getFirst();
            VertexFormat format = type.format();
            if (drawState.vertexCount() == 0 || drawState.mode() != GL11.GL_QUADS || !supportedVertexFormat(format)) {
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

    protected void addVertices(ByteBuffer bytebuffer, List<VertexFormatElement> list, ArrayList<Quad> quadsList, RenderType type, int vertexStartIndex, int vertexCount) {
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
                                vertex.setUvlight(new Vector2f(bytebuffer.getShort() / 65520.0f, bytebuffer.getShort() / 65520.0f));
                                break;
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

    // Resets bufferBuilders, offsets, and all internal quad lists
    protected void resetBuilders() {
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
        preCountVertices.clear();
    }

    protected void preCountLayerVertices() {
        preCountVertices.clear();

        // fallback buffer count, key of null, since it has no type
        preCountVertices.put(null, impl.builder.vertices);  // null refers to the fallback bufferBuilder, which has no single render type

        // loop over Impl renderTypes, get buffer, get vertex count from that, and store it
        for (RenderType type : impl.fixedBuffers.keySet()) {
            preCountVertices.put(type, impl.getBuilderRaw(type).vertices);
        }
    }

    protected void postCountLayerVertices() {
        // post counting and data handling for fallback buffer is handled with callback in CustomImpl.finish()

        // loop over Impl renderTypes, get buffer, get vertex count from that, and store the difference from pre count
        for (RenderType type : impl.fixedBuffers.keySet()) {
            int prevCount = preCountVertices.get(type);
            int difference = impl.getBuilderRaw(type).vertices - prevCount;
            if (difference == 0) continue;

            if (lastFixedIsBlock) {
                layerPosVerticesMap.computeIfAbsent(type, k -> new HashMap<>()).put(lastFixedBlock, Pair.of(prevCount, difference));
            } else {
                layerUUIDVerticesMap.computeIfAbsent(type, k -> new HashMap<>()).put(lastFixedEntityUUID, Pair.of(prevCount, difference));
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

        if (drawState.vertexCount() == 0 || drawState.mode() != GL11.GL_QUADS || !supportedVertexFormat(drawState.format())) {
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

    private boolean supportedVertexFormat(VertexFormat format) {
        return format.getElements().contains(DefaultVertexFormats.ELEMENT_POSITION) && format.getElements().contains(DefaultVertexFormats.ELEMENT_UV0);
    }

    // Returns the facing directions that should be forcibly enabled (at the edge of the export) for a given BlockPos
    public BitSet getForcedDirections(BlockPos pos) {
        BitSet bitSet = new BitSet();
        if (pos.getX() >= startPos.getX())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.X, Direction.AxisDirection.POSITIVE).get3DDataValue());
        if (pos.getX() <= endPos.getX())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.X, Direction.AxisDirection.NEGATIVE).get3DDataValue());
        if (pos.getY() >= startPos.getY())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.Y, Direction.AxisDirection.POSITIVE).get3DDataValue());
        if (pos.getY() <= endPos.getY())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.Y, Direction.AxisDirection.NEGATIVE).get3DDataValue());
        if (pos.getZ() >= startPos.getZ())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.Z, Direction.AxisDirection.POSITIVE).get3DDataValue());
        if (pos.getZ() <= endPos.getZ())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.Z, Direction.AxisDirection.NEGATIVE).get3DDataValue());
        return bitSet;
    }

    public boolean getNextChunkData() {
        if (currentX < endPos.getX() || currentZ < endPos.getZ()) {
            return false;
        }

        resetBuilders();
        AmbientOcclusionStatus preAO = mc.options.ambientOcclusion;
        boolean preShadows = mc.options.entityShadows;
        mc.options.ambientOcclusion = AmbientOcclusionStatus.OFF;
        mc.options.entityShadows = false;

        // ((a % b) + b) % b gives true modulus instead of just remainder
        int chunkXOffset = ((currentX % 16) + 16) % 16;
        int chunkZOffset = ((currentZ % 16) + 16) % 16;
        BlockPos thisChunkStart = new BlockPos(currentX, upperHeightLimit, currentZ);
        BlockPos thisChunkEnd = new BlockPos(Math.max(currentX - chunkXOffset, endPos.getX()), lowerHeightLimit, Math.max(currentZ - chunkZOffset, endPos.getZ()));
        Random random = new Random();
        MatrixStack matrixStack = new MatrixStack();

        for (BlockPos pos : BlockPos.betweenClosed(thisChunkStart, thisChunkEnd)) {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock().isAir(state, world, pos)) {
                continue;
            }

            preCountLayerVertices();
            preBlock(pos.immutable());

            if (state.hasTileEntity()) {
                TileEntity tileentity = world.getChunkAt(pos).getBlockEntity(pos);
                if (tileentity != null) {
                    TileEntityRenderer<TileEntity> tileEntityRenderer = TileEntityRendererDispatcher.instance.getRenderer(tileentity);
                    int i = WorldRenderer.getLightColor(world, tileentity.getBlockPos());
                    if (tileEntityRenderer != null) {
                        matrixStack.pushPose();
                        matrixStack.translate(pos.getX(), pos.getY(), pos.getZ());
                        float partialTicks = 0;
                        tileEntityRenderer.render(tileentity, partialTicks, matrixStack, impl, i, OverlayTexture.NO_OVERLAY);
                        matrixStack.popPose();
                    }
                }
            }

            // The rendering logic is roughly taken from ChunkRenderDispatcher.compile with multiple tweaks
            FluidState fluidState = world.getFluidState(pos);
            IModelData modelData = ModelDataManager.getModelData(world, pos);
            for (RenderType rendertype : RenderType.chunkBufferLayers()) {
                if (!fluidState.isEmpty() && RenderTypeLookup.canRenderInLayer(fluidState, rendertype)) {
                    BitSet forceRender = getForcedDirections(pos);
                    BufferBuilder bufferbuilder = impl.getBuffer(rendertype);  // automatically starts buffer
                    blockRendererDispatcher.renderLiquid(pos, world, bufferbuilder, fluidState, 0, 0, forceRender);
                }

                if (state.getRenderShape() != BlockRenderType.INVISIBLE && RenderTypeLookup.canRenderInLayer(state, rendertype)) {
                    matrixStack.pushPose();
                    matrixStack.translate(pos.getX(), pos.getY(), pos.getZ());
                    BitSet forceRender = getForcedDirections(pos);
                    BufferBuilder bufferbuilder = impl.getBuffer(rendertype);   // automatically starts buffer
                    // TODO: setting a fixed seed is not enough, texture randomness is seemingly baked into block positions/states
                    //  could potentially modify the block state before rendering to remove this. This would greatly decrease mesh size
                    blockRendererDispatcher.renderModel(state, pos, world, matrixStack, bufferbuilder, forceRender, random, modelData, randomize);

                    // use renderBlock? potentially support for more blocks, like animated type blocks?
//                  if (state.getRenderShape() == BlockRenderType.ENTITYBLOCK_ANIMATED && modelData != null) {
//                      int packedLight = 15 << 20 | 15 << 4;  // .lightmap(240, 240) is full-bright
//                      blockRendererDispatcher.renderBlock(state, matrixStack, impl, packedLight, OverlayTexture.NO_OVERLAY, modelData);
//                  }

                    matrixStack.popPose();
                }
            }

            postCountLayerVertices();
        }

        // export all entities within chunk
        for (Entity entity : world.getEntities(null, new AxisAlignedBB(thisChunkStart, thisChunkEnd))) {
            preEntity(entity.getUUID());
            preCountLayerVertices();
            matrixStack.pushPose();
            float partialTicks = 0;
            int packedLight = 15 << 20 | 15 << 4;  // .lightmap(240, 240) is full-bright
            mc.getEntityRenderDispatcher().render(entity, entity.getX(), entity.getY(), entity.getZ(), entity.yRot,
                    partialTicks, matrixStack, impl, packedLight);
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
        blockQuadsMap.values().forEach(this::translateQuads);
        entityUUIDQuadsMap.values().forEach(this::translateQuads);
        blockQuadsMap.values().forEach(Exporter::flipV);
        entityUUIDQuadsMap.values().forEach(Exporter::flipV);
        mc.options.ambientOcclusion = preAO;
        mc.options.entityShadows = preShadows;

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

    // returns null if the provided ResourceLocation does not refer to an AtlasTexture
    // could check if this is equivalent to MissingTextureSprite if this is ever a problem
    private Pair<ResourceLocation, TextureAtlasSprite> getTextureFromAtlas(ResourceLocation resource, UVBounds uvBounds) {
        Texture texture = Minecraft.getInstance().textureManager.getTexture(resource);
        if (!(texture instanceof AtlasTexture)) return null;
        AtlasTexture atlasTexture = (AtlasTexture) texture;

        // Currently this is a memoized linear check over all an atlasTexture's TextureAtlasSprites to find
        // which TextureAtlasSprite contains the given UVBounds
        // If this is ever too slow a structure like a quadtree or spatial hashing could be used, but profiling shows this to be a non-issue
        return atlasUVToSpriteCache.computeIfAbsent(Pair.of(resource.toString(), new UVBounds(uvBounds)), k -> {
            for (ResourceLocation name : atlasTexture.texturesByName.keySet()) {
                TextureAtlasSprite sprite = atlasTexture.getSprite(name);
                float uMin = sprite.getU0();
                float uMax = sprite.getU1();
                float vMin = sprite.getV0();
                float vMax = sprite.getV1();
                if (uvBounds.uMin >= uMin && uvBounds.uMax <= uMax && uvBounds.vMin >= vMin && uvBounds.vMax <= vMax) {
                    return Pair.of(name, sprite);
                }
            }
            return null;
        });
    }

    // For every quad: if its texture refers to an atlasImage, update its texture resourceLocation, add a reference to its sprite,
    // and update its UV coordinates respectively (in place)
    private void updateQuadTextures() {
        List<Quad> quads = blockQuadsMap.values().stream().flatMap(Collection::stream).collect(Collectors.toList());
        quads.addAll(entityUUIDQuadsMap.values().stream().flatMap(Collection::stream).collect(Collectors.toList()));
        for (Quad quad : quads) {
            Pair<ResourceLocation, TextureAtlasSprite> nameAndTexture = getTextureFromAtlas(quad.getResource(), quad.getUvBounds());
            boolean didModifyUV = false;
            // allowed error is very small by default
            float allowableErrorU = 0.00001f;
            float allowableErrorV = 0.00001f;
            if (nameAndTexture != null) {
                quad.setResource(nameAndTexture.getLeft());
                TextureAtlasSprite texture = nameAndTexture.getRight();
                // recalculate the allowable error based on the texture's size so that any rounding does not change
                // the texture's display on a texture-pixel level
                allowableErrorU = 1.0F / texture.getWidth() / 2.0F;
                allowableErrorV = 1.0F / texture.getHeight() / 2.0F;
                for (Vertex vertex : quad.getVertices()) {
                    Vector2f uv = vertex.getUv();
                    uv.x = (uv.x - texture.getU0()) / (texture.getU1() - texture.getU0());
                    uv.y = (uv.y - texture.getV0()) / (texture.getV1() - texture.getV0());
                }
                quad.setTexture(texture);
                didModifyUV = true;
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

    // Translate the quad vertex positions (in place) such that the players original position is the center of the import (except for y coordinates)
    protected void translateQuads(List<Quad> quads) {
        for (Quad quad : quads) {
            for (Vertex vertex : quad.getVertices()) {
                vertex.getPosition().translate(-playerX, 0, -playerZ);
            }
        }
    }
}
