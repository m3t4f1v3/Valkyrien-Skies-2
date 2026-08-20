package org.valkyrienskies.mod.compat.sodium.shader;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/**
 * One-shot probe for compute-shader support.
 *
 * <p>Minecraft asks GLFW for a 3.2 core context; desktop drivers hand back something much newer, so
 * compute is normally there. macOS is the exception — it caps at 4.1 and will never expose it. Every
 * GPU light path checks this and falls back to the CPU flood in
 * {@link VsWorldFromShipLightStorage} when it comes back false.
 */
public final class VsComputeSupport {
    private static Boolean available;

    private VsComputeSupport() {
    }

    /**
     * Must first be called on the render thread — {@link GL#getCapabilities()} is context-local. A
     * call made before the context exists answers false without caching, so the real answer is still
     * reachable later.
     */
    public static boolean isAvailable() {
        if (available != null) {
            return available;
        }
        final GLCapabilities caps;
        try {
            caps = GL.getCapabilities();
        } catch (final IllegalStateException noContextOnThisThread) {
            return false;
        }
        if (caps == null) {
            return false;
        }
        available = caps.OpenGL43 || caps.GL_ARB_compute_shader;
        return available;
    }
}
