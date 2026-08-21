#!/usr/bin/env bash
# Attribute the cost of the ship-lighting shaders, so optimisation targets what is actually hot.
#
# Four configurations of the same scene, so each line isolates one thing:
#   off        every VS light/AO feature off              -- what the scene costs without us
#   flood      ship->world flood on, AO off               -- adds the compute passes + flood sampling
#   flood+ao   both on                                    -- adds the per-fragment seam AO
#   ao-nodisp  both on, -Pvs_floodbench=1 (no dispatch)   -- flood's COMPUTE cost is the difference
#
# Resolution matters and is easy to get wrong: overrideWidth/Height do nothing in this setup, so the
# earlier "1920x1080" numbers in this project were almost certainly taken at the 854x480 default.
# Fullscreen inside a gamescope display sized here is what actually renders at 1080p.
set -euo pipefail
cd "$(dirname "$0")/.."
RUNTIME="${1:-legacy}"
W="${PERF_W:-1920}"; H="${PERF_H:-1080}"

run_case() {
    local label="$1" cfg="$2" extra="${3:-}"
    AUTOTEST_TIMEOUT="${AUTOTEST_TIMEOUT:-900}" \
    AUTOTEST_UNCAP_FPS=1 \
    AUTOTEST_FULLSCREEN=true \
    AUTOTEST_W="$W" AUTOTEST_H="$H" \
    AUTOTEST_VS_CONFIG="$cfg" \
    AUTOTEST_GRADLE_ARGS="-Psodium_runtime=$RUNTIME $extra" \
        bash autotest/run.sh autotest/perfscene.txt >/dev/null 2>&1 || true
    # The last few fps samples, after the scene has settled; the first is always still building chunks.
    local fps
    fps=$(grep -a "\[autotest\] fps SETTLED" forge/run/logs/latest.log | tail -3 |
          sed 's/.*: //' | tr '\n' ' ')
    printf "  %-12s %s\n" "$label" "${fps:-<no samples>}"
}

echo "perfscene @ ${W}x${H}, uncapped, $RUNTIME  (fps, last 3 settled samples)"
run_case "off"       "dynamicShipToWorldLighting=false dynamicShipLighting=false gpuDynamicLightFlood=false shipAmbientOcclusion=false debugFloodPaint=0"
run_case "flood"     "dynamicShipToWorldLighting=true dynamicShipLighting=true gpuDynamicLightFlood=true shipAmbientOcclusion=false debugFloodPaint=0"
run_case "flood+ao"  "dynamicShipToWorldLighting=true dynamicShipLighting=true gpuDynamicLightFlood=true shipAmbientOcclusion=true debugFloodPaint=0"
run_case "ao-nodisp" "dynamicShipToWorldLighting=true dynamicShipLighting=true gpuDynamicLightFlood=true shipAmbientOcclusion=true debugFloodPaint=0" "-Pvs_floodbench=1"
