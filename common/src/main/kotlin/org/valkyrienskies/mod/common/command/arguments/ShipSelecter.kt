package org.valkyrienskies.mod.common.command.arguments

import com.mojang.brigadier.context.CommandContext
import net.minecraft.advancements.critereon.MinMaxBounds
import net.minecraft.commands.CommandSourceStack
import org.valkyrienskies.core.api.ships.QueryableShipData
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.mod.api.toJOML

data class ShipSelector(
    val slug: String? = null,
    val id: ShipId? = null,
    val range: MinMaxBounds.Doubles? = null,
    val maxAmount: Int = Int.MAX_VALUE
) {

    fun select(queryableShipData: QueryableShipData<Ship>, ctx: CommandContext<CommandSourceStack>? = null): Set<Ship> {
        var found = queryableShipData.asSequence()

        ctx?.let {
            range?.let { range -> found = found.filter { range.matches(it.transform.positionInWorld.distance(ctx.source.position.toJOML())) }}
        }
        slug?.let { slug -> found = found.filter { it.slug == slug } }
        id?.let { id -> found = found.filter { it.id == id } }

        return found.take(maxAmount).toSet()
    }
}

