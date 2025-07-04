package org.valkyrienskies.mod.mixin.feature.container_distance_check;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(Container.class)
public interface MixinContainer {
    /**
     * @author Potato
     * @reason This mixin literally doesn't work as a redirect, so we have to overwrite it. Damn you ASM!
     */
    @Overwrite
    static boolean stillValidBlockEntity(BlockEntity blockEntity, Player player, int i) {
        Level level = blockEntity.getLevel();
        BlockPos blockPos = blockEntity.getBlockPos();
        if (level == null) {
            return false;
        } else if (level.getBlockEntity(blockPos) != blockEntity) {
            return false;
        } else {
            return VSGameUtilsKt.squaredDistanceToInclShips(player, (double)blockPos.getX() + (double)0.5F, (double)blockPos.getY() + (double)0.5F, (double)blockPos.getZ() + (double)0.5F) <= (double)(i * i);
        }
    }
}
