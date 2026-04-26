package org.valkyrienskies.mod.mixin.feature.air_pockets.client.renderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.air_pockets.client.AirPocketRenderHooks;
import org.valkyrienskies.mod.common.config.VSGameConfig;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import net.minecraft.client.renderer.RenderType;

@Mixin(DefaultMaterials.class)
public class MixinDefaultMaterials {

    @Unique
    private static final Material AIR_POCKET = new Material(
            AirPocketRenderHooks.AIR_POCKET_PASS,
            AlphaCutoffParameter.ZERO,
            true);

    @Inject(method = "forRenderLayer", at = @At("HEAD"), cancellable = true)
    private static void onForRenderLayer(RenderType layer, CallbackInfoReturnable<Material> cir) {
        if (layer == RenderType.tripwire()
                && VSGameConfig.CLIENT.getUnderwater().getEnableWaterCulling()) {
            cir.setReturnValue(AIR_POCKET);
        }
    }
}
