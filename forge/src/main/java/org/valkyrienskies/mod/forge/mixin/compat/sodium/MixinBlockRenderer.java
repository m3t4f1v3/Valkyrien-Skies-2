package org.valkyrienskies.mod.forge.mixin.compat.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import me.jellysquid.mods.sodium.client.model.light.data.QuadLightData;
import me.jellysquid.mods.sodium.client.model.quad.BakedQuadView;
import me.jellysquid.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.world.phys.Vec3;

import org.embeddedt.embeddium.render.chunk.ChunkColorWriter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.compat.sodium.light.VsVertexFlagPacker;

/**
 * Embeddium-on-Forge flavor of the chunk-vertex flag packer. Embeddium routes
 * the per-vertex color packing through {@code ChunkColorWriter.writeColor(int, float)}
 * (a per-instance interface dispatch) instead of sodium's direct
 * {@code ColorABGR.withAlpha} call. We rely on
 * {@link org.valkyrienskies.mod.forge.mixin.compat.sodium.MixinEmbeddiumShaderModBridge}
 * forcing the LEGACY writer so the alpha byte still carries the AO multiplier
 * — that's what we steal high bits from.
 */
@Mixin(value = BlockRenderer.class, remap = false)
public class MixinBlockRenderer {
    @Unique
    private boolean vs$inShipyard = false;
    @Unique
    private int vs$resolverType = 0;
    @Unique
    private int vs$faceSlot = VsVertexFlagPacker.FACE_UNSHADED;
    @Unique
    private float vs$shadeFactor = 1.0f;

    @Inject(method = "writeGeometry", at = @At("HEAD"))
    private void vs$captureFlags(BlockRenderContext ctx, ChunkModelBuilder builder, Vec3 origin,
                                 Material material, BakedQuadView quad, int[] colors, QuadLightData light,
                                 CallbackInfo ci) {
        // Skip per-quad detection entirely if no ship-shader feature is on.
        // Shade matters in addition to biome/light because the face-slot bits
        // drive the per-vertex world-normal that vanillaShadeFromNormal uses.
        if (!VSGameConfig.CLIENT.getDynamicShipBiomeTinting()
                && !VSGameConfig.CLIENT.getDynamicShipLighting()
                && !VSGameConfig.CLIENT.getBetterVanillaShipShading()) {
            return;
        }
        vs$inShipyard = VsVertexFlagPacker.isShipyardBlock(ctx.pos());
        // A quad is biome-tinted only if it has a tintIndex (>=0). A grass
        // block's dirt-side faces are colorIndex == -1 even though the block
        // type maps to GRASS in BiomeColorResolvers, so without this gate we'd
        // white-out their RGB and re-tint them at fragment time, which is wrong.
        vs$resolverType = (vs$inShipyard && quad.hasColor())
                ? VsVertexFlagPacker.resolverTypeFor(ctx.state())
                : 0;
        boolean isShaded = quad.hasShade();
        // Emissive quads (model JSON "emissive": true, glowstone, etc.) get the
        // dedicated FULLBRIGHT face slot — overrides direction so the FSH skips
        // shade and AO and forces the lightmap UV to max.
        boolean emissive = VsVertexFlagPacker.isEmissiveQuad((BakedQuad) quad);
        vs$faceSlot = emissive
                ? VsVertexFlagPacker.FACE_FULLBRIGHT
                : VsVertexFlagPacker.faceSlot(quad.getLightFace(), isShaded);
        vs$shadeFactor = VsVertexFlagPacker.standardShade(quad.getLightFace(), isShaded);
    }

    @WrapOperation(method = "writeGeometry",
            at = @At(value = "INVOKE",
                    target = "Lorg/embeddedt/embeddium/render/chunk/ChunkColorWriter;writeColor(IF)I",
                    remap = false))
    private int vs$packVertexColor(ChunkColorWriter writer, int origColor, float br, Operation<Integer> original) {
        if (!vs$inShipyard
                || (!VSGameConfig.CLIENT.getDynamicShipBiomeTinting()
                    && !VSGameConfig.CLIENT.getDynamicShipLighting()
                    && !VSGameConfig.CLIENT.getBetterVanillaShipShading())) {
            return original.call(writer, origColor, br);
        }
        // Divide out the directional shade sodium pre-multiplied so the FSH can
        // re-apply it from the ship's actual world-space orientation without
        // double-darkening.
        float ao = vs$shadeFactor > 1e-6f ? br / vs$shadeFactor : br;
        return VsVertexFlagPacker.packColor(origColor, ao, vs$resolverType, vs$faceSlot);
    }
}
