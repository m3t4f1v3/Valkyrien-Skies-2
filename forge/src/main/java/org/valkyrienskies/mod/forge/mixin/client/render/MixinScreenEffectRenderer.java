package org.valkyrienskies.mod.forge.mixin.client.render;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.apache.commons.lang3.tuple.Pair;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(ScreenEffectRenderer.class)
public class MixinScreenEffectRenderer {

    /**
     * How far a ship's box may have run past the sample point and still be considered a candidate.
     *
     * <p>The box and the point are not in the same frame -- see {@link #viewBlockingOnShip} -- so a
     * query that demands they overlap drops the very ship the player is inside. Four blocks is a
     * tick of travel at 80 m/s, and being generous costs only a handful of extra candidates, each
     * of which is then tested exactly.
     */
    @Unique
    private static final double VS$BROAD_PHASE_SLACK = 4.0;

    /**
     * The suffocation overlay, for a player whose head is inside a ship's block.
     *
     * <p><b>The point and the transform have to be in the same frame, and the two obvious choices
     * are a whole tick of travel apart.</b> Vanilla builds its sample points from
     * {@code player.getX()} -- the tick position -- and a mounted player's tick position is written
     * by {@code positionRider} during {@code level.tickEntities()}, which runs <i>before</i>
     * {@code shipObjectWorld.postTick()} advances the ship at the tail of that same client tick. So
     * the point stands in the previous tick's frame while {@link Ship#getWorldToShip()} is already
     * the new one, and the gap between them is exactly one tick of the ship's travel: nothing
     * parked, three blocks at sixty. Mapped through the wrong one, a driver sitting perfectly still
     * in their seat lands three blocks behind it -- in the bulkhead, the engine, or whatever else
     * happens to be back there -- and the screen goes black at speed for no reason they can see.
     * Whether it happens at all depends on what is behind the seat, which is why it reads as
     * arbitrary.
     *
     * <p>So a mounted player is tested against {@link Ship#getPrevTickTransform()}, the frame their
     * position was actually built in, and the error is then not small but exactly zero, at any
     * speed. An entity merely <i>standing</i> on a ship is the opposite case and wants the other
     * transform: {@code EntityDragger} carries it into the new frame at the tail of the tick, after
     * the ship has moved, so for that one {@link Ship#getWorldToShip()} is already right. Which of
     * the two wrote this entity's position is the whole question, and it is answerable -- it is
     * whether the entity is riding this ship.
     *
     * <p>The candidate query is widened for the same reason, since the index is over the ship's
     * new-frame box while the point is in the old frame; and it is then narrowed again by testing
     * the point against the box it is about to be mapped by. Without that second test a widened
     * query lets a ship several blocks away black the screen, which is the opposite fault and just
     * as hard to see.
     */
    @Inject(
        method = "getOverlayBlock",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
        cancellable = true
    )
    private static void viewBlockingOnShip(Player player, CallbackInfoReturnable<Pair<BlockState, BlockPos>> cir,
        @Local(ordinal = 0) double d, @Local(ordinal = 1) double e, @Local(ordinal = 2) double f) {
        final LoadedShip mountedTo = VSGameUtilsKt.getShipMountedTo(player);
        final AABB probe = player.getBoundingBox().inflate(VS$BROAD_PHASE_SLACK);
        for (final Ship ship : VSGameUtilsKt.getShipsIntersecting(player.level(), probe)) {
            final boolean riding = mountedTo != null && mountedTo.getId() == ship.getId();
            // A ship the player is not riding has to actually contain the point, or the widened
            // query above answers for a ship they are nowhere near.
            if (!riding && !ship.getWorldAABB().containsPoint(d, e, f)) {
                continue;
            }
            // The frame the sample point is really in. These differ by a tick of the ship's
            // travel, and that difference is the whole of this bug.
            final Matrix4dc worldToShip = riding
                ? ship.getPrevTickTransform().getWorldToShip()
                : ship.getWorldToShip();
            final Vector3d pos = worldToShip.transformPosition(d, e, f, new Vector3d());
            final BlockPos blockPos = BlockPos.containing(pos.x, pos.y, pos.z);
            final BlockState state = player.level().getBlockState(blockPos);
            if (state.getRenderShape() != RenderShape.INVISIBLE
                && state.isViewBlocking(player.level(), blockPos)) {
                cir.setReturnValue(Pair.of(state, blockPos));
                return;
            }
        }
    }
}
