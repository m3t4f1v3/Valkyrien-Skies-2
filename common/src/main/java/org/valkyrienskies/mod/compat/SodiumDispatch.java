package org.valkyrienskies.mod.compat;

import net.minecraft.client.multiplayer.ClientLevel;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.mixin.ValkyrienCommonMixinConfigPlugin;

/**
 * The handful of places where generation-neutral VS code has to call into a Sodium compat layer.
 *
 * <p>Each method is a plain branch on {@link SodiumGeneration}. That is deliberate: a reflective call
 * would work too, but the JVM already gives us exactly the property we need for free — a class is not
 * loaded until a bytecode that uses it executes — so the branch for the generation that isn't installed
 * never resolves its {@code SodiumCompat}, and no {@code NoClassDefFoundError} can occur. Keep every
 * branch a direct static call; do not hoist the two compat classes into a shared local or field, which
 * would load both.
 */
public final class SodiumDispatch {

    private SodiumDispatch() {
    }

    private static SodiumGeneration generation() {
        return ValkyrienCommonMixinConfigPlugin.getSodiumGeneration();
    }

    public static void onChunkAdded(final ClientLevel level, final int x, final int z) {
        switch (generation()) {
            case LEGACY -> org.valkyrienskies.mod.compat.sodium.SodiumCompat.onChunkAdded(level, x, z);
            case MODERN -> org.valkyrienskies.mod.compat.sodium09.SodiumCompat.onChunkAdded(level, x, z);
            default -> {
            }
        }
    }

    public static void onChunkRemoved(final ClientLevel level, final int x, final int z) {
        switch (generation()) {
            case LEGACY -> org.valkyrienskies.mod.compat.sodium.SodiumCompat.onChunkRemoved(level, x, z);
            case MODERN -> org.valkyrienskies.mod.compat.sodium09.SodiumCompat.onChunkRemoved(level, x, z);
            default -> {
            }
        }
    }

    public static void markShipSectionCacheDirty(final ClientShip ship) {
        switch (generation()) {
            case LEGACY -> org.valkyrienskies.mod.compat.sodium.SodiumCompat.markShipSectionCacheDirty(ship);
            case MODERN -> org.valkyrienskies.mod.compat.sodium09.SodiumCompat.markShipSectionCacheDirty(ship);
            default -> {
            }
        }
    }
}
