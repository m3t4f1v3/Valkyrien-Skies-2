package org.valkyrienskies.mod.mixinducks.mod_compat.echochest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import org.jetbrains.annotations.Nullable;

public class EchoChestBlockEntity extends ChestBlockEntity implements WorldlyContainer, GameEventListener.Holder,
    VibrationSystem {
    public EchoChestBlockEntity(BlockEntityType<?> blockEntityType,
        BlockPos blockPos, BlockState blockState) {
        super(blockEntityType, blockPos, blockState);
    }

    @Override
    public int[] getSlotsForFace(Direction direction) {
        return new int[0];
    }

    @Override
    public boolean canPlaceItemThroughFace(int i, ItemStack itemStack, @Nullable Direction direction) {
        return false;
    }

    @Override
    public boolean canTakeItemThroughFace(int i, ItemStack itemStack, Direction direction) {
        return false;
    }

    @Override
    public GameEventListener getListener() {
        return null;
    }

    @Override
    public Data getVibrationData() {
        return null;
    }

    @Override
    public User getVibrationUser() {
        return null;
    }
}
