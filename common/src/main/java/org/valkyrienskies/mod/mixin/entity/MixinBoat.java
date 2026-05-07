package org.valkyrienskies.mod.mixin.entity;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(Boat.class)
public class MixinBoat {

    @Redirect(
        method = {
            "getWaterLevelAbove",
            "checkInWater",
            // While we _could_ mixin this, it ends up with players not being
            // able to enter boats on specifically positioned ships because
            // the boat thinks its underwater. So it's safer to leave that value un-fixed for now.
            //"isUnderwater"
        },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"
        )
    )
    private FluidState redirectFluidState(Level level, BlockPos pos) {
        return valkyrienskies$getBlockStateIncludeShips(level, pos).getFluidState();
    }

    @Redirect(
        method = {
            "getGroundFriction"
        },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        )
    )
    private BlockState redirectBlockState(Level level, BlockPos pos) {
        return valkyrienskies$getBlockStateIncludeShips(level, pos);
    }


    @Unique
    private static BlockState valkyrienskies$getBlockStateIncludeShips(Level level, BlockPos pos) {
        AABB aabb = AABB.ofSize(Vec3.atLowerCornerOf(pos), 1, 1, 1);
        AtomicReference<BlockState> state = new AtomicReference<>();
        state.set(level.getBlockState(pos));

        VSGameUtilsKt.transformFromWorldToNearbyShipsAndWorld(level, aabb, newAabb -> {
            BlockPos newPos = BlockPos.containing(newAabb.getCenter());
            BlockState tempState = level.getBlockState(newPos);
            if (!tempState.isAir()) {
                state.set(tempState);
            }
        });

        return state.get();
    }
}
