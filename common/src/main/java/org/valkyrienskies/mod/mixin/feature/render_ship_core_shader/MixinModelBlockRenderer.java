package org.valkyrienskies.mod.mixin.feature.render_ship_core_shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.common.render.NoShadeVertexConsumer;

/**
 * Decodes, for the vanilla (non-Sodium) block renderer, the no-shade marker that
 * {@link MixinRenderChunkRegion} smuggles through the chunk builder.
 *
 * <p>This replaces an interface mixin that redefined {@code VertexConsumer#putBulkData}. That only
 * ever worked in a Mojang-mapped dev client. An un-annotated method merged into an interface gets
 * no refmap entry and is not reobfuscated, so against a production Forge runtime -- where the
 * target is {@code m_85995_} -- Mixin found no method to replace and quietly added an unused
 * default one instead. The {@code +4f} then reached the vertex buffer untouched, brightness came
 * out about 5x, and {@code BufferBuilder#vertex} writes colour as {@code (byte)(int)(c * 255)},
 * which wraps rather than clamps. Powered redstone dust, tinted (255, 51, 0), landed on
 * (251, 255, 0) -- yellow, while unpowered dust has no green to overflow and looked fine.
 *
 * <p>Wrapping the call rather than the callee also keeps the platform's own {@code putBulkData} in
 * the chain. The old copy was a copy of <em>vanilla's</em> body, so on Forge it silently dropped
 * {@code applyBakedLighting}, {@code applyBakedNormals} and the quad's baked vertex alpha -- for
 * every block in the game, since that mixin was global and had no config check.
 *
 * <p>The marker is read off {@link BakedQuad#isShade()}, not off the magnitude of the smuggled
 * float. A {@code brightness > 4f} test cannot survive ambient occlusion: {@code
 * AmbientOcclusionFace#calculate} finishes with {@code brightness[j] *= shade}, so a no-shade quad
 * in an AO-enabled model arrives as {@code ao * (base + 4)} -- below the threshold entirely once
 * {@code ao} drops under 0.8, and decoding to the wrong value above it. Vanilla models in that
 * combination are chain, sea pickle, big dripleaf, spore blossom, seagrass and the calibrated sculk
 * sensor; redstone dust escaped only because its model also sets {@code "ambientocclusion": false}.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class MixinModelBlockRenderer {

    @WrapOperation(
        method = "putQuadData",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;[FFFF[IIZ)V"
        )
    )
    private void vs$decodeNoShadeMarker(final VertexConsumer consumer, final PoseStack.Pose pose,
        final BakedQuad quad, final float[] brightness, final float red, final float green,
        final float blue, final int[] lightmap, final int overlay, final boolean readExistingColor,
        final Operation<Void> original, final BlockAndTintGetter view) {

        if (quad.isShade()) {
            // Shaded quads are handed the un-shaded value with nothing added to it, so there is
            // nothing to undo; the shader re-applies shade from the rotated normal.
            original.call(consumer, pose, quad, brightness, red, green, blue, lightmap, overlay,
                readExistingColor);
            return;
        }

        // getShade(dir, false) is exactly the value MixinRenderChunkRegion inflates, and it ignores
        // the direction it is given, so reading it back doubles as the "is this a shipyard region"
        // test: nothing but the smuggled value can exceed 4.
        final float smuggled = view.getShade(quad.getDirection(), false);
        if (smuggled <= 4.0f) {
            original.call(consumer, pose, quad, brightness, red, green, blue, lightmap, overlay,
                readExistingColor);
            return;
        }

        final float base = smuggled - 4.0f;
        // brightness is (base + 4) on the flat path and ao * (base + 4) under ambient occlusion.
        // Scaling by base / (base + 4) recovers the intended value in both, without having to know
        // which path produced it.
        final float scale = base / smuggled;
        for (int i = 0; i < brightness.length; i++) {
            brightness[i] *= scale;
        }
        original.call(new NoShadeVertexConsumer(consumer), pose, quad, brightness, red, green, blue,
            lightmap, overlay, readExistingColor);
    }
}
