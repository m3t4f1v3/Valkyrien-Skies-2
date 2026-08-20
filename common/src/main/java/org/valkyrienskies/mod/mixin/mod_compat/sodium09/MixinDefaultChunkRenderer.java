package org.valkyrienskies.mod.mixin.mod_compat.sodium09;

import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferSlice;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlTexelBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkFogMode;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.valkyrienskies.mod.compat.LoadedMods;
import org.valkyrienskies.mod.compat.iris.IrisCompat;
import org.valkyrienskies.mod.common.render.light.VsDynamicLight;
import org.valkyrienskies.mod.compat.sodium09.SodiumCompat;

/**
 * Swaps sodium's stock chunk program for one of VS's two.
 *
 * <p>Same shape as the 0.5 mixin, but {@code begin} now also carries the fog parameters, the globals
 * uniform slice and the section-time texture, all of which the VS program's {@code setupState} needs
 * exactly as much as the stock one does — so they are forwarded unchanged. The per-ship matrices are not
 * among them: 0.9 reads those from the globals block, which the ship pass rewrites before each draw.
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer {

    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;begin("
                + "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;"
                + "Lnet/caffeinemc/mods/sodium/client/util/FogParameters;"
                + "Lnet/caffeinemc/mods/sodium/client/gl/buffer/GlBufferSlice;"
                + "Lnet/caffeinemc/mods/sodium/client/gl/buffer/GlTexelBuffer;)V"))
    private void vs$redirectBegin(final ShaderChunkRenderer instance, final TerrainRenderPass renderPass,
        final FogParameters parameters, final GlBufferSlice uniformData, final GlTexelBuffer sectionTimeInfo) {
        final Matrix4f transform = SodiumCompat.popTransform();
        final ShaderChunkRendererAccessor accessor = (ShaderChunkRendererAccessor) instance;

        final boolean irisActive = LoadedMods.getIris() && IrisCompat.isIrisShaderActive();
        final boolean wantShip =
            SodiumCompat.isRenderingShip() && !irisActive && SodiumCompat.anyShipShaderFeatureEnabled();
        final boolean wantWorld =
            !SodiumCompat.isRenderingShip() && !irisActive && SodiumCompat.shouldUseWorldFromShipShader();

        if (wantShip) {
            final ChunkShaderOptions options =
                new ChunkShaderOptions(ChunkFogMode.SMOOTH, renderPass, accessor.getVertexType());
            accessor.setActiveProgram(SodiumCompat.getOrCreateShipProgram(options));

            if (SodiumCompat.needsShipProgramBind(renderPass)) {
                renderPass.startDrawing();
                accessor.getActiveProgram().bind();
                SodiumCompat.recordShipProgramBound(renderPass);
            }

            // Stock per-pass state: samplers, the section-time buffer texture and the globals block.
            accessor.getActiveProgram().getInterface()
                .setupState(renderPass, parameters, uniformData, sectionTimeInfo);
            // Per-ship uniforms — never elidable, these differ every ship.
            SodiumCompat.setupShipShaderState(accessor.getActiveProgram(), transform);

            if (SodiumCompat.needsListRebind()) {
                SodiumCompat.getShipEmitterList().bind(SodiumCompat.SHIP_EMITTER_LIST_TEXTURE_UNIT);
                SodiumCompat.getWorldFromShipStorage().bind(
                    SodiumCompat.WORLD_FROM_SHIP_SECTIONS_TEXTURE_UNIT,
                    SodiumCompat.WORLD_FROM_SHIP_LUT_TEXTURE_UNIT);
                SodiumCompat.getShipOccluderList().bind(SodiumCompat.SHIP_OCCLUDER_LIST_TEXTURE_UNIT);
                SodiumCompat.recordListsBound();
            }
            return;
        }

        if (wantWorld) {
            final ChunkShaderOptions options =
                new ChunkShaderOptions(ChunkFogMode.SMOOTH, renderPass, accessor.getVertexType());
            accessor.setActiveProgram(SodiumCompat.getOrCreateWorldProgram(options));

            if (SodiumCompat.needsWorldProgramBind(renderPass)) {
                renderPass.startDrawing();
                accessor.getActiveProgram().bind();
                SodiumCompat.recordWorldProgramBound(renderPass);
            }

            accessor.getActiveProgram().getInterface()
                .setupState(renderPass, parameters, uniformData, sectionTimeInfo);
            SodiumCompat.setupWorldShaderState(accessor.getActiveProgram());

            if (SodiumCompat.needsListRebind()) {
                SodiumCompat.getShipEmitterList().bind(SodiumCompat.SHIP_EMITTER_LIST_TEXTURE_UNIT);
                SodiumCompat.getWorldFromShipStorage().bind(
                    SodiumCompat.WORLD_FROM_SHIP_SECTIONS_TEXTURE_UNIT,
                    SodiumCompat.WORLD_FROM_SHIP_LUT_TEXTURE_UNIT);
                SodiumCompat.getShipOccluderList().bind(SodiumCompat.SHIP_OCCLUDER_LIST_TEXTURE_UNIT);
                SodiumCompat.recordListsBound();
            }
            return;
        }

        SodiumCompat.recordVanillaBound(renderPass);
        ((ShaderChunkRendererAccessor) this).invokeBegin(renderPass, parameters, uniformData, sectionTimeInfo);
    }

    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end("
                + "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V"))
    private void vs$redirectEnd(final ShaderChunkRenderer instance, final TerrainRenderPass renderPass) {
        if (!SodiumCompat.isLastShipInBatch()) {
            // Another ship's render() call is coming right after this one in the same pass — skip tearing
            // down shader state we're about to need again immediately. Mirrors the begin() elision:
            // together these collapse a per-ship begin/end pair into one real begin/end for the batch.
            return;
        }
        ((ShaderChunkRendererAccessor) this).invokeEnd(renderPass);
    }
}
