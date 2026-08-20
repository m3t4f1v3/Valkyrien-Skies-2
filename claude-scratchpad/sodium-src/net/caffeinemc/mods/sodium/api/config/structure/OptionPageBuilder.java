package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.class_2561;

/**
 * Builder interface for defining option pages, which are lists of option groups.
 */
public interface OptionPageBuilder extends PageBuilder {
    /**
     * Sets the name of the option page.
     *
     * @param name The name component.
     * @return The current builder instance.
     */
    OptionPageBuilder setName(class_2561 name);

    /**
     * Adds an option group to the option page.
     *
     * @param group The option group builder.
     * @return The current builder instance.
     */
    OptionPageBuilder addOptionGroup(OptionGroupBuilder group);

    /**
     * Adds an option directly to the option page. Options added directly to the page are grouped into an implicit unnamed option group at the bottom of the page.
     *
     * @param option The option to add.
     * @return The current builder instance.
     */
    OptionPageBuilder addOption(OptionBuilder option);
}
