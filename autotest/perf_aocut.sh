#!/usr/bin/env bash
# Where does the seam AO's FLOOR come from?
#
# The floor is what the AO costs with its code compiled into the shader and NONE of it executing:
# on the 49-ship fleet at 4K it was ~30 ms of a 100 ms frame against ~2 ms with the AO compiled out.
# That is not work, it is occupancy -- the shader's register allocation leaves too few warps resident
# to hide memory latency, and every fragment in the world pass pays it whether or not it is anywhere
# near a ship.
#
# perf_aoprof.sh cannot answer this. Its stages deliberately keep register allocation IDENTICAL (they
# return under a condition the compiler cannot fold), which is what makes them comparable as work and
# useless as an explanation of the floor.
#
# So every row here runs with -Pvs_aoprof=4 -- nothing executes in ANY of them -- and varies only
# -Pvs_aocut, which removes code from the compiler's view:
#
#   cut 0    the whole AO compiled in                       (this is the floor as shipped)
#   cut 1    minus the per-voxel stamp (mat3 stamp, taps, occ accumulation)
#   cut 2    minus all of pass 2 (Rinv, band setup, occ0/occ1, corner rule)
#   cut 3    minus pass 1's host search as well -- only the early-outs remain
#   ao off   the AO compiled out entirely; the target the rows above are walking towards
#
# Each row's drop from the one above is the register cost of the region it removed. A region whose
# removal moves nothing is not worth optimising for occupancy, however much arithmetic it contains.
#
#   usage: autotest/perf_aocut.sh [legacy|backport] [fixture]
set -euo pipefail
cd "$(dirname "$0")/.."
RUNTIME="${1:-legacy}"
FIXTURE="${2:-autotest/ao_fleet.txt}"
W="${PERF_W:-3840}"; H="${PERF_H:-2160}"

# TWO launches per row, reporting the second -- the first after a rebuild runs ~30% slow, and every
# row here changes a -P property. See the note on sample() in perf_aoprof.sh for the measurements.
sample() {
    local ao="$1" extra="$2" i
    for i in 1 2; do
        AUTOTEST_TIMEOUT="${AUTOTEST_TIMEOUT:-900}" \
        AUTOTEST_UNCAP_FPS=1 AUTOTEST_FULLSCREEN=true \
        AUTOTEST_W="$W" AUTOTEST_H="$H" \
        AUTOTEST_VS_CONFIG="dynamicShipToWorldLighting=true dynamicShipLighting=true gpuDynamicLightFlood=true shipAmbientOcclusion=$ao debugFloodPaint=0" \
        AUTOTEST_GRADLE_ARGS="-Psodium_runtime=$RUNTIME $extra" \
            bash autotest/run.sh "$FIXTURE" >/dev/null 2>&1 || true
    done
    grep -a "\[autotest\] fps SETTLED" forge/run/logs/latest.log | tail -3 | sed 's/.*: //' |
        awk '{s+=$1; n++} END {if (n) printf "%.1f", s/n; else printf "0"}'
}

res_seen() {
    grep -a "\[autotest\] fps " forge/run/logs/latest.log | tail -1 | sed -n 's/.*@ //p'
}

row() {
    awk -v l="$1" -v f="$2" -v r="$(res_seen)" 'BEGIN {
        printf "  %-10s %8s fps  %7.2f ms   %s\n", l, f, (f>0)?1000/f:0, r
    }'
}

echo "$(basename "$FIXTURE" .txt) @ ${W}x${H} requested, uncapped, $RUNTIME"
echo "every row executes NO ao code (-Pvs_aoprof=4); rows differ only in how much is COMPILED in"
row "cut 0"  "$(sample true '-Pvs_aoprof=4')"
row "cut 1"  "$(sample true '-Pvs_aoprof=4 -Pvs_aocut=1')"
row "cut 2"  "$(sample true '-Pvs_aoprof=4 -Pvs_aocut=2')"
row "cut 3"  "$(sample true '-Pvs_aoprof=4 -Pvs_aocut=3')"
row "ao off" "$(sample false '')"
