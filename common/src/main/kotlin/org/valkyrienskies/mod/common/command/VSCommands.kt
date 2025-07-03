package org.valkyrienskies.mod.common.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import de.bluecolored.bluemap.core.util.Key.minecraft
import net.minecraft.client.multiplayer.ClientSuggestionProvider
import net.minecraft.commands.CommandRuntimeException
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Component.translatable
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.BlockHitResult
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.core.api.world.ShipWorld
import org.valkyrienskies.core.apigame.ShipTeleportData
import org.valkyrienskies.core.impl.game.ShipTeleportDataImpl
import org.valkyrienskies.core.util.x
import org.valkyrienskies.core.util.y
import org.valkyrienskies.core.util.z
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.toJOML
import org.valkyrienskies.mod.common.util.toJOMLD
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.mod.mixin.feature.commands.ClientSuggestionProviderAccessor
import org.valkyrienskies.mod.util.logger
import java.util.UUID

object VSCommands {
    private val LOGGER by logger()
    private const val DELETED_SHIPS_MESSAGE = "command.valkyrienskies.delete.success"
    private const val SET_SHIP_STATIC_SUCCESS_MESSAGE = "command.valkyrienskies.set_static.success"
    private const val TELEPORT_SHIP_SUCCESS_MESSAGE = "command.valkyrienskies.teleport.success"
    private const val GET_SHIP_SUCCESS_MESSAGE = "command.valkyrienskies.get_ship.success"
    private const val GET_SHIP_FAIL_MESSAGE = "command.valkyrienskies.get_ship.fail"
    private const val GET_SHIP_ONLY_USABLE_BY_ENTITIES_MESSAGE = "command.valkyrienskies.get_ship.only_usable_by_entities"
    private const val TELEPORTED_MULTIPLE_SHIPS_SUCCESS = "command.valkyrienskies.teleport.multiple_ship_success"
    private const val TELEPORT_FIRST_ARG_CAN_ONLY_INPUT_1_SHIP = "command.valkyrienskies.mc_teleport.can_only_teleport_to_one_ship"

    fun registerServerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("vs")
                .then(literal("delete")
                    .then(argument("ships", ShipArgument.ships())
                        .executes {
                            try {
                                val r = ShipArgument.getShips(it, "ships").toList() as List<ServerShip>
                                vsCore.deleteShips(it.source.shipWorld as ServerShipWorld, r)
                                it.source.sendVSMessage(translatable(DELETED_SHIPS_MESSAGE, r.size))
                                r.size
                            } catch (e: Exception) {
                                if (e !is CommandRuntimeException) LOGGER.throwing(e)
                                throw e
                            }
                        }
                    )
                ).then(literal("rename")
                    .then(argument("ship", ShipArgument.ships())
                        .then(argument("newName", StringArgumentType.string())
                            .executes {
                                try {
                                    vsCore.renameShip(
                                        ShipArgument.getShip(it, "ship") as ServerShip,
                                        StringArgumentType.getString(it, "newName")
                                    )
                                    1
                                } catch (e: Exception) {
                                    if (e !is CommandRuntimeException) LOGGER.throwing(e)
                                    throw e
                                }
                            }
                        )
                    )
                )
                .then(
                    literal("set-static").then(
                        argument("ships", ShipArgument.ships()).then(
                            argument("is-static", BoolArgumentType.bool()).executes {
                                try {
                                    val r = ShipArgument.getShips(it, "ships").toList() as List<ServerShip>
                                    val isStatic = BoolArgumentType.getBool(it, "is-static")
                                    r.forEach { ship ->
                                        ship.isStatic = isStatic
                                    }
                                    it.source.sendVSMessage(
                                        translatable(
                                            SET_SHIP_STATIC_SUCCESS_MESSAGE, r.size, if (isStatic) "true" else "false"
                                        )
                                    )
                                    r.size
                                } catch (e: Exception) {
                                    if (e !is CommandRuntimeException) LOGGER.throwing(e)
                                    throw e
                                }
                            })
                    )
                )
                .then(
                    literal("teleport").then(
                        argument("ships", ShipArgument.ships()).then(
                            argument("position", Vec3Argument.vec3()).executes {
                                // If only position is present then we execute this code
                                try {
                                    val r = ShipArgument.getShips(it, "ships").toList() as List<ServerShip>
                                    val position =
                                        Vec3Argument.getVec3(it, "position")
                                    val dimensionId = it.source.level.dimensionId
                                    val shipTeleportData: ShipTeleportData =
                                        ShipTeleportDataImpl(
                                            newPos = position.toJOML(),
                                            newDimension = dimensionId
                                        )
                                    r.forEach { ship ->
                                        vsCore.teleportShip(
                                            it.source.shipWorld as ServerShipWorld,
                                            ship, shipTeleportData
                                        )
                                    }
                                    it.source.sendVSMessage(
                                        translatable(TELEPORT_SHIP_SUCCESS_MESSAGE, r.size, shipTeleportData.toString())
                                    )
                                    r.size
                                } catch (e: Exception) {
                                    if (e !is CommandRuntimeException) LOGGER.throwing(e)
                                    throw e
                                }
                            }.then(
                                argument("euler-angles", RelativeVector3Argument.relativeVector3()).executes {
                                    // If only position is present then we execute this code
                                    try {
                                        val r = ShipArgument.getShips(it, "ships").toList() as List<ServerShip>
                                        val position =
                                            Vec3Argument.getVec3(it, "position")
                                        val eulerAngles =
                                            RelativeVector3Argument.getRelativeVector3(
                                                it, "euler-angles"
                                            )

                                        val source = it.source
                                        val dimensionId = it.source.level.dimensionId
                                        val shipTeleportData: ShipTeleportData =
                                            ShipTeleportDataImpl(
                                                newPos = position.toJOML(),
                                                newRot = eulerAngles.toEulerRotationFromMCEntity(
                                                    source.rotation.x.toDouble(), source.rotation.y.toDouble(),
                                                ),
                                                newDimension = dimensionId
                                            )
                                        r.forEach { ship ->
                                            vsCore.teleportShip(
                                                it.source.shipWorld as ServerShipWorld,
                                                ship, shipTeleportData
                                            )
                                        }
                                        it.source.sendVSMessage(
                                            translatable(TELEPORT_SHIP_SUCCESS_MESSAGE, r.size, shipTeleportData.toString())
                                        )
                                        r.size
                                    } catch (e: Exception) {
                                        if (e !is CommandRuntimeException) LOGGER.throwing(e)
                                        throw e
                                    }
                                }.then(
                                    argument("velocity", RelativeVector3Argument.relativeVector3()).executes {
                                        // If only position is present then we execute this code
                                        try {
                                            val r = ShipArgument.getShips(it, "ships").toList() as List<ServerShip>
                                            val position =
                                                Vec3Argument.getVec3(
                                                    it, "position"
                                                )
                                            val eulerAngles =
                                                RelativeVector3Argument.getRelativeVector3(
                                                    it, "euler-angles"
                                                )
                                            val velocity = RelativeVector3Argument.getRelativeVector3(
                                                it, "velocity"
                                            )

                                            val source = it.source
                                            val dimensionId = it.source.level.dimensionId
                                            val shipTeleportData: ShipTeleportData =
                                                ShipTeleportDataImpl(
                                                    newPos = position.toJOML(),
                                                    newRot = eulerAngles.toEulerRotationFromMCEntity(
                                                        source.rotation.x.toDouble(), source.rotation.y.toDouble(),
                                                    ),
                                                    newVel = velocity.toVector3d(0.0, 0.0, 0.0),
                                                    newDimension = dimensionId
                                                )
                                            r.forEach { ship ->
                                                vsCore.teleportShip(
                                                    it.source.shipWorld as ServerShipWorld,
                                                    ship, shipTeleportData
                                                )
                                            }
                                            it.source.sendVSMessage(
                                                translatable(TELEPORT_SHIP_SUCCESS_MESSAGE, r.size, shipTeleportData.toString())
                                            )
                                            r.size
                                        } catch (e: Exception) {
                                            if (e !is CommandRuntimeException) LOGGER.throwing(e)
                                            throw e
                                        }
                                    }.then(
                                        argument(
                                            "angular-velocity", RelativeVector3Argument.relativeVector3()
                                        ).executes {
                                            // If only position is present then we execute this code
                                            try {
                                                val r = ShipArgument.getShips(it, "ships").toList() as List<ServerShip>
                                                val position =
                                                    Vec3Argument.getVec3(
                                                        it, "position"
                                                    )
                                                val eulerAngles =
                                                    RelativeVector3Argument.getRelativeVector3(
                                                        it, "euler-angles"
                                                    )
                                                val velocity = RelativeVector3Argument.getRelativeVector3(
                                                    it, "velocity"
                                                )
                                                val angularVelocity = RelativeVector3Argument.getRelativeVector3(
                                                    it, "angular-velocity"
                                                )

                                                val source = it.source
                                                val dimensionId = it.source.level.dimensionId
                                                val shipTeleportData: ShipTeleportData =
                                                    ShipTeleportDataImpl(
                                                        newPos = position.toJOML(),
                                                        newRot = eulerAngles.toEulerRotationFromMCEntity(
                                                            source.rotation.x.toDouble(), source.rotation.y.toDouble(),
                                                        ),
                                                        newVel = velocity.toVector3d(0.0, 0.0, 0.0),
                                                        newOmega = angularVelocity.toVector3d(0.0, 0.0, 0.0),
                                                        newDimension = dimensionId
                                                    )
                                                r.forEach { ship ->
                                                    vsCore.teleportShip(
                                                        it.source.shipWorld as ServerShipWorld,
                                                        ship, shipTeleportData
                                                    )
                                                }
                                                it.source.sendVSMessage(
                                                    translatable(TELEPORT_SHIP_SUCCESS_MESSAGE, r.size, shipTeleportData.toString())
                                                )
                                                r.size
                                            } catch (e: Exception) {
                                                if (e !is CommandRuntimeException) LOGGER.throwing(e)
                                                throw e
                                            }
                                        }
                                    )
                                )
                            )
                        )
                    )
                )
                .then(literal("get-ship").executes {
                    try {
                        val mcCommandContext = it

                        var success = false
                        val sourceEntity: Entity? = mcCommandContext.source.entity
                        if (sourceEntity != null) {
                            val rayTrace = sourceEntity.pick(10.0, 1.0.toFloat(), false)
                            if (rayTrace is BlockHitResult) {
                                val ship = sourceEntity.level().getShipManagingPos(rayTrace.blockPos)
                                if (ship != null) {
                                    it.source.sendVSMessage(
                                        translatable(GET_SHIP_SUCCESS_MESSAGE, ship.slug, ship.id)
                                    )
                                    success = true
                                }
                            }
                            if (success) {
                                1
                            } else {
                                it.source.sendVSMessage(translatable(GET_SHIP_FAIL_MESSAGE))
                                0
                            }
                        } else {
                            it.source.sendVSMessage(
                                translatable(GET_SHIP_ONLY_USABLE_BY_ENTITIES_MESSAGE)
                            )
                            0
                        }
                    } catch (e: Exception) {
                        if (e !is CommandRuntimeException) LOGGER.throwing(e)
                        throw e
                    }
                })

        )

        // TODO: fix this? It horrifically mangles the vanilla tp command at the moment
        /*dispatcher.root.children.firstOrNull { it.name == "teleport" }?.apply {
            addChild(
                argument("ships", ShipArgument.selectorOnly()).executes {
                    val serverShips = ShipArgument.getShips(it, "ships").toList() as List<ServerShip>
                    val serverShip = serverShips.singleOrNull() ?: throw CommandRuntimeException(
                        translatable(TELEPORT_FIRST_ARG_CAN_ONLY_INPUT_1_SHIP)
                    )
                    val source = it.source
                    val shipPos = serverShip.transform.positionInWorld

                    source.entity?.let { entity -> entity.teleportTo(shipPos.x, shipPos.y, shipPos.z); 1 } ?: 0
                }.then(
                    argument("entity", EntityArgument.entity()).executes {
                        val serverShips = ShipArgument.getShips(it, "ships").toList() as List<ServerShip>
                        val entity = EntityArgument.getEntity(it, "entity")

                        serverShips.forEach { serverShip ->
                            vsCore.teleportShip(
                                it.source.shipWorld as ServerShipWorld, serverShip,
                                ShipTeleportDataImpl(newPos = Vector3d(entity.x, entity.y, entity.z))
                            )
                        }
                        it.source.sendVSMessage(
                            translatable(TELEPORTED_MULTIPLE_SHIPS_SUCCESS, serverShips.size)
                        )
                        serverShips.size
                    }
                ).then(
                    argument("pos", BlockPosArgument.blockPos()).executes {
                        val serverShips = ShipArgument.getShips(it, "ships").toList() as List<ServerShip>
                        val pos = BlockPosArgument.getSpawnablePos(it, "pos")

                        serverShips.forEach { serverShip ->
                            vsCore.teleportShip(
                                it.source.shipWorld as ServerShipWorld, serverShip,
                                ShipTeleportDataImpl(newPos = pos.toJOMLD())
                            )
                        }
                        it.source.sendVSMessage(
                            translatable(TELEPORTED_MULTIPLE_SHIPS_SUCCESS, serverShips.size)
                        )
                        serverShips.size
                    }
                ).build()
            )

            getChild("targets").addChild(
                argument("ship", ShipArgument.selectorOnly()).executes {
                    val ship = ShipArgument.getShip(it, "ship")
                    val entities = EntityArgument.getEntities(it, "targets")
                    val shipPos = ship.transform.positionInWorld

                    entities.forEach { entity -> entity.teleportTo(shipPos.x, shipPos.y, shipPos.z) }

                    entities.size
                }.build()
            )
        }*/
    }

    fun registerClientCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        // TODO implement client commands
    }
}

/*val CommandSourceStack.shipWorld: ShipWorld
    get() = (this.level.shipObjectWorld)*/

val SharedSuggestionProvider.shipWorld: ShipWorld
    get() {
        return (
            if (this is CommandSourceStack) {
                return this.level.shipObjectWorld
            } else if (this is ClientSuggestionProviderAccessor) {
                checkNotNull(this.minecraft.level)
                return this.minecraft.level.shipObjectWorld
            } else {
                // Shouldn't happen
                throw CommandRuntimeException(Component.literal("Command source wasn't CommandSourceStack or ClientSuggestionProvider? Please report this as a bug"))
            }
            )
    }

fun SharedSuggestionProvider.sendVSMessage(component: Component) {
    if (this is CommandSourceStack) {
        this.sendSystemMessage(component)
    } else if (this is ClientSuggestionProviderAccessor) {
        this.minecraft.player?.sendSystemMessage(component)
    }
}
