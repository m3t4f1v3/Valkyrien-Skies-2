package org.valkyrienskies.mod.common.command.arguments

import com.google.common.primitives.Doubles
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.advancements.critereon.MinMaxBounds
import net.minecraft.advancements.critereon.WrappedMinMaxBounds
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.api.toJOML
import org.valkyrienskies.mod.common.command.arguments.ContraptionSelectorOptions.suggestOptionValue
import java.util.concurrent.CompletableFuture
import java.util.function.BiFunction
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate

class ContraptionSelectorParser(
    private val source: SharedSuggestionProvider?,
    val reader: StringReader
) {
    var suggestionProvider: (SuggestionsBuilder) -> CompletableFuture<Suggestions> = { it.buildFuture() }

    var amountLimit = 0
    var hasAmountLimit: Boolean = false

    var needsSpecificLevel = false

    var mass: MinMaxBounds.Doubles = MinMaxBounds.Doubles.ANY
    var size: MinMaxBounds.Doubles = MinMaxBounds.Doubles.ANY
    var isStatic: Boolean? = null

    var velocityX: MinMaxBounds.Doubles = MinMaxBounds.Doubles.ANY
    var velocityY: MinMaxBounds.Doubles = MinMaxBounds.Doubles.ANY
    var velocityZ: MinMaxBounds.Doubles = MinMaxBounds.Doubles.ANY
    var velocity: MinMaxBounds.Doubles = MinMaxBounds.Doubles.ANY

    var omegaX: MinMaxBounds.Doubles = MinMaxBounds.Doubles.ANY
    var omegaY: MinMaxBounds.Doubles = MinMaxBounds.Doubles.ANY
    var omegaZ: MinMaxBounds.Doubles = MinMaxBounds.Doubles.ANY
    var omega: MinMaxBounds.Doubles = MinMaxBounds.Doubles.ANY

    var distance: MinMaxBounds.Doubles = MinMaxBounds.Doubles.ANY
    var x: Double? = null
    var y: Double? = null
    var z: Double? = null
    var deltaX: Double? = null
    var deltaY: Double? = null
    var deltaZ: Double? = null
    var rotX: WrappedMinMaxBounds = WrappedMinMaxBounds.ANY
    var rotY: WrappedMinMaxBounds = WrappedMinMaxBounds.ANY
    var rotZ: WrappedMinMaxBounds = WrappedMinMaxBounds.ANY
    private var predicate = Predicate { _: ServerShip -> true }

    var customSort: (Vec3, Sequence<Ship>) -> Sequence<Ship> = ContraptionSelector.ORDER_ARBITRARY

    var slug: String? = null
    var notSlug: String? = null
    var id: Long? = null

    private var startingCursorIndex = 0

    fun shouldInvertValue(): Boolean {
        this.reader.skipWhitespace()
        if (this.reader.canRead() && this.reader.peek() == '!') {
            this.reader.skip()
            this.reader.skipWhitespace()
            return true
        } else {
            return false
        }
    }

    fun setWorldLimited() {
        this.needsSpecificLevel = true
    }


    fun setMaxResults(pMaxResults: Int) {
        this.amountLimit = pMaxResults
    }


    private fun suggest(block: (SuggestionsBuilder, SharedSuggestionProvider) -> Unit) {
        if (source != null) suggestionProvider = { block(it, source); it.buildFuture() }
    }

    val selector: ContraptionSelector
        get() {
            val aabb: AABB?
            if (this.deltaX == null && this.deltaY == null && this.deltaZ == null) {
                if (this.distance.getMax() != null) {
                    val d0: Double = this.distance.getMax()!!
                    aabb = AABB(-d0, -d0, -d0, d0 + 1.0, d0 + 1.0, d0 + 1.0)
                } else {
                    aabb = null
                }
            } else {
                aabb = this.createAabb(
                    (if (this.deltaX == null) 0.0 else this.deltaX)!!,
                    (if (this.deltaY == null) 0.0 else this.deltaY)!!, (if (this.deltaZ == null) 0.0 else this.deltaZ)!!
                )
            }

            val posFunc: Function<Vec3, Vec3>
            if (this.x == null && this.y == null && this.z == null) {
                posFunc = Function { pos: Vec3 -> pos }
            } else {
                posFunc = Function { pos: Vec3 ->
                    Vec3(
                        (if (this.x == null) pos.x else this.x)!!, (if (this.y == null) pos.y else this.y)!!,
                        (if (this.z == null) pos.z else this.z)!!
                    )
                }
            }

            return ContraptionSelector(
                this.amountLimit,
                this.needsSpecificLevel,
                this.predicate,
                this.distance,
                this.mass,
                this.size,
                posFunc,
                aabb,
                this.customSort,
                this.isStatic
            )
        }

    private fun createAabb(pSizeX: Double, pSizeY: Double, pSizeZ: Double): AABB {
        val flag = pSizeX < 0.0
        val flag1 = pSizeY < 0.0
        val flag2 = pSizeZ < 0.0
        val d0 = if (flag) pSizeX else 0.0
        val d1 = if (flag1) pSizeY else 0.0
        val d2 = if (flag2) pSizeZ else 0.0
        val d3 = (if (flag) 0.0 else pSizeX) + 1.0
        val d4 = (if (flag1) 0.0 else pSizeY) + 1.0
        val d5 = (if (flag2) 0.0 else pSizeZ) + 1.0
        return AABB(d0, d1, d2, d3, d4, d5)
    }

    fun finalizePredicates() {
        if (this.slug != null) {
            this.predicate = this.predicate.and { ship ->
                ship.slug == this.slug
            }
        }

        if (this.notSlug != null) {
            this.predicate = this.predicate.and { ship ->
                ship.slug != this.notSlug
            }
        }

        if (this.id != null) {
            this.predicate = this.predicate.and { ship ->
                ship.id == this.id
            }
        }

        if (this.rotX !== WrappedMinMaxBounds.ANY) {
            this.predicate = this.predicate.and(this.createRotationPredicate(this.rotX) { ship ->
                ship.transform.shipToWorldRotation.x()
            })
        }

        if (this.rotY !== WrappedMinMaxBounds.ANY) {
            this.predicate = this.predicate.and(this.createRotationPredicate(this.rotY) { ship ->
                ship.transform.shipToWorldRotation.y()
            })
        }

        if (this.rotY !== WrappedMinMaxBounds.ANY) {
            this.predicate = this.predicate.and(this.createRotationPredicate(this.rotZ) { ship ->
                ship.transform.shipToWorldRotation.z()
            })
        }

        if (this.velocity !== MinMaxBounds.Doubles.ANY) {
            this.predicate = this.predicate.and(Predicate { contraption: Ship ->
                this.velocity.matches(
                    contraption.velocity.length()
                )
            })
        }

        if (this.velocityX !== MinMaxBounds.Doubles.ANY || this.velocityY !== MinMaxBounds.Doubles.ANY || this.velocityZ !== MinMaxBounds.Doubles.ANY) {
            this.predicate = this.predicate.and(Predicate { contraption: Ship ->
                velocityX.matches(contraption.velocity.x()) &&
                    velocityY.matches(contraption.velocity.y()) &&
                    velocityZ.matches(contraption.velocity.z())
            })
        }

        if (this.omega !== MinMaxBounds.Doubles.ANY) {
            this.predicate = this.predicate.and(Predicate { contraption: Ship ->
                this.omega.matches(
                    Math.toDegrees(contraption.angularVelocity.length())
                )
            })
        }

        if (this.omegaX !== MinMaxBounds.Doubles.ANY || this.omegaY !== MinMaxBounds.Doubles.ANY || this.omegaZ !== MinMaxBounds.Doubles.ANY) {
            this.predicate = this.predicate.and(Predicate { contraption: Ship ->
                omegaX.matches(contraption.angularVelocity.x() / (2.0 * Math.PI)) &&
                    omegaY.matches(contraption.angularVelocity.y() / (2.0 * Math.PI)) &&
                    omegaZ.matches(contraption.angularVelocity.z() / (2.0 * Math.PI))
            })
        }
    }

    private fun createRotationPredicate(
        pAngleBounds: WrappedMinMaxBounds, pAngleFunction: (Ship) -> Double
    ): Predicate<Ship> {
        val d0 = Mth.wrapDegrees((pAngleBounds.min ?: 0.0f)).toDouble()
        val d1 = Mth.wrapDegrees((pAngleBounds.max ?: 359.0f)).toDouble()
        return Predicate { contraption: Ship ->
            val d2 = Mth.wrapDegrees(pAngleFunction(contraption))
            if (d0 > d1) {
                return@Predicate d2 >= d0 || d2 <= d1
            } else {
                return@Predicate d2 in d0..d1
            }
        }
    }

    @Throws(CommandSyntaxException::class)
    fun parse(pBuilder: SuggestionsBuilder?): ContraptionSelector {
        this.startingCursorIndex = this.reader.cursor
        suggest { builder, provider -> builder.suggest("@v") }
        if (this.reader.canRead() && this.reader.peek() == '@') {
            this.reader.skip()
            this.parseSelector()
        }

        this.finalizePredicates()
        return this.selector
    }

    @Throws(CommandSyntaxException::class)
    fun parseSelector() {
        suggest { builder, provider -> builder.suggest("@v") }
        if (!this.reader.canRead()) {
            throw ERROR_MISSING_SELECTOR_TYPE.createWithContext(this.reader)
        } else {
            this.reader.read()

            suggest { builder, provider -> builder.suggest("[") }

            if (this.reader.canRead() && this.reader.peek() == '[') {
                this.reader.skip()
                suggest { builder, provider ->
                    builder.suggest("]")
                    ContraptionSelectorOptions.suggestNames(this, builder)
                }
                this.parseOptions()
            }
        }
    }

    @Throws(CommandSyntaxException::class)
    fun parseOptions() {
        this.reader.skipWhitespace()

        while (true) {
            if (this.reader.canRead() && this.reader.peek() != ']') {
                this.reader.skipWhitespace()
                val i = this.reader.getCursor()
                val s = this.reader.readString()
                val `entityselectoroptions$modifier`: ContraptionSelectorOptions.Modifier =
                    ContraptionSelectorOptions.get(this, s, i)
                this.reader.skipWhitespace()
                if (!this.reader.canRead() || this.reader.peek() != '=') {
                    this.reader.cursor = i
                    throw ERROR_EXPECTED_OPTION_VALUE.createWithContext(this.reader, s)
                }

                this.reader.skip()
                this.reader.skipWhitespace()

                suggest { _, _ ->  }

                `entityselectoroptions$modifier`.handle(this)
                this.reader.skipWhitespace()


                if (source != null) {
                    suggestionProvider = { builder ->
                        val valueFuture = suggestOptionValue(source, builder)?.buildFuture()
                        if (valueFuture != null) valueFuture
                        else {
                            builder.suggest(','.toString())
                            builder.suggest(']'.toString())
                            builder.buildFuture()
                        }
                    }
                }

                if (!this.reader.canRead()) {
                    continue
                }

                if (this.reader.peek() == ',') {
                    this.reader.skip()

                    suggest { builder, provider -> ContraptionSelectorOptions.suggestNames(this, builder) }

                    continue
                }

                if (this.reader.peek() != ']') {
                    throw ERROR_EXPECTED_END_OF_OPTIONS.createWithContext(this.reader)
                }
            }

            if (this.reader.canRead()) {
                this.reader.skip()

                suggest { _, _ ->  }

                return
            }

            throw ERROR_EXPECTED_END_OF_OPTIONS.createWithContext(this.reader)
        }
    }

    private fun suggestOptionsKeyOrClose(
        builder: SuggestionsBuilder, consumer: Consumer<SuggestionsBuilder>
    ): CompletableFuture<Suggestions> {
        builder.suggest(']'.toString())
        ContraptionSelectorOptions.suggestNames(this, builder)
        return builder.buildFuture()
    }

    private fun suggestOptionsKey(
        builder: SuggestionsBuilder, consumer: Consumer<SuggestionsBuilder>
    ): CompletableFuture<Suggestions> {
        ContraptionSelectorOptions.suggestNames(this, builder)
        return builder.buildFuture()
    }

    private fun suggestOptionsNextOrClose(
        builder: SuggestionsBuilder, consumer: Consumer<SuggestionsBuilder>
    ): CompletableFuture<Suggestions> {

        if (source != null) {
            suggestOptionValue(source, builder)?.let { return it.buildFuture() }
        }

        builder.suggest(','.toString())
        builder.suggest(']'.toString())
        return builder.buildFuture()
    }

    companion object {
        private val ERROR_MISSING_SELECTOR_TYPE =
            SimpleCommandExceptionType(Component.translatable("industriacore.argument.contraption.selector.missing"))
        private val ERROR_EXPECTED_END_OF_OPTIONS = SimpleCommandExceptionType(
            Component.translatable("industriacore.argument.contraption.options.unterminated")
        )
        private val ERROR_EXPECTED_OPTION_VALUE = DynamicCommandExceptionType(
            Function { obj: Any? ->
                Component.translatable(
                    "industriacore.argument.contraption.options.valueless", obj
                )
            })

        val SUGGEST_NOTHING: BiFunction<SuggestionsBuilder, Consumer<SuggestionsBuilder>, CompletableFuture<Suggestions>> =
            BiFunction { builder: SuggestionsBuilder, _: Consumer<SuggestionsBuilder> -> builder.buildFuture() }

        val ORDER_NEAREST: (Vec3, Sequence<Ship>) -> Sequence<Ship> =
            { pos: Vec3, sort: Sequence<Ship> ->
                sort.sortedWith { s1, s2 -> Doubles.compare(s1.transform.positionInWorld.distance(pos.toJOML()), s2.transform.positionInWorld.distance(pos.toJOML())) }
            }
        val ORDER_FURTHEST:  (Vec3, Sequence<Ship>) -> Sequence<Ship> =
            { pos: Vec3, sort: Sequence<Ship> ->
                sort.sortedWith { s1, s2 -> Doubles.compare(s2.transform.positionInWorld.distance(pos.toJOML()), s1.transform.positionInWorld.distance(pos.toJOML())) }
            }
        val ORDER_RANDOM: (Vec3, Sequence<Ship>) -> Sequence<Ship> =
            { pos: Vec3, sort: Sequence<Ship> ->
                sort.shuffled()
            }
    }
}
