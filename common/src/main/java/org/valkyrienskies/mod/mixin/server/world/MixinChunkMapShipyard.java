package org.valkyrienskies.mod.mixin.server.world;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.VS2ChunkAllocator;

/**
 * Bypasses chunk pipeline neighbor requirements for shipyard chunks.
 *
 * When a shipyard chunk is being promoted through chunk statuses (EMPTY → ... → FULL),
 * each status normally requires neighbors at certain levels (e.g., FEATURES needs 8-radius
 * neighbors). With radius-0 tickets (level 33), no neighbor ChunkHolders exist, so the
 * pipeline would stall forever.
 *
 * This mixin intercepts getChunkRangeFuture to return only the center chunk when the
 * target is a shipyard chunk. The generation work at each status is already skipped by
 * MixinChunkStatus, so no actual neighbor data is needed.
 */
@Mixin(ChunkMap.class)
public class MixinChunkMapShipyard {

    @Inject(
        method = "getChunkRangeFuture",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vs$skipNeighborsForShipyard(
        ChunkHolder centerHolder, int range, IntFunction<ChunkStatus> statusByRange,
        CallbackInfoReturnable<CompletableFuture<ChunkResult<List<ChunkAccess>>>> cir
    ) {
        if (range <= 0) return; // No neighbors needed anyway

        ChunkPos center = centerHolder.getPos();
        if (!VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(center.x, center.z)) return;

        // For shipyard chunks, skip neighbor gathering entirely.
        // Request only the center chunk at the status required for distance 0.
        ChunkStatus requiredStatus = statusByRange.apply(0);
        CompletableFuture<ChunkResult<ChunkAccess>> centerFuture =
            centerHolder.scheduleChunkGenerationTask(requiredStatus, (ChunkMap) (Object) this);

        cir.setReturnValue(
            centerFuture.thenApply(result -> result.map(List::of))
        );
    }

    // 1.21's ChunkGenerationTask eagerly acquires a StaticCache2D of generation holders around the target chunk.
    // Suppressing propagated neighbor holders now causes acquireGeneration() to receive nulls and crash.
    // Keep the worldgen-stage skipping, but defer the propagated-holder optimization until it is ported to the
    // new generation pipeline.
}
