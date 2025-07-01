package org.valkyrienskies.mod.forge.mixin.compat.old_create.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ContraptionRenderInfo;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSClientGameUtils;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.render.FlwContraption")
public class MixinFlwContraption {

    @Inject(at = @At("HEAD"), method = "setupModelViewPartial", cancellable = true, remap = false)
    private static void beforeSetupModelViewPartial(final Matrix4f matrix, final Matrix4f modelMatrix,
        final AbstractContraptionEntity entity, final double camX, final double camY, final double camZ, final float pt,
        final CallbackInfo ci) {

        if (VSGameUtilsKt.getShipManaging(entity) instanceof final ClientShip ship) {
            VSClientGameUtils.transformRenderWithShip(ship.getRenderTransform(),
                matrix,
                Mth.lerp(pt, entity.xOld, entity.getX()),
                Mth.lerp(pt, entity.yOld, entity.getY()),
                Mth.lerp(pt, entity.zOld, entity.getZ()),
                camX,
                camY,
                camZ
            );

            matrix.mul(modelMatrix);
            ci.cancel();
        }
    }

    @Redirect(
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;move(DDD)Lnet/minecraft/world/phys/AABB;"
        ),
        method = "beginFrame"
    )
    private AABB transformLightboxToWorld(final AABB aabb, final double negCamX, final double negCamY,
        final double negCamZ) {
        return VSGameUtilsKt.transformAabbToWorld(((ContraptionRenderInfo) (Object) this).getContraption().entity.level(), aabb).move(negCamX, negCamY, negCamZ);
    }
}

