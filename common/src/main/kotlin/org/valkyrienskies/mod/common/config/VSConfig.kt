package org.valkyrienskies.mod.common.config

import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.fml.config.ModConfig
import org.valkyrienskies.core.impl.api_impl.config.VsiConfigModelImpl
import org.valkyrienskies.core.internal.config.VsiConfigModel
import org.valkyrienskies.core.internal.config.VsiConfigModelCategory
import org.valkyrienskies.core.internal.config.VsiConfigModelEntry
import org.valkyrienskies.mod.common.ValkyrienSkiesMod

object VSConfig {

    private val core_server_config = ValkyrienSkiesMod.vsCore.getServerConfig()
    val CORE_SERVER_SPEC: ForgeConfigSpec = buildCategory(core_server_config.root, ForgeConfigSpec.Builder()).build()

    private val server_config = VsiConfigModelImpl.build(VSGameConfig.SERVER, "VS Server")
    val SERVER_SPEC: ForgeConfigSpec = buildCategory(server_config.root, ForgeConfigSpec.Builder()).build()

    private val common_config = VsiConfigModelImpl.build(VSGameConfig.COMMON, "VS Common")
    val COMMON_SPEC: ForgeConfigSpec = buildCategory(common_config.root, ForgeConfigSpec.Builder()).build()

    private val client_config = VsiConfigModelImpl.build(VSGameConfig.CLIENT, "VS Client")
    val CLIENT_SPEC: ForgeConfigSpec = buildCategory(client_config.root, ForgeConfigSpec.Builder()).build()

    private fun buildCategory(configCategory: VsiConfigModelCategory, builder: ForgeConfigSpec.Builder ): ForgeConfigSpec.Builder {
        for (node in configCategory.children) {
            val entry = node.value
            if (entry is VsiConfigModelCategory) {
                builder.push(entry.title)
                buildCategory(entry, builder)
                builder.pop()
            } else if (entry is VsiConfigModelEntry<*>){
                fun <T> define(v: T) = builder.define<T>(entry.name, v)

                builder.comment(entry.description)

                when (val v = entry.getValue()) {
                    is Int -> builder.defineInRange(entry.name, v, entry.min as Int, entry.max as Int)
                    is Long -> builder.defineInRange(entry.name, v, entry.min as Long, entry.max as Long)
                    is Float -> builder.defineInRange(entry.name, v.toDouble(), (entry.min as Float).toDouble(), (entry.max as Float).toDouble())
                    is Double -> builder.defineInRange(entry.name, v, entry.min as Double, entry.max as Double)
                    is Boolean -> define(v)
                    is String -> define(v)
                    is Enum<*> -> {
                        // Use define with enum validator since defineEnum requires specific generic type
                        val enumClass = v.javaClass
                        @Suppress("UNCHECKED_CAST")
                        val enumValues = enumClass.enumConstants as Array<Enum<*>>
                        builder.define(entry.name, v) { obj ->
                            obj is Enum<*> && enumValues.contains(obj)
                        }
                    }
                    else -> {
                        throw IllegalArgumentException("invalid config type $v of class ${v?.javaClass}")
                    }
                }
            }
        }
        return builder
    }


    fun update(config: ModConfig) {
        core_server_config.update(config)
        common_config.update(config)
        client_config.update(config)
    }

    private fun VsiConfigModel.update(forgeConfig: ModConfig) {
        forEachEntry { path, node ->
            forgeConfig.configData.get<Any>("$path${node.name}")?.let { newValue ->
                if (node.default != null) {
                    val convertedValue = when {
                        // Handle Float stored as Double in Forge config
                        node.default is Float && newValue is Double -> newValue.toFloat()
                        node.default!!::class.isInstance(newValue) -> newValue
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
}
