package org.valkyrienskies.mod.mixin.mod_compat.theatrical;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(targets = {"dev.imabad.theatrical.client.blockentities.FixtureRenderer$1"})
public class MixinFixtureRenderer {
    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;atLowerCornerOf(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;"),
        remap = false
    )
    private static Vec3 wrapAtCornerOf(Vec3i vec3i, Operation<Vec3> original) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return original.call(vec3i);

        return VSGameUtilsKt.toWorldCoordinates(level, original.call(vec3i));
    }

    @WrapMethod(
        method = "getPos",
        remap = false
    )
    private Vec3 wrapGetPos(float partialTick, Operation<Vec3> original) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return original.call(partialTick);

        return VSGameUtilsKt.toWorldCoordinates(level, original.call(partialTick));
    }
}
