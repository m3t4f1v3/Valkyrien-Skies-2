package org.valkyrienskies.mod.compat.sodium;

import org.joml.Matrix4fc;

import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.valkyrienskies.mod.compat.sodium.light.GlUniformInt3v;

public class ShipThing extends ChunkShaderInterface {
    private final GlUniformMatrix4f uniformTransformMatrix;
    private final GlUniformMatrix4f uniformLocalToCameraRel;
    private final GlUniformInt3v uniformRenderOrigin;
    private final GlUniformInt uniformLightSections;
    private final GlUniformInt uniformLightLut;

    public ShipThing(ShaderBindingContext context, ChunkShaderOptions options) {
        super(context, options);
        this.uniformTransformMatrix = context.bindUniform("u_TransformMatrix", GlUniformMatrix4f::new);
        this.uniformLocalToCameraRel = context.bindUniform("u_LocalToCameraRel", GlUniformMatrix4f::new);
        this.uniformRenderOrigin = context.bindUniform("u_VsRenderOrigin", GlUniformInt3v::new);
        this.uniformLightSections = context.bindUniform("u_VsLightSections", GlUniformInt::new);
        this.uniformLightLut = context.bindUniform("u_VsLightLut", GlUniformInt::new);
    }

    public void setTransformMatrix(Matrix4fc matrix) {
        this.uniformTransformMatrix.set(matrix);
    }

    public void setLocalToWorldMatrix(Matrix4fc matrix) {
        this.uniformLocalToCameraRel.set(matrix);
    }

    public void setRenderOrigin(int x, int y, int z) {
        this.uniformRenderOrigin.set(x, y, z);
    }

    public void setLightSectionsSampler(int unit) {
        this.uniformLightSections.setInt(unit);
    }

    public void setLightLutSampler(int unit) {
        this.uniformLightLut.setInt(unit);
    }
}
