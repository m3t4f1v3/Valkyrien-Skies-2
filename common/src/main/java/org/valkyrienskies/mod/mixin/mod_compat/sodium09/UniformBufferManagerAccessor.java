package org.valkyrienskies.mod.mixin.mod_compat.sodium09;

import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code UniformBufferManager#update} latches after the first call in a frame, because the world's
 * matrices don't change between passes. Every ship needs its own model-view in the globals block, so the
 * ship pass clears the latch before each rewrite (and once more afterwards, to restore the world's).
 */
@Mixin(value = UniformBufferManager.class, remap = false)
public interface UniformBufferManagerAccessor {
    @Accessor("hasUpdatedThisFrame")
    void vs$setHasUpdatedThisFrame(boolean value);
}
