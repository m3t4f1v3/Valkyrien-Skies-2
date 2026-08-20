package net.caffeinemc.mods.sodium.api.config.option;

import net.minecraft.class_124;
import net.minecraft.class_2561;

/**
 * Represents the performance impact level of a configuration option.
 */
public enum OptionImpact implements NameProvider {
    /**
     * Low impact on performance. Changing this option won't affect performance in a measurable or noticeable way.
     */
    LOW(class_124.field_1060, "sodium.option_impact.low"),
    
    /**
     * Medium impact on performance. Changing this option may have a noticeable effect on performance in some scenarios and some systems.
     */
    MEDIUM(class_124.field_1054, "sodium.option_impact.medium"),
    
    /**
     * High impact on performance. Changing this option will likely have a significant effect on performance in most scenarios.
     */
    HIGH(class_124.field_1065, "sodium.option_impact.high"),
    
    /**
     * Varies in impact on performance. The effect of changing this option on performance is highly dependent on the specific scenario and system.
     */
    VARIES(class_124.field_1068, "sodium.option_impact.varies");

    private final class_2561 text;

    OptionImpact(class_124 formatting, String text) {
        this.text = class_2561.method_43471(text)
                .method_27692(formatting);
    }

    @Override
    public class_2561 getName() {
        return this.text;
    }
}
