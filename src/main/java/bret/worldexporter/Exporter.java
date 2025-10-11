package bret.worldexporter;

import bret.worldexporter.config.WorldExporterConfig;
import bret.worldexporter.render.CustomBlockRendererDispatcher;
import bret.worldexporter.util.*;
import bret.worldexporter.util.disk.SimpleSet;
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
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import org.apache.commons.lang3.tuple.Pair;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.awt.image.RasterFormatException;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static bret.worldexporter.WorldExporter.LOGGER;
import static bret.worldexporter.util.FileUtils.deleteDirectoryRecursive;
import static java.awt.image.BufferedImage.TYPE_INT_ARGB;

public class Exporter {
    public static final int WORLD_HEIGHT_LIMIT = 255;
    public static final int WORLD_LOWER_HEIGHT_LIMIT = 0;
    private static final int CHUNKS_PER_CONSUME = 4;
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
    public final boolean sidesAreForced = WorldExporterConfig.CLIENT.exportSides.get();
    public final Minecraft mc = Minecraft.getInstance();
    public final ClientWorld world = Objects.requireNonNull(mc.level);
    protected final CustomBlockRendererDispatcher blockRendererDispatcher = new CustomBlockRendererDispatcher(mc.getBlockRenderer().getBlockModelShaper(), mc.getBlockColors());
    protected final Map<Integer, BufferedImage> atlasCacheMap = new HashMap<>();
    protected final int playerX;
    protected final int playerZ;
    public final int playerXOffset;
    public final int playerZOffset;
    private final Map<Pair<ResourceLocation, UVBounds>, Pair<ResourceLocation, TextureAtlasSprite>> atlasUVToSpriteCache = new HashMap<>();
    private final Map<Pair<ResourceLocation, UVBounds>, Float> uvTransparencyCache = new HashMap<>();
    private final Comparator<Quad> quadComparator = getQuadSort();
    private final Comparator<Quad> quadComparatorThreaded = getQuadSortThreaded();
    private final LinkedBlockingQueue<Runnable> mainThreadTasks = new LinkedBlockingQueue<>();
    private final int threads;
    private ExecutorService threadPool = null;
    private BlockPos highPos;
    private BlockPos lowPos;
    private BlockPos highPosClampedHeight;
    private BlockPos lowPosClampedHeight;
    private AmbientOcclusionStatus preAO = mc.options.ambientOcclusion;
    private boolean preShadows = mc.options.entityShadows;
    private static Exporter instance = null;
    private ClassLoader renderThreadClassLoader = null;

    public Exporter(ClientPlayerEntity player, int radius, int lower, int upper, boolean optimizeMesh, boolean randomize, int threads) {
        OptifineReflector.init();
        this.randomize = randomize;
        this.optimizeMesh = optimizeMesh;
        this.threads = threads;
        playerX = (int) player.getX();
        playerZ = (int) player.getZ();
        playerXOffset = WorldExporterConfig.CLIENT.relativeCoordinates.get() ? playerX : 0;
        playerZOffset = WorldExporterConfig.CLIENT.relativeCoordinates.get() ? playerZ : 0;
        highPos = new BlockPos(playerX + radius, upper, playerZ + radius);
        lowPos = new BlockPos(playerX - radius, lower, playerZ - radius);
        highPosClampedHeight = new BlockPos(highPos.getX(), Math.min(upper, WORLD_HEIGHT_LIMIT), highPos.getZ());
        lowPosClampedHeight = new BlockPos(lowPos.getX(), Math.max(lower, WORLD_LOWER_HEIGHT_LIMIT), lowPos.getZ());
        instance = this;
    }

    public static Exporter getInstance() {
        return instance;
    }

    public LinkedBlockingQueue<Runnable> getMainThreadTasks() {
        return mainThreadTasks;
    }

    public BlockPos getHighPos() {
        return highPos;
    }

    public BlockPos getLowPos() {
        return lowPos;
    }

    public BlockPos getHighPosClampedHeight() {
        return highPosClampedHeight;
    }

    public BlockPos getLowPosClampedHeight() {
        return lowPosClampedHeight;
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

    // must only be called on the main thread
    public int getGlTextureId(ResourceLocation resource) {
        Texture texture = getTexture(resource);
        if (texture == null) return -1;
        return texture.getId();
    }

    // fetch the Texture for a ResourceLocation from Minecraft's TextureManager, or try to load it if needed
    public Texture getTexture(ResourceLocation resource) {
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
            try {
                RunnableFuture<Boolean> task = new FutureTask<>(() -> {
                    synchronized (textureManager) {
                        textureManager.register(resource, newTexture);
                    }
                    return true;
                });
                addMainThreadTask(task);
                task.get();
            } catch (InterruptedException | ExecutionException e) {
                LOGGER.warn("Failed to perform register texture task: ", e);
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

    public void useClassLoaderOnThisThread() {
        Thread.currentThread().setContextClassLoader(renderThreadClassLoader);
    }

    // required to change MC options for proper export rendering
    public void setup() {
        preAO = mc.options.ambientOcclusion;
        preShadows = mc.options.entityShadows;
        mc.options.ambientOcclusion = AmbientOcclusionStatus.OFF;
        mc.options.entityShadows = false;

        // pause the IntegratedServer, if there is one
        // setPause(true);
        ChunkThreadSyncManager.createCache();
        renderThreadClassLoader = Thread.currentThread().getContextClassLoader();
        threadPool = ThreadUtils.threadPoolWithModClassLoader(Runtime.getRuntime().availableProcessors());
    }

    // required to reset MC options related to rendering + finish tasks
    public void finish() {
        mc.options.ambientOcclusion = preAO;
        mc.options.entityShadows = preShadows;

        // resume the IntegratedServer, if there is one
        // setPause(false);
        Path cacheDir = Paths.get(WorldExporterClient.getCacheDirectory());
        deleteDirectoryRecursive(cacheDir);

        // finish any other tasks
        threadPool.shutdown();
        try {
            //noinspection ResultOfMethodCallIgnored
            threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isOnExportEdge(BlockPos pos) {
        return pos.getX() == highPos.getX() || pos.getX() == lowPos.getX()
                || pos.getY() == highPosClampedHeight.getY() || pos.getY() == lowPosClampedHeight.getY()
                || pos.getZ() == highPos.getZ() || pos.getZ() == lowPos.getZ();
    }

    public boolean inExportRange(BlockPos pos) {
        return lowPos.getX() <= pos.getX() && pos.getX() <= highPos.getX()
                && lowPosClampedHeight.getY() <= pos.getY() && pos.getY() <= highPosClampedHeight.getY()
                && lowPos.getZ() <= pos.getZ() && pos.getZ() <= highPos.getZ();
    }

    // returns 0 if pos lies along the edge of the export
    // returns 1 if pos is inside the export
    // returns -1 if pos is outside the export
    public int inExportStatus(BlockPos pos) {
        return isOnExportEdge(pos) ? 0 : (inExportRange(pos) ? 1 : -1);
    }

    private void preTouchChunks() {
        AtomicBoolean done = new AtomicBoolean(false);
        Runnable task = () -> {
            useClassLoaderOnThisThread();
            AtomicInteger i = new AtomicInteger(1);
            ChunkPos.rangeClosed(new ChunkPos(lowPos), new ChunkPos(highPos)).forEach(chunkPos -> {
                // hasChunk calls getChunk under the hood, and I'm not sure about obeying pLoad in the provider mixin
                if (!world.getChunkSource().storage.inRange(chunkPos.x, chunkPos.z)) {
                    ChunkThreadSyncManager.requestChunk(chunkPos.x, chunkPos.z);
                }
                // Pause and give the main thread a chance to catch up every once in a while
                if (i.incrementAndGet() % 100 == 0) {
                    i.set(1);
                    //noinspection StatementWithEmptyBody
                    while (ChunkThreadSyncManager.hasPending()) {
                    }
                }
            });
            done.set(true);
        };
        (new Thread(task)).start();

        // Some packets will probably come back and hit the task queue after, but that shouldn't cause problems
        ChunkThreadSyncManager.noSyncRequiredMainThreadEventLoop(done::get, mainThreadTasks);
    }

    // attempts to shrink the actual export radius to include only chunks that are loaded
    // (only in the NESW directions, never vertically)
    private void shrinkStartEndPosBBOXCardinal() {
        Chunk ch;
        int lowX = lowPos.getX();
        int lowZ = lowPos.getZ();
        int highX = highPos.getX();
        int highZ = highPos.getZ();
        LOGGER.info("Shrinking original bbox of (lowx, lowz, highx, highz) = ({}, {}, {}, {})", lowX, lowZ, highX, highZ);

        // starting at lowPos chunk, scanline chunks towards highPos in + x direction
        // finds lowX
        for (int x = lowPos.getX(); x != highPos.getX(); x = Math.min(x + 16, highPos.getX())) {
            boolean lineHasChunk = false;
            for (int z = lowPos.getZ(); z != highPos.getZ(); z = Math.min(z + 16, highPos.getZ())) {
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

        // starting at lowPos chunk, scanline chunks towards highPos in + z direction
        // finds lowZ
        for (int z = lowPos.getZ(); z != highPos.getZ(); z = Math.min(z + 16, highPos.getZ())) {
            boolean lineHasChunk = false;
            for (int x = lowPos.getX(); x != highPos.getX(); x = Math.min(x + 16, highPos.getX())) {
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

        // starting at highPos chunk, scanline chunks towards lowPos in - x direction
        // finds highX
        for (int x = highPos.getX(); x != lowPos.getX(); x = Math.max(x - 16, lowPos.getX())) {
            boolean lineHasChunk = false;
            for (int z = highPos.getZ(); z != lowPos.getZ(); z = Math.max(z - 16, lowPos.getZ())) {
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

        // starting at highPos chunk, scanline chunks towards lowPos in - z direction
        // finds highZ
        for (int z = highPos.getZ(); z != lowPos.getZ(); z = Math.max(z - 16, lowPos.getZ())) {
            boolean lineHasChunk = false;
            for (int x = highPos.getX(); x != lowPos.getX(); x = Math.max(x - 16, lowPos.getX())) {
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

        lowPos = new BlockPos(
                Math.max(lowPos.getX(), lowX),
                lowPos.getY(),
                Math.max(lowPos.getZ(), lowZ));
        highPos = new BlockPos(
                Math.min(highPos.getX(), highX),
                highPos.getY(),
                Math.min(highPos.getZ(), highZ));
        lowPosClampedHeight = new BlockPos(lowPos.getX(), lowPosClampedHeight.getY(), lowPos.getZ());
        highPosClampedHeight = new BlockPos(highPos.getX(), highPosClampedHeight.getY(), highPos.getZ());
        LOGGER.info("To (lowx, lowz, highx, highz) = ({}, {}, {}, {})",
                lowPos.getX(), lowPos.getZ(), highPos.getX(), highPos.getZ());
    }

    // this function MUST be run on the main thread
    public void runExport(Consumer<ArrayList<ExportChunk>> chunkConsumer) throws InterruptedException, ExecutionException {
        if (WorldExporterClient.canRequestChunks()) {
            // improve export speed by caching all the chunks we'll need beforehand
            LOGGER.info("Caching chunks from server before beginning export");
            preTouchChunks();
            LOGGER.info("Done caching chunks");
        } else {
            // shrink the initially supplied region to include only loaded chunks since chunks cannot be requested from server
            shrinkStartEndPosBBOXCardinal();
        }

        SimpleSet<Long> lightConnected = null;
        if (WorldExporterConfig.CLIENT.exportVisibleExteriorOnly.get()) {
            LOGGER.info("Building full light connected set");
            LightConnectedPathfinder lightFinder = new LightConnectedPathfinder(this, world, threads);
            lightConnected = lightFinder.lightConnectedBlockSet(WorldExporterConfig.CLIENT.maxVisibilityPathLength.get());
            LOGGER.info("Full light connected set done building");
        }

        boolean threadSafe = threads == 1;
        List<Pair<BlockPos, BlockPos>> allChunks = new ArrayList<>();
        BlockPosUtils.chunkBoundaries(lowPosClampedHeight, highPosClampedHeight).iterator().forEachRemaining(allChunks::add);
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
            tasks.add(new ExporterRunnable(this, chunkPartition, threadSafe, chunkConsumer, CHUNKS_PER_CONSUME, lightConnected));
        }

        // create the given amount of threads (capped to number of tasks), and start a runnable on each thread
        int numThreads = Math.min(threads, tasks.size());
        ChunkThreadSyncManager.reset(numThreads);
        ExecutorService exporterThreadPool = ThreadUtils.threadPoolWithModClassLoader(numThreads);
        LOGGER.info("Exporter created " + numThreads + " threads");
        tasks.forEach(exporterThreadPool::submit);
        exporterThreadPool.shutdown();
        // wait in this loop to do tasks that are required to be run in the main thread, until threads are finished
        while (!exporterThreadPool.isTerminated()) {
            try {
                // poll here in time increments waiting for tasks; recheck if threads are done on timeout
                Runnable task = mainThreadTasks.poll(1, TimeUnit.MILLISECONDS);
                if (task != null) {
                    task.run();
                } else {
                    // if the sync can be done now (all threads are waiting), execute the queued tasks and then return
                    ChunkThreadSyncManager.mainThreadEventLoop(ChunkThreadSyncManager::isEmpty, mainThreadTasks, true);
                }
            } catch (InterruptedException ignored) {
            }
        }

        // clear out all left-over tasks, if any
        runMainThreadTasksUntil(mainThreadTasks::isEmpty);
        ChunkThreadSyncManager.completeAllTasks();
        if (lightConnected != null) {
            lightConnected.dispose();
        }
    }

    // Must only be called on the main thread
    public void runMainThreadTasksUntil(BooleanSupplier hasFinished) {
        while (!hasFinished.getAsBoolean()) {
            try {
                Runnable task = mainThreadTasks.poll(1, TimeUnit.MILLISECONDS);
                if (task != null) task.run();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // Returns the facing directions that should be forcibly enabled (at the edge of the export) for a given BlockPos
    public BitSet getForcedDirections(BlockPos pos) {
        BitSet bitSet = new BitSet();
        if (sidesAreForced && pos.getX() >= highPosClampedHeight.getX())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.X, Direction.AxisDirection.POSITIVE).get3DDataValue());
        if (sidesAreForced && pos.getX() <= lowPosClampedHeight.getX())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.X, Direction.AxisDirection.NEGATIVE).get3DDataValue());
        if (pos.getY() >= highPosClampedHeight.getY())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.Y, Direction.AxisDirection.POSITIVE).get3DDataValue());
        if (pos.getY() <= lowPosClampedHeight.getY())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.Y, Direction.AxisDirection.NEGATIVE).get3DDataValue());
        if (sidesAreForced && pos.getZ() >= highPosClampedHeight.getZ())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.Z, Direction.AxisDirection.POSITIVE).get3DDataValue());
        if (sidesAreForced && pos.getZ() <= lowPosClampedHeight.getZ())
            bitSet.set(Direction.fromAxisAndDirection(Direction.Axis.Z, Direction.AxisDirection.NEGATIVE).get3DDataValue());
        return bitSet;
    }

    // may only be called on the main thread
    public synchronized BufferedImage getAtlasImage(ResourceLocation resource) {
        int glTextureId = getGlTextureId(resource);
        return getAtlasImage(glTextureId);
    }

    // may only be called on the main thread
    public synchronized BufferedImage getAtlasImage(int glTextureId) {
        if (invalidGlId(glTextureId)) return null;
        return atlasCacheMap.computeIfAbsent(glTextureId, Exporter::computeImage);
    }

    // returns null if the provided ResourceLocation does not refer to an AtlasTexture
    // could check if this is equivalent to MissingTextureSprite if this is ever a problem
    protected Pair<ResourceLocation, TextureAtlasSprite> getTextureFromAtlas(ResourceLocation resource, UVBounds uvBounds) {
        Texture texture = getTexture(resource);
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
    // may only be called on the main thread
    @Nullable
    protected SpecularData getSpecularData(Quad quad, boolean perceptualRoughness) {
        BufferedImage specularImage = getImageForField(quad, OptifineReflector.multiTexSpec);
        if (specularImage == null) return null;
        return LABPBRParser.parseSpecular(specularImage, perceptualRoughness);
    }

    // Gets the normal texture for a quad, if any, and separates it into separate images specified in this lab-pbr format:
    // https://github.com/rre36/lab-pbr/wiki/Normal-Texture-Details
    // may only be called on the main thread
    @Nullable
    protected NormalData getNormalData(Quad quad, boolean outputOpenGLNormals) {
        BufferedImage normalImage = getImageForField(quad, OptifineReflector.multiTexNorm);
        if (normalImage == null) return null;
        return LABPBRParser.parseNormal(normalImage, outputOpenGLNormals);
    }

    // expects either the norm or spec fields from OptifineReflector
    // may only be called on the main thread
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

    // may only be called on the main thread
    protected BufferedImage getAtlasSubImage(TextureAtlasSprite texture, int color, int glTextureId) {
        UVBounds originalUV = new UVBounds(texture.getU0(), texture.getU1(), texture.getV0(), texture.getV1());
        return getImageFromUV(glTextureId, originalUV, color);
    }

    // may only be called on the main thread
    protected BufferedImage getAtlasSubImage(TextureAtlasSprite texture, int color) {
        UVBounds originalUV = new UVBounds(texture.getU0(), texture.getU1(), texture.getV0(), texture.getV1());
        return getImageFromUV(texture.atlas().getId(), originalUV, color);
    }

    // Returns a subimage of a texture's image determined by uvbounds and tints with provided color
    // may only be called on the main thread
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

    protected void sortQuads(ArrayList<Quad> quads) {
        if (mc.isSameThread()) {
            quads.sort(quadComparator);
        } else {
            quads.sort(quadComparatorThreaded);
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

    // Not thread safe
    private Comparator<Quad> getQuadSort() {
        return (quad1, quad2) -> {
            RenderType quad1Layer = quad1.getType();
            RenderType quad2Layer = quad2.getType();
            int layer1Priority = renderOrder.getOrDefault(quad1Layer, -1);
            int layer2Priority = renderOrder.getOrDefault(quad2Layer, -1);
            if (layer1Priority == -1 || layer2Priority == -1 || layer1Priority == layer2Priority) {
                float avg1;
                float avg2;
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
                // higher avg -> less transparent
                return Float.compare(avg2, avg1);
            } else {
                // higher priority -> more transparent
                return Integer.compare(layer1Priority, layer2Priority);
            }
        };
    }

    public void addMainThreadTask(Runnable task) throws InterruptedException {
        if (mc.isSameThread()) {
            task.run();
        } else {
            mainThreadTasks.put(task);
        }
    }

    public <T> T waitForMainThreadTask(FutureTask<T> task) throws InterruptedException, ExecutionException {
        addMainThreadTask(task);
        return task.get();
    }

    protected void addThreadTask(Runnable task) {
        threadPool.submit(task);
    }
}
