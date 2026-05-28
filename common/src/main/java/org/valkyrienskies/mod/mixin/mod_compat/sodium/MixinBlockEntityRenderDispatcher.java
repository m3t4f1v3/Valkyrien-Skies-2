package org.valkyrienskies.mod.mixin.mod_compat.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.compat.sodium.SodiumCompat;

@Mixin(BlockEntityRenderDispatcher.class)
public class MixinBlockEntityRenderDispatcher {
    @WrapOperation(
        method = "setupAndRender",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I")
    )
    private static int getDynamicLight(BlockAndTintGetter blockAndTintGetter, BlockPos blockPos, Operation<Integer> original) {
        Level level = (Level) blockAndTintGetter;
        int packedLight = original.call(blockAndTintGetter, blockPos);
        if (!VSGameConfig.CLIENT.getDynamicShipLighting() && !VSGameConfig.CLIENT.getDynamicShipToWorldLighting()) return packedLight;
        int sky = LightTexture.sky(packedLight);
        int block = LightTexture.block(packedLight);
        if (VSGameUtilsKt.isBlockInShipyard(level, blockPos)) {
            Vec3 center = VSGameUtilsKt.toWorldCoordinates(level, blockPos.getCenter());
            BlockPos worldPos = BlockPos.containing(center);
            int packedLightWorld = original.call(blockAndTintGetter, worldPos);
            sky = Math.min(sky, LightTexture.sky(packedLightWorld));
            block = Math.max(block, LightTexture.block(packedLightWorld));
            if (VSGameConfig.CLIENT.getDynamicShipToWorldLighting()) {
                int blockShipToShip = SodiumCompat.getWorldFromShipStorage().getBlockLightAt(worldPos);
                block = Math.max(block, blockShipToShip);
            }
        } else if (VSGameConfig.CLIENT.getDynamicShipToWorldLighting()) {
            int blockShipToWorld = SodiumCompat.getWorldFromShipStorage().getBlockLightAt(blockPos);
            block = Math.max(block, blockShipToWorld);
        }
        return LightTexture.pack(block, sky);
    }
}
