package org.valkyrienskies.mod.mixin.mod_compat.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.LightUpdatedVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.visualization.VisualManagerImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.compat.LoadedMods;
import org.valkyrienskies.mod.compat.LoadedMods.FlywheelVersion;
import org.valkyrienskies.mod.compat.flywheel.ShipEmbeddingManager;
import org.valkyrienskies.mod.compat.sodium.SodiumCompat;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
    @Shadow
    private @Nullable ClientLevel level;

    /**
     * Updating world-to-ship, ship-to-world dynamic lighting and dynamic biome tint storages require
     * movement of ship or block update in either ship or world.
     * These changes only happen per game tick, not per frame. Updating per game tick disables dynamic
     * lighting/tinting interpolation over time, but the cost of update per frame is too much.
     */
    @Inject(
        method = "tick",
        at = @At("HEAD")
    )
    private void updateDynamicLight(CallbackInfo ci) {
        Minecraft.getInstance().getProfiler().push("vs_dynamic_lighting");
        try {
            SodiumCompat.populateWorldFromShipsForFrame(level);
            SodiumCompat.populateLightSectionStorage(level);
            SodiumCompat.populateBiomeSectionStorage(level);
            if(LoadedMods.getFlywheel() != FlywheelVersion.NONE) {
                VisualizationManagerImpl manager = (VisualizationManagerImpl) VisualizationManager.get(level);
                for (Long sectionLong : SodiumCompat.getWorldFromShipStorage().trackedSections()) {
                    manager.onLightUpdate(SectionPos.of(sectionLong), LightLayer.BLOCK);
                }
                for (BlockEntity blockEntity : ShipEmbeddingManager.INSTANCE.blockEntitiesOnShip()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    BlockEntityVisual<?> beVisual = ((VisualManagerImpl<BlockEntity, BlockEntityStorage>)manager.blockEntities()).getStorage().visualAtPos(pos.asLong());
                    if(beVisual instanceof LightUpdatedVisual lightUpdatedVisual) lightUpdatedVisual.updateLight(0.0f);
                }
            }
        } finally {
            Minecraft.getInstance().getProfiler().pop();
        }
    }

    @WrapMethod(
        method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I"
    )
    private static int getDynamicLight(BlockAndTintGetter blockAndTintGetter, BlockState blockState, BlockPos blockPos, Operation<Integer> original) {
        int packedLight = original.call(blockAndTintGetter, blockState, blockPos);
        if (!(blockAndTintGetter instanceof Level level)) return packedLight;
        if (!VSGameConfig.CLIENT.getDynamicShipLighting() && !VSGameConfig.CLIENT.getDynamicShipToWorldLighting()) return packedLight;
        int sky = LightTexture.sky(packedLight);
        int block = LightTexture.block(packedLight);
        if (VSGameUtilsKt.isBlockInShipyard(level, blockPos)) {
            Vec3 center = VSGameUtilsKt.toWorldCoordinates(level, blockPos.getCenter());
            BlockPos worldPos = BlockPos.containing(center);
            int packedLightWorld = original.call(blockAndTintGetter, blockState, worldPos);
            sky = Math.min(sky, LightTexture.sky(packedLightWorld));
            block = Math.max(block, LightTexture.block(packedLightWorld));
            if (VSGameConfig.CLIENT.getDynamicShipToWorldLighting()) {
                int blockShipToShip = SodiumCompat.getWorldFromShipStorage().getBlockLightAt(worldPos);
                block = Math.max(block, blockShipToShip);
            }
        } else if (VSGameConfig.CLIENT.getDynamicShipToWorldLighting()) {
            int blockShipToWorld = SodiumCompat.getWorldFromShipStorage().getBlockLightAt(blockPos);
            block = Math.max(block, blockShipToWorld);
        }
        return LightTexture.pack(block, sky);
    }
}
