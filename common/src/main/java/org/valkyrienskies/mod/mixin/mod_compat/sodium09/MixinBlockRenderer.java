package org.valkyrienskies.mod.mixin.mod_compat.sodium09;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.compat.LoadedMods;
import org.valkyrienskies.mod.compat.iris.IrisCompat;
import org.valkyrienskies.mod.compat.sodium.shader.VsVertexFlagPacker;

/**
 * Packs the VS per-quad flags into the chunk vertex colour, for Sodium 0.9.
 *
 * <p>0.5 wrote the smooth-lighting factor straight into the colour's alpha byte
 * ({@code ColorABGR.withAlpha}), which left the whole byte free for VS to overload — AO level in the low
 * bits, face slot and biome resolver above it. 0.9 changed the encoder: it now does
 * {@code ColorARGB.mulRGB(color, ao)}, folding the factor into RGB and <em>preserving</em> alpha.
 *
 * <p>So for the quads we pack, this restores the older arrangement rather than fighting it: the flag byte
 * goes into the colour's alpha, and {@code ao} is forced to 1.0 so {@code mulRGB} leaves RGB alone and
 * passes the alpha through untouched. The VS shader re-applies both the AO and the directional shade from
 * the unpacked bits, exactly as it did on 0.5 — the packing contract in {@link VsVertexFlagPacker} is
 * unchanged, and so are the shaders that decode it.
 *
 * <p>Quads we don't pack keep sodium's own encoding, so a config with every VS feature off (or Iris
 * driving the shaders) renders through the stock path untouched.
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class MixinBlockRenderer {

    @Shadow
    @Final
    private ChunkVertexEncoder.Vertex[] vertices;

    @Inject(
        method = "bufferQuad",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;"
                + "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V"))
    private void vs$packVertexFlags(final MutableQuadViewImpl quad, final float[] brightnesses,
        final Material material, final CallbackInfo ci) {
        final boolean anyShipFeature = VSGameConfig.CLIENT.getDynamicShipBiomeTinting()
            || VSGameConfig.CLIENT.getDynamicShipLighting()
            || VSGameConfig.CLIENT.getBetterVanillaShipShading();
        final boolean worldFromShip = VSGameConfig.CLIENT.getDynamicShipToWorldLighting();
        if (!anyShipFeature && !worldFromShip) {
            return;
        }
        if (LoadedMods.getIris() && IrisCompat.isIrisShaderActive()) {
            return;
        }

        // Inherited from AbstractBlockRenderContext, so reached through an accessor rather than @Shadow.
        final AbstractBlockRenderContextAccessor ctx = (AbstractBlockRenderContextAccessor) this;
        final BlockState state = ctx.vs$getState();
        final BlockPos pos = ctx.vs$getPos();

        final boolean inShipyard = VsVertexFlagPacker.isShipyardBlock(pos);
        // Pack for shipyard blocks when any ship feature is on, and for world blocks when ship-to-world
        // lighting is on (the world FSH decodes the face slot to get an exact world normal for AO
        // sampling).
        if (!((inShipyard && anyShipFeature) || (!inShipyard && worldFromShip))) {
            return;
        }

        // World blocks always get resolverType=0 — biome tinting on world chunks goes through sodium's
        // stock BlockColors path (already baked into the per-vertex RGB), no shipyard biome remapping.
        final int resolverType = (inShipyard && quad.getTintIndex() != -1)
            ? VsVertexFlagPacker.resolverTypeFor(state)
            : 0;

        final boolean isShaded = quad.hasShade();
        final boolean fullAoReceiver = Block.isShapeFullBlock(state.getOcclusionShape(ctx.vs$getLevel(), pos));
        final boolean useProjectedAo = isShaded && fullAoReceiver;
        // 0.9 carries the model's emissive flag on the quad itself, so there is no need for 0.5's probe
        // into BakedQuad's packed vertex array.
        final boolean emissive = quad.emissive();
        final int faceSlot = emissive
            ? VsVertexFlagPacker.FACE_FULLBRIGHT
            : VsVertexFlagPacker.faceSlot(quad.getLightFace(), useProjectedAo);
        final float shadeFactor = VsVertexFlagPacker.standardShade(quad.getLightFace(), useProjectedAo);

        for (int i = 0; i < 4; i++) {
            final ChunkVertexEncoder.Vertex out = this.vertices[i];
            // Divide out the directional shade sodium pre-multiplied so the FSH can re-apply it from the
            // actual world-space orientation. Ensures no double-darkening.
            final float ao = shadeFactor > 1e-6f ? out.ao / shadeFactor : out.ao;
            out.color = VsVertexFlagPacker.packColor(out.color, ao, resolverType, faceSlot);
            // Leave RGB alone: mulRGB(color, 1.0) is the identity, and the alpha byte we just wrote
            // survives it untouched.
            out.ao = 1.0f;
        }
    }
}
