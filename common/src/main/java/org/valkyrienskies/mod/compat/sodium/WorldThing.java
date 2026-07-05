package org.valkyrienskies.mod.compat.sodium;

import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;

import org.valkyrienskies.mod.compat.sodium.light.GlUniformInt3v;

/**
 * ChunkShaderInterface for the VS-modified world chunk shader. Rendered for
 * non-ship chunks when {@code dynamicShipToWorldLighting} is enabled and the
 * VsWorldFromShipLightStorage has tracked sections, so ships above the world
 * cast shadows and ship-internal emitters illuminate world blocks beneath them.
 */
public class WorldThing extends ChunkShaderInterface {
    private final GlUniformInt3v uniformRenderOrigin;
    private final GlUniformFloat3v uniformCameraFrac;
    private final GlUniformInt uniformShipEmitters;
    private final GlUniformInt uniformShipEmitterCount;
    private final GlUniformInt uniformShipOccluders;
    private final GlUniformInt uniformShipOccluderCount;
    private final GlUniformInt uniformSeamRuns;
    private final GlUniformInt uniformSeamRunCount;
    private final GlUniformInt uniformSeamShipDir;
    private final GlUniformFloat4v uniformSeamBounds;
    private final GlUniformInt uniformSeamGrid;
    private final GlUniformFloat3v uniformSeamGridOrigin;
    private final GlUniformFloat3v uniformSeamGridInvCell;

    public WorldThing(ShaderBindingContext context, ChunkShaderOptions options) {
        super(context, options);
        this.uniformRenderOrigin = context.bindUniform("u_VsRenderOrigin", GlUniformInt3v::new);
        this.uniformCameraFrac = context.bindUniform("u_VsCameraFrac", GlUniformFloat3v::new);
        this.uniformShipEmitters = context.bindUniform("u_VsShipEmitters", GlUniformInt::new);
        this.uniformShipEmitterCount = context.bindUniform("u_VsShipEmitterCount", GlUniformInt::new);
        this.uniformShipOccluders = context.bindUniform("u_VsShipOccluders", GlUniformInt::new);
        this.uniformShipOccluderCount = context.bindUniform("u_VsShipOccluderCount", GlUniformInt::new);
        this.uniformSeamRuns = context.bindUniform("u_VsSeamRuns", GlUniformInt::new);
        this.uniformSeamRunCount = context.bindUniform("u_VsSeamRunCount", GlUniformInt::new);
        this.uniformSeamShipDir = context.bindUniform("u_VsSeamShipDir", GlUniformInt::new);
        this.uniformSeamBounds = context.bindUniform("u_VsSeamBounds", GlUniformFloat4v::new);
        this.uniformSeamGrid = context.bindUniform("u_VsSeamGrid", GlUniformInt::new);
        this.uniformSeamGridOrigin = context.bindUniform("u_VsSeamGridOrigin", GlUniformFloat3v::new);
        this.uniformSeamGridInvCell = context.bindUniform("u_VsSeamGridInvCell", GlUniformFloat3v::new);
    }

    public void setRenderOrigin(int x, int y, int z) {
        this.uniformRenderOrigin.set(x, y, z);
    }

    public void setCameraFrac(float fx, float fy, float fz) {
        this.uniformCameraFrac.set(fx, fy, fz);
    }

    public void setShipEmitters(int textureUnit, int count) {
        this.uniformShipEmitters.setInt(textureUnit);
        this.uniformShipEmitterCount.setInt(count);
    }

    public void setShipOccluders(int textureUnit, int count) {
        this.uniformShipOccluders.setInt(textureUnit);
        this.uniformShipOccluderCount.setInt(count);
    }

    public void setSeamData(int runsTextureUnit, int runCount, int shipDirTextureUnit,
            float boundsCx, float boundsCy, float boundsCz, float boundsRadius) {
        this.uniformSeamRuns.setInt(runsTextureUnit);
        this.uniformSeamRunCount.setInt(runCount);
        this.uniformSeamShipDir.setInt(shipDirTextureUnit);
        this.uniformSeamBounds.set(new float[] {boundsCx, boundsCy, boundsCz, boundsRadius});
    }

    public void setSeamGrid(int gridTextureUnit, float ox, float oy, float oz,
            float icx, float icy, float icz) {
        this.uniformSeamGrid.setInt(gridTextureUnit);
        this.uniformSeamGridOrigin.set(ox, oy, oz);
        this.uniformSeamGridInvCell.set(icx, icy, icz);
    }
}
