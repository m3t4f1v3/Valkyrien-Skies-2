package org.valkyrienskies.mod.common.config

import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.fml.config.ModConfig
import org.valkyrienskies.core.internal.config.ConfigModelCategory
import org.valkyrienskies.core.internal.config.ConfigModelEntry
import org.valkyrienskies.core.internal.config.VSConfigModel
import org.valkyrienskies.mod.common.ValkyrienSkiesMod

object VSConfig {

    private val core_server_config = ValkyrienSkiesMod.vsCore.getServerConfig()
    val CORE_SERVER_SPEC: ForgeConfigSpec = buildCategory(core_server_config.root, ForgeConfigSpec.Builder()).build()

    private fun buildCategory(configCategory: ConfigModelCategory, builder: ForgeConfigSpec.Builder ): ForgeConfigSpec.Builder {
        for (node in configCategory.children) {
            val entry = node.value
            if (entry is ConfigModelCategory) {
                builder.push(entry.title)
                buildCategory(entry, builder)
                builder.pop()
            } else if (entry is ConfigModelEntry<*>){
                fun <T> define(v: T) = builder.define<T>(entry.name, v)

                when (val v = entry.value) {
                    is Int -> builder.defineInRange(entry.name, v, entry.min as Int, entry.max as Int)
                    is Double -> builder.defineInRange(entry.name, v, entry.min as Double, entry.max as Double)
                    is Boolean -> define(v)
                    is String -> define(v)
                    else -> {
                        throw IllegalArgumentException("invalid config type $v")
                    }
                }
            }
        }
        return builder
    }


    fun update(config: ModConfig) {
        core_server_config.update(config)
    }
}

internal fun VSConfigModel.update(forgeConfig: ModConfig) {
    forEachEntry { path, node ->
        forgeConfig.configData.get<Any>("$path${node.name}")?.let { newValue ->
            if (node.default != null && node.default!!::class.isInstance(newValue)) {
                @Suppress("UNCHECKED_CAST")
                (node as ConfigModelEntry<Any>).setValue(newValue)
            }
        }
    }
}
