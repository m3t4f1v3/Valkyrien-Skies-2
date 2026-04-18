package org.valkyrienskies.mod.mixin.server.world;

import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.VS2ChunkAllocator;

@Mixin(GenerationChunkHolder.class)
public abstract class MixinGenerationChunkHolder {

    /**
     * Shipyard chunk holders must report BLOCK_TICKING so ChunkMap registers their tick containers.
     * Otherwise scheduled block ticks stop working after save/reload.
     */
    @Inject(method = "getFullStatus", at = @At("HEAD"), cancellable = true)
    private void vs$upgradeShipyardHolderStatus(CallbackInfoReturnable<FullChunkStatus> cir) {
        final ChunkPos pos = ((GenerationChunkHolder) (Object) this).getPos();
        if (VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(pos.x, pos.z)) {
            cir.setReturnValue(FullChunkStatus.BLOCK_TICKING);
        }
    }
}
