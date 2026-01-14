package org.valkyrienskies.mod.mixin.mod_compat.hypertube;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Pseudo
@Mixin(targets = "com/pedrorok/hypertube/client/BezierTextureRenderer")
public class MixinBezierTextureRenderer {
    // Many rendering calculations here happen in floats, sometimes with narrowing casts.
    // We mitigate that by offsetting the first curve node to zero. Pose is adjusted as well.
    @Unique
    private Vec3 vs$coordOffset;

    @Inject(method = "renderBezierConnection", at = @At(value = "INVOKE", target = "Lcom/pedrorok/hypertube/client/BezierTextureRenderer;calculateAndCacheGeometry(Ljava/util/List;)Ljava/util/List;"))
    private void pushTransform(BlockPos blockPosInitial, @Coerce Object connection, PoseStack ms,
        MultiBufferSource bufferSource, int packedLight, int packedOverlay, CallbackInfo ci,
        @Local Level level,
        @Local Vec3 pos, @Local(name = "pose") LocalRef<Matrix4f> poseStackLocalRef
    ) {
        if (level != null && VSGameUtilsKt.isBlockInShipyard(level, pos)) {
            ms.popPose();
            ms.pushPose();
            vs$coordOffset = pos;
            poseStackLocalRef.set(ms.last().pose());
        } else vs$coordOffset = null;
    }

    @Inject(method = "calculateAndCacheGeometry", at = @At(value = "NEW", target = "com/pedrorok/hypertube/client/TubeRing"))
    private void offsetCenters(List<Vec3> points, CallbackInfoReturnable cir,
        @Local(name = "currentPoint") LocalRef<Vec3> currentPoint) {
        if (vs$coordOffset != null) currentPoint.set(currentPoint.get().subtract(vs$coordOffset));
    }
}
