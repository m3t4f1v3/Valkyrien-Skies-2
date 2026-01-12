package org.valkyrienskies.mod.common.assembly

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Clearable
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.joml.Quaterniond
import org.joml.RoundingMode
import org.joml.Vector3d
import org.joml.Vector3i
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.internal.ships.VsiServerShip
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.executeIf
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.inAssemblyBlacklist
import org.valkyrienskies.mod.common.isTickingChunk
import org.valkyrienskies.mod.common.networking.PacketRestartChunkUpdates
import org.valkyrienskies.mod.common.networking.PacketStopChunkUpdates
import org.valkyrienskies.mod.common.playerWrapper
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.toDenseVoxelUpdate
import org.valkyrienskies.mod.common.util.SplittingDisablerAttachment
import org.valkyrienskies.mod.common.util.toJOML
import org.valkyrienskies.mod.common.util.toJOMLD
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.mod.common.yRange
import org.valkyrienskies.mod.util.StructureTemplateFillFromVoxelSet

object ShipAssembler {
    class SingleItemMap<K, V>(val mkey: K, val mvalue: V, val default: V, val defaultFn: ((K) -> V)? = null): Map<K, V> {
        override val size: Int = 1
        override val keys: Set<K> = setOf(mkey)

        override val values: Collection<V> = setOf(mvalue)
        override val entries: Set<Map.Entry<K, V>> = setOf(object : Map.Entry<K, V> {
            override val key = mkey
            override val value = mvalue
        })

        override fun isEmpty(): Boolean = false
        override fun containsKey(key: K): Boolean = true
        override fun containsValue(value: V): Boolean = true
        override fun get(key: K): V? = if (key == this.mkey) mvalue else defaultFn?.invoke(key) ?: default
    }

    @JvmStatic
    fun findMinAndMax(blocks: Iterable<BlockPos>): Pair<BlockPos, BlockPos> {
        val minCorner = BlockPos.MutableBlockPos(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        val maxCorner = BlockPos.MutableBlockPos(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)

        for (pos in blocks) {
            minCorner.x = Math.min(minCorner.x, pos.x)
            minCorner.y = Math.min(minCorner.y, pos.y)
            minCorner.z = Math.min(minCorner.z, pos.z)

            maxCorner.x = Math.max(maxCorner.x, pos.x)
            maxCorner.y = Math.max(maxCorner.y, pos.y)
            maxCorner.z = Math.max(maxCorner.z, pos.z)
        }

        return minCorner to maxCorner
    }

    @JvmStatic
    private fun getDistinctChunksFromBlockPosSet(blocks: Set<BlockPos>): Set<ChunkPos> {
        val chunkSet = hashSetOf<ChunkPos>()
        for (blockPos in blocks) {
            val chunkPos = ChunkPos(blockPos)
            chunkSet.add(chunkPos)
        }
        return chunkSet
    }

    @JvmStatic
    @OptIn(GameTickOnly::class)
    fun assembleToShip(level: ServerLevel, blocks: Set<BlockPos>, scale: Double = 1.0): ServerShip {
        if (blocks.isEmpty()) throw RuntimeException("Empty set of blocks")

        val eventData = mutableMapOf<String, CompoundTag>()

        val (minB, maxB) = findMinAndMax(blocks)
        val oldMin = minB.toJOMLD()
        val oldMax = maxB.toJOMLD()
        //offset to center from corner of structure
        val offset = oldMax.get(Vector3d())
            .sub(oldMin)
            .add(1.0, 1.0, 1.0)
            .div(2.0)
        val oldCenter = offset.get(Vector3d()).add(oldMin)

        val oldShip = level.getLoadedShipManagingPos(oldCenter) ?: level.getShipManagingPos(oldCenter)
        val oldId = oldShip?.id ?: -1L
        val oldScale = oldShip?.transform?.scaling?.x() ?: 1.0

        var wasSplittingEnabled = true
        if (oldShip is LoadedServerShip) {
            val splittingDisabler = oldShip.getAttachment(SplittingDisablerAttachment::class.java)
            wasSplittingEnabled = splittingDisabler?.canSplit() != false
            splittingDisabler?.disableSplitting()
        }

        val worldOldCenter = oldShip?.shipToWorld?.transformPosition(oldCenter.get(Vector3d())) ?: oldCenter.get(Vector3d())

        VSAssemblyEvents.beforeCopy.emit(VSAssemblyEvents.BeforeCopy(level, oldMin, oldMax, oldCenter, oldShip, blocks, eventData))

        val template = StructureTemplate()
        template as StructureTemplateFillFromVoxelSet
        template.`vs$fillFromVoxelSet`(
            level, blocks,
            oldShip?.let { listOf(it) } ?: emptyList(),
            SingleItemMap(oldId, oldCenter, Vector3d()),
            minB, maxB
        )
        val newShip = level.shipObjectWorld.createNewShipAtBlock(Vector3i(worldOldCenter, RoundingMode.FLOOR), false, scale * oldScale, level.dimensionId)

        val shipChunkX = newShip.chunkClaim.xMiddle
        val shipChunkZ = newShip.chunkClaim.zMiddle

        val worldChunkX = oldMin.x shr 4
        val worldChunkZ = oldMin.z shr 4

        val deltaX = worldChunkX - shipChunkX
        val deltaZ = worldChunkZ - shipChunkZ

        val chunksToBeUpdated = mutableMapOf<ChunkPos, Pair<ChunkPos, ChunkPos>>()
        getDistinctChunksFromBlockPosSet(blocks).forEach { pos ->
            val sourcePos = pos
            val destPos = ChunkPos(sourcePos.x - deltaX, sourcePos.z - deltaZ)
            chunksToBeUpdated[sourcePos] = Pair(sourcePos, destPos)
        }
        val chunkPairs = chunksToBeUpdated.values.toList()
        val chunkPoses = chunkPairs.flatMap { it.toList() }
        val chunkPosesJOML = chunkPoses.map { it.toJOML() }

        level.players().forEach { player ->
            with(vsCore.simplePacketNetworking) {
                PacketStopChunkUpdates(chunkPosesJOML).sendToClient(player.playerWrapper)
            }
        }

        for (pos in blocks) {
            level.getBlockEntity(pos)?.let {
                Clearable.tryClear(it)
            }
            level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), Block.UPDATE_CLIENTS)
        }
        for (pos in blocks) {level.removeBlock(pos, true)}

        newShip.isStatic = oldShip == null || oldShip.isStatic
        val centerOfPlot = newShip.chunkClaim.getCenterBlockCoordinates(level.yRange, Vector3i())

        //structure template builds from a corner, so offset center of plot so that structure's center and center of
        //plot roughly align
        val cornerOfShip = Vector3d(centerOfPlot)
            .sub(offset)
            .ceil()
            .let { BlockPos(
                it.x.toInt(),
                it.y.toInt(),
                it.z.toInt(),
            ) }

        val centerOfShip = cornerOfShip.toJOMLD().add(offset)

        val structureSettings = StructurePlaceSettings().addProcessor(
            ICopyableProcessor(
                SingleItemMap(oldId, newShip.id, -1) {it},
                SingleItemMap(oldId, Pair(oldCenter, Vector3d(centerOfShip)), Pair(Vector3d(), Vector3d()))
            )
        )

        structureSettings.rotationPivot = cornerOfShip

        VSAssemblyEvents.onPasteBeforeBlocksAreLoaded.emit(VSAssemblyEvents.OnPasteBeforeBlocksAreLoaded(level, oldShip, newShip, Pair(oldCenter, centerOfShip), eventData))

        template.placeInWorld(level, cornerOfShip, cornerOfShip, structureSettings, level.random, Block.UPDATE_CLIENTS)
        val shipPos = Vector3d(oldCenter)

        //teleport fn uses COM as center of ship, so it calculates such offset that centerOfShip will be "center" instead
        val posOffset =
            Vector3d(newShip.inertiaData.centerOfMass)
            .sub(Vector3d(centerOfShip))
            .let { oldShip?.shipToWorld?.transformDirection(it) ?: it }

        (newShip as VsiServerShip).unsafeSetKinematics(vsCore.newBodyKinematics(
            oldShip?.velocity ?: Vector3d(),
            oldShip?.angularVelocity ?: Vector3d(),
            vsCore.newBodyTransform(
                (oldShip?.shipToWorld?.transformPosition(shipPos) ?: shipPos).add(posOffset),
                oldShip?.transform?.shipToWorldRotation ?: Quaterniond(),
                Vector3d(scale, scale, scale),
                centerOfShip
            )
        ))
        // level.shipObjectWorld.teleportShip(newShip, vsCore.newShipTeleportData(
        //     (oldShip?.shipToWorld?.transformPosition(shipPos) ?: shipPos).add(posOffset),
        //     oldShip?.transform?.shipToWorldRotation ?: Quaterniond(),
        //     oldShip?.velocity ?: Vector3d(),
        //     oldShip?.angularVelocity ?: Vector3d(),
        // ))

        newShip.isStatic = false

        level.server.executeIf(
            // This condition will return true if all modified chunks have been both loaded AND
            // chunk update packets were sent to players
            { chunkPoses.all(level::isTickingChunk) }
        ) {
            // Once all the chunk updates are sent to players, we can tell them to restart chunk updates
            level.players().forEach { player ->
                with (vsCore.simplePacketNetworking) {
                    PacketRestartChunkUpdates(chunkPosesJOML).sendToClient(player.playerWrapper)
                }
            }
            VSAssemblyEvents.onPasteAfterBlocksAreLoaded.emit(VSAssemblyEvents.OnPasteAfterBlocksAreLoaded(level, oldShip, newShip, Pair(oldCenter, centerOfShip), eventData))
            //force update connectivity because this new assemblyslop doesn't update it :(
            for (pos in chunkPoses) {
                val worldChunk = level.getChunk(pos.x, pos.z) ?: continue
                val chunkSections = worldChunk.sections ?: continue
                for (sectionY in 0 until worldChunk.sectionsCount) {
                    val sectionPos = Vector3i(pos.x, worldChunk.getSectionYFromSectionIndex(sectionY), pos.z)
                    val section = chunkSections[sectionY] ?: continue
                    if (section.hasOnlyAir()) continue
                    val update = section.toDenseVoxelUpdate(sectionPos)
                    level.shipObjectWorld.forceUpdateConnectivityChunk(
                        level.dimensionId,
                        sectionPos.x,
                        sectionPos.y,
                        sectionPos.z,
                        update
                    )
                }
            }
            if (oldShip is LoadedServerShip) {
                val splittingDisabler = oldShip.getAttachment(SplittingDisablerAttachment::class.java)
                if (wasSplittingEnabled) {
                    splittingDisabler?.enableSplitting()
                }
            }
        }



        return newShip
    }
    //legacy method to not break shit
    fun assembleToShip(level: ServerLevel, blocks: List<BlockPos>, shouldSplit: Boolean = true): ServerShip {
        return assembleToShip(level, blocks.toSet(), 1.0)
    }

    fun isValidShipBlock(state: BlockState?) : Boolean {
        if (state == null) return false
        if (state.isAir) return false
        val block: Block = state.block
        //assembly blacklist check
        return !state.inAssemblyBlacklist()
    }

    fun deleteShip(level: ServerLevel, ship: ServerShip, deleteBlocks: Boolean, dropBlocks: Boolean): Int {
        if (ship is LoadedServerShip) {
            val splittingDisabler = ship.getAttachment(SplittingDisablerAttachment::class.java)
            splittingDisabler?.disableSplitting()
        }
        if (deleteBlocks) {
            val aabb = ship.shipAABB ?: return 0
            // There has to be a better way to do this...
            for (x in aabb.minX()..aabb.maxX()) {
                for (y in aabb.minY()..aabb.maxY()) {
                    for (z in aabb.minZ()..aabb.maxZ()) {
                        // Not sure if 2 is what we want, but its what /fill uses
                        if (dropBlocks)
                            level.destroyBlock(BlockPos(x, y, z), true)
                        else
                            level.setBlock(BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2)
                    }
                }
            }
        }

        vsCore.deleteShips(level.shipObjectWorld, listOf<ServerShip>(ship))
        return 1
    }

    class ICopyableProcessor(
        val oldShipIdToNewShipId: Map<ShipId, ShipId>,
        val centerPositions: Map<Long, Pair<Vector3d, Vector3d>>
    ): StructureProcessor() {
        override fun processBlock(
            levelReader: LevelReader, oldBPos: BlockPos, newBPos: BlockPos,
            oldStructureBlockInfo: StructureTemplate.StructureBlockInfo,
            newStructureBlockInfo: StructureTemplate.StructureBlockInfo, structurePlaceSettings: StructurePlaceSettings
        ): StructureTemplate.StructureBlockInfo? {
            val block = newStructureBlockInfo.state.block
            if (block !is ICopyableBlock) return newStructureBlockInfo
            block.onPaste((levelReader as ServerLevelAccessor).level, newBPos, newStructureBlockInfo.state, oldShipIdToNewShipId, centerPositions, newStructureBlockInfo.nbt)
            return newStructureBlockInfo
        }

        // getType is used for referencing this processor from a datapack, which we don't need
        override fun getType(): StructureProcessorType<*>? = null
    }
}
