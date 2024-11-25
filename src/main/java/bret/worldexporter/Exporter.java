package bret.worldexporter;

import bret.worldexporter.lwjgl.Vector2f;
import bret.worldexporter.lwjgl.Vector3f;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RegionRenderCacheBuilder;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.ForgeHooksClient;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.awt.image.RasterFormatException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static java.awt.image.BufferedImage.TYPE_INT_ARGB;

public class Exporter {
    protected final Minecraft mc = Minecraft.getMinecraft();
    protected final CustomBlockRendererDispatcher blockRenderer = new CustomBlockRendererDispatcher(mc.getBlockRendererDispatcher().getBlockModelShapes(), mc.getBlockColors());
    protected final RegionRenderCacheBuilder renderCacheBuilder = new RegionRenderCacheBuilder();
    protected final boolean[] startedBufferBuilders = new boolean[BlockRenderLayer.values().length];
    protected final Map<BlockRenderLayer, Map<BlockPos, Pair<Integer, Integer>>> layerPosVerticesMap = new HashMap<>();
    protected final Map<BlockPos, ArrayList<Quad>> blockQuadsMap = new HashMap<>();
    protected static final Logger logger = LogManager.getLogger(WorldExporter.MODID);

    private final Map<BlockPos, Integer> blockLightValuesMap = new HashMap<>();
    protected final Map<Integer, BufferedImage> atlasCacheMap = new HashMap<>();
    private final Map<Pair<ResourceLocation, UVBounds>, Pair<ResourceLocation, TextureAtlasSprite>> atlasUVToSpriteCache = new HashMap<>();
    private final Map<Pair<ResourceLocation, UVBounds>, Float> uvTransparencyCache = new HashMap<>();
    private final ExecutorService threadPool = Executors.newFixedThreadPool(1);
    private final Comparator<Quad> quadComparator = getQuadSort();
    public final boolean optimizeMesh;
    public final boolean randomize;

    private static final Map<BlockRenderLayer, Integer> renderOrder = new HashMap<BlockRenderLayer, Integer>() {{
        put(BlockRenderLayer.SOLID, 0);
        put(BlockRenderLayer.CUTOUT_MIPPED, 1);
        put(BlockRenderLayer.CUTOUT, 1);
        put(BlockRenderLayer.TRANSLUCENT, Integer.MAX_VALUE);
    }};

    private int preAO = mc.gameSettings.ambientOcclusion;
    private final int lowerHeightLimit;
    private final int upperHeightLimit;
    private final int playerX;
    private final int playerZ;
    private final BlockPos startPos;
    private final BlockPos endPos;
    private final IBlockAccess world;
    private int currentX;
    private int currentZ;

    public Exporter(EntityPlayer player, int radius, int lower, int upper, boolean optimizeMesh, boolean randomize) {
        lowerHeightLimit = lower;
        upperHeightLimit = upper;
        playerX = (int) player.posX;
        playerZ = (int) player.posZ;
        startPos = new BlockPos(playerX + radius, upperHeightLimit, playerZ + radius);
        endPos = new BlockPos(playerX - radius, lowerHeightLimit, playerZ - radius);
        world = player.getEntityWorld();
        currentX = startPos.getX();
        currentZ = startPos.getZ();
        this.optimizeMesh = optimizeMesh;
        this.randomize = randomize;
    }

    public void setup() {
        preAO = mc.gameSettings.ambientOcclusion;
        mc.gameSettings.ambientOcclusion = 0;
    }

    // required to reset MC options related rendering
    public void finish() {
        mc.gameSettings.ambientOcclusion = preAO;
    }

    @Nullable
    public static BufferedImage computeImage(int glTextureId) {
        if (invalidGlId(glTextureId)) return null;

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        int size = width * height;
        if (size == 0) return null;
        BufferedImage image = new BufferedImage(width, height, TYPE_INT_ARGB);
        IntBuffer buffer = BufferUtils.createIntBuffer(size);
        int[] data = new int[size];
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        buffer.get(data);
        image.setRGB(0, 0, width, height, data, 0, width);
        return image;
    }

    public ITextureObject getTexture(ResourceLocation resource) {
        TextureManager textureManager = Minecraft.getMinecraft().getTextureManager();
        ITextureObject texture;
        texture = textureManager.getTexture(resource);
        //noinspection ConstantValue
        if (texture == null) {
            logger.info("Loading the following resource: " + resource);
            textureManager.bindTexture(resource);
            texture = textureManager.getTexture(resource);
        }
        return texture;
    }

    public int getGlTextureId(ResourceLocation resource) {
        ITextureObject texture = getTexture(resource);
        if (texture == null) return -1;
        return texture.getGlTextureId();
    }

    public static boolean invalidGlId(int glTextureId) {
        return (glTextureId == 0 || glTextureId == -1);
    }

    public synchronized BufferedImage getAtlasImage(ResourceLocation atlas) {
        int glTextureId = getGlTextureId(atlas);
        return getAtlasImage(glTextureId);
    }

    // may only be called on the main thread
    public synchronized BufferedImage getAtlasImage(int glTextureId) {
        if (invalidGlId(glTextureId)) return null;
        return atlasCacheMap.computeIfAbsent(glTextureId, Exporter::computeImage);
    }

    protected BufferedImage getImage(Quad quad) {
        BufferedImage image;
        TextureAtlasSprite sprite = quad.getSprite();
        if (sprite == null) {
            image = getAtlasImage(quad.getAtlas());
            image = ImgUtils.tintImage(image, quad.getColor());
        } else {
            image = getAtlasSubImage(sprite, quad.getColor(), quad.getAtlas());
        }
        return image;
    }

    protected BufferedImage getAtlasSubImage(TextureAtlasSprite texture, int color, int glTextureId) {
        UVBounds originalUV = new UVBounds(texture.getMinU(), texture.getMaxU(), texture.getMinV(), texture.getMaxV());
        return getImageFromUV(glTextureId, originalUV, color);
    }

    protected BufferedImage getAtlasSubImage(TextureAtlasSprite texture, int color, ResourceLocation atlasLocation) {
        UVBounds originalUV = new UVBounds(texture.getMinU(), texture.getMaxU(), texture.getMinV(), texture.getMaxV());
        int glTextureId = getGlTextureId(atlasLocation);
        return getImageFromUV(glTextureId, originalUV, color);
    }

    protected Pair<ResourceLocation, TextureAtlasSprite> getTextureFromAtlas(ResourceLocation atlas, UVBounds uvBounds) {
        ITextureObject texture = getTexture(atlas);

        if (!(texture instanceof TextureMap)) return null;
        TextureMap atlasTexture = (TextureMap) texture;

        // Currently this is a memoized linear check over all an atlasTexture's TextureAtlasSprites to find
        // which TextureAtlasSprite contains the given UVBounds
        // If this is ever too slow a structure like a quadtree or spatial hashing could be used, but profiling shows this to be a non-issue
        synchronized (atlasUVToSpriteCache) {
            return atlasUVToSpriteCache.computeIfAbsent(Pair.of(atlas, new UVBounds(uvBounds)), k -> {
                for (String name : atlasTexture.mapUploadedSprites.keySet()) {
                    TextureAtlasSprite sprite = atlasTexture.getAtlasSprite(name);
                    float uMin = sprite.getMinU();
                    float uMax = sprite.getMaxU();
                    float vMin = sprite.getMinV();
                    float vMax = sprite.getMaxV();
                    if (uvBounds.uMin >= uMin && uvBounds.uMax <= uMax && uvBounds.vMin >= vMin && uvBounds.vMax <= vMax) {
                        return Pair.of(new ResourceLocation(name), sprite);
                    }
                }
                return null;
            });
        }
    }

    // For every quad: if its atlas resource refers to an atlasImage, update its texture resource, add a reference to its sprite,
    // and update its UV coordinates respectively (in place)
    private void updateQuadTextures() {
        List<Quad> quads = blockQuadsMap.values().stream().flatMap(Collection::stream).collect(Collectors.toList());
        for (Quad quad : quads) {
            if (!quad.hasUV()) continue;

            ITextureObject baseTexture = getTexture(quad.getAtlas());
            quad.setTexture(baseTexture);
            boolean didModifyUV = false;
            // allowed error is very small by default
            float allowableErrorU = 0.0001F;
            float allowableErrorV = 0.0001F;
            if (baseTexture instanceof TextureMap) {
                Pair<ResourceLocation, TextureAtlasSprite> nameAndTexture = getTextureFromAtlas(quad.getAtlas(), quad.getUvBounds());
                if (nameAndTexture != null) {
                    quad.setResource(nameAndTexture.getLeft());
                    TextureAtlasSprite sprite = nameAndTexture.getRight();
                    // recalculate the allowable error based on the sprite's size so that any rounding does not change
                    // the sprite's display on a sprite-pixel level (capped to a certain level)
                    allowableErrorU = Math.max(1.0F / sprite.getIconWidth() / 2.0F, 0.00125F);
                    allowableErrorV = Math.max(1.0F / sprite.getIconHeight() / 2.0F, 0.00125F);
                    for (Vertex vertex : quad.getVertices()) {
                        Vector2f uv = vertex.getUv();
                        uv.x = (uv.x - sprite.getMinU()) / (sprite.getMaxU() - sprite.getMinU());
                        uv.y = (uv.y - sprite.getMinV()) / (sprite.getMaxV() - sprite.getMinV());
                    }
                    quad.setSprite(sprite);
                    didModifyUV = true;
                } else {
                    logger.warn("Unable to determine where on atlas " + ((TextureMap) baseTexture).getBasePath() +
                            " the texture is with the following UVs: " + quad.getUvBounds() +
                            ", derived from from ResourceLocation " + quad.getAtlas() +
                            ", at world position " + quad.getVertices()[0].getPosition());
                }
            } else if (baseTexture instanceof DynamicTexture) {
//                DynamicTexture quadImage = ((DynamicTexture) baseTexture);
//                if (quadImage != null) {
//                    allowableErrorU = 1.0F / quadImage.width / 2.0F;
//                    allowableErrorV = 1.0F / quadImage.height / 2.0F;
//                }
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

    protected void addBuilderData(RegionRenderCacheBuilder renderCacheBuilder) {
        for (int blockRenderLayerId = 0; blockRenderLayerId < BlockRenderLayer.values().length; ++blockRenderLayerId) {
            BufferBuilder bufferBuilder = renderCacheBuilder.getWorldRendererByLayerId(blockRenderLayerId);
            if (bufferBuilder.getVertexCount() == 0 || bufferBuilder.getDrawMode() != GL11.GL_QUADS) {
                continue;
            }
            VertexFormat vertexFormat = bufferBuilder.getVertexFormat();
            int vertexByteSize = vertexFormat.getIntegerSize() * 4;
            ByteBuffer bytebuffer = bufferBuilder.getByteBuffer();
            Buffer buf = (Buffer) bytebuffer;
            List<VertexFormatElement> list = vertexFormat.getElements();

            for (BlockPos pos : layerPosVerticesMap.get(BlockRenderLayer.values()[blockRenderLayerId]).keySet()) {
                // TODO
//                ResourceLocation resource = renderResourceLocationMap.getOrDefault(type, MissingTextureSprite.getLocation());
                ResourceLocation resource = TextureMap.LOCATION_BLOCKS_TEXTURE;

                Pair<Integer, Integer> verticesPosCount = layerPosVerticesMap.get(BlockRenderLayer.values()[blockRenderLayerId]).get(pos);
                int firstVertexBytePos = verticesPosCount.getLeft() * vertexByteSize;
                int vertexCount = verticesPosCount.getRight();

                if (vertexCount == 0) {
                    continue;
                }

                Quad quad = new Quad(BlockRenderLayer.values()[blockRenderLayerId], resource);
                boolean skipQuad = false;
                buf.position(firstVertexBytePos);
                for (int vertexNum = 0; vertexNum < vertexCount; ++vertexNum) {
                    if (skipQuad) {
                        vertexNum += 3 - (vertexNum - 1) % 4;
                        if (vertexNum >= vertexCount) break;
                        buf.position(buf.position() + (4 - ((vertexNum - 1) % 4)));
                        quad = new Quad(BlockRenderLayer.values()[blockRenderLayerId], resource);
                        skipQuad = false;
                    } else if (quad.getCount() == 4) {
                        blockQuadsMap.computeIfAbsent(pos, k -> new ArrayList<>()).add(quad);
                        quad = new Quad(BlockRenderLayer.values()[blockRenderLayerId], resource);
                    }

                    Vertex vertex = new Vertex();
                    for (VertexFormatElement vertexFormatElement : list) {
                        VertexFormatElement.EnumUsage vertexElementEnumUsage = vertexFormatElement.getUsage();
                        switch (vertexElementEnumUsage) {
                            case POSITION:
                                if (vertexFormatElement.getType() == VertexFormatElement.EnumType.FLOAT) {
                                    vertex.setPosition(new Vector3f(bytebuffer.getFloat(), bytebuffer.getFloat(), bytebuffer.getFloat()));
                                } else {
                                    buf.position(buf.position() + vertexFormatElement.getSize());
                                    logger.warn("Vertex position element had no supported type, skipping.");
                                    continue;
                                }
                                break;
                            case COLOR:
                                if (vertexFormatElement.getType() == VertexFormatElement.EnumType.UBYTE) {
                                    vertex.setColor(bytebuffer.getInt());
                                } else {
                                    buf.position(buf.position() + vertexFormatElement.getSize());
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
//                                            skipQuad = true;
//                                            logger.warn("Quad being skipped because UV was out of bounds on add.");
//                                            break;
                                            logger.warn("Quad had UV was out of bounds?");
                                        }

                                        vertex.setUv(new Vector2f(u, v));
                                        break;
                                    case SHORT:
                                        // TODO: ensure value / 16 / (2^16 - 1) gives proper 0-1 float range
                                        //  Minecraft.getMinecraft().getTextureManager().getTexture(new ResourceLocation( "minecraft", "dynamic/lightmap_1"))
                                        //  Discard first short (sky light) and only use second (block light) when implementing emissive lighting?
                                        // vertex.setUvlight(new Vector2f(bytebuffer.getShort() / 65520.0f, bytebuffer.getShort() / 65520.0f));
//                                        break;
                                    default:
                                        buf.position(buf.position() + vertexFormatElement.getSize());
//                                        logger.warn("Vertex UV element had no supported type, skipping.");
                                        // not currently used, appears in formats like ENTITY?
                                        continue;
                                }
                                break;
                            case PADDING:
                            case NORMAL:
                            default:
                                buf.position(buf.position() + vertexFormatElement.getSize());
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
            BufferBuilder bufferBuilder = renderCacheBuilder.getWorldRendererByLayerId(blockRenderLayerId);
            bufferBuilder.setTranslation(-playerX, 0, -playerZ);
            bufferBuilder.reset();
        }

        layerPosVerticesMap.clear();
        blockQuadsMap.clear();
    }

    @Nullable
    public ExportChunk getNextChunkData() {
        if (currentX < endPos.getX() || currentZ < endPos.getZ()) {
            return null;
        }

        resetBuilders();

        // ((a % b) + b) % b gives true modulus instead of just remainder
        final int chunkXOffset = ((currentX % 16) + 16) % 16;
        final int chunkZOffset = ((currentZ % 16) + 16) % 16;
        BlockPos thisChunkStart = new BlockPos(currentX, upperHeightLimit, currentZ);
        BlockPos thisChunkEnd = new BlockPos(Math.max(currentX - chunkXOffset, endPos.getX()), lowerHeightLimit, Math.max(currentZ - chunkZOffset, endPos.getZ()));

        for (BlockPos pos : BlockPos.getAllInBoxMutable(thisChunkStart, thisChunkEnd)) {
            IBlockState state = world.getBlockState(pos).getActualState(world, pos);
            Block block = state.getBlock();
            if (block.isAir(state, world, pos)) {
                continue;
            }

            BlockPos immutablePos = pos.toImmutable();
            int light = state.getLightValue(world, immutablePos);
            if (light != 0) {
                blockLightValuesMap.put(immutablePos, light);
            }

            for (BlockRenderLayer blockRenderLayer : BlockRenderLayer.values()) {
                if (!state.getBlock().canRenderInLayer(state, blockRenderLayer)) {
                    continue;
                }

                ForgeHooksClient.setRenderLayer(blockRenderLayer);
                int blockRenderLayerId = blockRenderLayer.ordinal();
                BufferBuilder bufferBuilder = renderCacheBuilder.getWorldRendererByLayerId(blockRenderLayerId);

                if (!startedBufferBuilders[blockRenderLayerId]) {
                    startedBufferBuilders[blockRenderLayerId] = true;
                    bufferBuilder.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                }

                // OptiFine Shaders compatibility -- https://gist.github.com/Cadiboo/753607e41ca4e2ca9e0ce3b928bab5ef
//				if (Config.isShaders()) SVertexBuilder.pushEntity(state, pos, blockAccess, bufferBuilder);
                try {
                    int vertexCountPre = bufferBuilder.getVertexCount();
                    if (blockRenderer.renderBlock(state, pos, world, bufferBuilder, randomize)) {
                        int addedVertexCount = bufferBuilder.getVertexCount() - vertexCountPre;
                        layerPosVerticesMap.computeIfAbsent(blockRenderLayer, k -> new HashMap<>()).put(immutablePos, new ImmutablePair<>(vertexCountPre, addedVertexCount));
                    }
                } catch (Exception exception) {
                    logger.warn("Unable to render block: " + state.getBlock() + "      with position: " + pos + "\n" + exception);
                }
//				if (Config.isShaders()) SVertexBuilder.popEntity(bufferBuilder);
            }
            ForgeHooksClient.setRenderLayer(null);
        }

        for (int blockRenderLayerId = 0; blockRenderLayerId < startedBufferBuilders.length; ++blockRenderLayerId) {
            if (!startedBufferBuilders[blockRenderLayerId]) {
                continue;
            }
            renderCacheBuilder.getWorldRendererByLayerId(blockRenderLayerId).finishDrawing();
        }

        addBuilderData(renderCacheBuilder);
        updateQuadTextures();
        fixOverlaps(blockQuadsMap.values());

        // update light values for quads that originate from a block
        for (BlockPos pos : blockQuadsMap.keySet()) {
            ArrayList<Quad> quadsForBlock = blockQuadsMap.get(pos);
            quadsForBlock.forEach(quad -> quad.setLightValue(blockLightValuesMap.getOrDefault(pos, 0)));
        }

        ArrayList<Quad> chunkQuads = new ArrayList<>();
        blockQuadsMap.values().forEach(chunkQuads::addAll);
        flipV(chunkQuads);
        if (optimizeMesh) {
            MeshOptimizer meshOptimizer = new MeshOptimizer();
            chunkQuads = meshOptimizer.optimize(chunkQuads);
        }
        ExportChunk chunk = new ExportChunk(chunkQuads, currentX >> 4, currentZ >> 4);

        // Update the current position to be the starting position of the next chunk export (which may be
        // outside the selected boundary, accounted for at the beginning of the function call).
        currentX -= (thisChunkStart.getX() - thisChunkEnd.getX() + 1);
        if (currentX < endPos.getX()) {
            currentX = startPos.getX();
            currentZ -= (thisChunkStart.getZ() - thisChunkEnd.getZ() + 1);
        }

        return chunk;
    }

    // Flips quad V values
    protected static void flipV(List<Quad> quads) {
        for (Quad quad : quads) {
            if (!quad.hasUV()) continue;

            for (Vertex vertex : quad.getVertices()) {
                vertex.getUv().y = 1 - vertex.getUv().y;
            }
            quad.updateUvBounds();
        }
    }

    private static void removeDuplicateQuads(Collection<ArrayList<Quad>> quadsArrays) {
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


    private void sortQuads(ArrayList<Quad> quads) {
        quads.sort(quadComparator);
    }

    private Comparator<Quad> getQuadSort() {
        return (quad1, quad2) -> {
            BlockRenderLayer quad1Layer = quad1.getType();
            BlockRenderLayer quad2Layer = quad2.getType();
            int layer1Priority = renderOrder.getOrDefault(quad1Layer, -1);
            int layer2Priority = renderOrder.getOrDefault(quad2Layer, -1);
            if (layer1Priority == -1 || layer2Priority == -1 || layer1Priority == layer2Priority) {
                float avg1;
                float avg2;
                synchronized (this) {
                    if (quad1.hasUV()) {
                        avg1 = uvTransparencyCache.computeIfAbsent(Pair.of(quad1.getAtlas(), quad1.getUvBounds()), k -> ImgUtils.averageTransparencyValue(getImage(quad1)));
                    } else {
                        avg1 = (float) ((quad1.getColor() & 0xFF000000) >>> 24);
                    }

                    if (quad2.hasUV()) {
                        avg2 = uvTransparencyCache.computeIfAbsent(Pair.of(quad2.getAtlas(), quad2.getUvBounds()), k -> ImgUtils.averageTransparencyValue(getImage(quad2)));
                    } else {
                        avg2 = (float) ((quad2.getColor() & 0xFF000000) >>> 24);
                    }
                }
                // higher avg -> less transparent
                return Float.compare(avg2, avg1);
            } else {
                // higher priority -> more transparent
                return Integer.compare(layer1Priority, layer2Priority);
            }
        };
    }

    protected BufferedImage getImageFromUV(int glTextureId, UVBounds uvbound, int color) {
        BufferedImage baseImage = getAtlasImage(glTextureId);
        if (baseImage == null) return null;

        uvbound = uvbound.clamped();
        if (uvbound.uDist() <= 0.000001f || uvbound.vDist() <= 0.000001f) {
            logger.warn("Could not determine texture image from UV since the distances were so small: ");
            return null;
        }

        int width = Math.max(1, Math.round(baseImage.getWidth() * uvbound.uDist()));
        int height = Math.max(1, Math.round(baseImage.getHeight() * uvbound.vDist()));
        int startX = Math.round(baseImage.getWidth() * uvbound.uMin);
        int startY = Math.round(baseImage.getHeight() * uvbound.vMin);
        BufferedImage textureImg = null;
        try {
            textureImg = baseImage.getSubimage(startX, startY, width, height);
        } catch (RasterFormatException exception) {
            logger.warn("Unable to get the texture for uvbounds: " + width + "w, " + height + "h, " + startX + "x, " + startY + "y, " + "with Uv bounds: " +
                    String.join(",", String.valueOf(uvbound.uMin), String.valueOf(uvbound.uMax), String.valueOf(uvbound.vMin), String.valueOf(uvbound.vMax)));
        }
        if (textureImg != null && color != -1) {
            textureImg = ImgUtils.tintImage(textureImg, color);
        }
        return textureImg;
    }

    // Generates a 1x1 pixel image with the color given by the quad
    protected BufferedImage generatePixelImage(int color) {
        BufferedImage image = new BufferedImage(1, 1, TYPE_INT_ARGB);
        // quad colors are packed ABGR, so it must be changed to the expected format
        int argb = color & 0xFF00FF00;  // alpha and green channels have the same position
        argb |= (color & 0x00FF0000) >>> 16;  // shift the blue channel
        argb |= (color & 0x000000FF) << 16;  // shift the red channel
        image.setRGB(0, 0, argb);
        return image;
    }

    // update any quads that overlap by translating by a small multiple of their normal
    private void fixOverlaps(Collection<ArrayList<Quad>> quadsArrays) {
        removeDuplicateQuads(quadsArrays);

        for (ArrayList<Quad> quads : quadsArrays) {
            sortQuads(quads);
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
                        float scaleDistance = 0.0005f - distance;
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

    protected void addThreadTask(Runnable task) {
        threadPool.submit(task);
    }
}
