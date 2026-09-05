#!/usr/bin/env bash
# Bisect the per-fragment seam AO: run the AO-saturated scene with the shader returning early at each
# stage, so the cost of each stage is the difference between consecutive rows.
#
#   ao off    the AO compiled out entirely
#   stage 1   global bounds cull only (the AO path is entered, nothing else runs)
#   stage 2   + grid cell lookup
#   stage 3   + pass 1, host selection over the cell's sub-runs
#   full      + pass 2, the per-voxel stamping
#   stage 0   the AO present in the shader but returning immediately -- the floor, i.e. what the extra
#             uniforms and code cost even when none of it runs
#
# The fixture is a parameter because ao_cover.txt is ONE ship: it isolates the per-fragment path but
# measures every stage in the merge's cheapest configuration, with the host loop running exactly
# once. Run it on ao_fleet.txt too, where each fragment builds four host lattices -- the stage
# ranking is not the same, and the fleet is the case the 100 fps target is set on.
#
#   usage: autotest/perf_aoprof.sh [legacy|backport] [fixture]
set -euo pipefail
cd "$(dirname "$0")/.."
RUNTIME="${1:-legacy}"
FIXTURE="${2:-autotest/ao_cover.txt}"
W="${PERF_W:-1920}"; H="${PERF_H:-1080}"

# TWO launches per row, reporting the second.
#
# The first client launch after anything that makes gradle rebuild -- and every row here changes a -P
# property, so every row rebuilds -- runs about 30% slow. Measured directly: the same build and
# config repeated gave 18.18, 13.95, 13.57 ms, and a separate pair gave 16.39 then 14.35. Comparing
# one cold run against another cold run is not obviously wrong, but comparing a cold row against a
# warm one manufactures differences out of nothing: a MAX_HOSTS change was measured at 27% that way
# and turned out to be 0% once both sides were warm.
#
# Doubling the runtime is the cost of the rows meaning anything.
sample() {
    local ao="$1" prof="$2" i
    for i in 1 2; do
        AUTOTEST_TIMEOUT="${AUTOTEST_TIMEOUT:-900}" \
        AUTOTEST_UNCAP_FPS=1 AUTOTEST_FULLSCREEN=true \
        AUTOTEST_W="$W" AUTOTEST_H="$H" \
        AUTOTEST_VS_CONFIG="dynamicShipToWorldLighting=true dynamicShipLighting=true gpuDynamicLightFlood=true shipAmbientOcclusion=$ao debugFloodPaint=0" \
        AUTOTEST_GRADLE_ARGS="-Psodium_runtime=$RUNTIME $prof" \
            bash autotest/run.sh "$FIXTURE" >/dev/null 2>&1 || true
    done
    grep -a "\[autotest\] fps SETTLED" forge/run/logs/latest.log | tail -3 | sed 's/.*: //' |
        awk '{s+=$1; n++} END {if (n) printf "%.1f", s/n; else printf "0"}'
}

# The resolution the client ACTUALLY rendered at, read back out of the log rather than assumed from
# PERF_W/PERF_H. AUTOTEST_W/H only reaches the client if fullscreen fits inside gamescope's nested
# display; when it does not, Minecraft silently keeps 854x480 and every number here is then a
# quarter-resolution measurement wearing a 1080p label.
res_seen() {
    grep -a "\[autotest\] fps " forge/run/logs/latest.log | tail -1 | sed -n 's/.*@ //p'
}

row() {
    local label="$1" fps="$2"
    awk -v l="$label" -v f="$fps" -v r="$(res_seen)" 'BEGIN {
        printf "  %-10s %8s fps  %7.2f ms   %s\n", l, f, (f>0)?1000/f:0, r
    }'
}

echo "$(basename "$FIXTURE" .txt) @ ${W}x${H} requested, uncapped, $RUNTIME"
row "ao off"  "$(sample false '')"
row "stage 0"  "$(sample true '-Pvs_aoprof=4')"
row "stage 1" "$(sample true '-Pvs_aoprof=1')"
row "stage 2" "$(sample true '-Pvs_aoprof=2')"
row "stage 3" "$(sample true '-Pvs_aoprof=3')"
row "full"    "$(sample true '')"
# The floor: AO code compiled in, returning immediately. Everything above is measured against this.
