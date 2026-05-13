package org.valkyrienskies.mod.common.command.arguments

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import org.valkyrienskies.core.api.ships.QueryableShipData
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.command.shipWorld
import java.util.concurrent.CompletableFuture

class ContraptionArgument private constructor(val selectorOnly: Boolean) : ArgumentType<ContraptionSelector?> {
    @Throws(CommandSyntaxException::class)
    override fun parse(pReader: StringReader): ContraptionSelector {
        val entityselectorparser = ContraptionSelectorParser(null, pReader)
        return entityselectorparser.parse(null)
    }

    /**
     * In vanilla, the only classes [S] will be are impls of [SharedSuggestionProvider].
     * Another mod might violate this, but hopefully null checks will handle it
     */
    override fun <S> listSuggestions(
        pContext: CommandContext<S>, pBuilder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {

        val reader = StringReader(pBuilder.input)
        reader.cursor = pBuilder.start

        val startsWithAt = reader.canRead() && reader.peek() == '@'

        // If context.source isn't an impl of SharedSuggestionProvider,
        // and is null, the parser will just not suggest anything
        val parser = ContraptionSelectorParser(pContext.source as? SharedSuggestionProvider, reader)

        try {
            parser.parse(pBuilder)
        } catch (_: CommandSyntaxException) {

        }

        // Reset cursor to fix suggestions
        if (!startsWithAt) {
            reader.cursor = pBuilder.start
        }

        val nBuilder = pBuilder.createOffset(reader.cursor)
        return parser.suggestionProvider(nBuilder)
    }

    override fun getExamples(): List<String> {
        return EXAMPLES
    }

    companion object {
        private val EXAMPLES = listOf("the-mogus", "@v", "@v[slug=the-mogus]")
        val ERROR_NOT_SINGLE_CONTRAPTION: SimpleCommandExceptionType =
            SimpleCommandExceptionType(Component.translatable("industriacore.argument.contraption.toomany"))

        @JvmStatic
        fun selectorOnly(): ContraptionArgument = ContraptionArgument(true)

        fun contraption(): ContraptionArgument {
            return ContraptionArgument(true)
        }

        fun contraptions(): ContraptionArgument {
            return ContraptionArgument(false)
        }

        @Throws(CommandSyntaxException::class)
        fun getContraptions(
            pContext: CommandContext<CommandSourceStack>, pName: String
        ): Sequence<Ship> {
            val shipWorld = pContext.source.shipWorld

            val fromLoadedShips = getOptionalContraptions(pContext, shipWorld.loadedShips, pName)
            val fromLoadedShipIds = fromLoadedShips.map { it.id }.toSet()

            val fromUnloadedShips = getOptionalContraptions(pContext, shipWorld.allShips, pName)

            // Return loaded ships and unloaded ships, do not return a loaded ship twice
            return fromLoadedShips + (fromUnloadedShips.filter { !fromLoadedShipIds.contains(it.id) })
        }

        @Throws(CommandSyntaxException::class)
        fun getOptionalContraptions(
            pContext: CommandContext<CommandSourceStack>, queryableShipData: QueryableShipData<Ship>, pName: String?
        ): Sequence<Ship> {
            return pContext.getArgument(pName, ContraptionSelector::class.java)
                .findContraptions(pContext.getSource(), queryableShipData)
        }
    }
}
