package org.valkyrienskies.mod.common.config

import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.fml.config.ModConfig
import org.valkyrienskies.core.impl.api_impl.config.VsiConfigModelImpl
import org.valkyrienskies.core.internal.config.VsiConfigModel
import org.valkyrienskies.core.internal.config.VsiConfigModelCategory
import org.valkyrienskies.core.internal.config.VsiConfigModelEntry
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import kotlin.collections.iterator

object VSConfigUpdater {

    private val core_server_config = ValkyrienSkiesMod.vsCore.getServerConfig()
    val CORE_SERVER_SPEC: ForgeConfigSpec = buildCategory(core_server_config.root, ForgeConfigSpec.Builder()).build()

    private val server_config = VsiConfigModelImpl.build(VSGameConfig.SERVER)
    val SERVER_SPEC: ForgeConfigSpec = buildCategory(server_config.root, ForgeConfigSpec.Builder()).build()

    private val common_config = VsiConfigModelImpl.build(VSGameConfig.COMMON)
    val COMMON_SPEC: ForgeConfigSpec = buildCategory(common_config.root, ForgeConfigSpec.Builder()).build()

    private val client_config = VsiConfigModelImpl.build(VSGameConfig.CLIENT)
    val CLIENT_SPEC: ForgeConfigSpec = buildCategory(client_config.root, ForgeConfigSpec.Builder()).build()

    /**
     * Call this from platform events when config is loaded or updated
     **/
    fun update(config: ModConfig) {
        core_server_config.update(config)
        server_config.update(config)
        common_config.update(config)
        client_config.update(config)
    }

    private fun buildCategory(configCategory: VsiConfigModelCategory, builder: ForgeConfigSpec.Builder ): ForgeConfigSpec.Builder {
        for (node in configCategory.children) {
            val entry = node.value
            if (entry is VsiConfigModelCategory) {
                builder.push(entry.title)
                buildCategory(entry, builder)
                builder.pop()
            } else if (entry is VsiConfigModelEntry<*>){
                fun <T> define(v: T) = builder.define<T>(entry.name, v)

                @Suppress("UNCHECKED_CAST")
                fun <T : Enum<T>> defineEnum(builder: ForgeConfigSpec.Builder, value: Enum<*>) {
                    builder.defineEnum(entry.name, value as T)
                }

                fun <T : Comparable<T>> defineNumeric(v: T) {
                    if (entry.min == null || entry.max == null) {
                        define(if (v is Float) v.toDouble() else v)
                    } else {
                        when (v) {
                            is Int -> builder.defineInRange(entry.name, v, entry.min as Int, entry.max as Int)
                            is Long -> builder.defineInRange(entry.name, v, entry.min as Long, entry.max as Long)
                            is Float -> builder.defineInRange(entry.name, v.toDouble(), (entry.min as Float).toDouble(), (entry.max as Float).toDouble())
                            is Double -> builder.defineInRange(entry.name, v, entry.min as Double, entry.max as Double)
                        }
                    }
                }

                entry.description?.let(builder::comment)

                when (val v = entry.getValue()) {
                    is Int -> defineNumeric(v)
                    is Long -> defineNumeric(v)
                    is Float -> defineNumeric(v)
                    is Double -> defineNumeric(v)
                    is Boolean -> define(v)
                    is String -> define(v)
                    is Enum<*> -> defineEnum(builder, v)
                    else -> {
                        throw IllegalArgumentException("invalid config type $v of class ${v?.javaClass}")
                    }
                }
            }
        }
        return builder
    }

    private fun VsiConfigModel.update(forgeConfig: ModConfig) {
        root.forEachEntry { path, node ->
            forgeConfig.configData.get<Any>("$path${node.name}")?.let { newValue ->
                val defaultValue = node.default
                if (defaultValue != null) {
                    val convertedValue = when {
                        // Handle Float stored as Double in Forge config
                        defaultValue is Float && newValue is Double -> newValue.toFloat()
                        defaultValue::class.isInstance(newValue) -> newValue
                        else -> null
                    }

                    if (convertedValue != null) {
                        @Suppress("UNCHECKED_CAST")
                        (node as VsiConfigModelEntry<Any>).setValue(convertedValue)
                    }
                }
            }
        }
    }

    private fun VsiConfigModelCategory.forEachEntry(path: String = "", callback: (String, VsiConfigModelEntry<*>) -> Unit) {
        for (node in children) {
            val value = node.value
            if (value is VsiConfigModelEntry<*>) {
                callback(path, value)
            } else if (value is VsiConfigModelCategory) {
                value.forEachEntry("$path${value.title}.", callback)
            }
        }
    }
}
