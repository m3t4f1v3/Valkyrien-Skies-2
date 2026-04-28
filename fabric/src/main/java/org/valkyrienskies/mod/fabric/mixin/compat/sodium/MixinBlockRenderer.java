package org.valkyrienskies.mod.fabric.mixin.compat.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import me.jellysquid.mods.sodium.client.model.light.data.QuadLightData;
import me.jellysquid.mods.sodium.client.model.quad.BakedQuadView;
import me.jellysquid.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;

import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.valkyrienskies.mod.compat.sodium.light.VsVertexFlagPacker;

/**
 * Sodium-on-Fabric flavor of the chunk-vertex flag packer. Sodium 0.5.13 calls
 * {@code ColorABGR.withAlpha(int, float)} directly inside writeGeometry — that
 * static call is the redirect target. See
 * {@link VsVertexFlagPacker} for the packing contract.
 */
@Mixin(BlockRenderer.class)
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
        vs$inShipyard = VsVertexFlagPacker.isShipyardBlock(ctx.pos());
        // A quad is biome-tinted only if it has a tintIndex (>=0). A grass
        // block's dirt-side faces are colorIndex == -1 even though the block
        // type maps to GRASS in BiomeColorResolvers, so without this gate we'd
        // white-out their RGB and re-tint them at fragment time, which is wrong.
        vs$resolverType = (vs$inShipyard && quad.hasColor())
                ? VsVertexFlagPacker.resolverTypeFor(ctx.state())
                : 0;
        boolean isShaded = quad.hasShade();
        vs$faceSlot = VsVertexFlagPacker.faceSlot(quad.getLightFace(), isShaded);
        vs$shadeFactor = VsVertexFlagPacker.standardShade(quad.getLightFace(), isShaded);
    }

    @WrapOperation(method = "writeGeometry",
            at = @At(value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/api/util/ColorABGR;withAlpha(IF)I",
                    remap = false))
    private int vs$packVertexColor(int origColor, float br, Operation<Integer> original) {
        if (!vs$inShipyard) {
            return original.call(origColor, br);
        }
        // Divide out the directional shade sodium pre-multiplied so the FSH can
        // re-apply it from the ship's actual world-space orientation without
        // double-darkening.
        float ao = vs$shadeFactor > 1e-6f ? br / vs$shadeFactor : br;
        return VsVertexFlagPacker.packColor(origColor, ao, vs$resolverType, vs$faceSlot);
    }
}
