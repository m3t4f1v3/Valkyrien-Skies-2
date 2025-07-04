package org.valkyrienskies.mod.mixin.feature.enchantment_table;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EnchantmentTableBlock;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

@Mixin(EnchantmentTableBlock.class)
public class MixinEnchantmentTableBlock {
    @WrapMethod(method = "isValidBookShelf")
    private static boolean alsoCheckWorldBookShelf(Level level, BlockPos blockPos, BlockPos offset,
        Operation<Boolean> original) {
        Vec3 worldCoordinates = VectorConversionsMCKt.toMinecraft(VSGameUtilsKt.getWorldCoordinates(level, blockPos, VectorConversionsMCKt.toJOML(blockPos.getCenter())));
        BlockPos worldBlockPos = BlockPos.containing(worldCoordinates);
        return original.call(level, blockPos, offset) || original.call(level, worldBlockPos, offset);
    }
}
