package org.valkyrienskies.mod.mixin.mod_compat.sodium;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkFogMode;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

import org.valkyrienskies.mod.compat.LoadedMods;
import org.valkyrienskies.mod.compat.iris.IrisCompat;
import org.valkyrienskies.mod.compat.sodium.SodiumCompat;

@Mixin(DefaultChunkRenderer.class)
public abstract class MixinDefaultChunkRenderer {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/ShaderChunkRenderer;begin(Lme/jellysquid/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V"), remap = false)
    private void redirectBegin(ShaderChunkRenderer instance, TerrainRenderPass renderPass, ChunkRenderMatrices matrices) {
        Matrix4f transform = SodiumCompat.popTransform();
        ShaderChunkRendererAccessor accessor = (ShaderChunkRendererAccessor) instance;

        boolean irisActive = LoadedMods.getIris() && IrisCompat.isIrisShaderActive();
        boolean wantShip = SodiumCompat.isRenderingShip() && !irisActive && SodiumCompat.anyShipShaderFeatureEnabled();
        boolean wantWorld = !SodiumCompat.isRenderingShip() && !irisActive && SodiumCompat.shouldUseWorldFromShipShader();

        if (wantShip) {
            ChunkShaderOptions options = new ChunkShaderOptions(ChunkFogMode.SMOOTH, renderPass, accessor.getVertexType());
            accessor.setActiveProgram(SodiumCompat.getOrCreateShipProgram(options));

            if (SodiumCompat.needsShipProgramBind(renderPass)) {
                renderPass.startDrawing();
                accessor.getActiveProgram().bind();
                SodiumCompat.recordShipProgramBound(renderPass);
            }

            // Per-ship uniforms — never elidable, these differ every ship.
            SodiumCompat.setupShipShaderState(accessor.getActiveProgram(), matrices, transform);

            if (SodiumCompat.needsListRebind()) {
                SodiumCompat.getShipEmitterList().bind(SodiumCompat.SHIP_EMITTER_LIST_TEXTURE_UNIT);
                SodiumCompat.getWorldFromShipStorage().bind(
                    SodiumCompat.WORLD_FROM_SHIP_SECTIONS_TEXTURE_UNIT,
                    SodiumCompat.WORLD_FROM_SHIP_LUT_TEXTURE_UNIT);
                SodiumCompat.getShipOccluderList().bind(SodiumCompat.SHIP_OCCLUDER_LIST_TEXTURE_UNIT);
                SodiumCompat.recordListsBound();
            }
            // World-section storage so ws_shipAo can scan world voxels in
            // the 3×3×3 around a world fragment. Bound to texture units
            // past the ValkyrienAir mask range (2..11) — see comment on
            // LIGHT_SECTIONS_TEXTURE_UNIT in SodiumCompat for the reason.
            SodiumCompat.getLightStorage().bind(
                    SodiumCompat.LIGHT_SECTIONS_TEXTURE_UNIT,
                    SodiumCompat.LIGHT_LUT_TEXTURE_UNIT);
            return;
        }

        if (wantWorld) {
            ChunkShaderOptions options = new ChunkShaderOptions(ChunkFogMode.SMOOTH, renderPass, accessor.getVertexType());
            accessor.setActiveProgram(SodiumCompat.getOrCreateWorldProgram(options));

            if (SodiumCompat.needsWorldProgramBind(renderPass)) {
                renderPass.startDrawing();
                accessor.getActiveProgram().bind();
                SodiumCompat.recordWorldProgramBound(renderPass);
            }

            SodiumCompat.setupWorldShaderState(accessor.getActiveProgram(), matrices);

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
        ((ShaderChunkRendererAccessor) this).invokeBegin(renderPass);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lme/jellysquid/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V"), remap = false)
    private void redirectEnd(ShaderChunkRenderer instance, TerrainRenderPass renderPass) {
        if (!SodiumCompat.isLastShipInBatch()) {
            // Another ship's render() call is coming right after this one in
            // the same pass — skip tearing down shader state we're about to
            // need again immediately. Mirrors the begin() elision: together
            // these collapse a per-ship begin/end pair into one real
            // begin/end for the whole batch.
            return;
        }
        ((ShaderChunkRendererAccessor) this).invokeEnd(renderPass);
    }
}
