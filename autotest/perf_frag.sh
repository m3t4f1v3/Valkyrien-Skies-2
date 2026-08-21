#!/usr/bin/env bash
# Profile the FRAGMENT cost of the seam AO by sweeping resolution at fixed AO coverage.
#
# The scene (ao_cover.txt) is a lattice of blocks whose shadows tile the ground, so nearly every
# visible fragment does real AO work. Frametime should then be
#
#     t(pixels) = fixed_cost + per_pixel_cost * pixels
#
# so running the same view at several resolutions gives per_pixel_cost as the SLOPE and everything
# that is not fragment work (CPU, compute dispatches, geometry) as the INTERCEPT. Doing it with AO on
# and off separates the AO's own per-pixel cost from the rest of the fragment shader -- which a single
# fps number never can, because it mixes the two.
#
#   usage: autotest/perf_frag.sh [legacy|backport]
set -euo pipefail
cd "$(dirname "$0")/.."
RUNTIME="${1:-legacy}"
RESOLUTIONS="${PERF_RES:-960x540 1360x765 1920x1080}"

sample() {
    local w="$1" h="$2" ao="$3"
    AUTOTEST_TIMEOUT="${AUTOTEST_TIMEOUT:-900}" \
    AUTOTEST_UNCAP_FPS=1 AUTOTEST_FULLSCREEN=true \
    AUTOTEST_W="$w" AUTOTEST_H="$h" \
    AUTOTEST_VS_CONFIG="dynamicShipToWorldLighting=true dynamicShipLighting=true gpuDynamicLightFlood=true shipAmbientOcclusion=$ao debugFloodPaint=0" \
    AUTOTEST_GRADLE_ARGS="-Psodium_runtime=$RUNTIME" \
        bash autotest/run.sh autotest/ao_cover.txt >/dev/null 2>&1 || true
    grep -a "\[autotest\] fps SETTLED" forge/run/logs/latest.log | tail -3 | sed 's/.*: //' |
        awk '{s+=$1; n++} END {if (n) printf "%.2f", s/n; else printf "0"}'
}

echo "AO-saturated scene, uncapped, $RUNTIME"
echo "res           px(M)    fps_aoOff  fps_aoOn   ms_off   ms_on    ms_ao"
for r in $RESOLUTIONS; do
    w="${r%x*}"; h="${r#*x}"
    off=$(sample "$w" "$h" false)
    on=$(sample "$w" "$h" true)
    awk -v r="$r" -v w="$w" -v h="$h" -v o="$off" -v n="$on" 'BEGIN {
        px = w*h/1e6
        mo = (o>0) ? 1000/o : 0
        mn = (n>0) ? 1000/n : 0
        printf "%-12s %6.3f   %8s  %8s   %6.2f  %6.2f  %6.2f\n", r, px, o, n, mo, mn, mn-mo
    }'
done
