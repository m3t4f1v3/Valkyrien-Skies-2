package net.caffeinemc.mods.sodium.api.blockentity;

import net.minecraft.class_1922;
import net.minecraft.class_2338;
import net.minecraft.class_2586;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Experimental
@ApiStatus.AvailableSince("0.6.0")
@FunctionalInterface
public interface BlockEntityRenderPredicate<T extends class_2586> {
    boolean shouldRender(class_1922 blockGetter, class_2338 blockPos, T entity);
}
