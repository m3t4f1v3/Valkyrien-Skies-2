package org.valkyrienskies.mod.fabric.mixin.compat.replay;

import com.replaymod.render.hooks.EntityRendererHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.assembly.SeamlessChunksManager;

@Mixin(EntityRendererHandler.class)
public class MixinEntityRendererHandler {

    @Inject(
        method = "renderWorld(FJ)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render(FJZ)V"
        ),
        remap = false
    )
    private static void injectBeforeRender(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        SeamlessChunksManager manager = SeamlessChunksManager.Companion.get();
        if (manager != null) {
            for (int i = 0; i < 500; i++) {
                manager.drainDeferredBatch();
            }
        }
    }
}
