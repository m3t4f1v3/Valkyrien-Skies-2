package org.valkyrienskies.mod.mixin.feature.physics_block_entities;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.api.BlockEntityPhysicsListener;
import org.valkyrienskies.mod.api.ValkyrienSkies;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.util.PhysicsBlockEntityUtil;

@Mixin(LevelChunk.class)
public abstract class MixinLevelChunk {
    @Shadow
    @Final
    Level level;

    @Shadow
    private boolean loaded;

    @Shadow
    public abstract Map<BlockPos, BlockEntity> getBlockEntities();

    @Inject(method = "updateBlockEntityTicker", at = @At("HEAD"))
    private <T extends BlockEntity> void onUpdateBlockEntityTickerHead(T blockEntity, CallbackInfo ci) {
        String dimensionId = ValkyrienSkies.getDimensionId(this.level);
        if (blockEntity instanceof BlockEntityPhysicsListener listener) {
            listener.setDimension(dimensionId);
            PhysicsBlockEntityUtil.onLoad(listener, blockEntity.getBlockPos(), this.level, "[ADDED] updateBlockEntityTicker");
        } else {
            //ValkyrienSkiesMod.INSTANCE.removeBlockEntityPhysTicker(blockEntity.getBlockPos(), dimensionId);
            PhysicsBlockEntityUtil.onRemove(blockEntity.getBlockPos(), this.level, "[REMOVED] updateBlockEntityTicker");
        }
    }

    @Inject(method = "clearAllBlockEntities", at = @At("HEAD"))
    private void onClearAllBlockEntitiesHead(CallbackInfo ci) {
        getBlockEntities().forEach((blockPos, blockEntity) -> {
            if (blockEntity instanceof BlockEntityPhysicsListener listener) {
                PhysicsBlockEntityUtil.onRemove(blockPos, this.level, "clearAllBlockEntities");
            }
        });
    }

    @Inject(method = "removeBlockEntity", at = @At("TAIL"))
    private void onRemoveBlockEntityTickerHead(BlockPos blockPos, CallbackInfo ci) {
        PhysicsBlockEntityUtil.onRemove(blockPos, this.level, "removeBlockEntity");
    }
}
