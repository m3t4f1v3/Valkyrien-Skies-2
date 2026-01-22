package org.valkyrienskies.mod.mixin.mod_compat.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkFogMode;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

import org.valkyrienskies.mod.compat.sodium.SodiumCompat;
import org.valkyrienskies.mod.mixin.mod_compat.sodium.ShaderChunkRendererAccessor;

@Mixin(DefaultChunkRenderer.class)
public abstract class MixinDefaultChunkRenderer {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/ShaderChunkRenderer;begin(Lme/jellysquid/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V"), remap = false)
    private void redirectBegin(ShaderChunkRenderer instance, TerrainRenderPass renderPass, ChunkRenderMatrices matrices) {
        if (SodiumCompat.isRenderingShip()) {
            renderPass.startDrawing();
            ChunkShaderOptions options = new ChunkShaderOptions(ChunkFogMode.SMOOTH, renderPass, ((ShaderChunkRendererAccessor) instance).getVertexType());
            ((ShaderChunkRendererAccessor) instance).setActiveProgram(SodiumCompat.getOrCreateShipProgram(options));
            ((ShaderChunkRendererAccessor) instance).getActiveProgram().bind();
            SodiumCompat.setupShipShaderState(((ShaderChunkRendererAccessor) instance).getActiveProgram(), matrices, SodiumCompat.popRotation());
        } else {
            ((ShaderChunkRendererAccessor) (Object) this).invokeBegin(renderPass);
            return;
        }
    }
}
