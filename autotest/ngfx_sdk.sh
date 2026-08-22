#!/usr/bin/env bash
# GPU-trace the client with Nsight, triggered by the GAME rather than by a timer.
#
# --start-with-ngfx-sdk hands the decision to the application: autotest/ao_trace.txt calls
# NGFX_GPUTrace_StartTrace_OpenGL through the shim once the scene has settled, and stops it a moment
# later. Nsight's own --start-after-ms trigger never completed a trace here (session connects, arms,
# then "Activity session destroyed, connection error encountered" -- at every delay, on gamescope and
# on the desktop, privileged and not, with nothing more under --verbose).
#
#   usage: autotest/ngfx_sdk.sh <capture-prefix> <output-dir> [ao-on|ao-off]
set -euo pipefail
cd "$(dirname "$0")/.."

CAP="${1:?usage: ngfx_sdk.sh <capture-prefix> <output-dir> [ao-on|ao-off]}"
OUTDIR="${2:?output dir}"
AO="${3:-ao-on}"
case "$AO" in ao-on) AO_CFG=true ;; ao-off) AO_CFG=false ;; *) echo "bad ao arg" >&2; exit 2 ;; esac

SHIM="$PWD/autotest/ngfx-shim/libvsngfx.so"
[ -r "$SHIM" ] || { echo "build the shim first: autotest/ngfx-shim/build.sh" >&2; exit 2; }
[ -r "$CAP.cmdline" ] || { echo "missing $CAP.cmdline" >&2; exit 2; }
mkdir -p "$OUTDIR"

# Wipe the world first. run.sh does this before every run, but this script launches the JVM directly
# to get it under the profiler, so without an explicit wipe the save carries over between traces --
# each run's `ao_ship` adds another ship on top of the last one's. That showed up as the two traces
# disagreeing on draws per frame (189 vs 78) and even on compute dispatches (25 vs 20), i.e. they
# were not measuring the same scene at all.
rm -rf forge/run/saves/autotest
echo "wiped forge/run/saves/autotest so both traces build the same scene"

# The client's own config decides whether the AO runs; the trace itself is identical either way.
CFG=forge/run/config/valkyrienskies/valkyrienskies-client.toml
if [ -f "$CFG" ]; then
    sed -i "s|^shipAmbientOcclusion = .*|shipAmbientOcclusion = $AO_CFG|" "$CFG"
    echo "shipAmbientOcclusion -> $(grep -h '^shipAmbientOcclusion = ' "$CFG")"
fi

mapfile -d '' ARGV < "$CAP.cmdline"
mapfile -d '' ENVV < "$CAP.environ"
RUNDIR="$(cat "$CAP.cwd")"
JAVA="${ARGV[0]}"

# Rebuild the argument list, swapping the fixture for the tracing one and adding the shim property.
# Everything else (classpath, module path, agent, architectury properties) is reused verbatim --
# reconstructing gradle's launch by hand is how earlier attempts went wrong.
ARGSTR=""
for a in "${ARGV[@]:1}"; do
    case "$a" in
        -Dvs.autotest=*) a="-Dvs.autotest=$PWD/autotest/ao_trace.txt" ;;
    esac
    ARGSTR+="$(printf '%q' "$a") "
done
ARGSTR="-Dvs.ngfxshim=$(printf '%q' "$SHIM") $ARGSTR"

# Extra -D properties for the traced run, e.g. NGFX_JVM_PROPS="-Dvs.seamkernel=2". The captured
# command line comes from one gradle launch, so anything selected by a gradle property is fixed in
# it; this is how a variant gets profiled without re-capturing. Recorded in the output directory so
# a trace can be told apart from its baseline afterwards.
for p in ${NGFX_JVM_PROPS:-}; do
    ARGSTR="$(printf '%q' "$p") $ARGSTR"
done
echo "${NGFX_JVM_PROPS:-(none)}" > "$OUTDIR/jvm_props.txt"
[ -n "${NGFX_JVM_PROPS:-}" ] && echo "extra JVM props: $NGFX_JVM_PROPS"

ize_env() {
    # The captured environment came from a gamescope run, so its display settings point at that
    # nested Xwayland (:1) and carry no XAUTHORITY. Launching on the session's own X server with
    # those gives "glfwInit failed": the client cannot authenticate against :0.
    #
    # The cookie is NOT ~/.Xauthority (that holds a stale entry here -- connecting with it gives
    # "Invalid MIT-MAGIC-COOKIE-1 key") and not Xorg's own -auth file either, which SDDM owns as
    # root. The usable one is whatever the session's own processes were given, so take it from one.
    if [ -z "${NGFX_XAUTH:-}" ]; then
        for p in $(pgrep -u "$(id -u)" -f "plasmashell|kwin_x11|kded|xfwm|gnome-shell" 2>/dev/null | head -5); do
            v=$(tr '\0' '\n' < "/proc/$p/environ" 2>/dev/null | grep -m1 '^XAUTHORITY=' | cut -d= -f2-)
            [ -n "$v" ] && { NGFX_XAUTH="$v"; break; }
        done
    fi
    XAUTH="${NGFX_XAUTH:-}"
    [ -n "$XAUTH" ] && [ -r "$XAUTH" ] || { echo "no readable X cookie found; set NGFX_XAUTH" >&2; exit 2; }
    ENV_FIXED=()
    for e in "${ENVV[@]}"; do
        case "$e" in DISPLAY=*|WAYLAND_DISPLAY=*|XAUTHORITY=*) ;; *) ENV_FIXED+=("$e") ;; esac
    done
    ENV_FIXED+=("DISPLAY=${NGFX_DISPLAY:-:0}" "XAUTHORITY=$XAUTH")
    echo "display ${NGFX_DISPLAY:-:0} with cookie $XAUTH"
}
ize_env

echo "tracing $AO -> $OUTDIR"
env -i "${ENV_FIXED[@]}" \
    ngfx --activity "GPU Trace Profiler" \
         --exe "$JAVA" --dir "$RUNDIR" --args "$ARGSTR" \
         --output-dir "$OUTDIR" --auto-export --no-timeout \
         --start-with-ngfx-sdk --stop-with-ngfx-sdk \
         --trace-timeout 600 2>&1 | grep -vE "Searching for attachable|Warning:|keysym|xkbcomp|^>" | tail -20

# Keep this run's screenshot with its trace. Both runs write the same filename, so without this the
# second overwrites the first and there is no way to check afterwards that the two traces were of the
# same scene -- which is exactly the question the draw-count guard raises.
cp -f forge/run/screenshots/ao_trace.png "$OUTDIR/scene.png" 2>/dev/null || true

echo "--- exported:"
find "$OUTDIR" -type f -printf '%s\t%p\n' 2>/dev/null | sort -rn | head -10
echo "--- shim results from the client log:"
grep -a "VS2-ngfx" forge/run/logs/latest.log 2>/dev/null | tail -5
