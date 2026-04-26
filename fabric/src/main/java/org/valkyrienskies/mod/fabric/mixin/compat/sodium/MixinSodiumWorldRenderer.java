package org.valkyrienskies.mod.fabric.mixin.compat.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.air_pockets.client.AirPocketRenderHooks;
import org.valkyrienskies.mod.compat.SodiumCompat;

import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.renderer.RenderType;

@Mixin(value = SodiumWorldRenderer.class, remap = false, priority = 1100)
public abstract class MixinSodiumWorldRenderer {
    @Shadow
    private RenderSectionManager renderSectionManager;

    @Inject(method = "drawChunkLayer", at = @At("TAIL"))
    private void afterChunkLayer(RenderType renderLayer, ChunkRenderMatrices matrices, double x, double y, double z,
            CallbackInfo ci) {
            //todo gate behind whatever, or move to a diff because the priority is a bit weird
            if (renderLayer == RenderType.tripwire()) {
                renderSectionManager.renderLayer(matrices, AirPocketRenderHooks.AIR_POCKET_PASS, x, y, z);
            }
            SodiumCompat.renderShips(renderSectionManager, renderLayer, matrices, x, y, z);
    }
}
