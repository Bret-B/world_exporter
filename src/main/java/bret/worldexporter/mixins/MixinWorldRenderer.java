package bret.worldexporter.mixins;

import bret.worldexporter.WorldExporterClient;
import net.minecraft.client.renderer.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class MixinWorldRenderer {
    @Inject(at = @At(value = "HEAD"), method = "setSectionDirty(IIIZ)V", cancellable = true)
    private void onSetSectionDirty(int pSectionX, int pSectionY, int pSectionZ, boolean pRerenderOnMainThread, CallbackInfo ci) {
        if (WorldExporterClient.isClientExporting()) {
            ci.cancel();
        }
    }
}
