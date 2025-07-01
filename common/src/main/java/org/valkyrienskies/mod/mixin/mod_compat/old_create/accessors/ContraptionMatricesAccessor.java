package org.valkyrienskies.mod.mixin.mod_compat.old_create.accessors;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.render.ContraptionMatrices")
public interface ContraptionMatricesAccessor {
    @Invoker
    void callSetup(PoseStack viewProjection, AbstractContraptionEntity entity);
}
