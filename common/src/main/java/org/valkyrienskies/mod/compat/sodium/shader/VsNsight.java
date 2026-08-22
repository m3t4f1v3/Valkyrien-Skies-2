package org.valkyrienskies.mod.compat.sodium.shader;

import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.SharedLibrary;
import org.slf4j.LoggerFactory;

/**
 * Lets the game start and stop an Nsight Graphics GPU Trace itself, via the small C shim in
 * autotest/ngfx-shim.
 *
 * <p>Nsight's own time-based trigger ({@code --start-after-ms}) never completed a trace against this
 * client: the session connects, arms, then dies with "Activity session destroyed, connection error
 * encountered" -- at every delay, on a headless gamescope display and on the real desktop,
 * privileged and not, and with nothing more under {@code --verbose}. The documented alternative is
 * {@code --start-with-ngfx-sdk}, where the application picks the moment. That is strictly better for
 * this use anyway: the trace can be started once the autotest scene is actually settled, instead of
 * guessing a delay that has to cover world generation and chunk building.
 *
 * <p>Enabled by pointing {@code -Dvs.ngfxshim} at the built library. Absent that, every call here is
 * a no-op, so this costs a null check in normal runs.
 */
public final class VsNsight {
    private static final String SHIM_PATH = System.getProperty("vs.ngfxshim", "");
    private static SharedLibrary library;
    private static long fnInit;
    private static long fnActivate;
    private static long fnStart;
    private static long fnStop;
    private static boolean failed;

    private VsNsight() {
    }

    public static boolean available() {
        return !SHIM_PATH.isEmpty() && !failed;
    }

    private static boolean load() {
        if (failed || SHIM_PATH.isEmpty()) {
            return false;
        }
        if (library != null) {
            return true;
        }
        try {
            library = APIUtil.apiCreateLibrary(SHIM_PATH);
            fnInit = library.getFunctionAddress("vs_ngfx_init");
            fnActivate = library.getFunctionAddress("vs_ngfx_activate");
            fnStart = library.getFunctionAddress("vs_ngfx_start");
            fnStop = library.getFunctionAddress("vs_ngfx_stop");
            return true;
        } catch (final Throwable t) {
            // Loud, not silent: a profiling run that quietly measures nothing is worse than one that
            // stops. See the fail-loudly rule this codebase follows for compat shims.
            LoggerFactory.getLogger("VS2-ngfx").error("failed to load the Nsight shim from {}", SHIM_PATH, t);
            failed = true;
            return false;
        }
    }

    /**
     * Bind Nsight's GPU-Trace entry points. Must run after the GL context exists, since the SDK
     * resolves OpenGL-specific functions here.
     */
    public static void init() {
        if (!load()) {
            return;
        }
        log("init", JNI.invokeI(fnInit));
        log("activate", JNI.invokeI(fnActivate));
    }

    public static void start() {
        if (!load()) {
            return;
        }
        log("start", JNI.invokeI(fnStart));
    }

    public static void stop() {
        if (!load()) {
            return;
        }
        log("stop", JNI.invokeI(fnStop));
    }

    private static void log(final String what, final int result) {
        // NGFX_RESULT_OK is 0; anything else means the trace did not do what was asked, and the run
        // should not be read as a measurement.
        LoggerFactory.getLogger("VS2-ngfx").info("NGFX {} -> {}{}", what, result,
            result == 0 ? " (ok)" : " (FAILED)");
    }
}
