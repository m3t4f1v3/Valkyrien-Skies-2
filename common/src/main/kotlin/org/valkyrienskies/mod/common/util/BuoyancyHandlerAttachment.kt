package org.valkyrienskies.mod.common.util

import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.mod.common.config.VSGameConfig

//todo: maybe make this more complex in the future, but the beyond oxygen method is more than acceptable for now
class BuoyancyHandlerAttachment : ShipPhysicsListener {
    val buoyancyData = BuoyancyData()

    override fun physTick(
        physShip: PhysShip, physLevel: PhysLevel
    ) {
        if (!VSGameConfig.SERVER.enablePocketBuoyancy) return
        physShip.buoyantFactor = 1.0 + (buoyancyData.pocketVolumeTotal * VSGameConfig.SERVER.buoyancyFactorPerPocketVolume)
    }


    data class BuoyancyData(
        @Volatile
        var pocketVolumeTotal: Double = 0.0,
    )
}
