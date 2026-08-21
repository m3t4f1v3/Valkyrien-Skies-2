package org.valkyrienskies.mod.compat.sodium09;

import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.joml.Matrix4fc;
import org.valkyrienskies.mod.compat.sodium09.shader.GlUniformInt3v;

/**
 * VS-specific chunk shader interface for Sodium 0.9.
 *
 * <p>Unlike the 0.5 path, where {@code ChunkShaderInterface} was an abstract class that we subclassed,
 * 0.9 makes it an interface and puts the projection/model-view matrices, the fog and the atlas
 * parameters in the {@code u_Globals} uniform block. Extending {@link DefaultShaderInterface} therefore
 * gets us all of the stock behaviour — block/light/section-time samplers, the globals block binding, and
 * the per-region offset — and this class only adds the VS uniforms on top.
 *
 * <p>Each uniform is bound optionally, mirroring the per-feature {@code #ifdef} gating in the VSH/FSH:
 * a uniform the shader did not declare is absent from the linked program, and the required
 * {@code bindUniform} throws on that. Feature bits come from {@link SodiumCompat} at link time and match
 * what the shader was compiled with.
 */
public class ShipThing extends DefaultShaderInterface {
    // Ship-to-world matrix; only needed when the shader transforms the per-quad face normal to world
    // space (i.e. when shading or world-light lookup is on).
    private final GlUniformMatrix4f uniformTransformMatrix;
    // Local-to-camera-relative matrix; only needed when the VSH emits world position for lighting/AO or
    // samples world biome (uses worldPosVertex).
    private final GlUniformMatrix4f uniformLocalToCameraRel;
    // Integer camera origin; same conditions as u_LocalToCameraRel.
    private final GlUniformInt3v uniformRenderOrigin;
    // World-light buffer textures; only when VS_DYNAMIC_LIGHT.
    private final GlUniformInt uniformLightSections;
    private final GlUniformInt uniformLightLut;
    // World-biome buffer textures; only when VS_DYNAMIC_BIOME.
    private final GlUniformInt uniformBiomeSections;
    private final GlUniformInt uniformBiomeLut;
    // World-from-ship buffer textures; only when VS_SHIP_ON_SHIP. Same data the world chunk shader
    // queries, so one ship can shadow / illuminate another ship that happens to be nearby in world space.
    private final GlUniformInt uniformShipEmitters;
    private final GlUniformInt uniformShipEmitterCount;
    // Same per-frame solid-voxel list the world FSH uses for ship-to-world AO; binding it on the ship
    // shader too lets one ship's voxels shadow another ship's surface (and the same ship's own concave
    // geometry).
    private final GlUniformInt uniformShipOccluders;
    private final GlUniformInt uniformShipOccluderCount;
    // Per-frame dense index of the ship being drawn. Named u_VsCurrentShipIndex to match the shader
    // block copied verbatim from the 0.5 path; it is the same quantity setSelfShipIndex always carried.
    private final GlUniformInt uniformSelfShipIndex;
    private final GlUniformInt uniformSeamRuns;
    private final GlUniformInt uniformSeamRunCount;
    private final GlUniformInt uniformSeamShipDir;
    private final GlUniformFloat4v uniformSeamBounds;
    private final GlUniformInt uniformSeamGrid;
    private final GlUniformFloat3v uniformSeamGridOrigin;
    private final GlUniformFloat3v uniformSeamGridInvCell;
    // Compute-flooded ship light, used to gate the emitter falloff so hulls block it; only when
    // VS_FLOOD_GRID.
    private final GlUniformInt uniformWorldFromShipSections;
    private final GlUniformInt uniformWorldFromShipLut;
    private final GlUniformInt uniformFloodGridValid;

    public ShipThing(final ShaderBindingContext context, final ChunkShaderOptions options, final int features) {
        super(context, options);
        final boolean light = (features & SodiumCompat.FEATURE_LIGHT) != 0;
        final boolean biome = (features & SodiumCompat.FEATURE_BIOME) != 0;
        final boolean shade = (features & SodiumCompat.FEATURE_SHADE) != 0;
        final boolean shipOnShip = (features & SodiumCompat.FEATURE_SHIP_ON_SHIP) != 0;
        final boolean floodGrid = (features & SodiumCompat.FEATURE_FLOOD_GRID) != 0;

        final boolean needsTransform = light || shade || shipOnShip;
        final boolean needsLocalToCameraRel = light || biome || shipOnShip;

        this.uniformTransformMatrix = needsTransform
            ? context.bindUniform("u_TransformMatrix", GlUniformMatrix4f::new) : null;
        this.uniformLocalToCameraRel = needsLocalToCameraRel
            ? context.bindUniform("u_LocalToCameraRel", GlUniformMatrix4f::new) : null;
        this.uniformRenderOrigin = needsLocalToCameraRel
            ? context.bindUniform("u_VsRenderOrigin", GlUniformInt3v::new) : null;

        this.uniformLightSections = light ? context.bindUniform("u_VsLightSections", GlUniformInt::new) : null;
        this.uniformLightLut = light ? context.bindUniform("u_VsLightLut", GlUniformInt::new) : null;

        this.uniformBiomeSections = biome ? context.bindUniform("u_VsBiomeSections", GlUniformInt::new) : null;
        this.uniformBiomeLut = biome ? context.bindUniform("u_VsBiomeLut", GlUniformInt::new) : null;

        // Ship-on-ship light comes from the flood when the flood exists, so the shader only declares
        // the emitter list on the non-compute fallback path. Occluders are unconditional: they still
        // drive ship-to-ship AO either way.
        final boolean sosEmitterList = shipOnShip && !floodGrid;
        this.uniformShipEmitters =
            sosEmitterList ? context.bindUniform("u_VsShipEmitters", GlUniformInt::new) : null;
        this.uniformShipEmitterCount =
            sosEmitterList ? context.bindUniform("u_VsShipEmitterCount", GlUniformInt::new) : null;
        // Declared only inside VS_SHIP_AO now; binding a name GLSL eliminated throws.
        final boolean shipAo = (features & SodiumCompat.FEATURE_SHIP_AO) != 0;
        this.uniformShipOccluders = (shipOnShip && shipAo)
            ? context.bindUniform("u_VsShipOccluders", GlUniformInt::new) : null;
        this.uniformShipOccluderCount =
            (shipOnShip && shipAo) ? context.bindUniform("u_VsShipOccluderCount", GlUniformInt::new) : null;
        // Read only by the AO loop, which is compiled out unless VS_SHIP_AO.
        this.uniformSelfShipIndex = (shipOnShip && shipAo)
            ? context.bindUniform("u_VsCurrentShipIndex", GlUniformInt::new) : null;
        final boolean seamAo = shipOnShip && shipAo;
        this.uniformSeamRuns = seamAo ? context.bindUniform("u_VsSeamRuns", GlUniformInt::new) : null;
        this.uniformSeamRunCount = seamAo
            ? context.bindUniform("u_VsSeamRunCount", GlUniformInt::new) : null;
        this.uniformSeamShipDir = seamAo
            ? context.bindUniform("u_VsSeamShipDir", GlUniformInt::new) : null;
        this.uniformSeamBounds = seamAo
            ? context.bindUniform("u_VsSeamBounds", GlUniformFloat4v::new) : null;
        this.uniformSeamGrid = seamAo ? context.bindUniform("u_VsSeamGrid", GlUniformInt::new) : null;
        this.uniformSeamGridOrigin = seamAo
            ? context.bindUniform("u_VsSeamGridOrigin", GlUniformFloat3v::new) : null;
        this.uniformSeamGridInvCell = seamAo
            ? context.bindUniform("u_VsSeamGridInvCell", GlUniformFloat3v::new) : null;
        this.uniformWorldFromShipSections =
            floodGrid ? context.bindUniform("u_VsWorldFromShipSections", GlUniformInt::new) : null;
        this.uniformWorldFromShipLut =
            floodGrid ? context.bindUniform("u_VsWorldFromShipLut", GlUniformInt::new) : null;
        this.uniformFloodGridValid = floodGrid
            ? context.bindUniform("u_VsFloodGridValid", GlUniformInt::new) : null;
    }

    public void setTransformMatrix(final Matrix4fc matrix) {
        if (this.uniformTransformMatrix != null) {
            this.uniformTransformMatrix.set(matrix);
        }
    }

    public void setLocalToWorldMatrix(final Matrix4fc matrix) {
        if (this.uniformLocalToCameraRel != null) {
            this.uniformLocalToCameraRel.set(matrix);
        }
    }

    public void setRenderOrigin(final int x, final int y, final int z) {
        if (this.uniformRenderOrigin != null) {
            this.uniformRenderOrigin.set(x, y, z);
        }
    }

    public void setLightSectionsSampler(final int textureUnit) {
        if (this.uniformLightSections != null) {
            this.uniformLightSections.setInt(textureUnit);
        }
    }

    public void setLightLutSampler(final int textureUnit) {
        if (this.uniformLightLut != null) {
            this.uniformLightLut.setInt(textureUnit);
        }
    }

    public void setBiomeSectionsSampler(final int textureUnit) {
        if (this.uniformBiomeSections != null) {
            this.uniformBiomeSections.setInt(textureUnit);
        }
    }

    public void setBiomeLutSampler(final int textureUnit) {
        if (this.uniformBiomeLut != null) {
            this.uniformBiomeLut.setInt(textureUnit);
        }
    }

    public void setShipEmitters(final int textureUnit, final int count) {
        if (this.uniformShipEmitters != null) {
            this.uniformShipEmitters.setInt(textureUnit);
            this.uniformShipEmitterCount.setInt(count);
        }
    }

    public void setShipOccluders(final int textureUnit, final int count) {
        if (this.uniformShipOccluders != null) {
            this.uniformShipOccluders.setInt(textureUnit);
            this.uniformShipOccluderCount.setInt(count);
        }
    }

    public void setFloodGridValid(final boolean valid) {
        if (this.uniformFloodGridValid != null) {
            this.uniformFloodGridValid.setInt(valid ? 1 : 0);
        }
    }

    /** Index of the ship being drawn, so its own voxels can be skipped by the per-fragment AO. */
    public void setSelfShipIndex(final int index) {
        if (this.uniformSelfShipIndex != null) {
            this.uniformSelfShipIndex.setInt(index);
        }
    }

    public void setWorldFromShipSamplers(final int sectionsUnit, final int lutUnit) {
        if (this.uniformWorldFromShipSections != null) {
            this.uniformWorldFromShipSections.setInt(sectionsUnit);
        }
        if (this.uniformWorldFromShipLut != null) {
            this.uniformWorldFromShipLut.setInt(lutUnit);
        }
    }

    public void setSeamData(final int runsTextureUnit, final int runCount, final int shipDirTextureUnit,
        final float boundsCx, final float boundsCy, final float boundsCz, final float boundsRadius) {
        if (this.uniformSeamRuns == null) {
            return;
        }
        this.uniformSeamRuns.setInt(runsTextureUnit);
        this.uniformSeamRunCount.setInt(runCount);
        this.uniformSeamShipDir.setInt(shipDirTextureUnit);
        this.uniformSeamBounds.set(new float[] {boundsCx, boundsCy, boundsCz, boundsRadius});
    }

    public void setSeamGrid(final int gridTextureUnit, final float ox, final float oy, final float oz,
        final float icx, final float icy, final float icz) {
        if (this.uniformSeamGrid == null) {
            return;
        }
        this.uniformSeamGrid.setInt(gridTextureUnit);
        this.uniformSeamGridOrigin.set(ox, oy, oz);
        this.uniformSeamGridInvCell.set(icx, icy, icz);
    }
}
