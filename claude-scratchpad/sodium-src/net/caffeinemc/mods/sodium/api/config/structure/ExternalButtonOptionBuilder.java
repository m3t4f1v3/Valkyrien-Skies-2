package net.caffeinemc.mods.sodium.api.config.structure;

import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_437;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Builder interface for defining external button options.
 */
public interface ExternalButtonOptionBuilder extends OptionBuilder {
    @Override
    ExternalButtonOptionBuilder setName(class_2561 name);

    @Override
    ExternalButtonOptionBuilder setTooltip(class_2561 tooltip);

    @Override
    ExternalButtonOptionBuilder setEnabled(boolean available);

    @Override
    ExternalButtonOptionBuilder setEnabledProvider(Function<ConfigState, Boolean> provider, class_2960... dependencies);

    /** Sets the screen consumer for the external button option.
     *
     * @param currentScreenConsumer A consumer that accepts the current screen and opens the external configuration screen.
     * @return The current builder instance.
     */
    ExternalButtonOptionBuilder setScreenConsumer(Consumer<class_437> currentScreenConsumer);
}
