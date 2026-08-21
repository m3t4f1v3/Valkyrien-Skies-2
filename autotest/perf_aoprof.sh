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
set -euo pipefail
cd "$(dirname "$0")/.."
RUNTIME="${1:-legacy}"
W="${PERF_W:-1920}"; H="${PERF_H:-1080}"

sample() {
    local ao="$1" prof="$2"
    AUTOTEST_TIMEOUT="${AUTOTEST_TIMEOUT:-900}" \
    AUTOTEST_UNCAP_FPS=1 AUTOTEST_FULLSCREEN=true \
    AUTOTEST_W="$W" AUTOTEST_H="$H" \
    AUTOTEST_VS_CONFIG="dynamicShipToWorldLighting=true dynamicShipLighting=true gpuDynamicLightFlood=true shipAmbientOcclusion=$ao debugFloodPaint=0" \
    AUTOTEST_GRADLE_ARGS="-Psodium_runtime=$RUNTIME $prof" \
        bash autotest/run.sh autotest/ao_cover.txt >/dev/null 2>&1 || true
    grep -a "\[autotest\] fps SETTLED" forge/run/logs/latest.log | tail -3 | sed 's/.*: //' |
        awk '{s+=$1; n++} END {if (n) printf "%.1f", s/n; else printf "0"}'
}

row() {
    local label="$1" fps="$2"
    awk -v l="$label" -v f="$fps" 'BEGIN {
        printf "  %-10s %8s fps  %7.2f ms\n", l, f, (f>0)?1000/f:0
    }'
}

echo "AO-saturated scene @ ${W}x${H}, uncapped, $RUNTIME"
row "ao off"  "$(sample false '')"
row "stage 0"  "$(sample true '-Pvs_aoprof=4')"
row "stage 1" "$(sample true '-Pvs_aoprof=1')"
row "stage 2" "$(sample true '-Pvs_aoprof=2')"
row "stage 3" "$(sample true '-Pvs_aoprof=3')"
row "full"    "$(sample true '')"
# The floor: AO code compiled in, returning immediately. Everything above is measured against this.
