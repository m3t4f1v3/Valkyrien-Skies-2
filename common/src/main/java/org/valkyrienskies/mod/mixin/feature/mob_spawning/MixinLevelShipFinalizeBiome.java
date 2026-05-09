package org.valkyrienskies.mod.mixin.feature.mob_spawning;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.mob_spawning.ShipSpawnFinalizeContext;

@Mixin(LevelReader.class)
public interface MixinLevelShipFinalizeBiome {

    @Inject(method = "getBiome", at = @At("HEAD"), cancellable = true)
    private void vs$shipFinalizeBiome(final BlockPos pos, final CallbackInfoReturnable<Holder<Biome>> cir) {
        final Ship ship = ShipSpawnFinalizeContext.current();
        if (ship == null) return;
        final Vector3d rendered = ship.getTransform().getShipToWorld().transformPosition(
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new Vector3d()
        );
        final BlockPos worldPos = BlockPos.containing(rendered.x, rendered.y, rendered.z);
        ShipSpawnFinalizeContext.push(null);
        try {
            cir.setReturnValue(((LevelReader) (Object) this).getBiome(worldPos));
        } finally {
            ShipSpawnFinalizeContext.pop();
        }
    }
}
