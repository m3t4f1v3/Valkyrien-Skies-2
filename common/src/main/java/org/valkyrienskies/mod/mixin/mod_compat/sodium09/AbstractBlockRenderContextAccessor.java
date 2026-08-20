package org.valkyrienskies.mod.mixin.mod_compat.sodium09;

import net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The block currently being meshed. {@code BlockRenderer} inherits these from
 * {@link AbstractBlockRenderContext} rather than declaring them, and {@code @Shadow} resolves against the
 * target class alone — so they have to be reached through an accessor on the class that owns them.
 */
@Mixin(value = AbstractBlockRenderContext.class, remap = false)
public interface AbstractBlockRenderContextAccessor {
    @Accessor("state")
    BlockState vs$getState();

    @Accessor("pos")
    BlockPos vs$getPos();

    @Accessor("level")
    BlockAndTintGetter vs$getLevel();
}
