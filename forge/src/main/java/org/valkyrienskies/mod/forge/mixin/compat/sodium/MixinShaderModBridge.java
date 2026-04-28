package org.valkyrienskies.mod.forge.mixin.compat.sodium;

import org.embeddedt.embeddium.render.ShaderModBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShaderModBridge.class, remap = false)
public abstract class MixinShaderModBridge {
    @Inject(method = "emulateLegacyColorBrightnessFormat", at = @At("HEAD"), cancellable = true)
    private static void useLegacyColorBrightnessFormat(final CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}
