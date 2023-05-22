package bret.worldexporter.mixins;

import bret.worldexporter.WorldExporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.network.play.ClientPlayNetHandler;
import net.minecraft.network.play.server.SUnloadChunkPacket;
import net.minecraft.network.play.server.SUpdateViewDistancePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetHandler.class)
public class MixinClientPlayNetHandler {

    @Inject(at = @At(value = "HEAD"), method = "handleForgetLevelChunk", cancellable = true)
    private void onHandleForgetLevelChunk(SUnloadChunkPacket pPacket, CallbackInfo info) {
        // cancel the chunk unload if it is within the custom WorldExporter force chunk radius
        if (WorldExporter.isInKeepDistance(pPacket)) {
            info.cancel();
            WorldExporter.addHeldChunk(pPacket);
        }
    }

    // Short circuit injection: cancel update if custom value for chunk cache radius is currently set
    @Inject(at = @At(value = "HEAD"), method = "handleSetChunkCacheRadius", cancellable = true)
    private void onHandleSetChunkCacheRadius(SUpdateViewDistancePacket pPacket, CallbackInfo info) {
        if (WorldExporter.getForceChunkRadius() > 0) info.cancel();
    }
}
