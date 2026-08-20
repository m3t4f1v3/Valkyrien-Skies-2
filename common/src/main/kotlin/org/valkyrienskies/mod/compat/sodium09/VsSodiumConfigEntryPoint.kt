package org.valkyrienskies.mod.compat.sodium09

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.common.ForgeConfigSpec
import org.valkyrienskies.core.internal.config.VsiConfigModelCategory
import org.valkyrienskies.core.internal.config.VsiConfigModelEntry
import org.valkyrienskies.mod.api.config.VSConfigApi
import org.valkyrienskies.mod.common.config.VSConfigUpdater
import org.valkyrienskies.mod.common.config.VSGameConfig
import java.util.Locale

/**
 * VS's page in Sodium 0.9's video settings screen.
 *
 * Sodium 0.9 removed `OptionImpl`, `OptionStorage` and `SodiumOptionsGUI` — the classes the 0.5-era
 * [org.valkyrienskies.mod.compat.SodiumOptionsMenu] builds against, and the screen its mixin injected
 * into. In their place there is a supported config API, so this registers through that instead of mixing
 * into the GUI at all: Sodium discovers the class through the `sodium:config_api_user` entrypoint (in
 * `fabric.mod.json`) or the [ConfigEntryPointForge] annotation below, and never loads it when it isn't
 * installed.
 *
 * The option tree itself is the same one the 0.5 menu builds — VS's own config model, with each category
 * becoming a group and each leaf an option — so the two screens stay in sync by construction.
 */
@ConfigEntryPointForge("valkyrienskies")
class VsSodiumConfigEntryPoint : ConfigEntryPoint {

    override fun registerConfigLate(builder: ConfigBuilder) {
        val page = builder.createOptionPage()
            .setName(Component.translatable("itemGroup.valkyrienSkies"))

        val clientConfig = VSConfigApi.buildVSConfigModel(VSGameConfig.CLIENT)
        addCategory(builder, clientConfig.root, page::addOptionGroup)

        builder.registerOwnModOptions().addPage(page)
    }

    private fun addCategory(
        builder: ConfigBuilder,
        category: VsiConfigModelCategory,
        addGroup: (OptionGroupBuilder) -> Unit
    ) {
        val group = builder.createOptionGroup()
        var hasOptions = false

        for (node in category.children) {
            when (val entry = node.value) {
                is VsiConfigModelCategory -> addCategory(builder, entry, addGroup)
                is VsiConfigModelEntry<*> -> defineOption(builder, entry)?.let {
                    group.addOption(it)
                    hasOptions = true
                }
            }
        }

        // A group with no options renders as an empty box, and Sodium has no notion of a "hidden" group.
        if (hasOptions) {
            addGroup(group)
        }
    }

    private fun defineOption(builder: ConfigBuilder, entry: VsiConfigModelEntry<*>): OptionBuilder? =
        when (entry.getValue()) {
            is Boolean -> defineBoolean(builder, entry)
            is Int -> defineInt(builder, entry)
            is Enum<*> -> defineEnum(builder, entry)
            else -> null
        }

    private fun defineBoolean(builder: ConfigBuilder, entry: VsiConfigModelEntry<*>): OptionBuilder =
        builder.createBooleanOption(entry.optionId)
            .setName(Component.literal(entry.fancyName))
            .setTooltip(Component.literal(entry.tooltipText))
            .setStorageHandler(STORAGE_HANDLER)
            .setDefaultValue(entry.default as? Boolean ?: entry.getValue() as Boolean)
            .setBinding({ value -> setConfigOption(entry, value) }, { entry.getValue() as Boolean })

    private fun defineInt(builder: ConfigBuilder, entry: VsiConfigModelEntry<*>): OptionBuilder {
        @Suppress("UNCHECKED_CAST")
        val intEntry = entry as VsiConfigModelEntry<Int>
        val min = intEntry.min ?: 0
        val max = intEntry.max ?: 10
        return builder.createIntegerOption(entry.optionId)
            .setName(Component.literal(entry.fancyName))
            .setTooltip(Component.literal(entry.tooltipText))
            .setStorageHandler(STORAGE_HANDLER)
            .setRange(min, max, 1)
            .setValueFormatter(ControlValueFormatter { value -> Component.literal(value.toString()) })
            .setDefaultValue(intEntry.default ?: intEntry.getValue())
            .setBinding({ value -> setConfigOption(entry, value) }, { intEntry.getValue() })
    }

    private fun <E : Enum<E>> defineEnum(builder: ConfigBuilder, entry: VsiConfigModelEntry<*>): OptionBuilder {
        @Suppress("UNCHECKED_CAST")
        val enumClass = entry.getValue()!!::class.java as Class<E>
        @Suppress("UNCHECKED_CAST")
        val current = entry.getValue() as E
        @Suppress("UNCHECKED_CAST")
        val default = entry.default as? E ?: current

        return builder.createEnumOption(entry.optionId, enumClass)
            .setName(Component.literal(entry.fancyName))
            .setTooltip(Component.literal(entry.tooltipText))
            .setStorageHandler(STORAGE_HANDLER)
            .setElementNameProvider { element -> Component.literal(element.name) }
            .setDefaultValue(default)
            .setBinding({ value -> setConfigOption(entry, value) }, {
                @Suppress("UNCHECKED_CAST")
                entry.getValue() as E
            })
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> setConfigOption(entry: VsiConfigModelEntry<*>, value: T) {
        // Write to the forge config
        (VSConfigUpdater.forgeConfigValuesMap[entry.name] as ForgeConfigSpec.ConfigValue<T>).set(value)

        // Write to the in-memory config to avoid race conditions with the forge config reloaded event
        (entry as VsiConfigModelEntry<T>).setValue.invoke(value)
    }

    companion object {
        /**
         * Each binding writes straight through to the Forge config, which persists itself, so there is
         * nothing left to flush — but the API requires a handler, so this is an explicit no-op rather
         * than an omission.
         */
        private val STORAGE_HANDLER = net.caffeinemc.mods.sodium.api.config.StorageEventHandler { }
    }
}

/**
 * Option ids have to be unique across every mod registering with Sodium, so they are namespaced under
 * `valkyrienskies` and derived from the config entry's own name.
 */
private val VsiConfigModelEntry<*>.optionId: ResourceLocation
    get() = ResourceLocation("valkyrienskies", this.name.snakeCase())

/** Sodium rejects a blank tooltip, so fall back to the display name when an entry has no description. */
private val VsiConfigModelEntry<*>.tooltipText: String
    get() = this.description?.takeIf { it.isNotBlank() } ?: this.fancyName

private val VsiConfigModelEntry<*>.fancyName: String
    get() = this.name
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replaceFirstChar { it.uppercase() }

private fun String.snakeCase(): String =
    this.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase(Locale.ROOT)
