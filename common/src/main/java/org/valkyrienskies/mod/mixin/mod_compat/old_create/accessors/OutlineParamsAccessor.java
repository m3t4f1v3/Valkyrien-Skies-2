package org.valkyrienskies.mod.mixin.mod_compat.old_create.accessors;

import com.simibubi.create.AllSpecialTextures;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.outliner.Outline.OutlineParams")
public interface OutlineParamsAccessor {
    @Accessor("alpha")
    float getAlpha();

    @Accessor("alpha")
    void setAlpha(float alpha);

    @Accessor
    boolean getDisableCull();

    @Accessor
    Optional<AllSpecialTextures> getFaceTexture();
}
