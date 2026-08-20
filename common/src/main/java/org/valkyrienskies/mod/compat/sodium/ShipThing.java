package org.valkyrienskies.mod.compat.sodium;

import org.joml.Matrix4fc;

import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.valkyrienskies.mod.compat.sodium.shader.GlUniformInt3v;

/**
 * VS-specific chunk shader interface. Mirrors the per-feature {@code #ifdef}
 * gating in the VSH/FSH: each uniform is only bound when the shader actually
 * declares it, because sodium's {@link me.jellysquid.mods.sodium.client.gl.shader.GlProgram#bindUniform}
 * throws when a uniform name is missing from the linked program.
 *
 * <p>Feature bits are passed in from {@link SodiumCompat} at link time and
 * mirror what the shader was compiled with.
 */
public class ShipThing extends ChunkShaderInterface {
    // Ship-to-world matrix; needed when the shader transforms the per-quad face
    // normal to world space (shading or world-light lookup), or when the
    // per-vertex ship-on-ship seam-AO pass lifts this quad's local corners.
    private final GlUniformMatrix4f uniformTransformMatrix;
    // Local-to-camera-relative matrix; only needed when the VSH emits world
    // position for lighting/AO or samples world biome (uses worldPosVertex).
    private final GlUniformMatrix4f uniformLocalToCameraRel;
    // Integer camera origin; same conditions as u_LocalToCameraRel.
    private final GlUniformInt3v uniformRenderOrigin;
    // World light/solid buffer textures. Full light data is only used when
    // VS_DYNAMIC_LIGHT is enabled, but ship-on-ship AO also reads the solid
    // bitmap so terrain blocks can participate in ship seam AO.
    private final GlUniformInt uniformLightSections;
    private final GlUniformInt uniformLightLut;
    // World-biome buffer textures; only when VS_DYNAMIC_BIOME.
    private final GlUniformInt uniformBiomeSections;
    private final GlUniformInt uniformBiomeLut;
    // World-from-ship buffer textures; only when VS_SHIP_ON_SHIP. Same data
    // the world chunk shader queries so one ship can shadow / illuminate
    // another ship that happens to be nearby in world space.
    private final GlUniformInt uniformShipEmitters;
    private final GlUniformInt uniformShipEmitterCount;
    // Same per-frame solid-voxel list the world FSH uses for ship-to-world
    // AO; binding it on the ship shader too lets one ship's voxels shadow
    // another ship's surface (and the same ship's own concave geometry).
    private final GlUniformInt uniformShipOccluders;
    private final GlUniformInt uniformShipOccluderCount;
    // Compute-flooded ship light: on this generation it IS the ship-on-ship light, not a gate on the
    // emitter falloff. Only when VS_FLOOD_GRID.
    private final GlUniformInt uniformWorldFromShipSections;
    private final GlUniformInt uniformWorldFromShipLut;
    private final GlUniformInt uniformFloodGridValid;
    // Seam-AO acceleration structures (sub-run headers, ship directory,
    // global bounds); only when VS_SHIP_ON_SHIP.
    private final GlUniformInt uniformSeamRuns;
    private final GlUniformInt uniformSeamRunCount;
    private final GlUniformInt uniformSeamShipDir;
    private final GlUniformFloat4v uniformSeamBounds;
    private final GlUniformInt uniformSeamGrid;
    private final GlUniformFloat3v uniformSeamGridOrigin;
    private final GlUniformFloat3v uniformSeamGridInvCell;
    // Per-frame index of the ship currently being rendered. The ship FSH
    // compares each occluder voxel's stored index against this and skips
    // matches — same-ship AO is already baked into v_Color.a, so counting
    // it again would double-darken self-shadows.
    private final GlUniformInt uniformCurrentShipIndex;

    public ShipThing(ShaderBindingContext context, ChunkShaderOptions options, int features) {
        super(context, options);
        boolean light = (features & SodiumCompat.FEATURE_LIGHT) != 0;
        boolean biome = (features & SodiumCompat.FEATURE_BIOME) != 0;
        boolean shade = (features & SodiumCompat.FEATURE_SHADE) != 0;
        boolean shipOnShip = (features & SodiumCompat.FEATURE_SHIP_ON_SHIP) != 0;
        boolean floodGrid = (features & SodiumCompat.FEATURE_FLOOD_GRID) != 0;
        // Seam AO compiles out with the config, taking its uniforms with it.
        boolean seamAo = shipOnShip && (features & SodiumCompat.FEATURE_SHIP_AO) != 0;
        // shipOnShip needs u_TransformMatrix in the VSH to lift this quad's
        // local corners into world space for the per-vertex seam-AO pass,
        // even when light/shade are both off.
        boolean wantTransform = light || shade || shipOnShip;
        boolean wantLocalToCamera = light || biome || shipOnShip;

        this.uniformTransformMatrix = wantTransform
                ? context.bindUniform("u_TransformMatrix", GlUniformMatrix4f::new) : null;
        this.uniformLocalToCameraRel = wantLocalToCamera
                ? context.bindUniform("u_LocalToCameraRel", GlUniformMatrix4f::new) : null;
        this.uniformRenderOrigin = wantLocalToCamera
                ? context.bindUniform("u_VsRenderOrigin", GlUniformInt3v::new) : null;
        boolean worldSolidLookup = light || shipOnShip;

        this.uniformLightSections = worldSolidLookup
                ? context.bindUniform("u_VsLightSections", GlUniformInt::new) : null;
        this.uniformLightLut = worldSolidLookup
                ? context.bindUniform("u_VsLightLut", GlUniformInt::new) : null;
        this.uniformBiomeSections = biome
                ? context.bindUniform("u_VsBiomeSections", GlUniformInt::new) : null;
        this.uniformBiomeLut = biome
                ? context.bindUniform("u_VsBiomeLut", GlUniformInt::new) : null;
        // Ship-on-ship light comes from the flood when the flood exists, so the shader only declares
        // the emitter list on the non-compute fallback path, and binding a name GLSL eliminated throws.
        boolean sosEmitterList = shipOnShip && !floodGrid;
        this.uniformShipEmitters = sosEmitterList
                ? context.bindUniform("u_VsShipEmitters", GlUniformInt::new) : null;
        this.uniformShipEmitterCount = sosEmitterList
                ? context.bindUniform("u_VsShipEmitterCount", GlUniformInt::new) : null;
        this.uniformWorldFromShipSections = floodGrid
                ? context.bindUniform("u_VsWorldFromShipSections", GlUniformInt::new) : null;
        this.uniformWorldFromShipLut = floodGrid
                ? context.bindUniform("u_VsWorldFromShipLut", GlUniformInt::new) : null;
        this.uniformFloodGridValid = floodGrid
            ? context.bindUniform("u_VsFloodGridValid", GlUniformInt::new) : null;
        this.uniformShipOccluders = seamAo
                ? context.bindUniform("u_VsShipOccluders", GlUniformInt::new) : null;
        this.uniformShipOccluderCount = seamAo
                ? context.bindUniform("u_VsShipOccluderCount", GlUniformInt::new) : null;
        this.uniformSeamRuns = seamAo
                ? context.bindUniform("u_VsSeamRuns", GlUniformInt::new) : null;
        this.uniformSeamRunCount = seamAo
                ? context.bindUniform("u_VsSeamRunCount", GlUniformInt::new) : null;
        this.uniformSeamShipDir = seamAo
                ? context.bindUniform("u_VsSeamShipDir", GlUniformInt::new) : null;
        this.uniformSeamBounds = seamAo
                ? context.bindUniform("u_VsSeamBounds", GlUniformFloat4v::new) : null;
        this.uniformSeamGrid = seamAo
                ? context.bindUniform("u_VsSeamGrid", GlUniformInt::new) : null;
        this.uniformSeamGridOrigin = seamAo
                ? context.bindUniform("u_VsSeamGridOrigin", GlUniformFloat3v::new) : null;
        this.uniformSeamGridInvCell = seamAo
                ? context.bindUniform("u_VsSeamGridInvCell", GlUniformFloat3v::new) : null;
        this.uniformCurrentShipIndex = seamAo
                ? context.bindUniform("u_VsCurrentShipIndex", GlUniformInt::new) : null;
    }

    public void setTransformMatrix(Matrix4fc matrix) {
        if (this.uniformTransformMatrix != null) this.uniformTransformMatrix.set(matrix);
    }

    public void setLocalToWorldMatrix(Matrix4fc matrix) {
        if (this.uniformLocalToCameraRel != null) this.uniformLocalToCameraRel.set(matrix);
    }

    public void setRenderOrigin(int x, int y, int z) {
        if (this.uniformRenderOrigin != null) this.uniformRenderOrigin.set(x, y, z);
    }

    public void setLightSectionsSampler(int unit) {
        if (this.uniformLightSections != null) this.uniformLightSections.setInt(unit);
    }

    public void setLightLutSampler(int unit) {
        if (this.uniformLightLut != null) this.uniformLightLut.setInt(unit);
    }

    public void setBiomeSectionsSampler(int unit) {
        if (this.uniformBiomeSections != null) this.uniformBiomeSections.setInt(unit);
    }

    public void setBiomeLutSampler(int unit) {
        if (this.uniformBiomeLut != null) this.uniformBiomeLut.setInt(unit);
    }

    public void setShipEmitters(int textureUnit, int count) {
        if (this.uniformShipEmitters != null) this.uniformShipEmitters.setInt(textureUnit);
        if (this.uniformShipEmitterCount != null) this.uniformShipEmitterCount.setInt(count);
    }

    public void setShipOccluders(int textureUnit, int count) {
        if (this.uniformShipOccluders != null) this.uniformShipOccluders.setInt(textureUnit);
        if (this.uniformShipOccluderCount != null) this.uniformShipOccluderCount.setInt(count);
    }

    public void setFloodGridValid(final boolean valid) {
        if (this.uniformFloodGridValid != null) {
            this.uniformFloodGridValid.setInt(valid ? 1 : 0);
        }
    }

    /** Index of the ship being drawn, so its own voxels can be skipped by the per-fragment AO. */
    public void setCurrentShipIndex(final int index) {
        if (this.uniformCurrentShipIndex != null) {
            this.uniformCurrentShipIndex.setInt(index);
        }
    }

    public void setWorldFromShipSamplers(int sectionsUnit, int lutUnit) {
        if (this.uniformWorldFromShipSections != null) {
            this.uniformWorldFromShipSections.setInt(sectionsUnit);
        }
        if (this.uniformWorldFromShipLut != null) this.uniformWorldFromShipLut.setInt(lutUnit);
    }

    public void setSeamData(int runsTextureUnit, int runCount, int shipDirTextureUnit,
            float boundsCx, float boundsCy, float boundsCz, float boundsRadius) {
        if (this.uniformSeamRuns != null) this.uniformSeamRuns.setInt(runsTextureUnit);
        if (this.uniformSeamRunCount != null) this.uniformSeamRunCount.setInt(runCount);
        if (this.uniformSeamShipDir != null) this.uniformSeamShipDir.setInt(shipDirTextureUnit);
        if (this.uniformSeamBounds != null) {
            this.uniformSeamBounds.set(new float[] {boundsCx, boundsCy, boundsCz, boundsRadius});
        }
    }

    public void setSeamGrid(int gridTextureUnit, float ox, float oy, float oz,
            float icx, float icy, float icz) {
        if (this.uniformSeamGrid != null) this.uniformSeamGrid.setInt(gridTextureUnit);
        if (this.uniformSeamGridOrigin != null) this.uniformSeamGridOrigin.set(ox, oy, oz);
        if (this.uniformSeamGridInvCell != null) this.uniformSeamGridInvCell.set(icx, icy, icz);
    }
}
