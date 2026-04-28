package org.valkyrienskies.mod.compat.sodium;

import org.joml.Matrix4fc;

import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;

public class ShipThing extends ChunkShaderInterface {
    private final GlUniformMatrix4f uniformTransformMatrix;

    // private final GlUniformMatrix4f uniformNormalMatrix;
    public ShipThing(ShaderBindingContext context, ChunkShaderOptions options) {
        super(context, options);
        this.uniformTransformMatrix = context.bindUniform("u_TransformMatrix", GlUniformMatrix4f::new);
        // this.uniformNormalMatrix = context.bindUniform("u_NormalMatrix",
        // GlUniformMatrix4f::new);
    }

    public void setTransformMatrix(Matrix4fc matrix) {
        this.uniformTransformMatrix.set(matrix);
    }

    // public void setNormalMatrix(Matrix4fc matrix) {
    // this.uniformNormalMatrix.set(matrix);
    // }
}
