package org.valkyrienskies.mod.mixin.world.level.levelgen;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.VS2ChunkAllocator;

@Mixin(ChunkStatusTasks.class)
public class MixinChunkStatus {

    @Inject(method = {"generateBiomes", "generateNoise"}, at = @At("HEAD"), cancellable = true)
    private static void vs$skipBiomeAndNoiseGeneration(
        final WorldGenContext worldGenContext, final ChunkStep chunkStep,
        final StaticCache2D<GenerationChunkHolder> staticCache2D, final ChunkAccess chunkAccess,
        final CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        final ChunkPos chunkPos = chunkAccess.getPos();
        if (VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(chunkPos.x, chunkPos.z)) {
            cir.setReturnValue(CompletableFuture.completedFuture(chunkAccess));
        }
    }

    @Inject(method = "generateStructureStarts", at = @At("HEAD"), cancellable = true)
    private static void vs$skipStructureStarts(
        final WorldGenContext worldGenContext, final ChunkStep chunkStep,
        final StaticCache2D<GenerationChunkHolder> staticCache2D, final ChunkAccess chunkAccess,
        final CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        final ChunkPos chunkPos = chunkAccess.getPos();
        if (VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(chunkPos.x, chunkPos.z)) {
            worldGenContext.level().onStructureStartsAvailable(chunkAccess);
            cir.setReturnValue(CompletableFuture.completedFuture(chunkAccess));
        }
    }

    @Inject(
        method = {
            "generateStructureReferences",
            "generateSurface",
            "generateCarvers",
            "generateFeatures",
            "generateSpawn",
        },
        at = @At("HEAD"),
        cancellable = true
    )
    private static void vs$skipShipyardWorldgenStages(
        final WorldGenContext worldGenContext, final ChunkStep chunkStep,
        final StaticCache2D<GenerationChunkHolder> staticCache2D, final ChunkAccess chunkAccess,
        final CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        final ChunkPos chunkPos = chunkAccess.getPos();
        if (VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(chunkPos.x, chunkPos.z)) {
            cir.setReturnValue(CompletableFuture.completedFuture(chunkAccess));
        }
    }

    // Let the vanilla light engine's lightChunk run for shipyard chunks.
    // MixinThreadedLevelLightEngine gives shipyard light tasks high priority so they
    // actually get processed. MixinChunkMapShipyard handles neighbor requirements.
    // lightChunk sets up internal light engine state (propagateLightSources, setLightEnabled)
    // that is required for lighting to work correctly after assembly.
}
