package org.valkyrienskies.mod.mixin.mod_compat.sodium09;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Same purpose as {@link org.valkyrienskies.mod.mixin.feature.fix_render_chunk_sorting.MixinRenderChunk}:
 * without it, ship chunks are considered far away from the current position.
 */
@Mixin(value = RenderSection.class, remap = false)
public abstract class MixinRenderSection {

    @Shadow
    public abstract int getCenterX();

    @Shadow
    public abstract int getCenterY();

    @Shadow
    public abstract int getCenterZ();

    @WrapMethod(method = "getSquaredDistance(FFF)F")
    private float vs$squareDistanceIfInShipyard(final float x, final float y, final float z,
        final Operation<Float> original) {
        final ClientLevel world = Minecraft.getInstance().level;
        final ClientShip shipObject =
            VSGameUtilsKt.getLoadedShipManagingPos(world, this.getCenterX(), this.getCenterY(), this.getCenterZ());
        if (shipObject != null) {
            final Vector3dc chunkPosInWorld = shipObject.getRenderTransform().getShipToWorld().transformPosition(
                new Vector3d(this.getCenterX(), this.getCenterY(), this.getCenterZ())
            );
            return (float) chunkPosInWorld.distanceSquared(x, y, z);
        }
        return original.call(x, y, z);
    }
}
