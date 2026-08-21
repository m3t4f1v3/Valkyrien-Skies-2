package org.valkyrienskies.mod.compat.sodium.shader;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.slf4j.LoggerFactory;

/**
 * GPU-side timing for the chunk terrain passes, enabled with {@code -Pvs_gputime}.
 *
 * <p>Why this exists rather than reading frame rate: attributing shader cost from fps produced two
 * physically impossible results while profiling the seam AO -- the AO-off configuration measured
 * slower at a quarter of the resolution, and AO-on measured faster than AO-off in a scene where the
 * two render identically. At several hundred fps the CPU, the driver and gamescope's compositing
 * dominate, and the run-to-run spread reached 25%, which is larger than the effects being chased. A
 * {@code GL_TIME_ELAPSED} query brackets the actual GPU work and is unaffected by all of that.
 *
 * <p>Results are read back several frames late, from a small ring of query objects, so the timer
 * never stalls the pipeline to fetch a number -- a stall would inflate exactly what it is measuring.
 */
public final class VsGpuTimer {
    private static final boolean ENABLED = Boolean.getBoolean("vs.gputime");
    /** Deep enough that a result is always ready by the time its slot comes round again. */
    private static final int RING = 6;
    private static final int REPORT_EVERY = 120;

    private static final int[] queries = new int[RING];
    private static final boolean[] pending = new boolean[RING];
    private static int cursor = 0;
    private static boolean open = false;

    private static long accumulatedNanos = 0L;
    private static long samples = 0L;
    private static long framesReported = 0L;

    private VsGpuTimer() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /** Start timing a chunk pass. Silently does nothing when the timer is off or already open. */
    public static void begin() {
        if (!ENABLED || open) {
            return;
        }
        if (queries[cursor] == 0) {
            queries[cursor] = GL33.glGenQueries();
        }
        // Drain this slot's previous result before reusing it. It was issued RING passes ago, so it is
        // ready and this does not stall.
        drain(cursor);
        GL33.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[cursor]);
        open = true;
    }

    /** Finish timing a chunk pass. */
    public static void end() {
        if (!ENABLED || !open) {
            return;
        }
        GL33.glEndQuery(GL33.GL_TIME_ELAPSED);
        pending[cursor] = true;
        open = false;
        cursor = (cursor + 1) % RING;
    }

    private static void drain(final int slot) {
        if (!pending[slot]) {
            return;
        }
        final int available = GL15.glGetQueryObjecti(queries[slot], GL15.GL_QUERY_RESULT_AVAILABLE);
        if (available == 0) {
            // Not ready: skip this sample rather than block. Losing an occasional sample costs
            // nothing; blocking would serialise CPU and GPU and corrupt every later measurement.
            return;
        }
        accumulatedNanos += GL33.glGetQueryObjecti64(queries[slot], GL15.GL_QUERY_RESULT);
        pending[slot] = false;
        samples++;
        if (samples >= REPORT_EVERY) {
            framesReported += samples;
            LoggerFactory.getLogger("VS2-gputime").info(
                "chunk terrain GPU: {} ms/pass over {} passes (total {})",
                String.format("%.3f", accumulatedNanos / 1.0e6 / samples), samples, framesReported);
            accumulatedNanos = 0L;
            samples = 0L;
        }
    }
}
