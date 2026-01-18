package org.valkyrienskies.mod.mixin.client.renderer.chunk;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.valkyrienskies.mod.common.VS2ChunkAllocator;
import org.valkyrienskies.mod.common.config.VSGameConfig;

@Mixin(RenderChunkRegion.class)
public abstract class MixinRenderChunkRegion {
    @Shadow
    @Final
    int centerX;

    @Shadow
    @Final
    int centerZ;

    @Shadow
    Level level;

    // getShade is. Its actual implementation is in getLevel
    @WrapMethod(method = "getShade")
    private float maxShadeForShips(Direction direction, boolean bl, Operation<Float> original) {
        if (VSGameConfig.CLIENT.getNormalCoreShader() && VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(centerX, centerZ))
            return original.call(direction, false);
        return original.call(direction, bl);
    }
}
