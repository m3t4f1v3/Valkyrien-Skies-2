#!/usr/bin/env bash
# Run the dev client UNDER Nsight Graphics and capture a GPU trace.
#
# Why not just attach to the running client: --attach-pid cannot work here at all. Nsight finds
# targets by scanning localhost:49152-49215 for processes that already have its interceptor loaded,
# which only happens when Nsight launched them. A client started by the harness has no interceptor,
# so attach reports "Cannot find process" no matter how valid the pid is -- and running the profiler
# as root does not change that either (it is not a ptrace permission problem, which is what it first
# looked like). The profiler has to be the parent:
#
#     gamescope --backend headless -- ngfx --exe java --args "<the client's own argv>"
#
# The client's argv, working directory and environment are captured once from a normal harness run
# (autotest/ngfx_capture_argv.sh writes them next to this script's CAP path), because reproducing
# gradle's launch by hand is guesswork -- it sets a long classpath, a natives path and a pile of
# architectury properties.
#
#   usage: autotest/ngfx_launch.sh <capture-prefix> <output-dir> [extra ngfx args...]
set -euo pipefail
cd "$(dirname "$0")/.."

CAP="${1:?usage: ngfx_launch.sh <capture-prefix> <output-dir> [ngfx args...]}"
OUTDIR="${2:?output dir}"
shift 2

[ -r "$CAP.cmdline" ] || { echo "missing $CAP.cmdline -- run the argv capture first" >&2; exit 2; }
mkdir -p "$OUTDIR"

# argv and env are NUL-separated as the kernel stores them.
mapfile -d '' ARGV < "$CAP.cmdline"
mapfile -d '' ENVV < "$CAP.environ"
RUNDIR="$(cat "$CAP.cwd")"
JAVA="${ARGV[0]}"
ARGS=("${ARGV[@]:1}")

# ngfx takes the arguments as ONE string, so each is quoted individually to survive its re-split.
ARGSTR=""
for a in "${ARGS[@]}"; do ARGSTR+="$(printf '%q' "$a") "; done

echo "client:  $JAVA (${#ARGS[@]} args)"
echo "workdir: $RUNDIR"
echo "output:  $OUTDIR"

# env -i plus the captured environment: gradle's launch depends on it (XDG_RUNTIME_DIR for the
# wayland socket, the JVM's library paths), and inheriting this shell's environment instead would
# quietly change the thing being profiled.
#
# Everything runs UNPRIVILEGED. NVIDIA gates GPU performance counters behind
# NVreg_RestrictProfilingToAdminUsers, which is why this first failed with "GPU Performance Counters
# unavailable"; that is fixed persistently in /etc/modprobe.d/nvidia-profiling.conf (needs a reboot,
# it is a load-time module parameter). Running the profiler under sudo instead does NOT work: the
# client becomes root's child, and Forge's early display initialisation then fails outright.
gamescope -W "${NGFX_W:-1920}" -H "${NGFX_H:-1080}" --backend headless -- \
    env -i "${ENVV[@]}" \
    ngfx --activity "GPU Trace Profiler" \
         --exe "$JAVA" \
         --dir "$RUNDIR" \
         --args "$ARGSTR" \
         --output-dir "$OUTDIR" \
         --auto-export \
         "$@" || true

echo "--- exported:"
find "$OUTDIR" -type f -printf '%s\t%p\n' 2>/dev/null | sort -rn | head -10
