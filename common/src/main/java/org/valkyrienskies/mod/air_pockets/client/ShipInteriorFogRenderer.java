package org.valkyrienskies.mod.air_pockets.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.air_pockets.ShipWaterPocketManager;
import org.valkyrienskies.mod.common.config.VSGameConfig;

public final class ShipInteriorFogRenderer {

    private static final ResourceLocation INTERIOR_MASK_SHADER_ID =
        new ResourceLocation(ValkyrienSkiesMod.MOD_ID, "ship_interior_mask");
    private static final ResourceLocation EXTERIOR_FOG_SHADER_ID =
        new ResourceLocation(ValkyrienSkiesMod.MOD_ID, "ship_exterior_fog");

    private static TextureTarget interiorMaskTarget;
    private static TextureTarget fogTarget;

    private static ShaderInstance interiorMaskShader;
    private static ShaderInstance fogShader;

    private static final BlockPos.MutableBlockPos TMP_BLOCK_POS = new BlockPos.MutableBlockPos();

    private ShipInteriorFogRenderer() {
    }

    public static void render(final Camera camera, final Matrix4f projectionMatrix, final Matrix4f modelViewMatrix) {
        if (!VSGameConfig.COMMON.getEnableAirPockets()) return;

        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || camera == null) return;
        if (camera.getFluidInCamera() != FogType.NONE) return;
        if (!ShipWaterPocketManager.isWorldPosInShipAirPocket(
            mc.level, camera.getPosition().x, camera.getPosition().y, camera.getPosition().z)) {
            return;
        }

        final FluidState exteriorFluid = ShipWaterPocketManager.overrideWaterFluidState(
            mc.level,
            camera.getPosition().x,
            camera.getPosition().y,
            camera.getPosition().z,
            Fluids.EMPTY.defaultFluidState()
        );
        if (exteriorFluid.isEmpty() || !exteriorFluid.is(Fluids.WATER)) {
            return;
        }

        final RenderTarget mainTarget = mc.getMainRenderTarget();
        ensureTargets(mainTarget.width, mainTarget.height);
        ensureShaders(mc);
        if (interiorMaskTarget == null || fogTarget == null || interiorMaskShader == null || fogShader == null) return;

        final int boundShips = renderInteriorMask(mc, camera, projectionMatrix, modelViewMatrix, mainTarget);
        if (boundShips <= 0) return;

        renderFog(mainTarget, camera);
        compositeFogToMain(mainTarget);
    }

    public static void clear() {
        if (interiorMaskShader != null) {
            interiorMaskShader.close();
            interiorMaskShader = null;
        }
        if (fogShader != null) {
            fogShader.close();
            fogShader = null;
        }
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

    private static void ensureShaders(final Minecraft mc) {
        if (interiorMaskShader == null) {
            interiorMaskShader = loadShader(mc, INTERIOR_MASK_SHADER_ID);
        }
        if (fogShader == null) {
            fogShader = loadShader(mc, EXTERIOR_FOG_SHADER_ID);
        }
    }

    private static ShaderInstance loadShader(final Minecraft mc, final ResourceLocation id) {
        try {
            return new ShaderInstance(mc.getResourceManager(), id.toString(), DefaultVertexFormat.POSITION);
        } catch (final Exception ex) {
            return null;
        }
    }

    private static int renderInteriorMask(final Minecraft mc, final Camera camera, final Matrix4f projectionMatrix,
        final Matrix4f modelViewMatrix, final RenderTarget mainTarget) {
        interiorMaskTarget.copyDepthFrom(mainTarget);
        interiorMaskTarget.bindWrite(true);
        interiorMaskTarget.clear(Minecraft.ON_OSX);

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.colorMask(true, true, true, true);

        final Matrix4f inverseProjection = new Matrix4f(projectionMatrix).invert();
        final Matrix4f inverseView = new Matrix4f(modelViewMatrix).invert();

        final int boundShips = ShipWaterPocketExternalWaterCull.bindInteriorVolumeSamplersAndUniforms(
            interiorMaskShader,
            mc.level,
            camera.getPosition().x,
            camera.getPosition().y,
            camera.getPosition().z
        );
        if (boundShips <= 0) {
            mainTarget.bindWrite(true);
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            return 0;
        }

        setMat4(interiorMaskShader, "InverseProjMat", inverseProjection);
        setMat4(interiorMaskShader, "InverseViewMat", inverseView);
        setVec2(interiorMaskShader, "ScreenSize", mainTarget.width, mainTarget.height);
        interiorMaskShader.setSampler("SceneDepthSampler", mainTarget.getDepthTextureId());

        interiorMaskShader.apply();
        drawFullscreenQuad();
        interiorMaskShader.clear();

        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        mainTarget.bindWrite(true);
        return boundShips;
    }

    private static void renderFog(final RenderTarget mainTarget, final Camera camera) {
        fogTarget.bindWrite(true);
        fogTarget.clear(Minecraft.ON_OSX);

        final int waterColor = sampleWaterFogColor(camera);
        setVec2(fogShader, "ScreenSize", mainTarget.width, mainTarget.height);
        setVec3(fogShader, "FogColor",
            ((waterColor >> 16) & 0xFF) / 255.0f,
            ((waterColor >> 8) & 0xFF) / 255.0f,
            (waterColor & 0xFF) / 255.0f
        );
        final float fogDensity = 0.045f;
        final float fogStart = 2.0f;
        setVec2(fogShader, "FogParams", fogDensity, fogStart);

        fogShader.setSampler("SceneColorSampler", mainTarget.getColorTextureId());
        fogShader.setSampler("SceneDepthSampler", mainTarget.getDepthTextureId());
        fogShader.setSampler("InteriorMaskSampler", interiorMaskTarget.getColorTextureId());

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        fogShader.apply();
        drawFullscreenQuad();
        fogShader.clear();
        RenderSystem.depthMask(true);
    }

    private static void compositeFogToMain(final RenderTarget mainTarget) {
        mainTarget.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, fogTarget.getColorTextureId());

        final BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.vertex(-1.0, -1.0, 0.0).uv(0.0f, 0.0f).endVertex();
        bufferBuilder.vertex(1.0, -1.0, 0.0).uv(1.0f, 0.0f).endVertex();
        bufferBuilder.vertex(1.0, 1.0, 0.0).uv(1.0f, 1.0f).endVertex();
        bufferBuilder.vertex(-1.0, 1.0, 0.0).uv(0.0f, 1.0f).endVertex();
        BufferUploader.drawWithShader(bufferBuilder.end());

        RenderSystem.depthMask(true);
    }

    private static int sampleWaterFogColor(final Camera camera) {
        final Minecraft mc = Minecraft.getInstance();
        TMP_BLOCK_POS.set(camera.getBlockPosition());
        return BiomeColors.getAverageWaterColor(mc.level, TMP_BLOCK_POS);
    }

    private static void drawFullscreenQuad() {
        final BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        bufferBuilder.vertex(-1.0, -1.0, 0.0).endVertex();
        bufferBuilder.vertex(1.0, -1.0, 0.0).endVertex();
        bufferBuilder.vertex(1.0, 1.0, 0.0).endVertex();
        bufferBuilder.vertex(-1.0, 1.0, 0.0).endVertex();
        BufferUploader.drawWithShader(bufferBuilder.end());
    }

    private static void setMat4(final ShaderInstance shader, final String uniformName, final Matrix4f value) {
        if (shader.getUniform(uniformName) != null) {
            shader.getUniform(uniformName).set(value);
            shader.getUniform(uniformName).upload();
        }
    }

    private static void setVec2(final ShaderInstance shader, final String uniformName, final float x, final float y) {
        if (shader.getUniform(uniformName) != null) {
            shader.getUniform(uniformName).set(x, y);
            shader.getUniform(uniformName).upload();
        }
    }

    private static void setVec3(final ShaderInstance shader, final String uniformName, final float x, final float y,
        final float z) {
        if (shader.getUniform(uniformName) != null) {
            shader.getUniform(uniformName).set(x, y, z);
            shader.getUniform(uniformName).upload();
        }
    }
}
