package org.valkyrienskies.mod.compat.hexcasting

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent.HasEditPermissionsAt
import at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent.IsVecInRange
import at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent.Key
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.api.positionToShip
import org.valkyrienskies.mod.api.positionToWorld
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.util.toJOML
import org.valkyrienskies.mod.compat.hexcasting.hextweaks.HexTweaksCompat
import java.util.UUID

open class AmbitRemapping(val env: CastingEnvironment) : IsVecInRange, HasEditPermissionsAt {
    private val id = UUID.randomUUID()
    private val key = Key(id)

    override fun getKey(): Key<*> = key

    @OptIn(GameTickOnly::class)
    override fun onHasEditPermissionsAt(pos: BlockPos, current: Boolean): Boolean {
        if (current) return true
        // Always use Worldspace for permissions
        env.world.getLoadedShipManagingPos(pos)?.let { ship ->
            return env.hasEditPermissionsAt(BlockPos.containing(ship.positionToWorld(pos.center)))
        }
        return current
    }

    @OptIn(GameTickOnly::class)
    override fun onIsVecInRange(vec: Vec3, current: Boolean): Boolean {
        if (current) return true
        val level = env.world
        val castVec = getCasterPosition() ?: Vec3.ZERO
        val casterShip = level.getLoadedShipManagingPos(castVec.toJOML())
        val otherShip = level.getLoadedShipManagingPos(vec.toJOML())

        // If both null or same ship, use current check
        if (casterShip == otherShip) return current

        // Is Other Position on a Ship? Transform to Worldspace
        val otherPos = otherShip?.positionToWorld(vec) ?: vec

        // Is Caster in the Shipyard?
        // Transform Other Position to Caster's Shipyard
        casterShip?.let { casterShip -> return env.isVecInRange(casterShip.positionToShip(otherPos)) } ?: return env.isVecInRange(otherPos)
    }

    open fun getCasterPosition(): Vec3? {
        try { // Since there is no X-Plat way to test if a mod is loaded
            return HexTweaksCompat.getComputerPosition(env)
        } catch (_: ClassNotFoundException) {}

        return when (env) {
            is CircleCastEnv -> {
                env.impetus?.blockPos?.center
            }
            else -> {
                env.caster?.position() ?: env.castingEntity?.position()
            }
        }
    }
}

class Key(val id: UUID) : Key<AmbitRemapping> {}
