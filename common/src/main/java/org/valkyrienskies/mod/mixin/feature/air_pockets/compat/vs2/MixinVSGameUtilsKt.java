package org.valkyrienskies.mod.mixin.feature.air_pockets.compat.vs2;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;

@Mixin(VSGameUtilsKt.class)
public abstract class MixinVSGameUtilsKt {

    @Inject(method = "isPositionSealed", at = @At("HEAD"), cancellable = true)
    private static void valkyrienair$disableVs2IsPositionSealed(final Level level, final BlockPos pos,
        final CallbackInfoReturnable<Boolean> cir) {
        if (VSGameConfig.COMMON.getEnableAirPockets()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isPositionMaybeSealed", at = @At("HEAD"), cancellable = true)
    private static void valkyrienair$disableVs2IsPositionMaybeSealed(final Level level, final BlockPos pos,
        final CallbackInfoReturnable<Boolean> cir) {
        if (VSGameConfig.COMMON.getEnableAirPockets()) {
            cir.setReturnValue(false);
        }
    }
}

