#!/usr/bin/env bash
# Capture the client's real launch command, so Nsight can start it itself.
#
# ngfx has to LAUNCH the process it profiles -- attaching to a running JVM by pid does not work here
# (the interceptor has to be present before the GL context exists; that was proved not to be a
# permissions problem by trying it as root). But the client's command line is built by gradle and
# loom: module path, the architectury transformer agent, a generated @argfile, a dozen system
# properties. Reconstructing it by hand is how several earlier attempts failed.
#
# So: start the client the normal way, read the argv, environment and cwd the JVM actually got out
# of /proc, then stop it. ngfx_sdk.sh replays those verbatim with only the fixture swapped.
#
# This used to be done by hand and the files lived in /tmp, which meant they vanished and the trace
# scripts failed with "missing <prefix>.cmdline" and no way to regenerate them.
#
#   usage: autotest/ngfx_capture.sh [capture-prefix]   (default /tmp/vsngfx)
set -euo pipefail
cd "$(dirname "$0")/.."

CAP="${1:-/tmp/vsngfx}"
TIMEOUT="${NGFX_CAPTURE_TIMEOUT:-300}"

# ao_hold just holds a scene open; anything long-lived works, since the fixture gets swapped later.
export AUTOTEST_VS_CONFIG="${AUTOTEST_VS_CONFIG:-dynamicShipToWorldLighting=true dynamicShipLighting=true gpuDynamicLightFlood=true shipAmbientOcclusion=true}"
export AUTOTEST_GRADLE_ARGS="${AUTOTEST_GRADLE_ARGS:--Psodium_runtime=legacy}"
export AUTOTEST_TIMEOUT="$TIMEOUT"

echo "launching the client to capture its argv (this builds first, so give it a few minutes)"
bash autotest/run.sh autotest/ao_hold.txt > "$CAP.launchlog" 2>&1 &
RUNPID=$!

cleanup() {
    # Kill only the process group this script started. An earlier version used `pkill -f java`,
    # which matched this script's own shell and killed the session.
    kill -- -$(ps -o pgid= $RUNPID 2>/dev/null | tr -d ' ') 2>/dev/null || kill $RUNPID 2>/dev/null || true
}
trap cleanup EXIT

FOUND=""
for _ in $(seq 1 "$TIMEOUT"); do
    kill -0 $RUNPID 2>/dev/null || { echo "the client exited before it could be captured; see $CAP.launchlog" >&2; exit 1; }
    # The JVM we want is the one the harness is driving: it carries -Dvs.autotest. The gradle daemon
    # and the wrapper are java too, hence matching on that property rather than on "java".
    for p in $(pgrep -u "$(id -u)" -f 'Dvs\.autotest=' 2>/dev/null); do
        tr '\0' '\n' < "/proc/$p/cmdline" 2>/dev/null | grep -q 'BootstrapLauncher\|TransformerRuntime' || continue
        FOUND="$p"; break
    done
    [ -n "$FOUND" ] && break
    sleep 1
done
[ -n "$FOUND" ] || { echo "no client JVM appeared within ${TIMEOUT}s; see $CAP.launchlog" >&2; exit 1; }

# Wait for a GL context, so the captured environment is one that actually reached the window system.
for _ in $(seq 1 120); do
    grep -aq "Settled\|OpenGL\|Sodium\|Backend library" forge/run/logs/latest.log 2>/dev/null && break
    sleep 1
done

cp "/proc/$FOUND/cmdline" "$CAP.cmdline"
cp "/proc/$FOUND/environ" "$CAP.environ"
readlink "/proc/$FOUND/cwd" > "$CAP.cwd"
echo "captured pid $FOUND"
echo "  $CAP.cmdline  ($(tr -cd '\0' < "$CAP.cmdline" | wc -c) args)"
echo "  $CAP.environ  ($(tr -cd '\0' < "$CAP.environ" | wc -c) vars)"
echo "  $CAP.cwd      ($(cat "$CAP.cwd"))"
echo
echo "now: autotest/ngfx_sdk.sh $CAP <output-dir> [ao-on|ao-off]"
