package org.valkyrienskies.mod.forge.mixin.compat.connectiblechains.client;

import com.lilypuree.connectiblechains.chain.ChainLink;
import com.lilypuree.connectiblechains.client.render.entity.ChainKnotEntityRenderer;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

@Mixin(value = ChainKnotEntityRenderer.class, remap = false)
public abstract class MixinChainKnotEntityRenderer {
    @Inject(
        method = "renderChainLink",
        at = @At(
            value = "NEW",
            target = "org/joml/Vector3f" // After all the offset calculations, just before the mod starts doing math on these positions
        ))
    private void adjustPositions(ChainLink link, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumerProvider, CallbackInfo ci,
        @Local(ordinal = 3) LocalRef<Vec3> srcPos, @Local(ordinal = 4) LocalRef<Vec3> destPos) {
        ClientShip srcShip = (ClientShip)VSGameUtilsKt.getShipManaging(link.primary);
        ClientShip destShip = (ClientShip)VSGameUtilsKt.getShipManaging(link.secondary);
        if (srcShip != destShip) {
            if (srcShip != null) {
                srcPos.set(VectorConversionsMCKt.toMinecraft(srcShip.getRenderTransform().getShipToWorld()
                    .transformPosition(VectorConversionsMCKt.toJOML(srcPos.get()))));
                // Negate the rotation that will happen when the shipyard entity is rendered.
                matrices.mulPose(new Quaternionf(srcShip.getRenderTransform().getShipToWorldRotation()).invert());
            }
            if (destShip != null) {
                destPos.set(VectorConversionsMCKt.toMinecraft(destShip.getRenderTransform().getShipToWorld()
                    .transformPosition(VectorConversionsMCKt.toJOML(destPos.get()))));
            }
        }
    }

    /**
     * In the Forge port a chain connecting two blocks is cached so to skip recalculating it every tick, as blocks don't move.
     * This is not applicable for inter-ship connections where blocks do indeed move.
     * Whether the chain is connected to a block is determined by checking if the other entity is also a chain knot.
     * Hijacking INSTANCEOF just to add a second condition to an if statement is peak mixin, isn't it?
     */
    @ModifyConstant(
        method = "renderChainLink",
        constant = @Constant(classValue = HangingEntity.class)
    )
    // According to Minecraft Development plugin for Idea, this is wrong. The signature it suggests is actually incorrect.
    // I have no idea what the leading Object is for, but the expected signature has it listed;
    private Class<?> disableBakingForShips(Object object, Class<?> constant, @Local(argsOnly = true) ChainLink link) {
        ClientShip srcShip = (ClientShip)VSGameUtilsKt.getShipManaging(link.primary);
        ClientShip destShip = (ClientShip)VSGameUtilsKt.getShipManaging(link.secondary);
        if (srcShip != null || destShip != null) {
            return Class.class; // or really any class that our entity is not instanceof
        } else {
            return constant;
        }
    }
}
