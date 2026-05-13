package org.valkyrienskies.mod.common.command.arguments

import com.google.common.collect.Maps
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.advancements.critereon.MinMaxBounds
import net.minecraft.advancements.critereon.WrappedMinMaxBounds
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.command.shipWorld
import java.util.function.Consumer
import java.util.function.Predicate

object ContraptionSelectorOptions {
    private val OPTIONS: MutableMap<String?, Option?> = Maps.newHashMap<String?, Option?>()
    val ERROR_UNKNOWN_OPTION: DynamicCommandExceptionType = DynamicCommandExceptionType { obj: Any? ->
        Component.translatable(
            "industriacore.argument.contraption.options.unknown", obj
        )
    }
    val ERROR_INAPPLICABLE_OPTION: DynamicCommandExceptionType = DynamicCommandExceptionType { obj: Any? ->
        Component.translatable(
            "industriacore.argument.contraption.options.inapplicable", obj
        )
    }
    val ERROR_RANGE_NEGATIVE: SimpleCommandExceptionType = SimpleCommandExceptionType(
        Component.translatable("industriacore.argument.contraption.options.distance.negative")
    )
    val ERROR_SIZE_NEGATIVE: SimpleCommandExceptionType =
        SimpleCommandExceptionType(Component.translatable("industriacore.argument.contraption.options.size.negative"))
    val ERROR_LIMIT_TOO_SMALL: SimpleCommandExceptionType =
        SimpleCommandExceptionType(Component.translatable("industriacore.argument.contraption.options.limit.toosmall"))
    val ERROR_SORT_UNKNOWN: DynamicCommandExceptionType = DynamicCommandExceptionType { obj: Any? ->
        Component.translatable(
            "industriacore.argument.contraption.options.sort.irreversible", obj
        )
    }

    fun register(
        pId: String?, pHandler: Modifier, pPredicate: Predicate<ContraptionSelectorParser>, pTooltip: Component? = null, autocomplete: ((SharedSuggestionProvider, SuggestionsBuilder) -> Unit)? = null
    ) {
        OPTIONS[pId] = Option(pHandler, pPredicate, description =  pTooltip, autocomplete = autocomplete)
    }

    fun bootStrap() {
        if (OPTIONS.isEmpty()) {
            register(
                "slug", { parser: ContraptionSelectorParser ->
                    val i = parser.reader.cursor
                    val flag = parser.shouldInvertValue()
                    val s = parser.reader.readString()
                    // Checks if we already have a slug=... or a slug=!...
                    if (((parser.slug != null) && !flag) || ((parser.notSlug != null) && flag)) {
                        parser.reader.cursor = i
                        throw ERROR_INAPPLICABLE_OPTION.createWithContext(parser.reader, "slug")
                    } else {
                        if (flag) {
                            parser.notSlug = s
                        } else {
                            parser.slug = s
                        }
                    }
                }, { parser: ContraptionSelectorParser -> (parser.slug == null) || (parser.notSlug == null) },
                Component.translatable("industriacore.argument.contraption.options.name.description"),
                { parser, builder ->
                    (parser as SharedSuggestionProvider).shipWorld.allShips
                        .mapNotNull { it.slug }
                        .filter { it.startsWith(builder.remaining) }
                        .forEach { builder.suggest(it) }
                }
            )
            register(
                "id", { parser: ContraptionSelectorParser ->
                    parser.id = parser.reader.readLong()
                }, { parser: ContraptionSelectorParser -> parser.id == null },
                Component.translatable("industriacore.argument.contraption.options.name.description"),
                { parser, builder ->
                    (parser as SharedSuggestionProvider).shipWorld.allShips
                        .mapNotNull { it.id.toString() }
                        .filter { it.startsWith(builder.remaining) }
                        .forEach { builder.suggest(it) }
                }
            )
            register(
                "distance", { parser: ContraptionSelectorParser ->
                    val i = parser.reader.cursor
                    val `minmaxbounds$doubles` = MinMaxBounds.Doubles.fromReader(parser.reader)
                    if ((`minmaxbounds$doubles`.getMin() == null || !(`minmaxbounds$doubles`.getMin()!! < 0.0)) && (`minmaxbounds$doubles`.getMax() == null || !(`minmaxbounds$doubles`.getMax()!! < 0.0))) {
                        parser.distance = `minmaxbounds$doubles`
                        parser.setWorldLimited()
                    } else {
                        parser.reader.cursor = i
                        throw ERROR_RANGE_NEGATIVE.createWithContext(parser.reader)
                    }
                }, { parser: ContraptionSelectorParser -> parser.distance.isAny },
                Component.translatable("industriacore.argument.contraption.options.distance.description")
            )
            register(
                "x", { parser: ContraptionSelectorParser ->
                    parser.setWorldLimited()
                    parser.x = parser.reader.readDouble()
                }, { parser: ContraptionSelectorParser -> parser.x == null },
                Component.translatable("industriacore.argument.contraption.options.x.description")
            )
            register(
                "y", { parser: ContraptionSelectorParser ->
                    parser.setWorldLimited()
                    parser.y = parser.reader.readDouble()
                }, { parser: ContraptionSelectorParser -> parser.y == null },
                Component.translatable("industriacore.argument.contraption.options.y.description")
            )
            register(
                "z", { parser: ContraptionSelectorParser ->
                    parser.setWorldLimited()
                    parser.z = parser.reader.readDouble()
                }, { parser: ContraptionSelectorParser -> parser.z == null },
                Component.translatable("industriacore.argument.contraption.options.z.description")
            )
            register(
                "dx", { parser: ContraptionSelectorParser ->
                    parser.setWorldLimited()
                    parser.deltaX = parser.reader.readDouble()
                }, { parser: ContraptionSelectorParser -> parser.deltaX == null },
                Component.translatable("industriacore.argument.contraption.options.dx.description")
            )
            register(
                "dy", { parser: ContraptionSelectorParser ->
                    parser.setWorldLimited()
                    parser.deltaY = parser.reader.readDouble()
                }, { parser: ContraptionSelectorParser -> parser.deltaY == null },
                Component.translatable("industriacore.argument.contraption.options.dy.description")
            )
            register(
                "dz", { parser: ContraptionSelectorParser ->
                    parser.setWorldLimited()
                    parser.deltaZ = parser.reader.readDouble()
                }, { parser: ContraptionSelectorParser -> parser.deltaZ == null },
                Component.translatable("industriacore.argument.contraption.options.dz.description")
            )
            register(
                "x_rotation", { parser: ContraptionSelectorParser ->
                    parser.rotX = WrappedMinMaxBounds.fromReader(
                        parser.reader, true
                    ) { f: Float -> Mth.wrapDegrees(f) }
                }, { parser: ContraptionSelectorParser -> parser.rotX === WrappedMinMaxBounds.ANY },
                Component.translatable("industriacore.argument.contraption.options.x_rotation.description")
            )
            register(
                "y_rotation", { parser: ContraptionSelectorParser ->
                    parser.rotY = WrappedMinMaxBounds.fromReader(
                        parser.reader, true
                    ) { f: Float -> Mth.wrapDegrees(f) }
                }, { parser: ContraptionSelectorParser -> parser.rotY === WrappedMinMaxBounds.ANY },
                Component.translatable("industriacore.argument.contraption.options.y_rotation.description")
            )
            register(
                "z_rotation", { parser: ContraptionSelectorParser ->
                    parser.rotZ = WrappedMinMaxBounds.fromReader(
                        parser.reader, true
                    ) { f: Float -> Mth.wrapDegrees(f) }
                }, { parser: ContraptionSelectorParser -> parser.rotZ === WrappedMinMaxBounds.ANY },
                Component.translatable("industriacore.argument.contraption.options.z_rotation.description")
            )

            register(
                "x_velocity", { parser: ContraptionSelectorParser ->
                    val `minmaxbounds$doubles` = MinMaxBounds.Doubles.fromReader(parser.reader)
                    parser.velocityX = `minmaxbounds$doubles`
                }, { parser: ContraptionSelectorParser -> parser.velocityX === MinMaxBounds.Doubles.ANY },
                Component.translatable("industriacore.argument.contraption.options.x_velocity.description")
            )
            register(
                "y_velocity", { parser: ContraptionSelectorParser ->
                    val `minmaxbounds$doubles` = MinMaxBounds.Doubles.fromReader(parser.reader)
                    parser.velocityY = `minmaxbounds$doubles`
                }, { parser: ContraptionSelectorParser -> parser.velocityY === MinMaxBounds.Doubles.ANY },
                Component.translatable("industriacore.argument.contraption.options.y_velocity.description")
            )
            register(
                "z_velocity", { parser: ContraptionSelectorParser ->
                    val `minmaxbounds$doubles` = MinMaxBounds.Doubles.fromReader(parser.reader)
                    parser.velocityZ = `minmaxbounds$doubles`
                }, { parser: ContraptionSelectorParser -> parser.velocityZ === MinMaxBounds.Doubles.ANY },
                Component.translatable("industriacore.argument.contraption.options.z_velocity.description")
            )

            register(
                "velocity", { parser: ContraptionSelectorParser ->
                    val i = parser.reader.cursor
                    val `minmaxbounds$doubles` = MinMaxBounds.Doubles.fromReader(parser.reader)
                    if ((`minmaxbounds$doubles`.getMin() == null || (`minmaxbounds$doubles`.getMin()!! >= 0.0)) && (`minmaxbounds$doubles`.getMax() == null || (`minmaxbounds$doubles`.getMax()!! >= 0.0))) {
                        parser.velocity = `minmaxbounds$doubles`
                    } else {
                        parser.reader.cursor = i
                        throw ERROR_RANGE_NEGATIVE.createWithContext(parser.reader)
                    }
                }, { parser: ContraptionSelectorParser -> parser.velocity === MinMaxBounds.Doubles.ANY },
                Component.translatable("industriacore.argument.contraption.options.velocity.description")
            )

            register(
                "x_omega", { parser: ContraptionSelectorParser ->
                    val `minmaxbounds$doubles` = MinMaxBounds.Doubles.fromReader(parser.reader)
                    parser.omegaX = `minmaxbounds$doubles`
                }, { parser: ContraptionSelectorParser -> parser.omegaX === MinMaxBounds.Doubles.ANY },
                Component.translatable("industriacore.argument.contraption.options.x_omega.description")
            )
            register(
                "y_omega", { parser: ContraptionSelectorParser ->
                    val `minmaxbounds$doubles` = MinMaxBounds.Doubles.fromReader(parser.reader)
                    parser.omegaY = `minmaxbounds$doubles`
                }, { parser: ContraptionSelectorParser -> parser.omegaY === MinMaxBounds.Doubles.ANY },
                Component.translatable("industriacore.argument.contraption.options.y_omega.description")
            )
            register(
                "z_omega", { parser: ContraptionSelectorParser ->
                    val `minmaxbounds$doubles` = MinMaxBounds.Doubles.fromReader(parser.reader)
                    parser.omegaZ = `minmaxbounds$doubles`
                }, { parser: ContraptionSelectorParser -> parser.omegaZ === MinMaxBounds.Doubles.ANY },
                Component.translatable("industriacore.argument.contraption.options.z_omega.description")
            )

            register(
                "omega", { parser: ContraptionSelectorParser ->
                    val i = parser.reader.cursor
                    val `minmaxbounds$doubles` = MinMaxBounds.Doubles.fromReader(parser.reader)
                    if ((`minmaxbounds$doubles`.getMin() == null || (`minmaxbounds$doubles`.getMin()!! >= 0.0)) && (`minmaxbounds$doubles`.getMax() == null || (`minmaxbounds$doubles`.getMax()!! >= 0.0))) {
                        parser.omega = `minmaxbounds$doubles`
                    } else {
                        parser.reader.cursor = i
                        throw ERROR_RANGE_NEGATIVE.createWithContext(parser.reader)
                    }
                }, { parser: ContraptionSelectorParser -> parser.omega === MinMaxBounds.Doubles.ANY },
                Component.translatable("industriacore.argument.contraption.options.omega.description")
            )

            register(
                "size", { parser: ContraptionSelectorParser ->
                    val i = parser.reader.cursor
                    val `minmaxbounds$ints` = MinMaxBounds.Doubles.fromReader(parser.reader)
                    if ((`minmaxbounds$ints`.getMin() == null || (`minmaxbounds$ints`.getMin()!! >= 0)) && (`minmaxbounds$ints`.getMax() == null || (`minmaxbounds$ints`.getMax()!! >= 0))) {
                        parser.size = `minmaxbounds$ints`
                    } else {
                        parser.reader.cursor = i
                        throw ERROR_RANGE_NEGATIVE.createWithContext(parser.reader)
                    }
                }, { parser: ContraptionSelectorParser -> parser.size === MinMaxBounds.Doubles.ANY },
                Component.translatable("industriacore.argument.contraption.options.size.description")
            )

            register(
                "static", { parser: ContraptionSelectorParser ->
                    val isStatic = parser.reader.readBoolean()

                    parser.isStatic = isStatic
                }, { parser: ContraptionSelectorParser -> parser.isStatic != null },
                Component.translatable("industriacore.argument.contraption.options.static.description")
            )

            register(
                "mass", { parser: ContraptionSelectorParser ->
                    val `minmaxbounds$doubles` = MinMaxBounds.Doubles.fromReader(parser.reader)
                    parser.mass = `minmaxbounds$doubles`
                }, { parser: ContraptionSelectorParser -> parser.mass === MinMaxBounds.Doubles.ANY },
                Component.translatable("industriacore.argument.contraption.options.mass.description")
            )


            register(
                "limit", { parser: ContraptionSelectorParser ->
                    val i = parser.reader.cursor
                    val j = parser.reader.readInt()
                    if (j < 1) {
                        parser.reader.cursor = i
                        throw ERROR_LIMIT_TOO_SMALL.createWithContext(parser.reader)
                    } else {
                        parser.setMaxResults(j)
                        parser.hasAmountLimit = true
                    }
                },
                { parser: ContraptionSelectorParser -> !parser.hasAmountLimit },
                Component.translatable("industriacore.argument.contraption.options.limit.description")
            )
            register(
                "sort", { parser: ContraptionSelectorParser ->
                    val i = parser.reader.cursor
                    val s = parser.reader.readUnquotedString()

                    val biconsumer: (Vec3, Sequence<Ship>) -> Sequence<Ship>
                    when (s) {
                        "nearest" -> biconsumer = ContraptionSelectorParser.ORDER_NEAREST
                        "furthest" -> biconsumer = ContraptionSelectorParser.ORDER_FURTHEST
                        "random" -> biconsumer = ContraptionSelectorParser.ORDER_RANDOM
                        else -> {
                            parser.reader.cursor = i
                            throw ERROR_SORT_UNKNOWN.createWithContext(parser.reader, s)
                        }
                    }

                    parser.customSort = biconsumer
                },
                { parser: ContraptionSelectorParser -> parser.customSort == ContraptionSelector.ORDER_ARBITRARY },
                Component.translatable("industriacore.argument.contraption.options.sort.description"),
                { provider, builder ->
                    SharedSuggestionProvider.suggest(
                        mutableListOf("nearest", "furthest", "random"), builder
                    )
                }
            )
        }
    }

    @Throws(CommandSyntaxException::class)
    fun get(pParser: ContraptionSelectorParser, pId: String?, pCursor: Int): Modifier {
        val `entityselectoroptions$option` = OPTIONS[pId]
        if (`entityselectoroptions$option` != null) {
            if (`entityselectoroptions$option`.canUse.test(pParser)) {
                return `entityselectoroptions$option`.modifier
            } else {
                throw ERROR_INAPPLICABLE_OPTION.createWithContext(pParser.reader, pId)
            }
        } else {
            pParser.reader.cursor = pCursor
            throw ERROR_UNKNOWN_OPTION.createWithContext(pParser.reader, pId)
        }
    }

    /**
     * Suggests the names for selector options, e.g. slug=, id=, etc
     */
    fun suggestNames(pParser: ContraptionSelectorParser, pBuilder: SuggestionsBuilder) {
        val s = pBuilder.remaining.lowercase()

        for (entry in OPTIONS.entries) {
            if ((entry.value)!!.canUse.test(pParser) && entry.key!!.lowercase().startsWith(s)) {
                pBuilder.suggest(entry.key as String + "=", (entry.value)!!.description)
            }
        }
    }

    /**
     * Suggests a value for an option if that option registered with an autocomplete provider.
     * For example, if the user has typed slug=, this function will suggest the actual slugs.
     */
    fun suggestOptionValue(provider: SharedSuggestionProvider, pBuilder: SuggestionsBuilder): SuggestionsBuilder? {
        for (entry in OPTIONS.entries) {
            val valueAutocomplete = entry.value!!.autocomplete ?: continue
            val split = pBuilder.input.lowercase().split("=")
            if (split.size < 2) continue

            if (split[split.size - 2].endsWith(entry.key!!)) {
                val offset = pBuilder.input.lastIndexOf('=') + 1
                val builder = pBuilder.createOffset(offset)

                valueAutocomplete(provider, builder)
                return builder
            }
        }
        return null
    }

    fun interface Modifier {
        @Throws(CommandSyntaxException::class)
        fun handle(pParser: ContraptionSelectorParser)
    }

    @JvmRecord
    internal data class Option(
        val modifier: Modifier, val canUse: Predicate<ContraptionSelectorParser>, val description: Component? = null, val autocomplete: ((SharedSuggestionProvider, SuggestionsBuilder) -> Unit)? = null
    )
}
