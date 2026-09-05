#!/usr/bin/env bash
# Run the seam-AO situation fixtures and score them.
#
# The situations are the ones the Python suite in claude-scratchpad verifies (verify4 rigidity +
# aligned layouts, verify5 cross-ship merging + aligned splits, scenes.py single/gap/rotation), so a
# regression here should show up in the same terms the algorithm was designed in.
#
#   usage: autotest/ao_all.sh [legacy|backport]
set -euo pipefail
cd "$(dirname "$0")/.."
RUNTIME="${1:-legacy}"

# debugFloodPaint=5 paints the AO loss into the red channel: the scorer reads the loss FIELD, not a
# shaded colour that AO only nudges by a few levels. shipAmbientOcclusion must be on or every frame
# is uniformly zero and every comparison passes vacuously.
export AUTOTEST_VS_CONFIG="dynamicShipToWorldLighting=true dynamicShipLighting=true gpuDynamicLightFlood=true debugFloodPaint=5 shipAmbientOcclusion=true"
export AUTOTEST_GRADLE_ARGS="-Psodium_runtime=$RUNTIME"
export AUTOTEST_TIMEOUT="${AUTOTEST_TIMEOUT:-900}"

for f in ao_parity ao_rigid ao_merge ao_contact ao_misalign ao_lod; do
    echo "=== $f ($RUNTIME)"
    bash autotest/run.sh "autotest/$f.txt" | tail -1
done

python3 autotest/score_ao.py
rc=$?

# The self-consistency checks above cannot tell a correct merge from one that degrades wrongly once
# alignment is lost -- for that the measured curve has to be compared against seam5 itself.
echo
python3 autotest/ref_merge.py || rc=$?
exit $rc
