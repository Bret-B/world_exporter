package bret.worldexporter;

import bret.worldexporter.render.CustomBlockRendererDispatcher;
import bret.worldexporter.util.ImgUtils;
import bret.worldexporter.util.LABPBRParser;
import bret.worldexporter.util.OptifineReflector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.renderer.Atlases;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.client.renderer.texture.Texture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.settings.AmbientOcclusionStatus;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.awt.image.RasterFormatException;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.awt.image.BufferedImage.TYPE_INT_ARGB;

public class Exporter {
    public static final Logger LOGGER = LogManager.getLogger(WorldExporter.MODID);
    private static final int CHUNKS_PER_CONSUME = 10;
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
    public final boolean randomize;
    public final boolean optimizeMesh;
    //    public static final OptifineReflector optifineReflector = new OptifineReflector();
    protected final Minecraft mc = Minecraft.getInstance();
    protected final CustomBlockRendererDispatcher blockRendererDispatcher = new CustomBlockRendererDispatcher(mc.getBlockRenderer().getBlockModelShaper(), mc.getBlockColors());
    protected final Map<Integer, BufferedImage> atlasCacheMap = new HashMap<>();
    protected final ClientWorld world = Objects.requireNonNull(mc.level);
    private final Map<Pair<ResourceLocation, UVBounds>, Pair<ResourceLocation, TextureAtlasSprite>> atlasUVToSpriteCache = new HashMap<>();
    private final Map<Pair<ResourceLocation, UVBounds>, Float> uvTransparencyCache = new HashMap<>();
    private final Comparator<Quad> quadComparator = getQuadSort();
    private final Comparator<Quad> quadComparatorThreaded = getQuadSortThreaded();
    private final ArrayBlockingQueue<Runnable> mainThreadTasks = new ArrayBlockingQueue<>(10);
    private final ExecutorService threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final int threads;
    private final int lowerHeightLimit;
    private final int upperHeightLimit;
    private final int playerX;
    private final int playerZ;
    private final BlockPos startPos;  // higher values
    private final BlockPos endPos;  // lower values
    private AmbientOcclusionStatus preAO = mc.options.ambientOcclusion;
    private boolean preShadows = mc.options.entityShadows;
    private int currentX;
    private int currentZ;

    public Exporter(ClientPlayerEntity player, int radius, int lower, int upper, boolean optimizeMesh, boolean randomize, int threads) {
        OptifineReflector.init();
        this.randomize = randomize;
        this.optimizeMesh = optimizeMesh;
        this.threads = threads;
        lowerHeightLimit = lower;
        upperHeightLimit = upper;
        playerX = (int) player.getX();
        playerZ = (int) player.getZ();
        startPos = new BlockPos(playerX + radius, upperHeightLimit, playerZ + radius);
        endPos = new BlockPos(playerX - radius, lowerHeightLimit, playerZ - radius);
        currentX = startPos.getX();
        currentZ = startPos.getZ();
    }

    public static boolean invalidGlId(int glTextureId) {
        return (glTextureId == 0 || glTextureId == -1);
    }

    public static int getGlTextureId(ResourceLocation resource) {
        Texture texture = Minecraft.getInstance().getTextureManager().getTexture(resource);
        if (texture == null) return -1;
        return texture.getId();
    }

    // only ResourceLocations with an associated Texture should be used
    // may only be called on the main thread due to the GL11 calls
    @Nullable
    public static BufferedImage computeImage(ResourceLocation resource) {
        return computeImage(getGlTextureId(resource));
    }

    // may only be called on the main thread due to the GL11 calls
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

    public static boolean supportedVertexFormat(VertexFormat format) {
        return format.getElements().contains(DefaultVertexFormats.ELEMENT_POSITION) && format.getElements().contains(DefaultVertexFormats.ELEMENT_UV0);
    }

    // required to change MC options for proper export rendering
    public void setup() {
        preAO = mc.options.ambientOcclusion;
        preShadows = mc.options.entityShadows;
        mc.options.ambientOcclusion = AmbientOcclusionStatus.OFF;
        mc.options.entityShadows = false;
    }

    // required to reset MC options related rendering
    public void finish() {
        mc.options.ambientOcclusion = preAO;
        mc.options.entityShadows = preShadows;
    }

    // this function MUST be run on the main thread
    public void exportQuads(Consumer<ArrayList<Quad>> quadConsumer) throws InterruptedException {
        boolean threaded = threads != 1;
        List<Pair<BlockPos, BlockPos>> allChunks = getMultipleChunkPos(Integer.MAX_VALUE);
        ArrayList<Runnable> tasks = new ArrayList<>();
        ArrayList<List<Pair<BlockPos, BlockPos>>> chunkPartitions = new ArrayList<>();
        int totalChunks = allChunks.size();
        int partitionSize = totalChunks / threads;
        if (totalChunks % threads != 0) partitionSize += 1;
        // partitions the chunks that need to be exported into at most `threads` number of partitions
        for (int i = 0; i < totalChunks; i += partitionSize) {
            chunkPartitions.add(allChunks.subList(i, Math.min(i + partitionSize, totalChunks)));
        }
        if (chunkPartitions.size() > threads) throw new RuntimeException("chunkPartition size mismatch");

        for (List<Pair<BlockPos, BlockPos>> chunkPartition : chunkPartitions) {
            tasks.add(new ExporterRunnable(this, chunkPartition, threaded, quadConsumer, CHUNKS_PER_CONSUME));
        }

        if (threads == 1) {
            // basic single threaded export ran on the main thread
            tasks.get(0).run();
        } else {
            // create the given amount of threads, and start a runnable on each thread
            ExecutorService exporterThreadPool = Executors.newFixedThreadPool(threads);
            tasks.forEach(exporterThreadPool::submit);
            exporterThreadPool.shutdown();
            // wait in this loop to do tasks that are required to be run in the main thread, until threads are finished
            while (!exporterThreadPool.isTerminated()) {
                try {
                    // poll here in time increments waiting for tasks; recheck if threads are done on timeout
                    Runnable task = mainThreadTasks.poll(50, TimeUnit.MILLISECONDS);
                    if (task != null) task.run();
                } catch (InterruptedException ignored) {
                }
            }

            // clear out all left-over tasks, if any
            for (Runnable task : mainThreadTasks) {
                if (task != null) task.run();
            }
        }

        // finish any other tasks
        threadPool.shutdown();
        //noinspection ResultOfMethodCallIgnored
        threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
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

    public synchronized boolean hasMoreData() {
        return currentX >= endPos.getX() && currentZ >= endPos.getZ();
    }

    // Update the current position to be the starting position of the next chunk export (which may move outside the boundary)
    synchronized Pair<BlockPos, BlockPos> getNextChunkPos() {
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

        return Pair.of(thisChunkStart, thisChunkEnd);
    }

    synchronized List<Pair<BlockPos, BlockPos>> getMultipleChunkPos(int count) {
        if (!hasMoreData()) return Collections.emptyList();

        ArrayList<Pair<BlockPos, BlockPos>> chunks = new ArrayList<>();
        for (int i = 0; i < count; ++i) {
            chunks.add(getNextChunkPos());
            if (!hasMoreData()) break;
        }
        return chunks;
    }

    public synchronized BufferedImage getAtlasImage(ResourceLocation resource) {
        int glTextureId = getGlTextureId(resource);
        return getAtlasImage(glTextureId);
    }

    public synchronized BufferedImage getAtlasImage(int glTextureId) {
        if (invalidGlId(glTextureId)) return null;
        return atlasCacheMap.computeIfAbsent(glTextureId, Exporter::computeImage);
    }

    // returns null if the provided ResourceLocation does not refer to an AtlasTexture
    // could check if this is equivalent to MissingTextureSprite if this is ever a problem
    protected Pair<ResourceLocation, TextureAtlasSprite> getTextureFromAtlas(ResourceLocation resource, UVBounds uvBounds) {
        Texture texture = mc.textureManager.getTexture(resource);
        if (!(texture instanceof AtlasTexture)) return null;
        AtlasTexture atlasTexture = (AtlasTexture) texture;

        // Currently this is a memoized linear check over all an atlasTexture's TextureAtlasSprites to find
        // which TextureAtlasSprite contains the given UVBounds
        // If this is ever too slow a structure like a quadtree or spatial hashing could be used, but profiling shows this to be a non-issue
        synchronized (this) {
            return atlasUVToSpriteCache.computeIfAbsent(Pair.of(resource, new UVBounds(uvBounds)), k -> {
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
    }

    @Nullable
    protected BufferedImage getImage(Quad quad) {
        BufferedImage image;
        TextureAtlasSprite sprite = quad.getSprite();
        if (sprite == null) {
            image = getAtlasImage(quad.getResource());
            image = ImgUtils.tintImage(image, quad.getColor());
        } else {
            image = getAtlasSubImage(sprite, quad.getColor());
        }
        return image;
    }

    // Gets the specular texture for a quad, if any, and separates it into separate images specified in this lab-pbr format:
    // https://github.com/rre36/lab-pbr/wiki/Specular-Texture-Details
    @Nullable
    protected SpecularData getSpecularData(Quad quad) {
        BufferedImage specularImage = getImageForField(quad, OptifineReflector.multiTexSpec);
        if (specularImage == null) return null;
        return LABPBRParser.parseSpecular(specularImage);
    }

    // Gets the normal texture for a quad, if any, and separates it into separate images specified in this lab-pbr format:
    // https://github.com/rre36/lab-pbr/wiki/Normal-Texture-Details
    @Nullable
    protected NormalData getNormalData(Quad quad) {
        BufferedImage normalImage = getImageForField(quad, OptifineReflector.multiTexNorm);
        if (normalImage == null) return null;
        return LABPBRParser.parseNormal(normalImage);
    }

    // expects either the norm or spec fields from OptifineReflector
    @Nullable
    private BufferedImage getImageForField(Quad quad, Field field) {
        BufferedImage image;
        if (quad.getSprite() != null) {
            TextureAtlasSprite sprite = quad.getSprite();
            Texture atlas = sprite.atlas();
            try {
                Object multiTex = OptifineReflector.multiTex.get(atlas);
                int glTextureId = field.getInt(multiTex);
                image = getAtlasSubImage(sprite, -1, glTextureId);
            } catch (IllegalAccessException e) {
                LOGGER.warn("Unable to access an optifine field: " + field);
                return null;
            }
        } else if (quad.getTexture() != null) {
            Texture texture = quad.getTexture();
            try {
                Object multiTex = OptifineReflector.multiTex.get(texture);
                int glTextureId = field.getInt(multiTex);
                image = getAtlasImage(glTextureId);
            } catch (IllegalAccessException e) {
                LOGGER.warn("Unable to access an optifine field: " + field);
                return null;
            }
        } else {
            return null;
        }

        return image;
    }

    protected BufferedImage getAtlasSubImage(TextureAtlasSprite texture, int color, int glTextureId) {
        UVBounds originalUV = new UVBounds(texture.getU0(), texture.getU1(), texture.getV0(), texture.getV1());
        return getImageFromUV(glTextureId, originalUV, color);
    }

    protected BufferedImage getAtlasSubImage(TextureAtlasSprite texture, int color) {
        UVBounds originalUV = new UVBounds(texture.getU0(), texture.getU1(), texture.getV0(), texture.getV1());
        return getImageFromUV(texture.atlas().getId(), originalUV, color);
    }

    // Returns a subimage of a texture's image determined by uvbounds and tints with provided color
    protected BufferedImage getImageFromUV(int glTextureId, UVBounds uvbound, int color) {
        BufferedImage baseImage = getAtlasImage(glTextureId);
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

    protected void sortQuads(ArrayList<Quad> quads, boolean threaded) {
        if (threaded) {
            quads.sort(quadComparatorThreaded);
        } else {
            quads.sort(quadComparator);
        }
    }

    // cannot use the standard sort in threads because it is unable to access openGL to get the quad's image
    private Comparator<Quad> getQuadSortThreaded() {
        return (quad1, quad2) -> {
            RenderType quad1Layer = quad1.getType();
            RenderType quad2Layer = quad2.getType();
            if (quad1Layer.equals(quad2Layer)) {
                return 0;
            } else {
                return Integer.compare(renderOrder.getOrDefault(quad1Layer, OTHER_ORDER), renderOrder.getOrDefault(quad2Layer, OTHER_ORDER));
            }
        };
    }

    private Comparator<Quad> getQuadSort() {
        return (quad1, quad2) -> {
            RenderType quad1Layer = quad1.getType();
            RenderType quad2Layer = quad2.getType();
            if (quad1Layer.equals(quad2Layer)) {
                float avg1;
                float avg2;
                synchronized (this) {
                    avg1 = uvTransparencyCache.computeIfAbsent(Pair.of(quad1.getResource(), quad1.getUvBounds()), k -> ImgUtils.averageTransparencyValue(getImage(quad1)));
                    avg2 = uvTransparencyCache.computeIfAbsent(Pair.of(quad2.getResource(), quad2.getUvBounds()), k -> ImgUtils.averageTransparencyValue(getImage(quad2)));
                }
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

    protected void addTask(Runnable task) throws InterruptedException {
        mainThreadTasks.put(task);
    }

    protected void addThreadTask(Runnable task) {
        threadPool.submit(task);
    }
}
