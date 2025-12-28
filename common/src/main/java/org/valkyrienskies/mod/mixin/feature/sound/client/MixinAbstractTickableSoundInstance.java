package org.valkyrienskies.mod.mixin.feature.sound.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.client.audio.VelocityTickableSoundInstance;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(AbstractTickableSoundInstance.class)
public abstract class MixinAbstractTickableSoundInstance extends AbstractSoundInstance implements VelocityTickableSoundInstance {
    @Unique
    private ClientShip ship;
    @Unique
    private Vector3d worldPosition = null;

    @Override
    public double getX() { updatePosition(); return this.worldPosition == null ? this.x : this.worldPosition.x(); }
    @Override
    public double getY() { updatePosition(); return this.worldPosition == null ? this.y : this.worldPosition.y(); }
    @Override
    public double getZ() { updatePosition(); return this.worldPosition == null ? this.z : this.worldPosition.z(); }
    @NonNull
    @Override
    public Vector3dc getVelocity() { return ship != null ? ship.getVelocity() : new Vector3d(); }

    @Unique
    private void updatePosition() {
        if (worldPosition == null) worldPosition = new Vector3d(x, y, z);
        ship = (ClientShip) VSGameUtilsKt.getShipManagingPos(Minecraft.getInstance().level, this.x, this.y, this.z);
        if (ship != null)
            ship.getRenderTransform().getShipToWorld().transformPosition(x, y, z, worldPosition);
    }

    // Dummy
    protected MixinAbstractTickableSoundInstance(SoundEvent soundEvent, SoundSource soundSource,
        RandomSource randomSource, Ship ship) {
        super(soundEvent, soundSource, randomSource);
    }
}
