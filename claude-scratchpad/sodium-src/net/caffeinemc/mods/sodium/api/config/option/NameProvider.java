package net.caffeinemc.mods.sodium.api.config.option;

import net.minecraft.class_2561;

/**
 * Base interface extended by enums whose members can provide display names.
 */
public interface NameProvider {
    /**
     * Gets the display name of this item.
     *
     * @return the display name
     */
    class_2561 getName();
}
