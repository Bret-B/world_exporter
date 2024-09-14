package bret.worldexporter.mixins;

import bret.worldexporter.IMixinChunkArrayAccessor;
import bret.worldexporter.WorldExporter;
import bret.worldexporter.util.Pairing;
import net.minecraft.client.multiplayer.ClientChunkProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.SectionPos;
import net.minecraft.world.biome.BiomeContainer;
import net.minecraft.world.chunk.AbstractChunkProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.lighting.WorldLightManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// TODO: start forgetting chunks from additional storage based on reference counting?
@Mixin(ClientChunkProvider.class)
public abstract class MixinClientChunkProvider extends AbstractChunkProvider {
    @Shadow
    public volatile ClientChunkProvider.ChunkArray storage;
    @Final
    @Shadow
    private ClientWorld level;

    // return: Chunk
//    @Inject(at = @At(value = "HEAD"), method = "getChunk(IILnet/minecraft/world/chunk/ChunkStatus;Z)Lnet/minecraft/world/chunk/Chunk;", cancellable = true)
//    private void onGetChunk(int pChunkX, int pChunkZ, ChunkStatus pRequiredStatus, boolean pLoad, CallbackInfoReturnable<Chunk> cir) {
//
//
////        if (this.storage.inRange(pChunkX, pChunkZ)) {
////            Chunk chunk = this.storage.getChunk(this.storage.getIndex(pChunkX, pChunkZ));
////            if (isValidChunk(chunk, pChunkX, pChunkZ)) {
////                return chunk;
////            }
////        }
////
////        return pLoad ? this.emptyChunk : null;
//    }

//    @Inject(at = @At(value = "HEAD"), method = "drop", cancellable = true)
//    public void drop(int pX, int pZ, CallbackInfo ci) {
//        if (!WorldExporter.isExporting()) return;
//
//        IMixinChunkArrayAccessor chunkArrayAccessor = (IMixinChunkArrayAccessor)(Object) storage;
////        chunkArrayAccessor
//
////        if (this.storage.inRange(pX, pZ)) {
////            int i = this.storage.getIndex(pX, pZ);
////            Chunk chunk = this.storage.getChunk(i);
////            if (isValidChunk(chunk, pX, pZ)) {
////                net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.world.ChunkEvent.Unload(chunk));
////                this.storage.replace(i, chunk, (Chunk)null);
////            }
////        }
//    }

    // return: Chunk
    @Inject(at = @At(value = "HEAD"), method = "replaceWithPacketData", cancellable = true)
    private void onReplaceWithPacketData(int pX, int pZ, BiomeContainer biomeContainer, PacketBuffer readBuffer,
                                         CompoundNBT heightMaps, int availableSections, boolean isFullChunk, CallbackInfoReturnable<Chunk> cir) {
        if (!WorldExporter.isExporting()) return;

        Chunk chunk;
        IMixinChunkArrayAccessor storageAccessor = (IMixinChunkArrayAccessor)(Object) storage;
        if (!storage.inRange(pX, pZ)) {
            // client is exporting and a chunk is being added outside the view range to the custom storage
            chunk = new Chunk(level, new ChunkPos(pX, pZ), biomeContainer);
            @SuppressWarnings("DataFlowIssue")
            int index = storageAccessor.worldexporter$createChunkIndex(pX, pZ);
            storageAccessor.worldexporter$addChunk(index, chunk);
        }

        chunk = this.getChunkNow(pX, pZ);
        if (!isFullChunk && ClientChunkProvider.isValidChunk(chunk, pX, pZ)) {
            chunk.replaceWithPacketData(biomeContainer, readBuffer, heightMaps, availableSections);
        } else {
            if (biomeContainer == null) {
                cir.setReturnValue(null);
                return;
            }

            chunk = new Chunk(level, new ChunkPos(pX, pZ), biomeContainer);
            chunk.replaceWithPacketData(biomeContainer, readBuffer, heightMaps, availableSections);
            storage.replace(storage.getIndex(pX, pZ), chunk);
        }

        ChunkSection[] achunksection = chunk.getSections();
        WorldLightManager worldlightmanager = this.getLightEngine();
        worldlightmanager.enableLightSources(new ChunkPos(pX, pZ), true);

        for(int j = 0; j < achunksection.length; ++j) {
            ChunkSection chunksection = achunksection[j];
            worldlightmanager.updateSectionStatus(SectionPos.of(pX, j, pZ), ChunkSection.isEmpty(chunksection));
        }

        level.onChunkLoaded(pX, pZ);
        // net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.world.ChunkEvent.Load(chunk));
        cir.setReturnValue(chunk);
        cir.cancel();
    }


    @Mixin(ClientChunkProvider.ChunkArray.class)
    public static abstract class MixinChunkArray implements IMixinChunkArrayAccessor {
        // TODO not currently thread safe
        // For a ChunkPos (X, Z), if createChunkIndex has been called with those coordinates then getIndex will return a
        // negative integer that will be valid for retrieving a chunk (nullable) in the additionalStorage map.
        // This also means that coordinate returns true for inRange() and other calls using that index are valid
        // note: chunkCount is not updated
        @Unique
        // private final Map<Integer, Chunk> worldexporter$additionalStorage = new HashMap<>();
        // private final Map<Integer, Chunk> worldexporter$additionalStorage = new Int2ObjectOpenHashMap<>();
        private final Map<Integer, Chunk> worldexporter$additionalStorage = new ConcurrentHashMap<>();
        @Unique
        // private final Map<Long, Integer> worldexporter$pairToNegativeKey = new HashMap<>();
        // private final Map<Long, Integer> worldexporter$pairToNegativeKey = new Long2IntOpenHashMap();
        // this is required because the standard ChunkArray calls work with integer keys
        private final Map<Long, Integer> worldexporter$pairToNegativeKey = new ConcurrentHashMap<>();
        @Unique
        private final AtomicInteger worldexporter$negativeCount = new AtomicInteger(0);
        // TODO add a set of previous negative keys to be reused when chunks are deleted, so there is
        //  no potential to underflow

        @Override
        public int worldexporter$createChunkIndex(int pX, int pZ) {
            // TODO pull from list of reused negative int keys
            long pairKey = Pairing.fromPair(pX, pZ);
            int intKey = worldexporter$negativeCount.decrementAndGet();
            worldexporter$pairToNegativeKey.put(pairKey, intKey);
            return intKey;
        }

        @Override
        public void worldexporter$addChunk(int index, Chunk chunk) {
            worldexporter$additionalStorage.put(index, chunk);
        }

        @Override
        public void worldexporter$removeChunkCustom(int pX, int pZ) {
            long pairKey = Pairing.fromPair(pX, pZ);
            int intKey = worldexporter$pairToNegativeKey.get(pairKey);
            worldexporter$removeChunkCustom(intKey);
        }

        @Override
        public void worldexporter$removeChunkCustom(int index) {
            // TODO add to reused negative int key list
            worldexporter$additionalStorage.remove(index);
        }

        @Override
        public void worldexporter$clear() {
            worldexporter$negativeCount.set(0);
            worldexporter$pairToNegativeKey.clear();
            worldexporter$additionalStorage.clear();
        }

        // return: int
        @Inject(at = @At(value = "HEAD"), method = "getIndex", cancellable = true)
        private void onGetIndex(int pX, int pZ, CallbackInfoReturnable<Integer> cir) {
            if (!WorldExporter.isExporting()) return;

            long pairKey = Pairing.fromPair(pX, pZ);
            if (worldexporter$pairToNegativeKey.containsKey(pairKey)) {
                int intKey = worldexporter$pairToNegativeKey.get(pairKey);
                cir.setReturnValue(intKey);
                cir.cancel();
            }
            // else: fallthrough to default function code
            // return Math.floorMod(pZ, this.viewRange) * this.viewRange + Math.floorMod(pX, this.viewRange);
        }

        @Inject(at = @At(value = "HEAD"), method = "replace(ILnet/minecraft/world/chunk/Chunk;)V", cancellable = true)
        private void onReplace(int pChunkIndex, Chunk pChunk, CallbackInfo ci) {
            if (!WorldExporter.isExporting()) return;

            // TODO: need to do anything like the default function related to level.unload?
            //  probably not, since the server should be paused?
            if (worldexporter$additionalStorage.containsKey(pChunkIndex)) {
                if (pChunk == null) {
                    worldexporter$removeChunkCustom(pChunkIndex);
                } else {
                    worldexporter$additionalStorage.put(pChunkIndex, pChunk);
                }
                ci.cancel();
            }
            // else: fallthrough to default function code
//            Chunk chunk = this.chunks.getAndSet(pChunkIndex, pChunk);
//            if (chunk != null) {
//                --this.chunkCount;
//                ClientChunkProvider.this.level.unload(chunk);
//            }
//            if (pChunk != null) {
//                ++this.chunkCount;
//            }
        }

        // From what I can tell, the function is always called with pReplaceWith set to null to delete the chunk
        // return: Chunk
        @Inject(at = @At(value = "HEAD"), method = "replace(ILnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/chunk/Chunk;)Lnet/minecraft/world/chunk/Chunk;", cancellable = true)
        protected void onReplace(int pChunkIndex, Chunk pChunk, Chunk pReplaceWith, CallbackInfoReturnable<Chunk> cir) {
            if (!WorldExporter.isExporting()) return;

            // TODO: need to do anything like the default function related to level.unload?
            if (worldexporter$additionalStorage.containsKey(pChunkIndex)) {
                if (pReplaceWith == null) {
                    worldexporter$removeChunkCustom(pChunkIndex);
                } else {
                    worldexporter$additionalStorage.put(pChunkIndex, pReplaceWith);
                }
                cir.setReturnValue(pChunk);
                cir.cancel();
            }
            // else: fallthrough to default function code
//            if (this.chunks.compareAndSet(pChunkIndex, pChunk, pReplaceWith) && pReplaceWith == null) {
//                --this.chunkCount;
//            }
//            ClientChunkProvider.this.level.unload(pChunk);
//            return pChunk;
        }

        // return: boolean
        @Inject(at = @At(value = "HEAD"), method = "inRange", cancellable = true)
        private void onInRange(int pX, int pZ, CallbackInfoReturnable<Boolean> cir) {
            if (!WorldExporter.isExporting()) return;

            long pairKey = Pairing.fromPair(pX, pZ);
            if (worldexporter$pairToNegativeKey.containsKey(pairKey)) {
                cir.setReturnValue(true);
                cir.cancel();
            }
            // else: fallthrough to default function code
            // return Math.abs(pX - this.viewCenterX) <= this.chunkRadius && Math.abs(pZ - this.viewCenterZ) <= this.chunkRadius;
        }

        // return: Chunk
        @Inject(at = @At(value = "HEAD"), method = "getChunk", cancellable = true)
        protected void getChunk(int pChunkIndex, CallbackInfoReturnable<Chunk> cir) {
            if (!WorldExporter.isExporting()) return;

            if (worldexporter$additionalStorage.containsKey(pChunkIndex)) {
                cir.setReturnValue(worldexporter$additionalStorage.get(pChunkIndex));
                cir.cancel();
            }
            // else: fallthrough to default function code
            // return this.chunks.get(pChunkIndex);
        }
    }
}
