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
        // Only swap in the VS ship shader when something actually needs it:
        //   - we're rendering a ship pass,
        //   - iris isn't running its own shader pack,
        //   - and at least one of the three VS shader features is enabled.
        // With all three off, the mesher mixin's gates fall through to sodium's
        // native byte format, so sodium's stock shader renders ship blocks
        // correctly. Skipping the swap also avoids compiling/binding an
        // effectively-empty program.
        if (SodiumCompat.isRenderingShip()
                && !(LoadedMods.getIris() && IrisCompat.isIrisShaderActive())
                && SodiumCompat.anyShipShaderFeatureEnabled()) {
            renderPass.startDrawing();
            ChunkShaderOptions options = new ChunkShaderOptions(ChunkFogMode.SMOOTH, renderPass, ((ShaderChunkRendererAccessor) instance).getVertexType());
            ((ShaderChunkRendererAccessor) instance).setActiveProgram(SodiumCompat.getOrCreateShipProgram(options));
            ((ShaderChunkRendererAccessor) instance).getActiveProgram().bind();
            SodiumCompat.setupShipShaderState(((ShaderChunkRendererAccessor) instance).getActiveProgram(), matrices, transform);
        } else {
            ((ShaderChunkRendererAccessor) (Object) this).invokeBegin(renderPass);
            return;
        }
    }
}
