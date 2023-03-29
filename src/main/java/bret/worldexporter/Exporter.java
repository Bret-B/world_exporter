package bret.worldexporter;

import bret.worldexporter.legacylwjgl.Vector2f;
import bret.worldexporter.legacylwjgl.Vector3f;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.renderer.*;
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
import java.awt.image.RasterFormatException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.stream.Collectors;

import static java.awt.image.BufferedImage.TYPE_INT_ARGB;

public class Exporter {
    protected static final Logger LOGGER = LogManager.getLogger(WorldExporter.MODID);
    private static final int OTHER_ORDER = 3;
    private static final Map<RenderType, Integer> renderOrder = new HashMap<RenderType, Integer>() {{
        put(RenderType.solid(), 0);
        put(Atlases.solidBlockSheet(), 0);
        put(RenderType.cutout(), 1);
        put(Atlases.cutoutBlockSheet(), 1);
        put(RenderType.cutoutMipped(), 2);
        put(RenderType.tripwire(), Integer.MAX_VALUE - 1);
        put(RenderType.translucent(), Integer.MAX_VALUE);
        put(RenderType.translucentMovingBlock(), Integer.MAX_VALUE);
        put(RenderType.translucentNoCrumbling(), Integer.MAX_VALUE);
        put(Atlases.translucentItemSheet(), Integer.MAX_VALUE);
        put(Atlases.translucentCullBlockSheet(), Integer.MAX_VALUE);
    }};
    protected final Minecraft mc = Minecraft.getInstance();
    protected final CustomBlockRendererDispatcher blockRendererDispatcher = new CustomBlockRendererDispatcher(mc.getBlockRenderer().getBlockModelShaper(), mc.getBlockColors());
    protected final Map<ResourceLocation, BufferedImage> atlasCacheMap = new HashMap<>();
    protected final Map<RenderType, Map<BlockPos, Pair<Integer, Integer>>> layerPosVerticesMap = new HashMap<>();
    protected final Map<BlockPos, ArrayList<Quad>> blockQuadsMap = new HashMap<>();
    protected final Map<RenderType, Map<UUID, Pair<Integer, Integer>>> layerUUIDVerticesMap = new HashMap<>();
    protected final Map<UUID, ArrayList<Quad>> entityUUIDQuadsMap = new HashMap<>();
    protected final Map<RenderType, ResourceLocation> renderResourceLocationMap = new HashMap<>();
    protected final CustomImpl impl;
    private final Map<Pair<String, UVBounds>, Pair<ResourceLocation, TextureAtlasSprite>> atlasUVToSpriteCache = new HashMap<>();
    private final ClientWorld world = Objects.requireNonNull(mc.level);
    private final Map<Pair<ResourceLocation, UVBounds>, Float> uvTransparencyCache = new HashMap<>();
    private final Comparator<Quad> quadComparator = getQuadSort();
    private final int lowerHeightLimit;
    private final int upperHeightLimit;
    private final int playerX;
    private final int playerZ;
    private final boolean randomize;
    private final boolean optimizeMesh;
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

    public Exporter(ClientPlayerEntity player, int radius, int lower, int upper, boolean optimizeMesh, boolean randomize) {
        this.randomize = randomize;
        this.optimizeMesh = optimizeMesh;
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

    protected static void removeDuplicateQuads(Collection<ArrayList<Quad>> quadsArrays) {
        for (ArrayList<Quad> quads : quadsArrays) {
            Set<Integer> added = new HashSet<>();
            ArrayList<Quad> uniqueQuads = new ArrayList<>();
            for (int i = 0; i < quads.size(); ++i) {
                if (added.contains(i)) {
                    continue;
                }

                Quad q1 = quads.get(i);
                uniqueQuads.add(q1);
                added.add(i);

                for (int j = i + 1; j < quads.size(); ++j) {
                    if (added.contains(j)) {
                        continue;
                    }

                    if (q1.isEquivalentTo(quads.get(j))) {
                        added.add(j);  // treat the quad as already added since it is equivalent to one that has already been added
                    }
                }
            }

            quads.clear();
            quads.addAll(uniqueQuads);
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
        impl.clearVerticesCounts();
    }

    protected void postCountLayerVertices() {
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

    public boolean hasMoreData() {
        return currentX >= endPos.getX() && currentZ >= endPos.getZ();
    }

    public ArrayList<Quad> getNextChunkData() {
        ArrayList<Quad> quads = new ArrayList<>();
        if (currentX < endPos.getX() || currentZ < endPos.getZ()) {
            return quads;
        }

        resetBuilders();
        AmbientOcclusionStatus preAO = mc.options.ambientOcclusion;
        boolean preShadows = mc.options.entityShadows;
        mc.options.ambientOcclusion = AmbientOcclusionStatus.OFF;
        mc.options.entityShadows = false;

        // critical section?
        // ((a % b) + b) % b gives true modulus instead of just remainder
        int chunkXOffset = ((currentX % 16) + 16) % 16;
        int chunkZOffset = ((currentZ % 16) + 16) % 16;
        BlockPos thisChunkStart = new BlockPos(currentX, upperHeightLimit, currentZ);
        BlockPos thisChunkEnd = new BlockPos(Math.max(currentX - chunkXOffset, endPos.getX()), lowerHeightLimit, Math.max(currentZ - chunkZOffset, endPos.getZ()));
        // Update the current position to be the starting position of the next chunk export (which may be
        // outside the selected boundary, accounted for at the beginning of the function call).
        currentX -= (thisChunkStart.getX() - thisChunkEnd.getX() + 1);
        if (currentX < endPos.getX()) {
            currentX = startPos.getX();
            currentZ -= (thisChunkStart.getZ() - thisChunkEnd.getZ() + 1);
        }
        // TODO: check if chunk for start pos is unloaded (empty? null?) -- world.getChunkSource().getChunkNow()
        //  if its unloaded, and Dist is Client, use ForgeChunkManager to forcibly load the chunk.
        //  then, wait up to a maximum amount of time for the chunk to be loaded? or use a different approach?

        Random random = new Random();
        MatrixStack matrixStack = new MatrixStack();
        for (BlockPos pos : BlockPos.betweenClosed(thisChunkStart, thisChunkEnd)) {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock().isAir(state, world, pos)) {
                continue;
            }

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

        // translate quads so that the player x,z are the origin
        blockQuadsMap.values().forEach(this::translateQuads);
        entityUUIDQuadsMap.values().forEach(this::translateQuads);

        // Minecraft uses a flipped V coordinate
        blockQuadsMap.values().forEach(Exporter::flipV);
        entityUUIDQuadsMap.values().forEach(Exporter::flipV);

        // fix quad data issues such as overlapping/duplicate faces
        fixOverlaps(blockQuadsMap.values());
        fixOverlaps(entityUUIDQuadsMap.values());

        blockQuadsMap.values().forEach(quads::addAll);
        entityUUIDQuadsMap.values().forEach(quads::addAll);

        if (optimizeMesh) {
            MeshOptimizer meshOptimizer = new MeshOptimizer();
            quads = meshOptimizer.optimize(quads);
        }

        mc.options.ambientOcclusion = preAO;
        mc.options.entityShadows = preShadows;

        return quads;
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

    // update any quads that overlap by translating by a small multiple of their normal
    protected void fixOverlaps(Collection<ArrayList<Quad>> quadsArrays) {
        removeDuplicateQuads(quadsArrays);
        for (ArrayList<Quad> quads : quadsArrays) {
            sortQuads(quads);
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

    protected BufferedImage getImage(Quad quad) {
        BufferedImage image;
        TextureAtlasSprite texture = quad.getTexture();
        if (texture == null) {
            image = Exporter.computeImage(quad.getResource());
            image = ImgUtils.tintImage(image, quad.getColor());
        } else {
            image = getAtlasSubImage(texture, quad.getColor());
        }
        return image;
    }

    protected BufferedImage getAtlasSubImage(TextureAtlasSprite texture, int color) {
        UVBounds originalUV = new UVBounds(texture.getU0(), texture.getU1(), texture.getV0(), texture.getV1());
        return getImageFromUV(texture.atlas().location(), originalUV, color);
    }

    // Returns a subimage of a resourceLocation's texture image determined by uvbounds and tints with provided color
    protected BufferedImage getImageFromUV(ResourceLocation resource, UVBounds uvbound, int color) {
        BufferedImage baseImage = getAtlasImage(resource);
        if (baseImage == null) return null;

        uvbound = uvbound.clamped();

        if (uvbound.uDist() <= 0.000001f || uvbound.vDist() <= 0.000001f) return null;

        int width = Math.max(1, Math.round(baseImage.getWidth() * uvbound.uDist()));
        int height = Math.max(1, Math.round(baseImage.getHeight() * uvbound.vDist()));
        int startX = Math.round(baseImage.getWidth() * uvbound.uMin);
        int startY = Math.round(baseImage.getHeight() * uvbound.vMin);
        BufferedImage textureImg = null;
        try {
            textureImg = baseImage.getSubimage(startX, startY, width, height);
        } catch (RasterFormatException exception) {
            LOGGER.warn("Unable to get the texture for uvbounds: " + width + "w, " + height + "h, " + startX + "x, " + startY + "y, " + "with Uv bounds: " +
                    String.join(",", String.valueOf(uvbound.uMin), String.valueOf(uvbound.uMax), String.valueOf(uvbound.vMin), String.valueOf(uvbound.vMax)));
        }
        if (textureImg != null && color != -1) {
            textureImg = ImgUtils.tintImage(textureImg, color);
        }
        return textureImg;
    }

    private void sortQuads(ArrayList<Quad> quads) {
        quads.sort(quadComparator);
    }

    private Comparator<Quad> getQuadSort() {
        return (quad1, quad2) -> {
            RenderType quad1Layer = quad1.getType();
            RenderType quad2Layer = quad2.getType();
            if (quad1Layer == quad2Layer) {
                float avg1 = uvTransparencyCache.computeIfAbsent(Pair.of(quad1.getResource(), quad1.getUvBounds()), k -> ImgUtils.averageTransparencyValue(getImage(quad1)));
                float avg2 = uvTransparencyCache.computeIfAbsent(Pair.of(quad2.getResource(), quad2.getUvBounds()), k -> ImgUtils.averageTransparencyValue(getImage(quad2)));
                return Float.compare(avg1, avg2);
            } else {
                return Integer.compare(renderOrder.getOrDefault(quad1Layer, OTHER_ORDER), renderOrder.getOrDefault(quad2Layer, OTHER_ORDER));
            }
        };
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
