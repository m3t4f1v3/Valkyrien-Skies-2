package org.valkyrienskies.mod.compat;

/**
 * Which generation of Sodium is on the classpath.
 *
 * <p>The two generations share no packages — 0.5-era Sodium and Embeddium live under
 * {@code me.jellysquid.mods.sodium}, 0.9 under {@code net.caffeinemc.mods.sodium} — so both can sit on
 * one compile classpath, and VS ships a compat layer for each. Which one runs is decided here, once,
 * and every crossing between generation-neutral code and a generation's compat layer goes through an
 * ordinary branch on this enum rather than reflection: the JVM only links a class when a branch that
 * uses it actually executes, so the absent generation's classes are never loaded.
 */
public enum SodiumGeneration {
    /** No Sodium-family renderer present. */
    NONE,
    /** Sodium 0.5.x on Fabric, or Embeddium 0.3.x on Forge. */
    LEGACY,
    /** Sodium 0.9.x — upstream on modern versions, or the 1.20.1 backport. */
    MODERN
}
