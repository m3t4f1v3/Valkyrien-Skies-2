package org.valkyrienskies.mod.mixin.feature.air_pockets.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.air_pockets.client.AirPocketRenderHooks;
import org.valkyrienskies.mod.air_pockets.client.ShipInteriorFogRenderer;
import org.valkyrienskies.mod.air_pockets.client.ShipPocketWorldWaterOccluder;
import org.valkyrienskies.mod.air_pockets.client.ShipWaterPocketLiquidOverlay;
import org.valkyrienskies.mod.common.config.VSGameConfig;

@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class MixinLevelRenderer {

    @Shadow
    private @Nullable ClientLevel level;

    @Shadow
    protected abstract void renderChunkLayer(RenderType arg, PoseStack arg2, double d, double e, double f,
        Matrix4f matrix4f);

    // render RIGHT before where water is rendered, tripwire chosen bc its last
    @Inject(
        method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
        at = @At("HEAD"),
        require = 1
    )
    private void valkyrienair$renderWorldWaterOccluder(final RenderType renderType, final PoseStack poseStack,
        final double camX, final double camY, final double camZ, final Matrix4f projectionMatrix,
        final CallbackInfo ci) {
        if (!VSGameConfig.CLIENT.getUnderwater().getEnableWaterCulling()) return;
        if (renderType != RenderType.tripwire()) return;
        if (this.level == null) return;
        final Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (camera == null) return;
        final Vec3 camPos = camera.getPosition();
        ShipPocketWorldWaterOccluder.render(camPos.x, camPos.y, camPos.z, projectionMatrix, poseStack);
        renderChunkLayer(AirPocketRenderHooks.AIR_CULL_RENDER_TYPE, poseStack, camX, camY, camZ, projectionMatrix);
    }

    @Inject(
        method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
        at = @At("TAIL"),
        require = 0
    )
    private void valkyrienair$renderLiquidOverlay(final RenderType renderType, final PoseStack poseStack,
        final double camX, final double camY, final double camZ, final Matrix4f projectionMatrix,
        final CallbackInfo ci) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (this.level == null || camera == null) return;
        if (renderType != RenderType.translucent()) return;

        final var camPos = camera.getPosition();
        final Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        final VertexSorting oldVertexSorting = RenderSystem.getVertexSorting();

        final PoseStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushPose();
        modelViewStack.setIdentity();
        modelViewStack.mulPoseMatrix(poseStack.last().pose());

        RenderSystem.setProjectionMatrix(projectionMatrix, VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.applyModelViewMatrix();
        try {
            if (VSGameConfig.CLIENT.getUnderwater().getEnableFluidOverlay() && !(VSGameConfig.CLIENT.getUnderwater().getEnableCustomFluidFog() && VSGameConfig.CLIENT.getUnderwater().getFadeFluidOverlayInCustomFog() && ShipInteriorFogRenderer.shouldSuppressLiquidOverlay(camera))) {
                ShipWaterPocketLiquidOverlay.render(camPos.x, camPos.y, camPos.z);
            }
        } finally {
            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProjection, oldVertexSorting);
        }
    }

    @Inject(
        method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
        at = @At("TAIL"),
        require = 0
    )
    private void valkyrienair$renderFogOverlay(final RenderType renderType, final PoseStack poseStack,
        final double camX, final double camY, final double camZ, final Matrix4f projectionMatrix,
        final CallbackInfo ci) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (this.level == null || camera == null) return;
        if (renderType != RenderType.tripwire()) return;

        final var camPos = camera.getPosition();
        final Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        final VertexSorting oldVertexSorting = RenderSystem.getVertexSorting();

        final PoseStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushPose();
        modelViewStack.setIdentity();
        modelViewStack.mulPoseMatrix(poseStack.last().pose());

        RenderSystem.setProjectionMatrix(projectionMatrix, VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.applyModelViewMatrix();
        try {
            if (VSGameConfig.CLIENT.getUnderwater().getEnableCustomFluidFog()) {
                ShipInteriorFogRenderer.render(camera, projectionMatrix, poseStack.last().pose());
            }
        } finally {
            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProjection, oldVertexSorting);
        }
    }
}
