package org.valkyrienskies.mod.common.mob_spawning

import org.valkyrienskies.core.api.ships.Ship

/**
 * Thread-local stack tracking the current ship whose mob is being finalized via
 * `Mob.finalizeSpawn(...)`. Enables `MixinLevelShipFinalizeBiome` to project
 * `level.getBiome(pos)` calls inside finalizeSpawn implementations to the ship's world-
 * rendered position, so biome-derived variants (Frog warm/cold/temperate, Rabbit color
 * by snowy/desert/etc., and any future biome-variant mobs) correctly reflect where the
 * ship is rendered rather than the shipyard chunk's noise-generator biome.
 *
 * Per-callsite mixins on `Mob.finalizeSpawn` invocations (NaturalSpawner, BaseSpawner,
 * EntityType.spawn, CatSpawner, PhantomSpawner, our `ShipNaturalSpawner`) push the
 * managing ship if the entity is on a ship, run the call, pop on the way out. The
 * Level wrap is a no-op when the stack is empty (non-ship entities or non-finalize
 * code paths see no behavior change).
 *
 * Stack-based for nesting safety (e.g., a mob whose finalizeSpawn spawns a passenger
 * that also has finalizeSpawn).
 */
object ShipSpawnFinalizeContext {

    private val STACK: ThreadLocal<ArrayDeque<Ship?>> = ThreadLocal.withInitial { ArrayDeque() }

    @JvmStatic
    fun push(ship: Ship?) {
        STACK.get().addLast(ship)
    }

    @JvmStatic
    fun pop() {
        STACK.get().removeLast()
    }

    @JvmStatic
    fun current(): Ship? = STACK.get().lastOrNull()
}
