package org.valkyrienskies.mod.common.command.arguments

import com.google.gson.JsonObject
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.synchronization.ArgumentTypeInfo
import net.minecraft.network.FriendlyByteBuf
import org.valkyrienskies.mod.common.command.arguments.ContraptionArgument.Companion

internal class ContraptionArgumentInfo : ArgumentTypeInfo<ContraptionArgument, ContraptionArgumentInfoTemplate> {
    override fun serializeToNetwork(template: ContraptionArgumentInfoTemplate, friendlyByteBuf: FriendlyByteBuf) {
        friendlyByteBuf.writeBoolean(template.selectorOnly)
    }

    override fun deserializeFromNetwork(friendlyByteBuf: FriendlyByteBuf): ContraptionArgumentInfoTemplate {
        return ContraptionArgumentInfoTemplate(
            this, friendlyByteBuf.readBoolean()
        )
    }

    override fun unpack(argumentType: ContraptionArgument): ContraptionArgumentInfoTemplate {
        return ContraptionArgumentInfoTemplate(this, argumentType.selectorOnly)
    }

    override fun serializeToJson(template: ContraptionArgumentInfoTemplate, jsonObject: JsonObject) {
        jsonObject.addProperty("selectorOnly", template.selectorOnly)
    }
}

internal class ContraptionArgumentInfoTemplate(private val info: ContraptionArgumentInfo, internal val selectorOnly: Boolean) :
    ArgumentTypeInfo.Template<ContraptionArgument> {
    override fun instantiate(commandBuildContext: CommandBuildContext): ContraptionArgument {
        return if (selectorOnly) {
            ContraptionArgument.selectorOnly()
        } else {
            Companion.contraptions()
        }
    }

    override fun type(): ArgumentTypeInfo<ContraptionArgument, *> {
        return info
    }
}
