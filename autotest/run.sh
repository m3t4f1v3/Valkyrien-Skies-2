#!/usr/bin/env bash
# In-game autotest runner: launches VS's dev Forge client on a virtual display (no window on the desktop),
# drives it with the given script via VsAutoTestHarness, and collects screenshots + result.
#
#   usage: autotest/run.sh <script.txt>
#
# Ported from the Simulated-Project runner. The virtual display is gamescope's headless backend — a nested
# compositor on the real GPU, so the client gets a hardware GL 4.6 context. Xvfb is deliberately not used: it
# renders through Mesa's llvmpipe, and the GPU light flood needs real compute-shader support.
#
# Shutdown is not left to the client. Minecraft's exit regularly never completes here: Minecraft#stop() calls
# System.exit, the VM starts exiting and then blocks forever on a thread — usually in the GL driver — that never
# reaches a safepoint. The run is therefore over the moment the harness writes autotest-result.txt, and this
# script takes the process down itself.
set -euo pipefail
cd "$(dirname "$0")/.."

SCRIPT="$(realpath "${1:?usage: autotest/run.sh <script.txt>}")"
RUN_DIR="forge/run"
RESULT_FILE="$RUN_DIR/autotest-result.txt"
WORLD="${2:-autotest}"

TIMEOUT="${AUTOTEST_TIMEOUT:-900}"
GRACE="${AUTOTEST_GRACE:-8}"

rm -rf "$RUN_DIR/saves/$WORLD" "$RESULT_FILE"
mkdir -p "$RUN_DIR/screenshots"

# Enforce the client config the lighting tests depend on, and re-check it after the run.
# ForgeConfigSpec silently rewrites this file to defaults whenever the schema changes — e.g. when a
# config entry changes type between builds. That turns the feature under test off without a word in
# the log, and every screenshot then shows correct-looking vanilla lighting. Diagnosing that from the
# images alone costs hours, so the values are asserted here instead of assumed.
VS_CFG="$RUN_DIR/config/valkyrienskies/valkyrienskies-client.toml"
if [ -f "$VS_CFG" ]; then
    # A plain space-separated string, deliberately not an array: an array cannot be exported, so
    # setting one as a command prefix silently wrote a literal "(" and ")" into the toml, which the
    # config parser then rejected by resetting the file -- turning off the feature under test.
    for kv in "${AUTOTEST_VS_CONFIG:-dynamicShipToWorldLighting=true dynamicShipLighting=true gpuDynamicLightFlood=true}"; do
        for pair in $kv; do
            k="${pair%%=*}"; v="${pair#*=}"
            if grep -qE "^$k = " "$VS_CFG"; then
                sed -i "s|^$k = .*|$k = $v|" "$VS_CFG"
            else
                echo "$k = $v" >> "$VS_CFG"
            fi
        done
    done
    echo "autotest: client config -> $(grep -hE '^(dynamicShipToWorldLighting|dynamicShipLighting|gpuDynamicLightFlood|debugFloodPaint|defaultRenderer) = ' "$VS_CFG" | tr '\n' ' ')"
fi

# Frame rate has to be uncapped for a perf run to mean anything: with vsync on, every configuration
# reads exactly 60 and the "does this lag?" question cannot be answered at all.
MC_OPTS="$RUN_DIR/options.txt"
if [ -f "$MC_OPTS" ] && [ -n "${AUTOTEST_UNCAP_FPS:-}" ]; then
    sed -i 's/^enableVsync:.*/enableVsync:false/' "$MC_OPTS"
    sed -i 's/^maxFps:.*/maxFps:260/' "$MC_OPTS"
    echo "autotest: fps uncapped for this run ($(grep -hE '^(enableVsync|maxFps):' "$MC_OPTS" | tr '\n' ' '))"
fi

# Crash detection inputs, cleared up front so a crash left by an earlier run cannot be mistaken for this one's.
CRASH_DIR="$RUN_DIR/crash-reports"
LOG_FILE="$RUN_DIR/logs/latest.log"
mkdir -p "$CRASH_DIR"
CRASH_COUNT_BEFORE="$(find "$CRASH_DIR" -maxdepth 1 -type f | wc -l)"
rm -f "$LOG_FILE"

# The client JVM is forked by the gradle *daemon*, so it is not in this script's process group and a group kill
# does not reach it. Match it by this project's own dev-launch classpath instead.
CLIENT_PATTERN="architectury.main.class"

kill_client() {
    pkill -f "$CLIENT_PATTERN" 2>/dev/null || true
    sleep 2
    pkill -9 -f "$CLIENT_PATTERN" 2>/dev/null || true
}

setsid gamescope -W 1600 -H 900 --backend headless -- \
    ./gradlew ":forge:runClient" -Pvs_autotest="$SCRIPT" ${AUTOTEST_GRADLE_ARGS:-} \
    --console=plain &
LAUNCHER_PID=$!

trap 'kill_client; kill -- "-$LAUNCHER_PID" 2>/dev/null || true' EXIT INT TERM

TIMED_OUT=0
CRASHED=0
DEADLINE=$((SECONDS + TIMEOUT))
while [ ! -f "$RESULT_FILE" ]; do
    if ! kill -0 "$LAUNCHER_PID" 2>/dev/null; then
        break
    fi
    # Fail fast on a crash. A mod-loading failure does not exit the process: FML puts the client on its error
    # screen and it sits there, so no result is ever written and the launcher stays alive.
    if [ "$(find "$CRASH_DIR" -maxdepth 1 -type f | wc -l)" -gt "$CRASH_COUNT_BEFORE" ]; then
        CRASHED=1; break
    fi
    if [ -f "$LOG_FILE" ] && grep -qaE "Mod Loading has failed|has failed to load correctly|Minecraft has crashed" "$LOG_FILE"; then
        CRASHED=1; break
    fi
    if [ "$SECONDS" -ge "$DEADLINE" ]; then
        TIMED_OUT=1; break
    fi
    sleep 2
done

[ -f "$RESULT_FILE" ] && sleep "$GRACE"

kill_client
kill -- "-$LAUNCHER_PID" 2>/dev/null || true
wait "$LAUNCHER_PID" 2>/dev/null || true
trap - EXIT INT TERM

if [ "$TIMED_OUT" = 1 ]; then echo "autotest: timed out after ${TIMEOUT}s with no result" >&2; exit 124; fi
if [ "$CRASHED" = 1 ]; then
    LATEST="$(find "$CRASH_DIR" -maxdepth 1 -type f -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)"
    echo "autotest: client crashed${LATEST:+ (see $LATEST)}" >&2; exit 2
fi
if [ ! -f "$RESULT_FILE" ]; then echo "autotest: client exited without writing a result" >&2; exit 1; fi

RESULT="$(cat "$RESULT_FILE")"
echo "autotest: $RESULT"
[[ "$RESULT" == OK* ]]
