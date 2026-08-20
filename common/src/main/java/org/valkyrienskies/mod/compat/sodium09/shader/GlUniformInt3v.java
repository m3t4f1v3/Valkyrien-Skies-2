package org.valkyrienskies.mod.compat.sodium09.shader;

import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniform;
import org.lwjgl.opengl.GL30C;

/**
 * Sodium 0.9 has no ivec3 uniform type of its own; this is the same three-int uniform VS uses on the
 * 0.5 path, rebased onto {@code net.caffeinemc}'s {@link GlUniform}.
 */
public class GlUniformInt3v extends GlUniform<int[]> {
    public GlUniformInt3v(final int index) {
        super(index);
    }

    @Override
    public void set(final int[] value) {
        GL30C.glUniform3iv(this.index, value);
    }

    public void set(final int x, final int y, final int z) {
        GL30C.glUniform3i(this.index, x, y, z);
    }
}
