package org.valkyrienskies.mod.mixin.feature.enchantment_table;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.EnchantmentTableBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(EnchantmentTableBlockEntity.class)
public class MixinEnchantmentTableBlockEntity extends BlockEntity {
    @WrapOperation(method = "bookAnimationTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getNearestPlayer(DDDDZ)Lnet/minecraft/world/entity/player/Player;"))
    private static Player adjustForWorld(Level level, double x, double y, double z, double r, boolean b,
        Operation<Player> original) {
        Vec3 worldPos = VSGameUtilsKt.toWorldCoordinates(level, new Vec3(x, y, z));
        return original.call(level, worldPos.x, worldPos.y, worldPos.z, r, b);
    }

    @ModifyVariable(method = "bookAnimationTick", at = @At("STORE"), ordinal = 0)
    private static double worldX(double x, @Local Player player, @Local(argsOnly = true) BlockPos blockPos) {
        Vec3 worldPos = VSGameUtilsKt.toWorldCoordinates(player.level(), blockPos.getCenter());
        return player.getX() - worldPos.x;
    }

    @ModifyVariable(method = "bookAnimationTick", at = @At("STORE"), ordinal = 1)
    private static double worldZ(double x, @Local Player player, @Local(argsOnly = true) BlockPos blockPos) {
        Vec3 worldPos = VSGameUtilsKt.toWorldCoordinates(player.level(), blockPos.getCenter());
        return player.getZ() - worldPos.z;
    }

    @WrapOperation(method = "bookAnimationTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;atan2(DD)D"))
    private static double negateShipRotation(double d, double e, Operation<Double> original, @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos blockPos) {
        double result = Mth.atan2(d, e);
        Ship ship = VSGameUtilsKt.getShipManagingPos(level, blockPos);
        if (ship != null) {
            Vector3d eulers = ship.getTransform().getShipToWorldRotation().getEulerAnglesXYZ(new Vector3d());
            result += eulers.y;
        }
        return result;
    }

    public MixinEnchantmentTableBlockEntity(BlockEntityType<?> blockEntityType,
        BlockPos blockPos, BlockState blockState) {
        super(blockEntityType, blockPos, blockState);
    }
}
