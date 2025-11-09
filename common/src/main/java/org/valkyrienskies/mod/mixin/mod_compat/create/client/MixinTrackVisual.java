package org.valkyrienskies.mod.mixin.mod_compat.create.client;

import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackVisual;
import dev.engine_room.flywheel.api.visual.LightUpdatedVisual;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TrackVisual.class)
public abstract class MixinTrackVisual implements LightUpdatedVisual {

    @Shadow
    @Final
    protected TrackBlockEntity blockEntity;

    @Override
    public void updateLight(final float partialTick) {
        update(partialTick);
    }
}
