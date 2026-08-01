package org.valkyrienskies.mod.fabric.mixin.compat.replay;

import com.replaymod.render.FFmpegWriter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.replaymod.render.rendering.VideoRenderer;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.render.batched.ShipBatchRenderer;

@Mixin(FFmpegWriter.class)
public class MixinFFmpegWriter {

    private static final Logger LOGGER = LogManager.getLogger("VS2-Replay");

    @Inject(method = "<init>(Lcom/replaymod/render/rendering/VideoRenderer;)V", at = @At("HEAD"))
    private static void FFmpegWriter(VideoRenderer renderer, CallbackInfo ci) {
        LOGGER.info("[VS2] Marking ships as DirtyChunks.");
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        for (final Ship ship : VSGameUtilsKt.getAllShips(level)) {
            ship.getActiveChunksSet().forEach((x, z) -> {
                for (int y = level.getMinSection(); y < level.getMaxSection(); y++) {
                    ShipBatchRenderer.INSTANCE.markSectionDirty(ship.getId(), x, y, z);
                    LOGGER.info("[VS2] Marked section ({}, {}, {}) as dirty for ship {}", x, y, z, ship.getId());
                }
            });
        }
        try {
        } catch (final Exception e) {
            LOGGER.warn("[VS2] Failed to trigger ShipBatchRenderer.beginFrame:", e);
        }
    }
}
