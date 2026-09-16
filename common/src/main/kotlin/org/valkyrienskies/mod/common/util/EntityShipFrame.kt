package org.valkyrienskies.mod.common.util

import net.minecraft.world.entity.Entity
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.allShips
import org.valkyrienskies.mod.common.getShipMountedToData

/**
 * Where an entity stands in the frame of the ship that is carrying it.
 *
 * A rider's place in the car it is riding is a fact about the car. Its blocks have not moved
 * relative to it since it was built and they do not start moving relative to it at two hundred
 * kilometres an hour, so the answer to "is this entity inside one of them" is a fixed number that
 * no amount of speed can change.
 *
 * Where the *car* is is a different question and a much harder one. It is answered by a transform
 * that the game thread, the physics thread and the network each hold their own version of, and
 * every one of those versions is correct about a different moment: the tick transform is where the
 * ship will be at the end of this tick, the render transform is the previous one lerped towards it,
 * and what a client reports is where it thought the ship was a round trip ago. Take an entity out
 * into the world through one of them and back through another and the error is exactly how far the
 * ship travelled in between -- nothing while parked, and three blocks a tick at sixty metres a
 * second, which on a car is the seat, the bulkhead behind it and the boot lid.
 *
 * That error has a direction, too, and it is always backwards: the position was fixed against an
 * older transform than the one used to read it, so the entity lands behind where it is, in whatever
 * happens to be behind it. Hence a driver who suffocates on their own rear bulkhead at speed and on
 * nothing at all when parked.
 *
 * So anything asking a question about an entity and the ship carrying it should ask it here, where
 * the answer does not depend on which moment you meant.
 */
object EntityShipFrame {

    /** A ship and where something stands inside it, in that ship's own coordinates. */
    data class Carried(val ship: Ship, val posInShip: Vector3dc)

    /**
     * The ship carrying [entity] and where [entity] stands in it, or null if no ship is.
     *
     * Two ways an entity can be carried and both are covered, because they are the same fact:
     * mounted to something in the shipyard (a seat, a bed), where the mount position *is* the
     * answer and is derived rather than sent; or dragged, standing on the deck, where the position
     * in the ship is the one the client reports and the one the server already trusts for reach
     * checks. A dragged position can be a moment out of date, but it is out of date by how far the
     * entity walked, not by how far the ship flew.
     */
    @JvmStatic
    fun carrierOf(entity: Entity): Carried? {
        getShipMountedToData(entity, null)?.let {
            return Carried(it.shipMountedTo, it.mountPosInShip)
        }
        val dragging = (entity as? IEntityDraggingInformationProvider)?.draggingInformation ?: return null
        if (!dragging.isEntityBeingDraggedByAShip()) return null
        val stoodOn = dragging.lastShipStoodOn ?: return null
        val where = dragging.bestRelativeEntityPosition() ?: return null
        val ship = entity.level().allShips.getById(stoodOn) ?: return null
        return Carried(ship, Vector3d(where))
    }
}
