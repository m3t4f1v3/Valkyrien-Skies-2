package org.valkyrienskies.mod.mixin.mod_compat.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;

@Mixin(RenderSectionManager.class)
public interface RenderSectionManagerAccessor {
    @Accessor("chunkRenderer")
    ChunkRenderer getChunkRenderer();
}
