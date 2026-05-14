package org.valkyrienskies.mod.common.command.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.coordinates.RotationArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.RelativeMovement
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.valkyrienskies.core.api.ships.LoadedShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.mod.common.command.arguments.ShipArgument
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLevelFromDimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.toWorldCoordinates
import org.valkyrienskies.mod.common.util.toJOML
import org.valkyrienskies.mod.common.util.toMinecraft
import org.valkyrienskies.mod.common.vsCore
import kotlin.math.atan2
import kotlin.math.sqrt

object VanillaTeleportCommand {
    private const val PERMISSION_LEVEL = 2
    private const val ERROR_ONE_SHIP_DESTINATION = "command.valkyrienskies.mc_teleport.can_only_teleport_to_one_ship"
    private const val ENTITY_TO_SHIP_SUCCESS = "command.valkyrienskies.mc_teleport.entity_to_ship"
    private const val SHIP_TO_ENTITY_SUCCESS = "command.valkyrienskies.mc_teleport.ship_to_entity"
    private const val SHIP_TO_SHIP_SUCCESS = "command.valkyrienskies.mc_teleport.ship_to_ship"
    private const val SHIP_TO_POS_SUCCESS = "command.valkyrienskies.mc_teleport.ship_to_pos"

    @JvmStatic
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(build("teleport"))
    }

    private fun build(name: String): LiteralArgumentBuilder<CommandSourceStack> =
        literal(name)
            .requires { it.hasPermission(PERMISSION_LEVEL) }
            .then(
                argument("shipDestination", ShipArgument.selectorOnly())
                    .executes { teleportSourceToShip(it) }
            )
            .then(
                argument("shipTargets", ShipArgument.selectorOnly())
                    .then(shipPositionArgument())
                    .then(
                        argument("entityDestination", EntityArgument.entity())
                            .executes { teleportShipsToEntity(it) }
                    )
                    .then(
                        argument("shipDestination", ShipArgument.selectorOnly())
                            .executes { teleportShipsToShip(it) }
                    )
            )
            .then(
                argument("targets", EntityArgument.entities())
                    .then(
                        argument("shipDestination", ShipArgument.selectorOnly())
                            .executes { teleportEntitiesToShip(it) }
                    )
            )

    private fun shipPositionArgument() =
        argument("position", Vec3Argument.vec3())
            .executes { teleportShipsToPosition(it) }
            .then(
                argument("rotation", RotationArgument.rotation())
                    .executes { teleportShipsToPositionWithRotation(it) }
            )
            .then(
                literal("facing")
                    .then(
                        argument("facingLocation", Vec3Argument.vec3())
                            .executes { teleportShipsToPositionFacingLocation(it) }
                    )
                    .then(
                        literal("entity")
                            .then(
                                argument("facingEntity", EntityArgument.entity())
                                    .executes { teleportShipsToPositionFacingEntity(it) }
                                    .then(
                                        argument("facingAnchor", EntityAnchorArgument.anchor())
                                            .executes { teleportShipsToPositionFacingEntity(it) }
                                    )
                            )
                            .then(
                                argument("facingShip", ShipArgument.selectorOnly())
                                    .executes { teleportShipsToPositionFacingShip(it) }
                            )
                    )
            )

    private fun teleportSourceToShip(context: CommandContext<CommandSourceStack>): Int =
        teleportEntitiesToShip(context, listOf(context.source.entityOrException))

    private fun teleportEntitiesToShip(context: CommandContext<CommandSourceStack>): Int =
        teleportEntitiesToShip(context, EntityArgument.getEntities(context, "targets"))

    private fun teleportEntitiesToShip(
        context: CommandContext<CommandSourceStack>,
        targets: Collection<Entity>
    ): Int {
        val source = context.source
        val destination = getSingleShip(context, "shipDestination")
        val level = getShipLevel(source, destination)
        val pos = destination.transform.positionInWorld.toMinecraft()

        targets.forEach { entity ->
            entity.teleportTo(level, pos.x, pos.y, pos.z, emptySet<RelativeMovement>(), entity.yRot, entity.xRot)
        }

        source.sendSuccess(
            { Component.translatable(ENTITY_TO_SHIP_SUCCESS, targets.size, destination.slug ?: destination.id.toString()) },
            true
        )
        return targets.size
    }

    private fun teleportShipsToPosition(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val targets = getShipTargets(context)
        val position = Vec3Argument.getVec3(context, "position")

        teleportShips(source, targets, source.level, position, null)

        source.sendSuccess(
            {
                Component.translatable(
                    SHIP_TO_POS_SUCCESS,
                    targets.size,
                    position.x,
                    position.y,
                    position.z
                )
            },
            true
        )
        return targets.size
    }

    private fun teleportShipsToPositionWithRotation(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val targets = getShipTargets(context)
        val position = Vec3Argument.getVec3(context, "position")
        val rotation = RotationArgument.getRotation(context, "rotation").getRotation(source)

        teleportShips(source, targets, source.level, position, rotationToShipRotation(rotation.x.toDouble(), rotation.y.toDouble()))

        source.sendSuccess(
            { Component.translatable(SHIP_TO_POS_SUCCESS, targets.size, position.x, position.y, position.z) },
            true
        )
        return targets.size
    }

    private fun teleportShipsToPositionFacingLocation(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val targets = getShipTargets(context)
        val position = Vec3Argument.getVec3(context, "position")
        val facingPosition = Vec3Argument.getVec3(context, "facingLocation")

        teleportShips(source, targets, source.level, position, rotationFacing(position, facingPosition))

        source.sendSuccess(
            { Component.translatable(SHIP_TO_POS_SUCCESS, targets.size, position.x, position.y, position.z) },
            true
        )
        return targets.size
    }

    private fun teleportShipsToPositionFacingEntity(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val targets = getShipTargets(context)
        val position = Vec3Argument.getVec3(context, "position")
        val facingEntity = EntityArgument.getEntity(context, "facingEntity")
        val anchor = try {
            EntityAnchorArgument.getAnchor(context, "facingAnchor")
        } catch (_: IllegalArgumentException) {
            EntityAnchorArgument.Anchor.FEET
        }
        val facingPosition = anchor.apply(facingEntity)

        teleportShips(source, targets, source.level, position, rotationFacing(position, facingPosition))

        source.sendSuccess(
            { Component.translatable(SHIP_TO_POS_SUCCESS, targets.size, position.x, position.y, position.z) },
            true
        )
        return targets.size
    }

    private fun teleportShipsToPositionFacingShip(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val targets = getShipTargets(context)
        val position = Vec3Argument.getVec3(context, "position")
        val facingShip = getSingleShip(context, "facingShip")
        val facingPosition = facingShip.transform.positionInWorld.toMinecraft()

        teleportShips(source, targets, source.level, position, rotationFacing(position, facingPosition))

        source.sendSuccess(
            { Component.translatable(SHIP_TO_POS_SUCCESS, targets.size, position.x, position.y, position.z) },
            true
        )
        return targets.size
    }

    private fun teleportShipsToEntity(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val targets = getShipTargets(context)
        val destination = EntityArgument.getEntity(context, "entityDestination")
        val level = destination.level() as ServerLevel
        val position = level.toWorldCoordinates(destination.position())
        val rotation = rotationToShipRotation(destination.xRot.toDouble(), destination.yRot.toDouble())

        teleportShips(source, targets, level, position, rotation)

        source.sendSuccess(
            { Component.translatable(SHIP_TO_ENTITY_SUCCESS, targets.size, destination.displayName) },
            true
        )
        return targets.size
    }

    private fun teleportShipsToShip(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val targets = getShipTargets(context)
        val destination = getSingleShip(context, "shipDestination")
        val level = getShipLevel(source, destination)
        val position = destination.transform.positionInWorld.toMinecraft()
        val rotation = if (destination is LoadedShip) Quaterniond(destination.transform.shipToWorldRotation) else null

        teleportShips(source, targets, level, position, rotation)

        source.sendSuccess(
            { Component.translatable(SHIP_TO_SHIP_SUCCESS, targets.size, destination.slug ?: destination.id.toString()) },
            true
        )
        return targets.size
    }

    private fun teleportShips(
        source: CommandSourceStack,
        ships: Collection<ServerShip>,
        level: ServerLevel,
        position: Vec3,
        rotation: Quaterniond?
    ) {
        val teleportData = if (rotation == null) {
            vsCore.newShipTeleportData(
                newPos = position.toJOML(),
                newDimension = level.dimensionId
            )
        } else {
            vsCore.newShipTeleportData(
                newPos = position.toJOML(),
                newRot = rotation,
                newDimension = level.dimensionId
            )
        }
        ships.forEach { ship ->
            vsCore.teleportShip(source.server.shipObjectWorld as ServerShipWorld, ship, teleportData)
        }
    }

    private fun getShipTargets(context: CommandContext<CommandSourceStack>): List<ServerShip> =
        ShipArgument.getShips(context, "shipTargets").map { it as ServerShip }.toList()

    private fun getSingleShip(context: CommandContext<CommandSourceStack>, argumentName: String): Ship {
        val ships = ShipArgument.getShips(context, argumentName).toList()
        if (ships.size != 1) {
            throw com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                Component.translatable(ERROR_ONE_SHIP_DESTINATION)
            ).create()
        }
        return ships.single()
    }

    private fun getShipLevel(source: CommandSourceStack, ship: Ship): ServerLevel =
        source.server.getLevelFromDimensionId(ship.chunkClaimDimension) ?: source.level

    private fun rotationFacing(from: Vec3, to: Vec3): Quaterniond {
        val deltaX = to.x - from.x
        val deltaY = to.y - from.y
        val deltaZ = to.z - from.z
        val horizontal = sqrt(deltaX * deltaX + deltaZ * deltaZ)
        val yaw = Math.toDegrees(atan2(deltaZ, deltaX)) - 90.0
        val pitch = -Math.toDegrees(atan2(deltaY, horizontal))
        return rotationToShipRotation(pitch, yaw)
    }

    private fun rotationToShipRotation(pitch: Double, yaw: Double): Quaterniond =
        Quaterniond().rotateZYX(0.0, Math.toRadians(-yaw), Math.toRadians(pitch))
}
