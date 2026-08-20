package org.valkyrienskies.mod.mixin.mod_compat.sodium09;

import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.light.LightPipeline;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadViewMutable;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.compat.LoadedMods;
import org.valkyrienskies.mod.compat.iris.IrisCompat;
import org.valkyrienskies.mod.compat.sodium.shader.VsVertexFlagPacker;

/**
 * The fluid counterpart of {@link MixinBlockRenderer}.
 *
 * <p>Without this, ship water keeps the shipyard-biome water colour baked at mesh time and never re-tints
 * to the world biome at the ship's actual position.
 *
 * <p>The flags are captured in {@code updateQuad}, which is the only place with the block position, and
 * applied in {@code writeQuad}, which is where the vertices exist. Sodium calls the lighter with
 * {@code shade=false} for fluids, so the brightness carries no directional shade to divide out and the
 * quad is packed with the UNSHADED slot — the FSH then skips its world-space directional shade for water.
 */
@Mixin(value = DefaultFluidRenderer.class, remap = false)
public abstract class MixinDefaultFluidRenderer {

    @Shadow
    @Final
    private ChunkVertexEncoder.Vertex[] vertices;

    @Unique
    private boolean vs$shouldPack = false;

    @Unique
    private int vs$resolverType = 0;

    @Inject(method = "updateQuad", at = @At("HEAD"))
    private void vs$captureFluidFlags(final ModelQuadViewMutable quad, final LevelSlice level, final BlockPos pos,
        final LightPipeline lighter, final Direction dir, final ModelQuadFacing facing, final float brightness,
        final ColorProvider<FluidState> colorProvider, final FluidState fluidState, final CallbackInfo ci) {
        final boolean anyShipFeature = VSGameConfig.CLIENT.getDynamicShipBiomeTinting()
            || VSGameConfig.CLIENT.getDynamicShipLighting();
        final boolean worldFromShip = VSGameConfig.CLIENT.getDynamicShipToWorldLighting();
        if (!anyShipFeature && !worldFromShip) {
            this.vs$shouldPack = false;
            return;
        }
        if (LoadedMods.getIris() && IrisCompat.isIrisShaderActive()) {
            this.vs$shouldPack = false;
            return;
        }

        final boolean inShipyard = VsVertexFlagPacker.isShipyardBlock(pos);
        // Pack shipyard fluids when ship features are on, and world fluids when ship-to-world lighting is
        // on (so the world FSH gets the FACE_UNSHADED slot and skips its AO sample for water surfaces).
        this.vs$shouldPack = (inShipyard && anyShipFeature) || (!inShipyard && worldFromShip);
        this.vs$resolverType = (inShipyard && this.vs$shouldPack)
            ? VsVertexFlagPacker.resolverTypeFor(fluidState) : 0;
    }

    @Inject(
        method = "writeQuad",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;"
                + "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;"
                + "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;)V"))
    private void vs$packFluidVertexFlags(final ChunkModelBuilder builder,
        final TranslucentGeometryCollector collector, final Material material, final BlockPos offset,
        final ModelQuadView quad, final ModelQuadFacing facing, final boolean flip, final CallbackInfo ci) {
        if (!this.vs$shouldPack) {
            return;
        }

        for (int i = 0; i < 4; i++) {
            final ChunkVertexEncoder.Vertex out = this.vertices[i];
            out.color = VsVertexFlagPacker.packColor(out.color, out.ao, this.vs$resolverType,
                VsVertexFlagPacker.FACE_UNSHADED);
            out.ao = 1.0f;
        }
    }
}
