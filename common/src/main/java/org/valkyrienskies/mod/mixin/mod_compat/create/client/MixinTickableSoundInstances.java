package org.valkyrienskies.mod.mixin.mod_compat.create.client;

import com.simibubi.create.content.decoration.steamWhistle.WhistleSoundInstance;
import com.simibubi.create.content.kinetics.fan.AirCurrentSound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.client.audio.VelocityTickableSoundInstance;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

// Maybe we should mixin AbstractTickableSoundInstance instead?
// Still would need mixins for tick() because the overrides don't call super tick so no universal solution.
@Mixin(value = {
    WhistleSoundInstance.class,
    AirCurrentSound.class
}, targets = {
    "Lcom/simibubi/create/content/trains/entity/CarriageSounds$LoopingSound"
})
public abstract class MixinTickableSoundInstances extends AbstractTickableSoundInstance implements VelocityTickableSoundInstance {
    @Unique
    private Vector3dc position = null;
    @Unique
    private Vector3dc lastPosition = null;
    @Unique
    private Vector3dc velocity = new Vector3d();

    @Override
    public double getX() { return this.position == null ? this.x : this.position.x(); }
    @Override
    public double getY() { return this.position == null ? this.y : this.position.y(); }
    @Override
    public double getZ() { return this.position == null ? this.z : this.position.z(); }
    @Override
    public Vector3dc getVelocity() { return this.velocity; }

    @Inject(method = "tick", at = @At("RETURN"))
    public void tick(final CallbackInfo ci) {
        this.position = VSGameUtilsKt.toWorldCoordinates(Minecraft.getInstance().level, this.x, this.y, this.z);
        if (this.lastPosition != null) {
            this.velocity = this.position.sub(this.lastPosition, new Vector3d());
        }
        this.lastPosition = this.position;
    }

    // Dummy
    protected MixinTickableSoundInstances(SoundEvent soundEvent, SoundSource soundSource, RandomSource randomSource) {
        super(soundEvent, soundSource, randomSource);
    }
}
