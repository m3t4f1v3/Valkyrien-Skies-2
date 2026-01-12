package org.valkyrienskies.mod.fabric.mixin.compat.connectiblechains;

import com.github.legoatoom.connectiblechains.entity.Chainable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.decoration.HangingEntity;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(value = Chainable.class, remap = false)
public interface MixinChainable {
    // Chainable is an interface and tickChain accepts a generic. We are lucky it is only ever used once, for HangingEntity
    // so we can use it as a Local. Forge port handles this in a far less overengineered way.
    @WrapOperation(method = "tickChain", at = @At(value = "INVOKE", target = "Lcom/github/legoatoom/connectiblechains/entity/Chainable;getMaxChainLength()D"))
    private static double adjustMaxChainLength(Operation<Double> original, @Local(argsOnly = true) HangingEntity entity) {
        Ship ship = VSGameUtilsKt.getShipManaging(entity);
        if (ship != null) {
            Vector3dc scale = ship.getTransform().getScaling();
            return original.call() * scale.get(scale.maxComponent()); // here we get max length so multiplying
        } else {
            return original.call();
        }
    }
}
