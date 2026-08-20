package org.valkyrienskies.mod.compat.sodium09;

import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.valkyrienskies.mod.compat.sodium09.shader.GlUniformInt3v;

/**
 * Sodium 0.9 counterpart of the VS world chunk shader interface. Rendered for non-ship chunks when
 * {@code dynamicShipToWorldLighting} is enabled, so ships above the world cast shadows and ship-internal
 * emitters illuminate world blocks beneath them.
 */
public class WorldThing extends DefaultShaderInterface {
    private final GlUniformInt3v uniformRenderOrigin;
    private final GlUniformFloat3v uniformCameraFrac;
    private final GlUniformInt uniformShipEmitters;
    private final GlUniformInt uniformShipEmitterCount;
    private final GlUniformInt uniformShipOccluders;
    private final GlUniformInt uniformShipOccluderCount;
    // Compute-flooded ship light; only bound under VS_FLOOD_GRID, because sodium's bindUniform throws
    // on a name the linked program doesn't declare.
    private final GlUniformInt uniformWorldFromShipSections;
    private final GlUniformInt uniformWorldFromShipLut;
    private final GlUniformInt uniformFloodGridValid;

    public WorldThing(final ShaderBindingContext context, final ChunkShaderOptions options,
        final int features) {
        super(context, options);
        final boolean floodGrid = (features & SodiumCompat.FEATURE_FLOOD_GRID) != 0;
        this.uniformRenderOrigin = context.bindUniform("u_VsRenderOrigin", GlUniformInt3v::new);
        this.uniformCameraFrac = context.bindUniform("u_VsCameraFrac", GlUniformFloat3v::new);
        // Under VS_FLOOD_GRID the world shader takes ship light entirely from the flood, so it does
        // not declare these at all and sodium's bindUniform would throw on the missing names. They
        // remain for the non-compute fallback, which still evaluates the emitter list per fragment.
        this.uniformShipEmitters = floodGrid
            ? null : context.bindUniform("u_VsShipEmitters", GlUniformInt::new);
        this.uniformShipEmitterCount = floodGrid
            ? null : context.bindUniform("u_VsShipEmitterCount", GlUniformInt::new);
        this.uniformShipOccluders = context.bindUniform("u_VsShipOccluders", GlUniformInt::new);
        this.uniformShipOccluderCount = context.bindUniform("u_VsShipOccluderCount", GlUniformInt::new);
        this.uniformWorldFromShipSections = floodGrid
            ? context.bindUniform("u_VsWorldFromShipSections", GlUniformInt::new) : null;
        this.uniformWorldFromShipLut = floodGrid
            ? context.bindUniform("u_VsWorldFromShipLut", GlUniformInt::new) : null;
        this.uniformFloodGridValid = floodGrid
            ? context.bindUniform("u_VsFloodGridValid", GlUniformInt::new) : null;
    }

    public void setFloodGridValid(final boolean valid) {
        if (this.uniformFloodGridValid != null) {
            this.uniformFloodGridValid.setInt(valid ? 1 : 0);
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

    public void setRenderOrigin(final int x, final int y, final int z) {
        this.uniformRenderOrigin.set(x, y, z);
    }

    public void setCameraFrac(final float fx, final float fy, final float fz) {
        this.uniformCameraFrac.set(fx, fy, fz);
    }

    public void setShipEmitters(final int textureUnit, final int count) {
        if (this.uniformShipEmitters == null) {
            return;
        }
        this.uniformShipEmitters.setInt(textureUnit);
        this.uniformShipEmitterCount.setInt(count);
    }

    public void setShipOccluders(final int textureUnit, final int count) {
        this.uniformShipOccluders.setInt(textureUnit);
        this.uniformShipOccluderCount.setInt(count);
    }
}
