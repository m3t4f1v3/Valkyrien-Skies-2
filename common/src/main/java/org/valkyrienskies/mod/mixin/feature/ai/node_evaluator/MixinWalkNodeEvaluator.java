package org.valkyrienskies.mod.mixin.feature.ai.node_evaluator;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.pathfinding.PathfindingFrame;
import org.valkyrienskies.mod.common.ShipBlock;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(WalkNodeEvaluator.class)
public abstract class MixinWalkNodeEvaluator extends NodeEvaluator {

    @Unique
    private static final ThreadLocal<Vector3d> VS$IN = ThreadLocal.withInitial(Vector3d::new);
    @Unique
    private static final ThreadLocal<Vector3d> VS$OUT = ThreadLocal.withInitial(Vector3d::new);

    @Unique
    private PathfindingFrame.InShip vs$inShipOrNull() {
        if (!VSGameConfig.SERVER.getAiOnShips()) return null;
        if (this.mob == null) return null;
        final PathfindingFrame frame = PathfindingFrame.current(this.mob);
        return frame instanceof PathfindingFrame.InShip inShip ? inShip : null;
    }

    @Inject(method = "getStart", at = @At("HEAD"), cancellable = true)
    private void vs$shipLocalStart(final CallbackInfoReturnable<Node> cir) {
        final PathfindingFrame.InShip inShip = vs$inShipOrNull();
        if (inShip == null) return;
        final Ship ship = inShip.getShip();

        final ShipBlock standingOn = VSGameUtilsKt.getShipBlockStoodOn(this.mob, 0.5);
        if (standingOn != null && standingOn.ship.getId() == ship.getId()) {
            final BlockPos above = standingOn.shipLocalBlockPos.above();
            cir.setReturnValue(new Node(above.getX(), above.getY(), above.getZ()));
            return;
        }

        final Vector3d local = ship.getTransform().getWorldToShip().transformPosition(
            VS$IN.get().set(this.mob.getX(), this.mob.getY(), this.mob.getZ()), VS$OUT.get()
        );
        cir.setReturnValue(new Node(
            (int) Math.floor(local.x), (int) Math.floor(local.y), (int) Math.floor(local.z)
        ));
    }

    // Vanilla canReachWithoutCollision sweeps an AABB from mob.position() to node coords assuming both share one frame; in an InShip frame the mob is at world coords (~hundreds) and the node at shipyard coords (~10^7), and the delta blows up to millions of iterations.
    @Inject(
        method = "canReachWithoutCollision(Lnet/minecraft/world/level/pathfinder/Node;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vs$shipFrameCollisionSweep(final Node node, final CallbackInfoReturnable<Boolean> cir) {
        final PathfindingFrame.InShip inShip = vs$inShipOrNull();
        if (inShip == null) return;
        if (this.level == null) return;

        final AABB worldBbox = this.mob.getBoundingBox();
        final double w = worldBbox.getXsize();
        final double h = worldBbox.getYsize();
        final double d = worldBbox.getZsize();

        final Vector3d mobShip = inShip.getShip().getTransform().getWorldToShip().transformPosition(
            VS$IN.get().set(this.mob.getX(), this.mob.getY(), this.mob.getZ()), VS$OUT.get()
        );

        AABB bbox = new AABB(
            mobShip.x - w / 2.0, mobShip.y, mobShip.z - d / 2.0,
            mobShip.x + w / 2.0, mobShip.y + h, mobShip.z + d / 2.0
        );

        final Vec3 delta = new Vec3(
            node.x - mobShip.x,
            node.y - mobShip.y,
            node.z - mobShip.z
        );

        final int iterations = Mth.ceil(delta.length() / bbox.getSize());
        final Vec3 step = delta.scale(1.0 / (double) iterations);

        for (int i = 1; i <= iterations; i++) {
            bbox = bbox.move(step);
            if (!this.level.noCollision(this.mob, bbox)) {
                cir.setReturnValue(false);
                return;
            }
        }
        cir.setReturnValue(true);
    }
}
