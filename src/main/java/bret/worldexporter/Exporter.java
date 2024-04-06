package bret.worldexporter;

import bret.worldexporter.config.WorldExporterConfig;
import bret.worldexporter.render.CustomBlockRendererDispatcher;
import bret.worldexporter.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.renderer.Atlases;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.settings.AmbientOcclusionStatus;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Timer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
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
import java.util.concurrent.*;
import java.util.function.Consumer;

import static java.awt.image.BufferedImage.TYPE_INT_ARGB;

public class Exporter {
    public static final Logger LOGGER = LogManager.getLogger(WorldExporter.MODID);
    public static final int WORLD_HEIGHT_LIMIT = 255;
    public static final int WORLD_LOWER_HEIGHT_LIMIT = 0;
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
    protected final Minecraft mc = Minecraft.getInstance();
    protected final CustomBlockRendererDispatcher blockRendererDispatcher = new CustomBlockRendererDispatcher(mc.getBlockRenderer().getBlockModelShaper(), mc.getBlockColors());
    protected final Map<Integer, BufferedImage> atlasCacheMap = new HashMap<>();
    protected final ClientWorld world = Objects.requireNonNull(mc.level);
    protected final int playerX;
    protected final int playerZ;
    public final int playerXOffset;
    public final int playerZOffset;
    private final Map<Pair<ResourceLocation, UVBounds>, Pair<ResourceLocation, TextureAtlasSprite>> atlasUVToSpriteCache = new HashMap<>();
    private final Map<Pair<ResourceLocation, UVBounds>, Float> uvTransparencyCache = new HashMap<>();
    private final Comparator<Quad> quadComparator = getQuadSort();
    private final Comparator<Quad> quadComparatorThreaded = getQuadSortThreaded();
    private final ArrayBlockingQueue<Runnable> mainThreadTasks = new ArrayBlockingQueue<>(10);
    private final ExecutorService threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final int threads;
    private BlockPos startPos;  // higher values
    private BlockPos endPos;  // lower values
    private BlockPos startPosClampedHeight;  // higher values
    private BlockPos endPosClampedHeight;  // lower values
    private AmbientOcclusionStatus preAO = mc.options.ambientOcclusion;
    private boolean preShadows = mc.options.entityShadows;
    private int currentX;
    private int currentZ;

    public Exporter(ClientPlayerEntity player, int radius, int lower, int upper, boolean optimizeMesh, boolean randomize, int threads) {
        OptifineReflector.init();
        this.randomize = randomize;
        this.optimizeMesh = optimizeMesh;
        this.threads = threads;
        playerX = (int) player.getX();
        playerZ = (int) player.getZ();
        playerXOffset = WorldExporterConfig.CLIENT.relativeCoordinates.get() ? playerX : 0;
        playerZOffset = WorldExporterConfig.CLIENT.relativeCoordinates.get() ? playerZ : 0;
        startPos = new BlockPos(playerX + radius, upper, playerZ + radius);
        endPos = new BlockPos(playerX - radius, lower, playerZ - radius);
        startPosClampedHeight = new BlockPos(startPos.getX(), Math.min(upper, WORLD_HEIGHT_LIMIT), startPos.getZ());
        endPosClampedHeight = new BlockPos(endPos.getX(), Math.max(lower, WORLD_LOWER_HEIGHT_LIMIT), endPos.getZ());
        currentX = startPos.getX();
        currentZ = startPos.getZ();
    }

    public BlockPos getStartPos() {
        return startPos;
    }

    public BlockPos getEndPos() {
        return endPos;
    }

    public BlockPos getStartPosClampedHeight() {
        return startPosClampedHeight;
    }

    public BlockPos getEndPosClampedHeight() {
        return endPosClampedHeight;
    }

    public static boolean invalidGlId(int glTextureId) {
        return (glTextureId == 0 || glTextureId == -1);
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
            if (!quad.hasUV()) continue;

            for (Vertex vertex : quad.getVertices()) {
                vertex.getUv().y = 1 - vertex.getUv().y;
            }
            quad.updateUvBounds();
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
        return format.getElements().contains(DefaultVertexFormats.ELEMENT_POSITION)
                && (
                format.getElements().contains(DefaultVertexFormats.ELEMENT_UV0)
                        || format.getElements().contains(DefaultVertexFormats.ELEMENT_COLOR)
        );
    }

    public int getGlTextureId(ResourceLocation resource, boolean threaded) {
        Texture texture = getTexture(resource, threaded);
        if (texture == null) return -1;
        return texture.getId();
    }

    // fetch the Texture for a ResourceLocation from Minecraft's TextureManager, or try to load it if needed
    public Texture getTexture(ResourceLocation resource, boolean threaded) {
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        Texture texture;
        // For some reason, the block atlas texture can rarely become null and therefore tries to be registered which
        // results in the texture being set to the missing texture.
        // Could be caused by a duplicate reload of the same texture or some kind of race condition/mutex problem?
        // This synchronization on the textureManager instance may prevent this
        synchronized (textureManager) {
            texture = textureManager.getTexture(resource);
        }
        if (texture == null) {
            LOGGER.info("Loading the following resource: " + resource);
            // The texture is not currently loaded. This can be caused by entities outside render distance for example.
            // attempt to load the texture into the texture manager (see TextureManager._bind() and register())
            Texture newTexture = new SimpleTexture(resource);  // using a SimpleTexture replicates behavior of _bind()
            if (threaded) {
                try {
                    RunnableFuture<Boolean> task = new FutureTask<>(() -> {
                        synchronized (textureManager) {
                            textureManager.register(resource, newTexture);
                        }
                        return true;
                    });
                    addTask(task);
                    task.get();
                } catch (InterruptedException | ExecutionException ignored) {
                }
            } else {
                textureManager.register(resource, newTexture);
            }

            synchronized (textureManager) {
                texture = textureManager.getTexture(resource);
            }
        }
        return texture;
    }

    private void setPause(boolean paused) {
        if (mc.getSingleplayerServer() == null) {
            return;
        }

        try {
            Field pause = Objects.requireNonNull(ReflectionHandler.getField(Minecraft.class, "pause"));
            Field pausePartialTick = Objects.requireNonNull(ReflectionHandler.getField(Minecraft.class, "pausePartialTick"));
            Timer mcTimer = (Timer) Objects.requireNonNull(ReflectionHandler.getField(Minecraft.class, "timer")).get(mc);
            pause.setBoolean(mc, paused);
            if (paused) {
                pausePartialTick.setFloat(mc, mcTimer.partialTick);
            } else {
                mcTimer.partialTick = pausePartialTick.getFloat(mc);
            }
        } catch (IllegalAccessException | NullPointerException | ClassCastException e) {
            LOGGER.warn("Unable to change pause status of internal server");
        }
    }

    // required to change MC options for proper export rendering
    public void setup() {
        preAO = mc.options.ambientOcclusion;
        preShadows = mc.options.entityShadows;
        mc.options.ambientOcclusion = AmbientOcclusionStatus.OFF;
        mc.options.entityShadows = false;

        // pause the IntegratedServer, if there is one
        setPause(true);
    }

    // required to reset MC options related rendering
    public void finish() {
        mc.options.ambientOcclusion = preAO;
        mc.options.entityShadows = preShadows;

        // resume the IntegratedServer, if there is one
        setPause(false);
    }

    public boolean isOnExportEdge(BlockPos pos) {
        return pos.getX() == startPos.getX() || pos.getX() == endPos.getX()
                || pos.getY() == startPosClampedHeight.getY() || pos.getY() == endPosClampedHeight.getY()
                || pos.getZ() == startPos.getZ() || pos.getZ() == endPos.getZ();
    }

    public boolean inExportRange(BlockPos pos) {
        return endPos.getX() <= pos.getX() && pos.getX() <= startPos.getX()
                && endPosClampedHeight.getY() <= pos.getY() && pos.getY() <= startPosClampedHeight.getY()
                && endPos.getZ() <= pos.getZ() && pos.getZ() <= startPos.getZ();
    }

    // returns 0 if pos lies along the edge of the export
    // returns 1 if pos is inside the export
    // returns -1 if pos is outside the export
    public int inExportStatus(BlockPos pos) {
        return isOnExportEdge(pos) ? 0 : (inExportRange(pos) ? 1 : -1);
    }

    // attempts to shrink the actual export radius to include only chunks that are loaded
    // (only in the NESW directions, never vertically)
    private void shrinkStartEndPosBBOXCardinal() {
        Chunk ch;
        int lowX = endPos.getX();
        int lowZ = endPos.getZ();
        int highX = startPos.getX();
        int highZ = startPos.getZ();
        LOGGER.info("Shrinking original bbox of (lowx, lowz, highx, highz) = ({}, {}, {}, {})", lowX, lowZ, highX, highZ);

        // starting at endPos chunk, scanline chunks towards startPos in + x direction
        // finds lowX
        for (int x = endPos.getX(); x != startPos.getX(); x = Math.min(x + 16, startPos.getX())) {
            boolean lineHasChunk = false;
            for (int z = endPos.getZ(); z != startPos.getZ(); z = Math.min(z + 16, startPos.getZ())) {
                ch = world.getChunk(x >> 4, z >> 4);
                if (!ch.isEmpty()) {
                    lineHasChunk = true;
                    break;
                }
            }
            if (lineHasChunk) {
                lowX = BlockPosUtils.lowestCoordinateInChunk(x);
                break;
            }
        }

        // starting at endPos chunk, scanline chunks towards startPos in + z direction
        // finds lowZ
        for (int z = endPos.getZ(); z != startPos.getZ(); z = Math.min(z + 16, startPos.getZ())) {
            boolean lineHasChunk = false;
            for (int x = endPos.getX(); x != startPos.getX(); x = Math.min(x + 16, startPos.getX())) {
                ch = world.getChunk(x >> 4, z >> 4);
                if (!ch.isEmpty()) {
                    lineHasChunk = true;
                    break;
                }
            }
            if (lineHasChunk) {
                lowZ = BlockPosUtils.lowestCoordinateInChunk(z);
                break;
            }
        }

        // starting at startPos chunk, scanline chunks towards endPos in - x direction
        // finds highX
        for (int x = startPos.getX(); x != endPos.getX(); x = Math.max(x - 16, endPos.getX())) {
            boolean lineHasChunk = false;
            for (int z = startPos.getZ(); z != endPos.getZ(); z = Math.max(z - 16, endPos.getZ())) {
                ch = world.getChunk(x >> 4, z >> 4);
                if (!ch.isEmpty()) {
                    lineHasChunk = true;
                    break;
                }
            }
            if (lineHasChunk) {
                highX = BlockPosUtils.highestCoordinateInChunk(x);
                break;
            }
        }

        // starting at startPos chunk, scanline chunks towards endPos in - z direction
        // finds highZ
        for (int z = startPos.getZ(); z != endPos.getZ(); z = Math.max(z - 16, endPos.getZ())) {
            boolean lineHasChunk = false;
            for (int x = startPos.getX(); x != endPos.getX(); x = Math.max(x - 16, endPos.getX())) {
                ch = world.getChunk(x >> 4, z >> 4);
                if (!ch.isEmpty()) {
                    lineHasChunk = true;
                    break;
                }
            }
            if (lineHasChunk) {
                highZ = BlockPosUtils.highestCoordinateInChunk(z);
                break;
            }
        }

        endPos = new BlockPos(
                Math.max(endPos.getX(), lowX),
                endPos.getY(),
                Math.max(endPos.getZ(), lowZ));
        startPos = new BlockPos(
                Math.min(startPos.getX(), highX),
                startPos.getY(),
                Math.min(startPos.getZ(), highZ));
        endPosClampedHeight = new BlockPos(endPos.getX(), endPosClampedHeight.getY(), endPos.getZ());
        startPosClampedHeight = new BlockPos(startPos.getX(), startPosClampedHeight.getY(), startPos.getZ());
        currentX = startPos.getX();
        currentZ = startPos.getZ();
        LOGGER.info("To (lowx, lowz, highx, highz) = ({}, {}, {}, {})",
                endPos.getX(), endPos.getZ(), startPos.getX(), startPos.getZ());
    }

    // this function MUST be run on the main thread
    public void exportQuads(Consumer<ArrayList<ExportChunk>> chunkConsumer) throws InterruptedException {
        // shrink the initially supplied region to include only loaded chunks
        shrinkStartEndPosBBOXCardinal();

        // build the entire light connected set if segmentation is disabled
        Set<Long> lightConnected = null;
        if (WorldExporterConfig.CLIENT.exportVisibleExteriorOnly.get() && !WorldExporterConfig.CLIENT.segmentedExteriorPathfinding.get()) {
            LightConnectedPathfinder lightFinder = new LightConnectedPathfinder(this, world);
            lightConnected = lightFinder.lightConnectedBlockSet(WorldExporterConfig.CLIENT.maxVisibilityPathLength.get());
        }

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
            tasks.add(new ExporterRunnable(this, chunkPartition, threaded, chunkConsumer, CHUNKS_PER_CONSUME, lightConnected));
        }

        if (threads == 1) {
            // basic single threaded export ran on the main thread
            tasks.get(0).run();
        } else {
            // create the given amount of threads (capped to number of tasks), and start a runnable on each thread
            int numThreads = Math.min(threads, tasks.size());
            ExecutorService exporterThreadPool = Executors.newFixedThreadPool(numThreads);
            LOGGER.info("Exporter created " + numThreads + " threads");
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
        if (pos.getX() >= startPosClampedHeight.getX())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.X, Direction.AxisDirection.POSITIVE).get3DDataValue());
        if (pos.getX() <= endPosClampedHeight.getX())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.X, Direction.AxisDirection.NEGATIVE).get3DDataValue());
        if (pos.getY() >= startPosClampedHeight.getY())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.Y, Direction.AxisDirection.POSITIVE).get3DDataValue());
        if (pos.getY() <= endPosClampedHeight.getY())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.Y, Direction.AxisDirection.NEGATIVE).get3DDataValue());
        if (pos.getZ() >= startPosClampedHeight.getZ())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.Z, Direction.AxisDirection.POSITIVE).get3DDataValue());
        if (pos.getZ() <= endPosClampedHeight.getZ())
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
        BlockPos thisChunkStart = new BlockPos(currentX, startPos.getY(), currentZ);
        BlockPos thisChunkEnd = new BlockPos(
                Math.max(currentX - chunkXOffset, endPos.getX()),
                endPos.getY(),
                Math.max(currentZ - chunkZOffset, endPos.getZ()));
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

    // may only be called on the main thread
    public synchronized BufferedImage getAtlasImage(ResourceLocation resource) {
        int glTextureId = getGlTextureId(resource, false);
        return getAtlasImage(glTextureId);
    }

    // may only be called on the main thread
    public synchronized BufferedImage getAtlasImage(int glTextureId) {
        if (invalidGlId(glTextureId)) return null;
        return atlasCacheMap.computeIfAbsent(glTextureId, Exporter::computeImage);
    }

    // returns null if the provided ResourceLocation does not refer to an AtlasTexture
    // could check if this is equivalent to MissingTextureSprite if this is ever a problem
    protected Pair<ResourceLocation, TextureAtlasSprite> getTextureFromAtlas(ResourceLocation resource, UVBounds uvBounds, boolean threaded) {
        Texture texture = getTexture(resource, threaded);
        if (!(texture instanceof AtlasTexture)) return null;
        AtlasTexture atlasTexture = (AtlasTexture) texture;

        // Currently this is a memoized linear check over all an atlasTexture's TextureAtlasSprites to find
        // which TextureAtlasSprite contains the given UVBounds
        // If this is ever too slow a structure like a quadtree or spatial hashing could be used, but profiling shows this to be a non-issue
        synchronized (atlasUVToSpriteCache) {
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

    // may only be called on the main thread
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

    // Gets the specular texture for a quad, if any, and separates it into separate images specified in this lab-pbr format:
    // https://github.com/rre36/lab-pbr/wiki/Specular-Texture-Details
    @Nullable
    protected SpecularData getSpecularData(Quad quad, boolean perceptualRoughness) {
        BufferedImage specularImage = getImageForField(quad, OptifineReflector.multiTexSpec);
        if (specularImage == null) return null;
        return LABPBRParser.parseSpecular(specularImage, perceptualRoughness);
    }

    // Gets the normal texture for a quad, if any, and separates it into separate images specified in this lab-pbr format:
    // https://github.com/rre36/lab-pbr/wiki/Normal-Texture-Details
    @Nullable
    protected NormalData getNormalData(Quad quad, boolean outputOpenGLNormals) {
        BufferedImage normalImage = getImageForField(quad, OptifineReflector.multiTexNorm);
        if (normalImage == null) return null;
        return LABPBRParser.parseNormal(normalImage, outputOpenGLNormals);
    }

    // expects either the norm or spec fields from OptifineReflector
    @Nullable
    private BufferedImage getImageForField(Quad quad, Field field) {
        BufferedImage image = null;
        if (quad.getSprite() != null) {
            TextureAtlasSprite sprite = quad.getSprite();
            Texture atlas = sprite.atlas();
            try {
                Object multiTex = OptifineReflector.multiTex.get(atlas);
                int glTextureId = field.getInt(multiTex);
                image = getAtlasSubImage(sprite, -1, glTextureId);
            } catch (Exception e) {
                LOGGER.warn("Unable to access an optifine field: " + field + ", disabling optifine for this export.");
                OptifineReflector.validOptifine = false;
            }
        } else if (quad.getTexture() != null) {
            Texture texture = quad.getTexture();
            try {
                Object multiTex = OptifineReflector.multiTex.get(texture);
                int glTextureId = field.getInt(multiTex);
                image = getAtlasImage(glTextureId);
            } catch (Exception e) {
                LOGGER.warn("Unable to access an optifine field: " + field + ", disabling optifine for this export.");
                OptifineReflector.validOptifine = false;
            }
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
        if (uvbound.uDist() <= 0.000001f || uvbound.vDist() <= 0.000001f) {
            LOGGER.warn("Could not determine texture image from UV since the distances were so small: ");
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
            int layer1Priority = renderOrder.getOrDefault(quad1Layer, OTHER_ORDER);
            int layer2Priority = renderOrder.getOrDefault(quad2Layer, OTHER_ORDER);
            // higher priority -> more transparent
            return Integer.compare(layer1Priority, layer2Priority);
        };
    }

    private Comparator<Quad> getQuadSort() {
        return (quad1, quad2) -> {
            RenderType quad1Layer = quad1.getType();
            RenderType quad2Layer = quad2.getType();
            int layer1Priority = renderOrder.getOrDefault(quad1Layer, -1);
            int layer2Priority = renderOrder.getOrDefault(quad2Layer, -1);
            if (layer1Priority == -1 || layer2Priority == -1 || layer1Priority == layer2Priority) {
                float avg1;
                float avg2;
                synchronized (this) {
                    if (quad1.hasUV()) {
                        avg1 = uvTransparencyCache.computeIfAbsent(Pair.of(quad1.getResource(), quad1.getUvBounds()), k -> ImgUtils.averageTransparencyValue(getImage(quad1)));
                    } else {
                        avg1 = (float) ((quad1.getColor() & 0xFF000000) >>> 24);
                    }

                    if (quad2.hasUV()) {
                        avg2 = uvTransparencyCache.computeIfAbsent(Pair.of(quad2.getResource(), quad2.getUvBounds()), k -> ImgUtils.averageTransparencyValue(getImage(quad2)));
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

    protected void addTask(Runnable task) throws InterruptedException {
        mainThreadTasks.put(task);
    }

    protected void addThreadTask(Runnable task) {
        threadPool.submit(task);
    }
}
