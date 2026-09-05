#!/usr/bin/env bash
# What does the cross-ship merge cost, and what does the distance LOD give back?
#
# perf_aoprof.sh answers "which stage of the AO is hot" on ao_cover.txt, which is ONE ship on
# purpose. That isolates the per-fragment path but measures the merge in its cheapest configuration:
# one host lattice, no partners. This runs ao_fleet.txt instead -- 49 touching ships, so every
# interior fragment builds the full four host lattices -- and moves only the merge settings between
# rows, so each difference is attributable.
#
#   merge off      shipAmbientOcclusionMerging=false -- the floor, no host but the owner
#   no LOD         merging on, fade disabled outright (mergeDistance 0)
#   LOD 192        merging on, shipped default; the camera is ~15 blocks out so NOTHING should fade.
#                  This row is the control: it must land on "no LOD", and if it does not then the
#                  fade is engaging at distances it was never meant to.
#   LOD faded      merging on, band shrunk until the budget is 0 at this camera. Must land on
#                  "merge off", because a zeroed claim IS the unmerged path (ao_lod.txt asserts the
#                  two are pixel-identical); here it says whether that also costs the same.
#
# The last pair is the point: the gap between "no LOD" and "LOD faded" is what a distant fleet stops
# paying.
#
#   usage: autotest/perf_aomerge.sh [legacy|backport]
set -euo pipefail
cd "$(dirname "$0")/.."
RUNTIME="${1:-legacy}"
W="${PERF_W:-1920}"; H="${PERF_H:-1080}"

BASE="dynamicShipToWorldLighting=true dynamicShipLighting=true gpuDynamicLightFlood=true"
BASE="$BASE debugFloodPaint=0 shipAmbientOcclusion=true"

sample() {
    AUTOTEST_TIMEOUT="${AUTOTEST_TIMEOUT:-900}" \
    AUTOTEST_UNCAP_FPS=1 AUTOTEST_FULLSCREEN=true \
    AUTOTEST_W="$W" AUTOTEST_H="$H" \
    AUTOTEST_VS_CONFIG="$BASE $1" \
    AUTOTEST_GRADLE_ARGS="-Psodium_runtime=$RUNTIME" \
        bash autotest/run.sh autotest/ao_fleet.txt >/dev/null 2>&1 || true
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
    awk -v l="$1" -v f="$2" -v r="$(res_seen)" 'BEGIN {
        printf "  %-12s %8s fps  %7.2f ms   %s\n", l, f, (f>0)?1000/f:0, r
    }'
}

echo "49-ship merged fleet @ ${W}x${H}, uncapped, $RUNTIME"
row "merge off" "$(sample 'shipAmbientOcclusionMerging=false')"
row "no LOD"    "$(sample 'shipAmbientOcclusionMerging=true shipAmbientOcclusionMergeDistance=0.0')"
row "LOD 192"   "$(sample 'shipAmbientOcclusionMerging=true shipAmbientOcclusionMergeDistance=192.0')"
# The camera sits ~15 blocks above the fleet's fattened seam boxes, so a far edge of 12 puts the near
# edge at 4 and the budget at 0: every claim faded out, nothing merged.
row "LOD faded" "$(sample 'shipAmbientOcclusionMerging=true shipAmbientOcclusionMergeDistance=12.0')"
