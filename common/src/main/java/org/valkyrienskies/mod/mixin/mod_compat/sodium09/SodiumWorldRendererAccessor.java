package org.valkyrienskies.mod.mixin.mod_compat.sodium09;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The per-frame GPU state a ship draw has to reuse. In 0.9 the uniform block, the fog parameters and the
 * translucency-sorting flag are all private to the world renderer and are not passed down to
 * {@code drawChunkLayer}, so the ship pass has to reach them here.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface SodiumWorldRendererAccessor {
    @Accessor("renderSectionManager")
    RenderSectionManager vs$getRenderSectionManager();

    @Accessor("uniformBufferManager")
    UniformBufferManager vs$getUniformBufferManager();

    @Accessor("lastFogParameters")
    FogParameters vs$getLastFogParameters();

    @Accessor("useTranslucencySorting")
    boolean vs$getUseTranslucencySorting();
}
