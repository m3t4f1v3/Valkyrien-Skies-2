#!/usr/bin/env bash
# Profile the ship-lighting shaders with Nsight Graphics instead of inferring cost from frame rate.
#
# Why: fps-derived attribution on this rig gave contradictory and impossible results -- the AO-off
# configuration measured SLOWER at a quarter of the resolution, AO-on measured faster than AO-off in
# a scene where both render identically, and a GL timer query around the terrain pass disagreed with
# the fps bisect by 20x. At several hundred fps the CPU, the driver and gamescope's compositing
# dominate and the run-to-run spread reaches 25%, which is larger than the effects being measured.
# A GPU trace reads the hardware's own counters and does not care about any of that.
#
# How it runs: the client is started by the normal autotest harness on the ao_hold fixture, which
# builds an AO-saturated scene and then renders the same frame for ~150 s. Nsight attaches to the
# already-running JVM by pid, traces a fixed number of frames, and exports the metrics.
#
#   usage: autotest/ngfx_trace.sh [ao-on|ao-off]
#
# Note: --max-duration-ms is capped below 10000 by the tool; NGFX_MAX_MS defaults to 8000. The trace
# stops at whichever comes first, that duration or --limit-to-frames.
set -euo pipefail
cd "$(dirname "$0")/.."

AO="${1:-ao-on}"
case "$AO" in
    ao-on)  AO_CFG=true ;;
    ao-off) AO_CFG=false ;;
    *) echo "usage: $0 [ao-on|ao-off]" >&2; exit 2 ;;
esac

OUTDIR="${NGFX_OUT:-/tmp/ngfx-$AO}"
rm -rf "$OUTDIR"; mkdir -p "$OUTDIR"
CLIENT_PATTERN='architectury.main[.]class'

# The harness owns the client lifecycle; this script only attaches to it.
AUTOTEST_TIMEOUT=600 \
AUTOTEST_UNCAP_FPS=1 AUTOTEST_FULLSCREEN=true \
AUTOTEST_W="${NGFX_W:-1920}" AUTOTEST_H="${NGFX_H:-1080}" \
AUTOTEST_VS_CONFIG="dynamicShipToWorldLighting=true dynamicShipLighting=true gpuDynamicLightFlood=true shipAmbientOcclusion=$AO_CFG debugFloodPaint=0" \
AUTOTEST_GRADLE_ARGS="-Psodium_runtime=legacy -Pcreate_runtime=false" \
    bash autotest/run.sh autotest/ao_hold.txt > "$OUTDIR/harness.log" 2>&1 &
HARNESS=$!

cleanup() { kill "$HARNESS" 2>/dev/null || true; pkill -f "$CLIENT_PATTERN" 2>/dev/null || true; }
trap cleanup EXIT

# Wait for the scene to be up rather than for the process: attaching while chunks are still building
# would trace the wrong work entirely.
echo "waiting for the scene to settle..."
for _ in $(seq 1 150); do
    if grep -qa "HOLD_BEGIN" forge/run/logs/latest.log 2>/dev/null; then break; fi
    sleep 2
done
PID="$(pgrep -f "$CLIENT_PATTERN" | head -1 || true)"
if [ -z "$PID" ]; then
    echo "no client process to attach to -- the harness never got one running:" >&2
    tail -6 "$OUTDIR/harness.log" >&2
    # A previous aborted gamescope leaves its Xwayland socket behind, and the next launch dies on it
    # with "X11 I/O error! This is fatal". Say so rather than leaving a bare failure.
    ls /tmp/.X11-unix/ 2>/dev/null | tr '\n' ' ' | sed 's/^/existing X sockets: /' >&2; echo >&2
    exit 1
fi
grep -qa "HOLD_BEGIN" forge/run/logs/latest.log 2>/dev/null \
    || { echo "scene never reached the hold window" >&2; exit 1; }
echo "attaching Nsight to pid $PID ($AO)"

# Nsight runs as ROOT; the client stays as the normal user. Two separate restrictions need it:
#   * NVIDIA gates GPU performance counters behind NVreg_RestrictProfilingToAdminUsers, so an
#     unprivileged profiler gets "GPU Performance Counters unavailable" after connecting;
#   * kernel.yama.ptrace_scope = 1 only lets a process be traced by an ancestor, and root bypasses
#     that -- so attaching to a client this script did not spawn works.
# Running the CLIENT as root instead would leave root-owned files in forge/run and the gradle caches.
sudo -n ngfx --activity "GPU Trace Profiler" \
     --attach-pid "$PID" \
     --start-after-ms 3000 \
     --limit-to-frames "${NGFX_FRAMES:-20}" \
     --max-duration-ms "${NGFX_MAX_MS:-8000}" \
     --auto-export \
     --output-dir "$OUTDIR" 2>&1 | tail -25

echo "--- exported to $OUTDIR:"
find "$OUTDIR" -maxdepth 2 -type f -printf '%s\t%p\n' 2>/dev/null | sort -rn | head -10
