#!/usr/bin/env bash
# Profile the dev client with Nsight on the REAL desktop display, not inside headless gamescope.
#
# The gamescope route works for screenshots but not for GPU Trace: the session connects, arms the
# trace, and then dies with "Activity session destroyed, connection error encountered" -- with or
# without --no-timeout. The profiler is talking to an application rendering through Xwayland nested
# inside a headless compositor, which is an unusual stack to instrument. Running on the session's own
# X display removes that layer.
#
# The trade-off is that a Minecraft window appears on the desktop for the duration. options.txt is
# forced out of fullscreen first so it stays a small window.
#
#   usage: autotest/ngfx_desktop.sh <capture-prefix> <output-dir> [extra ngfx args...]
set -euo pipefail
cd "$(dirname "$0")/.."

CAP="${1:?usage: ngfx_desktop.sh <capture-prefix> <output-dir> [ngfx args...]}"
OUTDIR="${2:?output dir}"
shift 2
[ -r "$CAP.cmdline" ] || { echo "missing $CAP.cmdline" >&2; exit 2; }
mkdir -p "$OUTDIR"

mapfile -d '' ARGV < "$CAP.cmdline"
mapfile -d '' ENVV < "$CAP.environ"
RUNDIR="$(cat "$CAP.cwd")"
JAVA="${ARGV[0]}"
ARGS=("${ARGV[@]:1}")

ARGSTR=""
for a in "${ARGS[@]}"; do ARGSTR+="$(printf '%q' "$a") "; done

# The captured environment points at gamescope's nested Xwayland (DISPLAY=:1), which will not exist
# here. Everything else in it matters -- the JVM's library paths, XDG_RUNTIME_DIR -- so only DISPLAY
# is replaced rather than rebuilding the environment by hand.
ENV_FIXED=()
for e in "${ENVV[@]}"; do
    case "$e" in
        DISPLAY=*) ;;
        WAYLAND_DISPLAY=*) ;;
        *) ENV_FIXED+=("$e") ;;
    esac
done
ENV_FIXED+=("DISPLAY=${NGFX_DISPLAY:-:0}")

echo "client:  $JAVA (${#ARGS[@]} args) on ${NGFX_DISPLAY:-:0}"
echo "output:  $OUTDIR"

env -i "${ENV_FIXED[@]}" \
    ngfx --activity "GPU Trace Profiler" \
         --exe "$JAVA" \
         --dir "$RUNDIR" \
         --args "$ARGSTR" \
         --output-dir "$OUTDIR" \
         --auto-export \
         "$@" || true

echo "--- exported:"
find "$OUTDIR" -type f -printf '%s\t%p\n' 2>/dev/null | sort -rn | head -10
