#!/usr/bin/env python3
"""Score the in-game seam-AO fixtures against the properties this algorithm was built for.

The situations come straight from the Python verification suite in this directory -- verify4
(rigidity, aligned layouts), verify5 (cross-ship merging, aligned splits), scenes.py (single block,
one-block gap, rotation) -- and the autotest fixtures under autotest/ao_*.txt reproduce them in the
running game. This script reads the resulting screenshots and checks the same invariants.

Everything runs with debugFloodPaint=5 (VS_DEBUG_SEAM_AO), where the world shader replaces the
shaded colour with the AO loss in the red channel. So `loss(frame)` below is the actual field the
Python scripts plot, sampled off the GPU instead of out of numpy.

  usage: python3 autotest/score_ao.py [screenshot-dir]
"""
import sys
import os
import numpy as np
from PIL import Image

SHOTS = sys.argv[1] if len(sys.argv) > 1 else "forge/run/screenshots"

# The debug paint writes clamp((1 - ao) * 1.25) into red, and keeps a 1e-3 trace of the real colour
# so the driver cannot dead-strip the samplers. That trace is what this floor rejects.
TRACE = 3


def loss(name):
    """AO-loss field of one frame, as a float array in [0, 1]. None if the shot is missing."""
    path = os.path.join(SHOTS, name + ".png")
    if not os.path.exists(path):
        return None
    a = np.asarray(Image.open(path).convert("RGB")).astype(float)
    red = a[:, :, 0]
    red[red <= TRACE] = 0.0
    return red / 255.0


def area(f):
    """Total AO 'mass' in a frame -- the integral of the loss field."""
    return float(f.sum())


def sweep(names):
    """Total AO at each step of a pose sweep, normalised to the first step.

    This is verify4's border-jump measure. A field evaluated in the OCCLUDER ship's lattice keeps its
    total as the ship slides or turns; a receiver-anchored one snaps between world-vertex
    configurations, and the total jumps between adjacent steps (0.16-0.20 on the drifted variant vs
    <=0.02 on the shipped one).
    """
    fs = [loss(n) for n in names]
    if any(f is None for f in fs):
        return None
    base = max(area(fs[0]), 1e-9)
    return [area(f) / base for f in fs]


def worst_jump(profile):
    """Largest change between ADJACENT steps -- a snap shows up here even if the ends agree."""
    return max(abs(profile[i + 1] - profile[i]) for i in range(len(profile) - 1))


def worst_drift(profile):
    """Largest deviation from the starting pose over the whole sweep."""
    return max(abs(p - 1.0) for p in profile)


def report(name, value, limit, detail=""):
    ok = value <= limit
    print(f"  {'PASS' if ok else 'FAIL'}  {name:34s} {value:9.5f}  (limit {limit})  {detail}")
    return ok


# A frame with no AO in it at all compares equal to any other empty frame, so every "these two match"
# check below would pass vacuously if the paint failed, the config was off, or the ships were buried.
# Counted in PIXELS carrying any loss, not in summed loss: a single block at this camera height
# covers ~460 px and a three-cell layout ~770, while an empty scene is exactly 0, so the gap between
# "a shadow" and "nothing" is unambiguous.
MIN_AO_PIXELS = 100


def lit_pixels(f):
    return int((f > 0).sum())


def nonempty(label, *frames):
    """Guard against the vacuous pass: assert the frames actually contain a shadow."""
    worst = min(lit_pixels(f) for f in frames)
    if worst < MIN_AO_PIXELS:
        print(f"  FAIL  {label:34s} {worst:9d}  (needs >{MIN_AO_PIXELS} lit px) "
              f"-- empty frame, the comparison below would pass vacuously")
        return False
    return True


def need(*names):
    fs = [loss(n) for n in names]
    if any(f is None for f in fs):
        missing = [n for n, f in zip(names, fs) if f is None]
        print(f"  SKIP  missing screenshots: {', '.join(missing)}")
        return None
    return fs


def parity():
    """verify5:scene_aligned -- an aligned layout split across ships == the same layout as one ship.

    This is the property the whole responsibility-weighting scheme exists to produce: hosts each
    carry the merged pattern at 1/(#ships) and SUM back to the single-grid field.
    """
    print("cross-ship aligned-split parity (verify5:scene_aligned)")
    ok = True
    for case in ("row3", "L", "2x2"):
        fs = need(f"aop_{case}_one", f"aop_{case}_split")
        if fs is None:
            ok = False
            continue
        one, split = fs
        if not nonempty(f"{case}: frames contain a shadow", one, split):
            ok = False
            continue
        # Scale-free: compare against the amount of AO in the frame, so a case that happens to cast a
        # bigger shadow is not judged more harshly than a small one.
        denom = max(area(one), 1.0)
        ok &= report(f"{case}: split vs single ship", area(np.abs(one - split)) / denom, 0.02)
    return ok


def rigidity():
    """verify4:scene_translate / scene_rotate -- no drag under sub-block motion or yaw."""
    print("shadow rigidity (verify4:scene_translate, :scene_rotate)")
    ok = True
    tx = ["aor_tx_000", "aor_tx_0125", "aor_tx_025", "aor_tx_0375", "aor_tx_050"]
    yaw = ["aor_yaw_00", "aor_yaw_1125", "aor_yaw_225", "aor_yaw_3375", "aor_yaw_45"]

    fs = need(*tx)
    if fs is None:
        ok = False
    else:
        ok &= nonempty("translate: frames contain a shadow", *fs)
        prof = sweep(tx)
        print(f"        translate sweep 0 -> 0.5 blocks: {[round(p, 4) for p in prof]}")
        ok &= report("translate: jump between steps", worst_jump(prof), 0.06,
                     "(0.16-0.20 is what the drifted variant did)")
        ok &= report("translate: drift over the sweep", worst_drift(prof), 0.06)

    fs = need(*yaw)
    if fs is None:
        ok = False
    else:
        ok &= nonempty("yaw: frames contain a shadow", *fs)
        prof = sweep(yaw)
        print(f"        yaw sweep 0 -> 45 deg:          {[round(p, 4) for p in prof]}")
        # Yaw is allowed a wider band than translation: a rotated square's shadow is genuinely a
        # different shape, and the dominant-slice-axis pick is a known pop risk near 45 degrees.
        ok &= report("yaw: jump between steps", worst_jump(prof), 0.10)
        ok &= report("yaw: drift over the sweep", worst_drift(prof), 0.12)
    return ok


def merging():
    """verify5:scene_pair / scene_rot -- separate ships merge instead of stacking independently."""
    print("cross-ship merging (verify5:scene_pair, :scene_rot)")
    fs = need("aom_single", "aom_pair_z000", "aom_pair_z025", "aom_pair_z050",
              "aom_gaprot_00", "aom_gaprot_225", "aom_gaprot_45")
    if fs is None:
        return False
    single, p000, p025, p050, g00, g225, g45 = fs
    ok = nonempty("merge: frames contain a shadow", single, p000, g00)
    # The direct form of the property: two touching SEPARATE ships must look like the same two cells
    # built as ONE ship. Everything below is a weaker consequence of this.
    two = loss("aom_two_one_ship")
    if two is not None:
        ok &= report("touching pair vs one 2-cell ship",
                     area(np.abs(two - p000)) / max(area(two), 1.0), 0.02)
    # Merged, two touching single-block ships read as ONE two-block shadow. Un-merged (seam3) they
    # each cast a full single-block shadow and the total lands near 2x the single case.
    ratio = area(p000) / max(area(single), 1.0)
    ok &= report("touching pair vs 2x independent", ratio, 1.80,
                 f"(2.0 would mean no merging; single={area(single):.0f})")
    # Sliding one ship sideways must move the merged pattern smoothly, not snap between regimes.
    for name, f in (("z+0.25", p025), ("z+0.50", p050)):
        ok &= report(f"pair {name} vs flush", abs(area(f) - area(p000)) / max(area(p000), 1.0), 0.35)
    # One-block gap, second ship yawing: the merge bridge must survive rotation. Any of these
    # collapsing to the single-ship area means the partner stopped being claimed.
    for name, f in (("0", g00), ("22.5", g225), ("45", g45)):
        ok &= report(f"gap+yaw {name}: still two shadows",
                     max(0.0, 1.0 - area(f) / max(area(single), 1.0)), 0.45,
                     "(1.0 would mean the second ship vanished)")
    return ok


def contact():
    """Band weighting through hover -> flush -> sunk, and the support cutoff."""
    print("contact continuity and support cutoff (seam3 band rule, probe_support)")
    fs = need("aoc_h_000", "aoc_h_025", "aoc_h_050", "aoc_h_075", "aoc_h_100",
              "aoc_h_400", "aoc_empty")
    if fs is None:
        return False
    h000, h025, h050, h075, h100, h400, empty = fs
    # Only the flush frame has to be non-empty here -- h400 and the empty scene are SUPPOSED to be.
    ok = nonempty("contact: flush frame contains a shadow", h000)
    base = max(area(h000), 1.0)
    steps = [area(f) / base for f in (h000, h025, h050, h075, h100)]
    print(f"        hover profile (flush -> +1 block): {[round(s, 3) for s in steps]}")
    # Monotone fade: lifting the ship may only ever reduce the AO it casts.
    worst_rise = max([steps[i + 1] - steps[i] for i in range(len(steps) - 1)] + [0.0])
    ok &= report("hover fade is monotone", worst_rise, 0.05)
    # ... and continuous: no step may drop the whole shadow at once.
    worst_jump = max(steps[i] - steps[i + 1] for i in range(len(steps) - 1))
    ok &= report("hover fade has no cliff", worst_jump, 0.75)
    # Past SEAM_SUPPORT the contribution is exactly zero, so the frame must match the empty scene.
    ok &= report("beyond support == empty scene",
                 abs(area(h400) - area(empty)) / base, 0.02)
    sunk = loss("aoc_sunk_050")
    if sunk is not None:
        # Sinking into the surface must not INCREASE the loss (the slab in front of the face only
        # shrinks), and must not zero it either -- the voxel is still in contact.
        ok &= report("sunk 0.5 does not exceed flush", max(0.0, area(sunk) / base - 1.0), 0.10)
    return ok


def merge_lod():
    """The distance LOD fades merging out instead of switching it off (seam_lod.py).

    Two properties, and the first is the one worth having. Zeroing a pair's claim is not meant to
    APPROXIMATE the unmerged field -- responsibility r = 1/(1 + sum claims) becomes 1 and every
    off-diagonal weight is gone, which is precisely the `aomerge off` path -- so the near end of the
    sweep has to land on that frame exactly, not merely close to it. If it only lands close, the fade
    is doing something else and the tolerance is hiding it.
    """
    print("merge distance LOD (seam_lod.py)")
    # Sweep shots are named by far edge in tenths of a block, ascending here so fs[2:] walks the
    # fade from fully merged to fully faded.
    names = ["aol_far", "aol_off"] + [
        "aol_d%04d" % v for v in (1920, 320, 160, 100, 90, 85, 80, 75, 70, 65, 60, 55, 50, 10)]
    fs = need(*names)
    if fs is None:
        return False
    far, off, d192 = fs[0], fs[1], fs[2]
    d001 = fs[-1]
    ok = nonempty("lod: frames contain a shadow", far, off, d192)
    # The anchors have to actually differ, or every check below passes vacuously on a build where
    # merging never happened at all.
    spread = area(np.abs(far - off)) / max(area(far), 1.0)
    if spread < 0.01:
        print(f"  FAIL  {'lod: merged and unmerged differ':34s} {spread:9.5f}  (needs >0.01) "
              f"-- nothing to fade between, the sweep below is meaningless")
        ok = False
    # Off by config and off by distance must be the SAME path, to the pixel.
    ok &= report("far edge 0 == merging on", area(np.abs(far - d192)) / max(area(far), 1.0), 0.001)
    ok &= report("budget 0 == merging off", area(np.abs(d001 - off)) / max(area(off), 1.0), 0.001)
    # ... and the walk between them may not jump. Measured as a fraction of the whole
    # merged-to-unmerged distance, so a scene with a faint seam is not scored more leniently.
    steps = fs[2:]
    total = max(area(np.abs(far - off)), 1.0)
    jumps = [area(np.abs(steps[i + 1] - steps[i])) / total for i in range(len(steps) - 1)]
    print(f"        fade profile (as fraction of the full swing): {[round(j, 3) for j in jumps]}")
    # 0.40 against a measured 0.26 at half-block spacing. The number that matters is what a
    # REGRESSION would score: a rank dropped whole instead of faded puts the entire swing into one
    # step and reads 1.0, so this has room to spare and still catches the failure it is for.
    ok &= report("no step dominates the fade", max(jumps), 0.40)
    # The fade may only ever travel FROM the merged field TOWARDS the unmerged one. A step that
    # moves back is the tell for a rank being dropped as a block rather than faded, or for the
    # responsibility r not being rebuilt from the faded claims -- both of which would still pass
    # the endpoint checks above, since both endpoints are correct by construction.
    prog = [area(np.abs(s - far)) / total for s in steps]
    print(f"        distance travelled from merged:  {[round(p, 3) for p in prog]}")
    ok &= report("fade never travels backwards",
                 max([prog[i] - prog[i + 1] for i in range(len(prog) - 1)] + [0.0]), 0.05)
    return ok


def main():
    print(f"reading {SHOTS}\n")
    results = [parity(), rigidity(), merging(), contact(), merge_lod()]
    print()
    if all(results):
        print("ALL GROUPS PASS")
        return 0
    print("FAILURES PRESENT")
    return 1


if __name__ == "__main__":
    sys.exit(main())
