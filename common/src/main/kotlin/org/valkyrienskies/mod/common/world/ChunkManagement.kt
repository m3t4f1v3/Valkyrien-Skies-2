package org.valkyrienskies.mod.common.world

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.world.level.ChunkPos
import org.valkyrienskies.core.internal.world.VsiServerShipWorld
import org.valkyrienskies.core.internal.world.chunks.VsiChunkUnwatchTask
import org.valkyrienskies.core.internal.world.chunks.VsiChunkWatchTask
import org.valkyrienskies.mod.common.VS2ChunkAllocator
import org.valkyrienskies.mod.common.executeIf
import org.valkyrienskies.mod.common.getLevelFromDimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.isChunkLoadedForVS
import org.valkyrienskies.mod.common.isTickingChunk
import org.valkyrienskies.mod.common.mcPlayer
import org.valkyrienskies.mod.common.util.MinecraftPlayer
import org.valkyrienskies.mod.mixin.accessors.server.level.ChunkMapAccessor
import org.valkyrienskies.mod.util.logger

object ChunkManagement {
    @JvmStatic
    fun tickChunkLoading(shipWorld: VsiServerShipWorld, server: MinecraftServer) {
        val (chunkWatchTasks, chunkUnwatchTasks) = shipWorld.getChunkWatchTasks()

        // for now, just do all the watch tasks

        chunkWatchTasks.forEach { chunkWatchTask: VsiChunkWatchTask ->
            logger.debug(
                "Watch task for dimension " + chunkWatchTask.dimensionId + ": " +
                    chunkWatchTask.chunkX + " : " + chunkWatchTask.chunkZ
            )

            val chunkPos = ChunkPos(chunkWatchTask.chunkX, chunkWatchTask.chunkZ)

            val level = server.getLevelFromDimensionId(chunkWatchTask.dimensionId)!!
            if (VS2ChunkAllocator.isChunkInShipyardCompanion(chunkPos.x, chunkPos.z)) {
                // Shipyard chunks use radius-0 tickets (level 33 = FULL status) to avoid
                // loading ~25 neighbor chunks per ship chunk. The chunk pipeline's neighbor
                // requirements are bypassed by MixinChunkMapShipyard.
                level.chunkSource.addRegionTicket(VSTicketType.SHIP_CHUNK, chunkPos, 0, chunkPos)
            } else {
                level.chunkSource.updateChunkForced(chunkPos, true)
            }

            val isShipyard = VS2ChunkAllocator.isChunkInShipyardCompanion(chunkPos.x, chunkPos.z)
            // Shipyard chunks use FULL status (level 33), so isTickingChunk never returns true.
            // Use isChunkLoadedForVS which accepts FULL status for shipyard chunks.
            val condition = if (isShipyard) {
                { level.isChunkLoadedForVS(chunkPos) }
            } else {
                { level.isTickingChunk(chunkPos) }
            }
            level.server.executeIf(condition) {
                for (player in chunkWatchTask.playersNeedWatching) {
                    if (player !is MinecraftPlayer) continue
                    val serverPlayer = player.playerEntityReference.get() as ServerPlayer?
                    if (serverPlayer != null) {
                        if (chunkWatchTask.dimensionId != player.dimension) {
                            logger.warn("Player received watch task for chunk in dimension that they are not also in!")
                        }
                        val map = level.chunkSource.chunkMap as ChunkMapAccessor
                        if (isShipyard) {
                            // 1.21 still only auto-tracks ticking chunks here. Shipyard chunks stop at FULL,
                            // so send the ready chunk directly once the ticketed chunk exists.
                            val chunk = level.chunkSource.getChunkNow(chunkPos.x, chunkPos.z)
                            if (chunk != null) {
                                serverPlayer.connection.send(
                                    ClientboundLevelChunkWithLightPacket(chunk, level.lightEngine, null, null)
                                )
                            }
                        } else {
                            map.callMarkChunkPendingToSend(serverPlayer, chunkPos)
                        }
                    }
                }
            }
        }

        chunkUnwatchTasks.forEach { chunkUnwatchTask: VsiChunkUnwatchTask ->
            logger.debug(
                "Unwatch task for dimension " + chunkUnwatchTask.dimensionId + ": " +
                    chunkUnwatchTask.chunkX + " : " + chunkUnwatchTask.chunkZ
            )
            val chunkPos = ChunkPos(chunkUnwatchTask.chunkX, chunkUnwatchTask.chunkZ)

            if (chunkUnwatchTask.shouldUnload) {
                val level = server.getLevelFromDimensionId(chunkUnwatchTask.dimensionId)!!
                if (VS2ChunkAllocator.isChunkInShipyardCompanion(chunkPos.x, chunkPos.z)) {
                    // Only release the SHIP_CHUNK ticket if the ship that owns this chunk
                    // is actually gone. If the ship still exists (just no player watcher),
                    // keep the chunk loaded — otherwise scheduled ticks, block entities, and
                    // fluid flow on unattended ships all silently stop working (redstone
                    // clocks halt, hoppers freeze, furnaces don't smelt).
                    //
                    // Check via the task's own ship field (rather than re-looking up via
                    // allShips.getByChunkPos, which has already stopped answering by the
                    // time this task fires). If the task's ship is no longer registered
                    // in allShips, it's been deleted — drop the ticket. Otherwise keep it.
                    val taskShip = chunkUnwatchTask.ship
                    val shipStillAlive = shipWorld.allShips.getById(taskShip.id) != null
                    if (!shipStillAlive) {
                        level.chunkSource.removeRegionTicket(VSTicketType.SHIP_CHUNK, chunkPos, 0, chunkPos)
                    }
                } else {
                    level.chunkSource.updateChunkForced(chunkPos, false)
                }
            }

            for (player in chunkUnwatchTask.playersNeedUnwatching) {
                if (player !is MinecraftPlayer) continue
                val serverPlayer = player.mcPlayer as ServerPlayer
                (server.getLevelFromDimensionId(chunkUnwatchTask.dimensionId)!!.chunkSource.chunkMap as ChunkMapAccessor)
                    .callDropChunk(serverPlayer, chunkPos)
            }
        }

        shipWorld.setExecutedChunkWatchTasks(chunkWatchTasks, chunkUnwatchTasks)
    }

    /**
     * Returns the list of pending tracking updates (currently empty — stub for tests).
     */
    @JvmStatic
    fun getPendingTrackingUpdates(): List<Any> = emptyList()

    /**
     * Clears any pending chunk management state (stub for tests).
     */
    @JvmStatic
    fun clearPendingState() {
        // No-op in current implementation
    }

    private val logger by logger()
}
