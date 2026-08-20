package net.caffeinemc.mods.sodium.api.config.structure;

import java.util.function.Consumer;
import net.minecraft.class_2561;
import net.minecraft.class_437;

/**
 * Builder interface for defining external configuration pages.
 */
public interface ExternalPageBuilder extends PageBuilder {
    /**
     * Sets the name of the external configuration page.
     *
     * @param name The name component.
     * @return The current builder instance.
     */
    ExternalPageBuilder setName(class_2561 name);

    /**
     * Sets the screen provider for the external configuration page.
     *
     * @param currentScreenConsumer A consumer that accepts the current screen and opens the external configuration screen.
     * @return The current builder instance.
     */
    ExternalPageBuilder setScreenConsumer(Consumer<class_437> currentScreenConsumer);
}
