package org.valkyrienskies.mod.common.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Forces vertex alpha to zero on everything written through it.
 *
 * <p>Alpha zero is how the ship core shaders are told "this quad was never directionally shaded, do
 * not shade it either" -- see the {@code Color.a == 0.0} branch in {@code rendertype_ship_solid.vsh}
 * and its siblings. Chunk geometry never carries a meaningful alpha of its own (vanilla writes 1.0
 * for every block vertex), so the channel is free to carry the flag.
 */
public final class NoShadeVertexConsumer implements VertexConsumer {

    private final VertexConsumer delegate;

    public NoShadeVertexConsumer(final VertexConsumer delegate) {
        this.delegate = delegate;
    }

    @Override
    public void vertex(final float x, final float y, final float z, final float red, final float green,
        final float blue, final float alpha, final float texU, final float texV, final int overlayUV,
        final int lightmapUV, final float normalX, final float normalY, final float normalZ) {
        delegate.vertex(x, y, z, red, green, blue, 0.0f, texU, texV, overlayUV, lightmapUV,
            normalX, normalY, normalZ);
    }

    @Override
    public VertexConsumer color(final int red, final int green, final int blue, final int alpha) {
        // The chained builder path, for any putBulkData implementation that does not use the
        // fast vertex() overload above. Returns this rather than the delegate so the rest of the
        // chain stays wrapped.
        delegate.color(red, green, blue, 0);
        return this;
    }

    @Override
    public VertexConsumer vertex(final double x, final double y, final double z) {
        delegate.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer uv(final float u, final float v) {
        delegate.uv(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(final int u, final int v) {
        delegate.overlayCoords(u, v);
        return this;
    }

    @Override
    public VertexConsumer uv2(final int u, final int v) {
        delegate.uv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer normal(final float x, final float y, final float z) {
        delegate.normal(x, y, z);
        return this;
    }

    @Override
    public void endVertex() {
        delegate.endVertex();
    }

    @Override
    public void defaultColor(final int red, final int green, final int blue, final int alpha) {
        delegate.defaultColor(red, green, blue, alpha);
    }

    @Override
    public void unsetDefaultColor() {
        delegate.unsetDefaultColor();
    }
}
