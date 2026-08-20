package org.valkyrienskies.mod.mixin.mod_compat.sodium09;

import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferSlice;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlTexelBuffer;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ShaderChunkRenderer.class, remap = false)
public interface ShaderChunkRendererAccessor {
    @Accessor("vertexType")
    ChunkVertexType getVertexType();

    @Accessor("activeProgram")
    GlProgram<ChunkShaderInterface> getActiveProgram();

    @Accessor("activeProgram")
    void setActiveProgram(GlProgram<ChunkShaderInterface> program);

    @Invoker("begin")
    void invokeBegin(TerrainRenderPass pass, FogParameters parameters, GlBufferSlice uniformData,
        GlTexelBuffer sectionTimeInfo);

    @Invoker("end")
    void invokeEnd(TerrainRenderPass pass);
}
