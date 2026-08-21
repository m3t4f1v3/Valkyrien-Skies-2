#!/usr/bin/env bash
# Capture the merging situations, framed to fill the shot.
#
# The three settings below are what make the framing in ao_shots.txt come out right, and every one of
# them was got wrong on the first attempt:
#   AUTOTEST_FOV=-1.0    MC's fov is a DELTA FROM 70 DEGREES over +/-1, so -1.0 is 30 deg (the
#                        narrowest the game allows) and the instance default 1.0 is 110.
#   AUTOTEST_FULLSCREEN  overrideWidth/Height do nothing here; fullscreen makes the render take
#                        gamescope's display size, so W/H below set both resolution and aspect.
#   AUTOTEST_W/H=1200    square, so a roughly square top-down scene wastes no width.
#
#   usage: autotest/ao_shots.sh [legacy|backport]
set -euo pipefail
cd "$(dirname "$0")/.."
RUNTIME="${1:-legacy}"

AUTOTEST_FOV=-1.0 \
AUTOTEST_FULLSCREEN=true \
AUTOTEST_W=1200 AUTOTEST_H=1200 \
AUTOTEST_TIMEOUT="${AUTOTEST_TIMEOUT:-900}" \
AUTOTEST_VS_CONFIG="dynamicShipToWorldLighting=true dynamicShipLighting=true gpuDynamicLightFlood=true debugFloodPaint=5 shipAmbientOcclusion=true" \
AUTOTEST_GRADLE_ARGS="-Psodium_runtime=$RUNTIME" \
    bash autotest/run.sh autotest/ao_shots.txt | tail -1

# Report how much of each frame the situation actually occupies -- the whole point of this fixture.
python3 - <<'PY'
from PIL import Image
import numpy as np, glob, os
print(f"{'shot':22s} {'size':>10s}  {'fills':>16s}  peak")
for p in sorted(glob.glob("forge/run/screenshots/shot_*.png")):
    a = np.asarray(Image.open(p).convert("RGB")).astype(float)
    r = a[:, :, 0].copy(); r[r <= 3] = 0
    h, w = r.shape
    ys, xs = np.nonzero(r > 0)
    if not len(ys):
        print(f"{os.path.basename(p):22s} {w}x{h:<6}  EMPTY"); continue
    print(f"{os.path.basename(p):22s} {w}x{h:<6}  "
          f"{(xs.max()-xs.min())/w*100:4.0f}% x {(ys.max()-ys.min())/h*100:4.0f}%  {int(r.max())}")
PY
