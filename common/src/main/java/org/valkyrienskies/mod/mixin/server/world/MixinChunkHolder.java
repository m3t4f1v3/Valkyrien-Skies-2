package org.valkyrienskies.mod.mixin.server.world;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.VS2ChunkAllocator;

/**
 * Fix multiple issues with ship chunks at level 33 (FULL status):
 *
 * 1. getTickingChunk() returns null — blocks blockChanged() and broadcastChanges()
 *    from sending block update packets to clients. Fixed by getting the chunk
 *    directly from the ServerLevel's chunk cache.
 *
 * 2. getFullStatus() returns FULL instead of BLOCK_TICKING — this prevents:
 *    - registerTickContainerInLevel() from being called during chunk loading,
 *      so scheduled ticks (repeaters, observers, etc.) don't work after save/reload
 *    - Level.markAndNotifyBlock() from calling sendBlockUpdated()
 *    Fixed by returning BLOCK_TICKING for shipyard positions.
 */
@Mixin(ChunkHolder.class)
public abstract class MixinChunkHolder {

    @Shadow
    private LevelHeightAccessor levelHeightAccessor;

    @Inject(method = "getTickingChunk", at = @At("HEAD"), cancellable = true)
    private void vs$getTickingChunkForShipyard(CallbackInfoReturnable<LevelChunk> cir) {
        final ChunkPos pos = ((ChunkHolder) (Object) this).getPos();
        if (VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(pos.x, pos.z)) {
            if (levelHeightAccessor instanceof ServerLevel serverLevel) {
                LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk != null) {
                    cir.setReturnValue(chunk);
                }
            }
        }
    }

}
