package bret.worldexporter.mixins;

import bret.worldexporter.WorldExporter;
import bret.worldexporter.WorldExporterClient;
import bret.worldexporter.networking.packets.PacketHandler;
import net.minecraft.network.INetHandler;
import net.minecraft.network.IPacket;
import net.minecraft.network.PacketThreadUtil;
import net.minecraft.network.play.server.SCustomPayloadPlayPacket;
import net.minecraft.util.concurrent.ThreadTaskExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// For some reason, in versions up to ~1.19 or so, custom packets are forcibly handled on the main thread by Forge.
// This mixin cancels WorldExporter packets from being rescheduled on the main thread - they are therefore handled
// on the netty client thread. Packets that still need to be handled on the main thread should
// queue work through Exporter.addMainThreadTask or ChunkThreadSyncManager.add in their handle() function.
@Mixin(PacketThreadUtil.class)
public class MixinPacketThreadUtil {
    @Inject(at = @At(value = "HEAD"), method = "ensureRunningOnSameThread(Lnet/minecraft/network/IPacket;Lnet/minecraft/network/INetHandler;Lnet/minecraft/util/concurrent/ThreadTaskExecutor;)V", cancellable = true)
    private static <T extends INetHandler> void onEnsureRunningOnSameThread(IPacket<T> pPacket, T pProcessor, ThreadTaskExecutor<?> pExecutor, CallbackInfo ci) {
        if (!WorldExporterClient.isClientExporting()) {
            return;
        }

        if (pPacket instanceof SCustomPayloadPlayPacket) {
            SCustomPayloadPlayPacket customPacket = (SCustomPayloadPlayPacket) pPacket;
            if (customPacket.getIdentifier().equals(PacketHandler.CHANNEL_RESOURCE)) {
                // WorldExporter.LOGGER.info("Cancelling ensureRunningOnSameThread");
                ci.cancel();
            }
        }
    }
}
