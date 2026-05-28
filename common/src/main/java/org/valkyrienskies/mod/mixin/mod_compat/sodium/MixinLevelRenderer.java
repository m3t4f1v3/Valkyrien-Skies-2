package org.valkyrienskies.mod.mixin.mod_compat.sodium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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
        } finally {
            Minecraft.getInstance().getProfiler().pop();
        }
    }
}
