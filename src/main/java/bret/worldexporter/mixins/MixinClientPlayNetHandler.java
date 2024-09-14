package bret.worldexporter.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.play.ClientPlayNetHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.registry.DynamicRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ClientPlayNetHandler.class)
public abstract class MixinClientPlayNetHandler {
    @Shadow
    private ClientWorld level;
    @Shadow
    private Minecraft minecraft;
    @Shadow
    private DynamicRegistries registryAccess;

//    @Inject(at = @At(value = "HEAD"), method = "handleLevelChunk", cancellable = true)
//    public void onHandleLevelChunk(SChunkDataPacket pPacket, CallbackInfo ci) {
//        PacketThreadUtil.ensureRunningOnSameThread(pPacket, (ClientPlayNetHandler)(Object)this, this.minecraft);
//        int i = pPacket.getX();
//        int j = pPacket.getZ();
//        BiomeContainer biomecontainer = pPacket.getBiomes() == null ? null : new BiomeContainer(this.registryAccess.registryOrThrow(Registry.BIOME_REGISTRY), pPacket.getBiomes());
//        Chunk chunk = this.level.getChunkSource().replaceWithPacketData(i, j, biomecontainer, pPacket.getReadBuffer(), pPacket.getHeightmaps(), pPacket.getAvailableSections(), pPacket.isFullChunk());
//        if (chunk != null && pPacket.isFullChunk()) {
//            this.level.reAddEntitiesToChunk(chunk);
//        }
//
//        for(int k = 0; k < 16; ++k) {
//            this.level.setSectionDirtyWithNeighbors(i, k, j);
//        }
//
//        for(CompoundNBT compoundnbt : pPacket.getBlockEntitiesTags()) {
//            BlockPos blockpos = new BlockPos(compoundnbt.getInt("x"), compoundnbt.getInt("y"), compoundnbt.getInt("z"));
//            TileEntity tileentity = this.level.getBlockEntity(blockpos);
//            if (tileentity != null) {
//                tileentity.handleUpdateTag(this.level.getBlockState(blockpos), compoundnbt);
//            }
//        }
//    }
}
