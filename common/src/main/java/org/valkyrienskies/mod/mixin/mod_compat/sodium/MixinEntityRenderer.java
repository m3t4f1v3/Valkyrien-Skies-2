package org.valkyrienskies.mod.mixin.mod_compat.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.compat.sodium.SodiumCompat;

@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer<T extends Entity> {
    @Shadow
    protected abstract int getSkyLightLevel(T entity, BlockPos blockPos);

    @Shadow
    protected abstract int getBlockLightLevel(T entity, BlockPos blockPos);

    @WrapMethod(
        method = "getPackedLightCoords"
    )
    private int getDynamicLight(T entity, float f, Operation<Integer> original){
        Level level = entity.level();
        Vec3 lightProbe = entity.getLightProbePosition(f);
        int packedLight = original.call(entity, f);
        int sky = LightTexture.sky(packedLight);
        int block = LightTexture.block(packedLight);
        if (VSGameUtilsKt.isBlockInShipyard(level, lightProbe)) {
            Vec3 center = VSGameUtilsKt.toWorldCoordinates(level, lightProbe);
            BlockPos worldPos = BlockPos.containing(center);
            sky = Math.min(sky, getSkyLightLevel(entity, worldPos));
            block = Math.max(block, getBlockLightLevel(entity, worldPos));
            int blockShipToShip = SodiumCompat.getWorldFromShipStorage().getBlockLightAt(worldPos);
            block = Math.max(block, blockShipToShip);
        } else {
            int blockShipToWorld = SodiumCompat.getWorldFromShipStorage().getBlockLightAt(BlockPos.containing(lightProbe));
            block = Math.max(block, blockShipToWorld);
        }
        return LightTexture.pack(block, sky);
    }
}
