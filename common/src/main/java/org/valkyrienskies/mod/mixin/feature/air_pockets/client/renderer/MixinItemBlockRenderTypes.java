package org.valkyrienskies.mod.mixin.feature.air_pockets.client.renderer;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

@Mixin(ItemBlockRenderTypes.class)
public class MixinItemBlockRenderTypes {

    @Shadow
    private static Map<Fluid, RenderType> TYPE_BY_FLUID;

    // todo: we need a custom rendertype, and doing it this way breaks water on ships, so it should be changed in the SectionCompiler part
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void onClinit(CallbackInfo ci) {
        RenderType renderType = RenderType.tripwire();

        TYPE_BY_FLUID.put(Fluids.FLOWING_WATER, renderType);
        TYPE_BY_FLUID.put(Fluids.WATER, renderType);
    }
}
