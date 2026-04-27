package org.valkyrienskies.mod.compat.sodium;

import org.joml.Matrix4fc;

import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;

public class ShipThing extends ChunkShaderInterface {
    private final GlUniformMatrix4f uniformRotationMatrix;

    // private final GlUniformMatrix4f uniformNormalMatrix;
    public ShipThing(ShaderBindingContext context, ChunkShaderOptions options) {
        super(context, options);
        this.uniformRotationMatrix = context.bindUniform("u_RotationMatrix", GlUniformMatrix4f::new);
        // this.uniformNormalMatrix = context.bindUniform("u_NormalMatrix",
        // GlUniformMatrix4f::new);
    }

    public void setRotationMatrix(Matrix4fc matrix) {
        this.uniformRotationMatrix.set(matrix);
    }

    // public void setNormalMatrix(Matrix4fc matrix) {
    // this.uniformNormalMatrix.set(matrix);
    // }
}
