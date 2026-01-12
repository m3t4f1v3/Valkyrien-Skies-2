package org.valkyrienskies.mod.common.assembly

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Clearable
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
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.inAssemblyBlacklist
import org.valkyrienskies.mod.common.shipObjectWorld
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

        val oldShip = level.getShipManagingPos(oldCenter)
        val oldId = oldShip?.id ?: -1L
        val oldScale = oldShip?.transform?.scaling?.x() ?: 1.0

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

        for (pos in blocks) {
            level.getBlockEntity(pos)?.let {
                Clearable.tryClear(it)
            }
            level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), Block.UPDATE_CLIENTS)
        }
        for (pos in blocks) {level.removeBlock(pos, true)}

        val newShip = level.shipObjectWorld.createNewShipAtBlock(Vector3i(worldOldCenter, RoundingMode.FLOOR), false, scale * oldScale, level.dimensionId)
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
        VSAssemblyEvents.onPasteAfterBlocksAreLoaded.emit(VSAssemblyEvents.OnPasteAfterBlocksAreLoaded(level, oldShip, newShip, Pair(oldCenter, centerOfShip), eventData))

        val shipPos = Vector3d(oldCenter)

        //teleport fn uses COM as center of ship, so it calculates such offset that centerOfShip will be "center" instead
        val posOffset =
            Vector3d(newShip.inertiaData.centerOfMass)
            .sub(Vector3d(centerOfShip))
            .let { oldShip?.shipToWorld?.transformDirection(it) ?: it }

        level.shipObjectWorld.teleportShip(newShip, vsCore.newShipTeleportData(
            (oldShip?.shipToWorld?.transformPosition(shipPos) ?: shipPos).add(posOffset),
            oldShip?.transform?.shipToWorldRotation ?: Quaterniond(),
            oldShip?.velocity ?: Vector3d(),
            oldShip?.angularVelocity ?: Vector3d(),
        ))

        newShip.isStatic = false

        return newShip
    }

    fun isValidShipBlock(state: BlockState?) : Boolean {
        if (state == null) return false
        if (state.isAir) return false
        val block: Block = state.block
        //assembly blacklist check
        return !state.inAssemblyBlacklist()
    }

    fun deleteShip(level: ServerLevel, ship: ServerShip, deleteBlocks: Boolean, dropBlocks: Boolean): Int {
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
