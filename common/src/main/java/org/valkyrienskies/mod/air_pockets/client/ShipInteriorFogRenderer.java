package org.valkyrienskies.mod.air_pockets.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FogType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.air_pockets.ShipWaterPocketManager;
import org.valkyrienskies.mod.common.config.VSGameConfig;

public final class ShipInteriorFogRenderer {
    private static final Logger LOGGER = LogManager.getLogger("ValkyrienAir ShipInteriorFog");
    private static final long DIAG_LOG_INTERVAL_MS = 3000L;

    private static final String INTERIOR_MASK_PASS_NAME = ValkyrienSkiesMod.MOD_ID + ":ship_interior_mask";
    private static final String EXTERIOR_FOG_PASS_NAME = ValkyrienSkiesMod.MOD_ID + ":ship_exterior_fog";
    private static final String FOG_COMPOSITE_PASS_NAME = ValkyrienSkiesMod.MOD_ID + ":ship_fog_composite";

    private static TextureTarget interiorMaskTarget;
    private static TextureTarget fogTarget;

    private static PostPass interiorMaskPass;
    private static PostPass fogPass;
    private static PostPass fogCompositePass;

    private static final BlockPos.MutableBlockPos TMP_BLOCK_POS = new BlockPos.MutableBlockPos();
    private static long lastDiagLogAtMs = 0L;

    private ShipInteriorFogRenderer() {
    }

    public static void render(final Camera camera, final Matrix4f projectionMatrix, final Matrix4f modelViewMatrix) {
        if (!VSGameConfig.COMMON.getEnableAirPockets()) {
            logDiag("Skipped interior fog render: air pockets disabled");
            return;
        }

        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || camera == null) {
            logDiag("Skipped interior fog render: missing level/camera");
            return;
        }
        final FogType cameraFogType = camera.getFluidInCamera();
        if (cameraFogType != FogType.NONE) {
            logDiag("Skipped interior fog render: camera fluid={}", cameraFogType);
            return;
        }

        final boolean inShipAirPocket = ShipWaterPocketManager.isWorldPosInShipAirPocket(
            mc.level, camera.getPosition().x, camera.getPosition().y, camera.getPosition().z
        );
        final boolean inWorldFluidSuppressionZone = ShipWaterPocketManager.isWorldPosInShipWorldFluidSuppressionZone(
            mc.level, camera.getPosition().x, camera.getPosition().y, camera.getPosition().z
        );
        if (!shouldRenderInteriorWaterFog(inShipAirPocket, inWorldFluidSuppressionZone)) {
            logDiag(
                "Skipped interior fog render: inShipAirPocket={} inWorldFluidSuppressionZone={}",
                inShipAirPocket,
                inWorldFluidSuppressionZone
            );
            return;
        }

        final RenderTarget mainTarget = mc.getMainRenderTarget();
        ensureTargets(mainTarget.width, mainTarget.height);
        ensurePasses(mc, mainTarget);
        if (interiorMaskTarget == null || fogTarget == null || interiorMaskPass == null || fogPass == null
            || fogCompositePass == null) {
            logDiag(
                "Skipped interior fog render: targets/passes missing maskTarget={} fogTarget={} maskPass={} fogPass={} compositePass={}",
                interiorMaskTarget != null,
                fogTarget != null,
                interiorMaskPass != null,
                fogPass != null,
                fogCompositePass != null
            );
            return;
        }

        final int boundShips = renderInteriorMask(mc, camera, projectionMatrix, modelViewMatrix, mainTarget);
        if (boundShips <= 0) {
            logDiag("Skipped interior fog render: no bound ships for mask");
            return;
        }

        renderFog(mainTarget, camera, projectionMatrix);
        compositeFogToMain(mainTarget);
        logDiag("Rendered interior fog: boundShips={}", boundShips);
    }

    public static void clear() {
        closePasses();
        if (interiorMaskTarget != null) {
            interiorMaskTarget.destroyBuffers();
            interiorMaskTarget = null;
        }
        if (fogTarget != null) {
            fogTarget.destroyBuffers();
            fogTarget = null;
        }
    }

    private static void ensureTargets(final int width, final int height) {
        if (width <= 0 || height <= 0) return;

        if (interiorMaskTarget == null) {
            interiorMaskTarget = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            interiorMaskTarget.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        } else if (interiorMaskTarget.width != width || interiorMaskTarget.height != height) {
            interiorMaskTarget.resize(width, height, Minecraft.ON_OSX);
            interiorMaskTarget.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        }

        if (fogTarget == null) {
            fogTarget = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            fogTarget.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        } else if (fogTarget.width != width || fogTarget.height != height) {
            fogTarget.resize(width, height, Minecraft.ON_OSX);
            fogTarget.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        }
    }

    private static void ensurePasses(final Minecraft mc, final RenderTarget mainTarget) {
        if (interiorMaskPass != null && fogPass != null && fogCompositePass != null) {
            updatePassMatrices(mainTarget);
            return;
        }

        closePasses();
        interiorMaskPass = createPass(mc, INTERIOR_MASK_PASS_NAME, mainTarget, interiorMaskTarget);
        fogPass = createPass(mc, EXTERIOR_FOG_PASS_NAME, mainTarget, fogTarget);
        fogCompositePass = createPass(mc, FOG_COMPOSITE_PASS_NAME, fogTarget, mainTarget);
        if (interiorMaskPass != null && fogPass != null && fogCompositePass != null) {
            updatePassMatrices(mainTarget);
        }
    }

    private static PostPass createPass(final Minecraft mc, final String programName, final RenderTarget inTarget,
        final RenderTarget outTarget) {
        try {
            return new PostPass(mc.getResourceManager(), programName, inTarget, outTarget);
        } catch (final Exception ex) {
            LOGGER.warn("Failed to load interior fog pass {}", programName, ex);
            return null;
        }
    }

    private static void closePasses() {
        if (interiorMaskPass != null) {
            interiorMaskPass.close();
            interiorMaskPass = null;
        }
        if (fogPass != null) {
            fogPass.close();
            fogPass = null;
        }
        if (fogCompositePass != null) {
            fogCompositePass.close();
            fogCompositePass = null;
        }
    }

    private static void updatePassMatrices(final RenderTarget mainTarget) {
        if (interiorMaskPass != null && interiorMaskTarget != null) {
            interiorMaskPass.setOrthoMatrix(makeOrthoMatrix(interiorMaskTarget.width, interiorMaskTarget.height));
        }
        if (fogPass != null && fogTarget != null) {
            fogPass.setOrthoMatrix(makeOrthoMatrix(fogTarget.width, fogTarget.height));
        }
        if (fogCompositePass != null && mainTarget != null) {
            fogCompositePass.setOrthoMatrix(makeOrthoMatrix(mainTarget.width, mainTarget.height));
        }
    }

    private static Matrix4f makeOrthoMatrix(final int width, final int height) {
        return new Matrix4f().setOrtho(0.0f, (float) width, 0.0f, (float) height, 0.1f, 1000.0f);
    }

    private static void logDiag(final String message, final Object... args) {
        final long now = System.currentTimeMillis();
        if (now - lastDiagLogAtMs < DIAG_LOG_INTERVAL_MS) {
            return;
        }
        lastDiagLogAtMs = now;
        LOGGER.info(message, args);
    }

    private static int renderInteriorMask(final Minecraft mc, final Camera camera, final Matrix4f projectionMatrix,
        final Matrix4f modelViewMatrix, final RenderTarget mainTarget) {
        interiorMaskTarget.copyDepthFrom(mainTarget);
        interiorMaskTarget.bindWrite(true);
        interiorMaskTarget.clear(Minecraft.ON_OSX);

        final Matrix4f inverseProjection = new Matrix4f(projectionMatrix).invert();
        final Matrix4f inverseView = new Matrix4f(modelViewMatrix).invert();
        final EffectInstance effect = interiorMaskPass.getEffect();
        final int boundShips = ShipWaterPocketExternalWaterCull.bindInteriorVolumeSamplersAndUniforms(
            effect,
            mc.level,
            camera.getPosition().x,
            camera.getPosition().y,
            camera.getPosition().z
        );
        if (boundShips <= 0) {
            return 0;
        }

        setMat4(effect, "InverseProjMat", inverseProjection);
        setMat4(effect, "InverseViewMat", inverseView);
        setVec3(effect, "CameraWorldPos",
            (float) camera.getPosition().x,
            (float) camera.getPosition().y,
            (float) camera.getPosition().z
        );
        setVec3(effect, "CameraLookVector", camera.getLookVector().x(), camera.getLookVector().y(), camera.getLookVector().z());
        setVec3(effect, "CameraUpVector", camera.getUpVector().x(), camera.getUpVector().y(), camera.getUpVector().z());
        setVec3(effect, "CameraLeftVector", camera.getLeftVector().x(), camera.getLeftVector().y(), camera.getLeftVector().z());
        effect.setSampler("SceneDepthSampler", mainTarget::getDepthTextureId);
        interiorMaskPass.process(0.0f);
        return boundShips;
    }

    private static void renderFog(final RenderTarget mainTarget, final Camera camera, final Matrix4f projectionMatrix) {
        fogTarget.clear(Minecraft.ON_OSX);

        final EffectInstance effect = fogPass.getEffect();
        final int waterColor = sampleWaterFogColor(camera);
        setVec3(effect, "FogColor",
            ((waterColor >> 16) & 0xFF) / 255.0f,
            ((waterColor >> 8) & 0xFF) / 255.0f,
            (waterColor & 0xFF) / 255.0f
        );
        final float fogDensity = 0.045f;
        final float fogStart = 2.0f;
        setVec2(effect, "FogParams", fogDensity, fogStart);
        setMat4(effect, "InverseProjMat", new Matrix4f(projectionMatrix).invert());
        effect.setSampler("SceneDepthSampler", mainTarget::getDepthTextureId);
        effect.setSampler("InteriorMaskSampler", interiorMaskTarget::getColorTextureId);
        fogPass.process(0.0f);
    }

    private static void compositeFogToMain(final RenderTarget mainTarget) {
        fogCompositePass.process(0.0f);
    }

    private static int sampleWaterFogColor(final Camera camera) {
        final Minecraft mc = Minecraft.getInstance();
        TMP_BLOCK_POS.set(camera.getBlockPosition());
        return BiomeColors.getAverageWaterColor(mc.level, TMP_BLOCK_POS);
    }

    static boolean shouldRenderInteriorWaterFog(final boolean inShipAirPocket, final boolean inWorldFluidSuppressionZone) {
        return inShipAirPocket && inWorldFluidSuppressionZone;
    }

    private static void setMat4(final EffectInstance effect, final String uniformName, final Matrix4f value) {
        final Uniform uniform = effect.getUniform(uniformName);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static void setVec2(final EffectInstance effect, final String uniformName, final float x, final float y) {
        final Uniform uniform = effect.getUniform(uniformName);
        if (uniform != null) {
            uniform.set(x, y);
        }
    }

    private static void setVec3(final EffectInstance effect, final String uniformName, final float x, final float y,
        final float z) {
        final Uniform uniform = effect.getUniform(uniformName);
        if (uniform != null) {
            uniform.set(x, y, z);
        }
    }
}
