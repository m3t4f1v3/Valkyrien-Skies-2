package org.valkyrienskies.mod.mixin.feature.air_pockets.client.renderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.client.multiplayer.ClientLevel;

@Mixin(WorldSlice.class)
public interface WorldSliceAccessor {
    @Accessor("world")
    public ClientLevel getWorld();
}
