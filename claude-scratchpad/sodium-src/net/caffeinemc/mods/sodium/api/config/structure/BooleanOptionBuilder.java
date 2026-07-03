package net.caffeinemc.mods.sodium.api.config.structure;

import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionBinding;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Builder interface for defining boolean options. Refines builder methods to return this class instead of the base interface and have a {@link Boolean} value type.
 */
public interface BooleanOptionBuilder extends StatefulOptionBuilder<Boolean> {
    @Override
    BooleanOptionBuilder setName(class_2561 name);

    @Override
    BooleanOptionBuilder setEnabled(boolean available);

    @Override
    BooleanOptionBuilder setEnabledProvider(Function<ConfigState, Boolean> provider, class_2960... dependencies);

    @Override
    BooleanOptionBuilder setStorageHandler(StorageEventHandler storage);

    @Override
    BooleanOptionBuilder setTooltip(class_2561 tooltip);

    @Override
    BooleanOptionBuilder setTooltip(Function<Boolean, class_2561> tooltip);

    @Override
    BooleanOptionBuilder setImpact(OptionImpact impact);

    @Override
    BooleanOptionBuilder setFlags(OptionFlag... flags);

    @Override
    BooleanOptionBuilder setFlags(class_2960... flags);

    @Override
    BooleanOptionBuilder setDefaultValue(Boolean value);

    @Override
    BooleanOptionBuilder setDefaultProvider(Function<ConfigState, Boolean> provider, class_2960... dependencies);

    @Override
    BooleanOptionBuilder setControlHiddenWhenDisabled(boolean hidden);

    @Override
    BooleanOptionBuilder setBinding(Consumer<Boolean> save, Supplier<Boolean> load);

    @Override
    BooleanOptionBuilder setBinding(OptionBinding<Boolean> binding);

    @Override
    BooleanOptionBuilder setApplyHook(Consumer<ConfigState> hook);
}
