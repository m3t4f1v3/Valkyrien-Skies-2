package org.valkyrienskies.mod.mixin.feature.air_pockets.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
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
import org.valkyrienskies.mod.air_pockets.client.ShipInteriorFogRenderer;
import org.valkyrienskies.mod.air_pockets.client.ShipPocketWorldWaterOccluder;
import org.valkyrienskies.mod.air_pockets.client.ShipWaterPocketLiquidOverlay;
import org.valkyrienskies.mod.common.config.VSGameConfig;

@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class MixinLevelRenderer {

    @Shadow
    private @Nullable ClientLevel level;

    /**
     * Right before the world translucent chunk layer (water, glass, ice, ...) draws, write
     * opaque depth (with color writes off) at every pocket-air voxel of every ship. World
     * translucent then z-fails inside pockets and the pixels keep whatever was already in the
     * framebuffer — no shader injection required, so this works for vanilla, Sodium, Embeddium
     * and Iris/Oculus shaderpacks.
     *
     * <p>We hook at the HEAD of {@code renderChunkLayer} itself rather than at a specific
     * {@code INVOKE} call site inside {@code renderLevel}. This is robust against Embeddium /
     * Iris / Oculus reordering or removing call sites in {@code renderLevel}, and we filter
     * by {@link RenderType#translucent()} at runtime so this only fires once per frame at the
     * right moment.</p>
     */
    @Inject(
        method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
        at = @At("HEAD"),
        require = 1
    )
    private void valkyrienair$renderWorldWaterOccluder(final RenderType renderType, final PoseStack poseStack,
        final double camX, final double camY, final double camZ, final Matrix4f projectionMatrix,
        final CallbackInfo ci) {
        if (renderType != RenderType.tripwire()) return;
        if (this.level == null) return;
        final Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (camera == null) return;
        final Vec3 camPos = camera.getPosition();
        ShipPocketWorldWaterOccluder.render(camPos.x, camPos.y, camPos.z, projectionMatrix, poseStack);
    }

    @Inject(
        method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
        at = @At("TAIL"),
        require = 0
    )
    private void valkyrienair$renderLiquidOverlay(final PoseStack poseStack, final float partialTick, final long finishNanoTime,
        final boolean renderBlockOutline, final Camera camera, final GameRenderer gameRenderer, final LightTexture lightTexture,
        final Matrix4f projectionMatrix, final CallbackInfo ci) {
        if (this.level == null || camera == null) return;

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
