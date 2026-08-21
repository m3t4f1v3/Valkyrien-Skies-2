#!/usr/bin/env python3
"""Compare the game's cross-ship merging against the scratchpad reference (seam5) on SLIGHTLY
misaligned and slightly off-grid configurations.

At exact alignment the merge is exact by construction, and autotest/ao_parity.txt already shows the
game lands on the single-ship field to the pixel. This checks the regime that actually exercises the
merging arithmetic: a neighbouring ship a few degrees off, or a fraction of a block off the lattice,
where seam5's pair claim

    claim(s -> t) = gate(AABB gap) x o_rot(rotation alignment) x o_trans(lattice offset)

is a FRACTION, responsibility r = 1/(1 + sum claims) redistributes between the hosts, and each host
carries only part of the merged pattern.

Method. Both sides produce one scalar per configuration: the integral of the AO loss over the floor,
outside the ships' own footprints.

  * game      -- sum of the loss field (debugFloodPaint=5 paints it into red) over the pixels the
                 masking shot says are floor rather than ship.
  * reference -- seam5.ship_ao integrated over the floor plane on a fine grid, with the ships'
                 footprint cells excluded exactly as verify5's `buried` does.

The two are then normalised by their own aligned (first) configuration, so only the SHAPE of the
curve is compared and the pixels-per-block scale drops out entirely. A merge that degrades
differently from the reference as alignment is lost shows up as the curves diverging.

  usage: python3 autotest/ref_merge.py [screenshot-dir]
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "claude-scratchpad"))
try:
    from seam5 import ship_ao as seam5_ao
    from viz import IDENT, yaw_quat
except ImportError as exc:  # pragma: no cover - the scratchpad is gitignored
    print(f"reference unavailable: {exc}")
    print("This check needs claude-scratchpad/{seam5,viz}.py -- the Python reference the shader was "
          "ported from. Without it there is nothing to compare against, so this is a hard stop "
          "rather than a pass.")
    sys.exit(2)

SHOTS = sys.argv[1] if len(sys.argv) > 1 else "forge/run/screenshots"

# The debug paint keeps a 1e-3 trace of the real colour so the driver cannot dead-strip the samplers.
TRACE = 3
# Floor plane and cell layout of the fixture: two 1-block ships resting on the superflat surface at
# world cells (2, 3) and (3, 3). ao_misalign.txt builds exactly this.
FLOOR_Y = 1.0
SHIP_A = (2, 3)
SHIP_B = (3, 3)
UP = np.array([0.0, 1.0, 0.0])
# Integration window around the pair, comfortably past SEAM_SUPPORT so nothing is clipped.
EXTENT = (-2.0, 8.0, -2.0, 8.0)
RES = 110


def field(name):
    path = os.path.join(SHOTS, name + ".png")
    if not os.path.exists(path):
        return None
    a = np.asarray(Image.open(path).convert("RGB")).astype(float)
    red = a[:, :, 0].copy()
    red[red <= TRACE] = 0.0
    return red / 255.0


def ship_mask(name):
    """Pixels showing a ship rather than floor, from the ordinary (paint 0) render.

    The ships are stone and the ground is grass, so 'roughly grey' separates them. This is only used
    to EXCLUDE pixels, so a slightly generous mask costs a little floor area on every configuration
    equally and cannot bias the curve.
    """
    path = os.path.join(SHOTS, name + ".png")
    if not os.path.exists(path):
        return None
    a = np.asarray(Image.open(path).convert("RGB")).astype(float)
    r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    return (np.abs(r - g) < 18) & (np.abs(g - b) < 18) & (r > 40)




def game_integral(base):
    """Floor-only AO mass for one configuration, or None if either shot is missing."""
    f = field(base)
    m = ship_mask(base + "_mask")
    if f is None or m is None:
        return None
    return float(f[~m].sum())


def occluders(yaw_deg=0.0, off_z=0.0):
    """The fixture's two ships as seam5 occluders: voxel centre, quaternion, ship index."""
    a = (SHIP_A[0] + 0.5, FLOOR_Y + 0.5, SHIP_A[1] + 0.5)
    b = (SHIP_B[0] + 0.5, FLOOR_Y + 0.5, SHIP_B[1] + 0.5 + off_z)
    return [(a, IDENT, 1), (b, yaw_quat(yaw_deg) if yaw_deg else IDENT, 2)]


def ref_integral(yaw_deg=0.0, off_z=0.0):
    """seam5's loss integrated over the floor, with the two footprint cells excluded."""
    occ = occluders(yaw_deg, off_z)
    x0, x1, z0, z1 = EXTENT
    xs = np.linspace(x0, x1, RES)
    zs = np.linspace(z0, z1, RES)
    buried = {SHIP_A, SHIP_B}
    total = 0.0
    for z in zs:
        for x in xs:
            if (int(np.floor(x)), int(np.floor(z))) in buried:
                continue
            total += seam5_ao(np.array([x, FLOOR_Y, z]), UP, occ)
    # cell area per sample, so the number is an area integral rather than a sample count
    return total * ((x1 - x0) / RES) * ((z1 - z0) / RES)


def curve(label, names, params, limit):
    """Print and score one sweep: game vs reference, both normalised to their first point."""
    game = [game_integral(n) for n in names]
    if any(g is None for g in game):
        missing = [n for n, g in zip(names, game) if g is None]
        print(f"  SKIP  {label}: missing {', '.join(missing)}")
        return False
    if game[0] <= 0:
        print(f"  FAIL  {label}: the aligned configuration has no AO at all -- "
              f"every ratio below would be meaningless")
        return False
    ref = [ref_integral(**p) for p in params]

    g = np.array(game) / game[0]
    r = np.array(ref) / ref[0]
    worst = float(np.max(np.abs(g - r)))
    print(f"  {'PASS' if worst <= limit else 'FAIL'}  {label:28s} max|game-ref| = {worst:.4f} "
          f"(limit {limit})")
    print(f"        game {np.round(g, 4).tolist()}")
    print(f"        ref  {np.round(r, 4).tolist()}")
    return worst <= limit


def main():
    print(f"reading {SHOTS}")
    print("cross-ship merging vs seam5 on slight misalignment\n")
    ok = True
    # Rotational misalignment. o_rot falls off with the mean column-max of the relative rotation, so
    # a few degrees is a small but real reduction in the claim.
    degs = [0, 3, 6, 9, 12, 15]
    ok &= curve("slight yaw 0-15 deg",
                [f"aox_rot_{d:02d}" for d in degs],
                [{"yaw_deg": float(d)} for d in degs],
                0.05)
    # Off-grid translation. o_trans is the product over axes of 1 - |fract(anchor) - 0.5|, so half a
    # block is the worst case and the claim should decay smoothly to it. Anchored on the ALIGNED
    # configuration -- aox_rot_00 is that same scene (yaw 0, offset 0) -- so a discontinuity right at
    # alignment, where the claim is exactly 1 and the merge is exact, cannot hide outside the sweep.
    offs = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5]
    ok &= curve("off-grid 0-0.5 blocks",
                ["aox_rot_00"] + [f"aox_off_{int(o * 10):02d}" for o in offs[1:]],
                [{"off_z": float(o)} for o in offs],
                0.05)
    print()
    print("MATCHES REFERENCE" if ok else "DIVERGES FROM REFERENCE")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
