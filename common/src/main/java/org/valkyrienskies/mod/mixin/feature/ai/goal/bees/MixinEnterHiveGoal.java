package org.valkyrienskies.mod.mixin.feature.ai.goal.bees;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.entity.animal.Bee;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(Bee.BeeEnterHiveGoal.class)
public class MixinEnterHiveGoal {
    // Synthetic outer-class reference on this non-static inner class. In dev with fabric-loom
    // mappings this used to be shadowed as field_20367 (intermediary), but synthetic `this$0`
    // fields aren't remapped across namespaces so the name differs by platform: under
    // NeoForge's mojmap runtime it stays `this$0` and the refmap translation fails if the
    // jar's mixin config doesn't ship a refmap ("@Shadow field field_20367 was not located
    // in the target class ... No refMap loaded"). Reference the synthetic name directly so
    // no remap is needed. The field is accessed via reflection-style escape in Java source
    // because `this$0` contains a `$` — valid in identifiers — but requires this spelling.
    @Shadow
    @Final
    Bee this$0;

    @WrapOperation(method = "canBeeUse", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;closerToCenterThan(Lnet/minecraft/core/Position;D)Z"))
    private boolean onCloserToCenterThan(BlockPos instance, Position position, double v, Operation<Boolean> original) {
        return original.call(BlockPos.containing(VSGameUtilsKt.toWorldCoordinates(this.this$0.level(), instance)), position, v);
    }
}
