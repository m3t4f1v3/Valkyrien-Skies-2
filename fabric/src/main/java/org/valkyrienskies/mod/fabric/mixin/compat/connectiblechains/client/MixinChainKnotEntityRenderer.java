package org.valkyrienskies.mod.fabric.mixin.compat.connectiblechains.client;

import com.github.legoatoom.connectiblechains.client.render.entity.ChainKnotEntityRenderer;
import com.github.legoatoom.connectiblechains.client.render.entity.state.ChainKnotEntityRenderState;
import com.github.legoatoom.connectiblechains.client.render.entity.state.ChainKnotEntityRenderState.ChainData;
import com.github.legoatoom.connectiblechains.entity.ChainKnotEntity;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

@Mixin(value = ChainKnotEntityRenderer.class, remap = false)
public abstract class MixinChainKnotEntityRenderer {
    @Unique
    ClientLevel valkyrienskies$level;

    @Inject(
        method = "render(Lcom/github/legoatoom/connectiblechains/entity/ChainKnotEntity;Lcom/github/legoatoom/connectiblechains/client/render/entity/state/ChainKnotEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
        at = @At("HEAD")
    )
    private void saveEntity(ChainKnotEntity entity, ChainKnotEntityRenderState state, PoseStack matrices,
        MultiBufferSource vertexConsumers, int light, float tickDelta, CallbackInfo ci) {
        // One thing the Forge port does right, it exposes entities to `renderChainLink` via its ChainLink class.
        // While nice to have, knot/holder entities are not essential to our mixin but access to a Level is.
        // We store it as a mixin field on the assumption there's only one level the renderer is handling at the same
        // time. The assumption is fairly reasonable unless Immersive Portals reuses entity renderers for different levels.
        this.valkyrienskies$level = (ClientLevel)entity.level();
    }

    @Inject(
        method = "renderChainLink",
        at = @At(
            value = "NEW",
            target = "org/joml/Vector3f" // After all the offset calculations, just before the mod starts doing math on these positions
        ))
    private void adjustPositions(PoseStack matrices, MultiBufferSource vertexConsumerProvider, ChainData chainData, CallbackInfo ci,
        @Local(ordinal = 1) LocalRef<Vec3> srcPos, @Local(ordinal = 2) LocalRef<Vec3> destPos) {
        if (valkyrienskies$level == null) return;

        // We don't have access to entities acting as chain ends so instead of `getShipManaging` we have to rely on
        // determining ships from level and coordinates. It is possible to do better with a mixin to `render`, the
        // caller of this function.
        ClientShip srcShip = VSGameUtilsKt.getShipObjectManagingPos(valkyrienskies$level, srcPos.get());
        ClientShip destShip = VSGameUtilsKt.getShipObjectManagingPos(valkyrienskies$level, destPos.get());
        if (srcShip != destShip) {
            if (srcShip != null) {
                srcPos.set(VectorConversionsMCKt.toMinecraft(srcShip.getRenderTransform().getShipToWorld()
                    .transformPosition(VectorConversionsMCKt.toJOML(srcPos.get()))));
                // As the knot is a shipyard entity it is rendered with a transform matching the one of this ship.
                // This is undesirable as both source and destination positions are in world coordinates, so we negate
                // the transform.
                matrices.mulPose(new Quaternionf(srcShip.getRenderTransform().getShipToWorldRotation()).invert());
            }
            if (destShip != null) {
                destPos.set(VectorConversionsMCKt.toMinecraft(destShip.getRenderTransform().getShipToWorld()
                    .transformPosition(VectorConversionsMCKt.toJOML(destPos.get()))));
                // No complex transforms involved as the knot we're rendering is positioned in worldspace.
            }
        }
    }
}
