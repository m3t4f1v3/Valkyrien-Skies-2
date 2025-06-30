package org.valkyrienskies.mod.common.util

import net.minecraft.world.level.block.entity.BlockEntity
import org.joml.Vector3dc
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.api.world.PhysWorld
import org.valkyrienskies.core.apigame.joints.VSJoint
import org.valkyrienskies.core.apigame.joints.VSJointAndId
import org.valkyrienskies.core.apigame.joints.VSJointId
import org.valkyrienskies.core.apigame.world.PhysWorldCore
import org.valkyrienskies.core.util.pollUntilEmpty
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.Function

@OptIn(VsBeta::class)
class GameTickForceApplier {
    private val invForces = ConcurrentLinkedQueue<Pair<ShipId, Vector3dc>>()
    private val invTorques = ConcurrentLinkedQueue<Pair<ShipId, Vector3dc>>()
    private val rotForces = ConcurrentLinkedQueue<Pair<ShipId, Vector3dc>>()
    private val rotTorques = ConcurrentLinkedQueue<Pair<ShipId, Vector3dc>>()
    private val invPosForces = ConcurrentLinkedQueue<Pair<ShipId, InvForceAtPos>>()
    private val rotPosForces = ConcurrentLinkedQueue<Pair<ShipId, InvForceAtPos>>()

    private val joints = ConcurrentLinkedQueue<Pair<VSJoint, Function<VSJointId, VSJointId>>>()
    private val updatedJoints = ConcurrentLinkedQueue<VSJointAndId>()
    private val deletedJoints = ConcurrentLinkedQueue<VSJointId>()

    private val toBeStatic = ConcurrentLinkedQueue<Pair<ShipId, Boolean>>()

    fun physTick(physWorld: PhysWorld, delta: Double) {
        invForces.pollUntilEmpty { pair -> physWorld.getShipById(pair.first)?.applyInvariantForce(pair.second) }
        invTorques.pollUntilEmpty { pair -> physWorld.getShipById(pair.first)?.applyInvariantTorque(pair.second) }
        rotForces.pollUntilEmpty { pair -> physWorld.getShipById(pair.first)?.applyRotDependentForce(pair.second) }
        rotTorques.pollUntilEmpty { pair -> physWorld.getShipById(pair.first)?.applyRotDependentTorque(pair.second) }
        invPosForces.pollUntilEmpty { pair -> physWorld.getShipById(pair.first)?.applyInvariantForceToPos(pair.second.force, pair.second.pos) }
        rotPosForces.pollUntilEmpty { pair -> physWorld.getShipById(pair.first)?.applyInvariantForceToPos(pair.second.force, pair.second.pos) }

        joints.pollUntilEmpty { joint ->
            val id = (physWorld as PhysWorldCore).addJoint(joint.first)
            joint.second.apply(id)
        }

        toBeStatic.pollUntilEmpty { pair -> physWorld.getShipById(pair.first)?.isStatic = pair.second }
    }

    fun applyInvariantForce(ship: ShipId, force: Vector3dc) {
        invForces.add(ship to force)
    }

    fun applyInvariantTorque(ship: ShipId, torque: Vector3dc) {
        invForces.add(ship to torque)
    }

    fun applyRotDependentForce(ship: ShipId, force: Vector3dc) {
        invForces.add(ship to force)
    }

    fun applyRotDependentTorque(ship: ShipId, torque: Vector3dc) {
        invForces.add(ship to torque)
    }

    fun applyInvariantForceToPos(ship: ShipId, force: Vector3dc, pos: Vector3dc) {
        invPosForces.add(ship to InvForceAtPos(force, pos))
    }

    fun setStatic(ship: ShipId, b: Boolean) {
        toBeStatic.add(ship to b)
    }

    fun addJoint(joint: VSJoint, function: Function<VSJointId, VSJointId>) {
        joints.add(joint to function)
    }
    fun updateJoint(jointAndId: VSJointAndId) {
        updatedJoints.add(jointAndId)
    }
    fun removeJoint(jointId: VSJointId) {
        deletedJoints.add(jointId)
    }

    private data class InvForceAtPos(val force: Vector3dc, val pos: Vector3dc)
}
