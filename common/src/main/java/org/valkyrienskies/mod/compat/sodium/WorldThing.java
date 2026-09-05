package org.valkyrienskies.mod.compat.sodium;

import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;

import org.valkyrienskies.mod.compat.sodium.shader.GlUniformInt3v;

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
    // Compute-flooded ship light. Only bound under VS_FLOOD_GRID: the shader declares these only
    // there, and sodium's bindUniform throws on a name the linked program does not have.
    private final GlUniformInt uniformWorldFromShipSections;
    private final GlUniformInt uniformWorldFromShipLut;
    private final GlUniformInt uniformFloodGridValid;
    private final GlUniformInt uniformSeamRuns;
    private final GlUniformInt uniformSeamRunCount;
    private final GlUniformInt uniformSeamShipDir;
    private final GlUniformFloat4v uniformSeamBounds;
    private final GlUniformInt uniformSeamGrid;
    private final GlUniformInt uniformSeamOcc;
    private final GlUniformInt uniformSeamOccDesc;
    private final GlUniformFloat3v uniformSeamGridOrigin;
    private final GlUniformFloat3v uniformSeamGridInvCell;

    public WorldThing(ShaderBindingContext context, ChunkShaderOptions options, int features) {
        super(context, options);
        this.uniformRenderOrigin = context.bindUniform("u_VsRenderOrigin", GlUniformInt3v::new);
        this.uniformCameraFrac = context.bindUniform("u_VsCameraFrac", GlUniformFloat3v::new);
        // Under VS_FLOOD_GRID the world shader takes ship light entirely from the flood and never
        // reads the emitter list, so GLSL drops it and binding the name would throw. It remains for
        // the no-compute fallback.
        final boolean floodGrid = (features & SodiumCompat.FEATURE_FLOOD_GRID) != 0;
        // Seam AO is compiled out when the config is off, so its uniforms are not in the linked
        // program and binding them by name would throw.
        final boolean shipAo = (features & SodiumCompat.FEATURE_SHIP_AO) != 0;
        // The precomputed field replaces the per-fragment voxel loop, so its samplers exist only in
        // that variant; in the ordinary one GLSL never sees them and binding by name would throw.
        // It cuts the other way too: with the loop gone nothing reads the OCCLUDER list any more, so
        // u_VsShipOccluders is dropped from the precompute variant and must not be bound there.
        // (u_VsShipOccluderCount survives -- the early-out still tests it.)
        final boolean precomp = (features & SodiumCompat.FEATURE_SEAM_PRECOMP) != 0;
        this.uniformShipEmitters = floodGrid
            ? null : context.bindUniform("u_VsShipEmitters", GlUniformInt::new);
        this.uniformShipEmitterCount = floodGrid
            ? null : context.bindUniform("u_VsShipEmitterCount", GlUniformInt::new);
        this.uniformWorldFromShipSections = floodGrid
            ? context.bindUniform("u_VsWorldFromShipSections", GlUniformInt::new) : null;
        this.uniformWorldFromShipLut = floodGrid
            ? context.bindUniform("u_VsWorldFromShipLut", GlUniformInt::new) : null;
        this.uniformFloodGridValid = floodGrid
            ? context.bindUniform("u_VsFloodGridValid", GlUniformInt::new) : null;
        this.uniformShipOccluders = (shipAo && !precomp)
            ? context.bindUniform("u_VsShipOccluders", GlUniformInt::new) : null;
        this.uniformShipOccluderCount = shipAo ? context.bindUniform("u_VsShipOccluderCount", GlUniformInt::new) : null;
        this.uniformSeamRuns = shipAo ? context.bindUniform("u_VsSeamRuns", GlUniformInt::new) : null;
        this.uniformSeamRunCount = shipAo ? context.bindUniform("u_VsSeamRunCount", GlUniformInt::new) : null;
        this.uniformSeamShipDir = shipAo ? context.bindUniform("u_VsSeamShipDir", GlUniformInt::new) : null;
        this.uniformSeamBounds = shipAo ? context.bindUniform("u_VsSeamBounds", GlUniformFloat4v::new) : null;
        this.uniformSeamGrid = shipAo ? context.bindUniform("u_VsSeamGrid", GlUniformInt::new) : null;
        this.uniformSeamGridOrigin = shipAo ? context.bindUniform("u_VsSeamGridOrigin", GlUniformFloat3v::new) : null;
        this.uniformSeamGridInvCell = shipAo ? context.bindUniform("u_VsSeamGridInvCell", GlUniformFloat3v::new) : null;
        this.uniformSeamOcc = precomp ? context.bindUniform("u_VsSeamOcc", GlUniformInt::new) : null;
        this.uniformSeamOccDesc = precomp
            ? context.bindUniform("u_VsSeamOccDesc", GlUniformInt::new) : null;
    }

    public void setRenderOrigin(int x, int y, int z) {
        this.uniformRenderOrigin.set(x, y, z);
    }

    public void setCameraFrac(float fx, float fy, float fz) {
        this.uniformCameraFrac.set(fx, fy, fz);
    }

    public void setFloodGridValid(boolean valid) {
        if (this.uniformFloodGridValid != null) {
            this.uniformFloodGridValid.setInt(valid ? 1 : 0);
        }
    }

    public void setWorldFromShipSamplers(int sectionsUnit, int lutUnit) {
        if (this.uniformWorldFromShipSections != null) {
            this.uniformWorldFromShipSections.setInt(sectionsUnit);
            this.uniformWorldFromShipLut.setInt(lutUnit);
        }
    }

    public void setLightSectionsSampler(int unit) { }

    public void setLightLutSampler(int unit) { }

    public void setShipEmitters(int textureUnit, int count) {
        if (this.uniformShipEmitters == null) {
            return;
        }
        this.uniformShipEmitters.setInt(textureUnit);
        this.uniformShipEmitterCount.setInt(count);
    }

    public void setShipOccluders(int textureUnit, int count) {
        // Guarded separately, not as a pair. The precompute variant drops the occluder SAMPLER --
        // nothing reads voxels per fragment there any more -- but keeps the COUNT, because the
        // early-out at the top of ws_seamAoFrag still tests it. Bailing on the sampler being absent
        // would leave that count at zero and the AO would silently never run.
        if (this.uniformShipOccluders != null) {
            this.uniformShipOccluders.setInt(textureUnit);
        }
        if (this.uniformShipOccluderCount != null) {
            this.uniformShipOccluderCount.setInt(count);
        }
    }

    public void setSeamData(int runsTextureUnit, int runCount, int shipDirTextureUnit,
            float boundsCx, float boundsCy, float boundsCz, float boundsRadius) {
        if (this.uniformSeamRuns == null) return;
        this.uniformSeamRuns.setInt(runsTextureUnit);
        this.uniformSeamRunCount.setInt(runCount);
        this.uniformSeamShipDir.setInt(shipDirTextureUnit);
        this.uniformSeamBounds.set(new float[] {boundsCx, boundsCy, boundsCz, boundsRadius});
    }

    public void setSeamGrid(int gridTextureUnit, float ox, float oy, float oz,
            float icx, float icy, float icz) {
        if (this.uniformSeamGrid == null) return;
        this.uniformSeamGrid.setInt(gridTextureUnit);
        this.uniformSeamGridOrigin.set(ox, oy, oz);
        this.uniformSeamGridInvCell.set(icx, icy, icz);
    }

    public void setSeamOccField(int fieldTextureUnit, int descTextureUnit) {
        if (this.uniformSeamOcc == null) return;
        this.uniformSeamOcc.setInt(fieldTextureUnit);
        this.uniformSeamOccDesc.setInt(descTextureUnit);
    }
}
