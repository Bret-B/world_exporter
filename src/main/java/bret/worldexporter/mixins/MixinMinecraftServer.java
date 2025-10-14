package bret.worldexporter.mixins;

import bret.worldexporter.WorldExporterServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer {
    @Unique
    private final BooleanSupplier worldexporter$unlimitedTimeSupplier = () -> true;

    @Inject(at = @At(value = "HEAD"), method = "tickServer", cancellable = true)
    private void onTickServer(BooleanSupplier pHasTimeLeft, CallbackInfo ci) {
        if (!WorldExporterServer.serverExporting.get()) {
            return;
        }

//        if (WorldExporterServer.serverShouldBePaused.get()) {
//            ci.cancel();
//            // WorldExporter.LOGGER.info("Skipping server tick");
//            // keep the connections alive
//            Objects.requireNonNull(((MinecraftServer)(Object) this).getConnection()).tick();
//            // let the server unload chunks (?)
////            WorldExporterServer.requesterWorld.get().getChunkSource().tick(worldexporter$unlimitedTimeSupplier);
////            WorldExporterServer.requesterWorld.get().getChunkSource().chunkMap.tick(worldexporter$unlimitedTimeSupplier);
////            WorldExporterServer.requesterWorld.get().getChunkSource().chunkMap.processUnloads(worldexporter$unlimitedTimeSupplier);
//        }

        WorldExporterServer.processTasks(worldexporter$unlimitedTimeSupplier);
    }
}
