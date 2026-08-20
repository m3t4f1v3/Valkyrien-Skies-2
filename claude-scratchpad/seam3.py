"""Occluder-lattice seam AO ("no drag" rework).

The receiver-anchored construction (seam2: merge quads / corner sampling on
the RECEIVING grid) locks the shadow to the receiving lattice's vertices, so a
ship translating or yawing off-grid drags: the field redistributes over fixed
world vertices instead of moving rigidly with the ship.

Here the whole vanilla construction is anchored to the OCCLUDER's lattice
instead: vanilla AO is evaluated in ship space, sliced by the receiving plane.
Per ship:
  - fragment -> ship-local coords, shifted so lattice VERTICES are integers
    (voxel centers sit at k + 0.5).
  - dominant slice axis a = argmax |normal_shiplocal|, sign s toward the
    face's front. Vanilla samples the ONE layer of cells directly in front of
    the face; the continuous generalization is: each ship voxel BAND (unit
    slab of lattice cells along a) is weighted by its overlap length with the
    unit slab in front of the fragment plane. At flush contact the voxel band
    coincides with the slab (weight 1, vanilla-exact); hovering by h or
    sinking below flush by h both fade linearly to 0 at h = 1; bands behind
    the face never intersect the slab (a ship behind a ceiling casts nothing);
    and at most two bands are ever active, so multi-deck ships lerp between
    their layers. No discontinuity anywhere in ship pose.
  - the fragment's in-plane ship cell (cu, cv) + its 4 corners at the sampled
    plane; per corner the vanilla AoFaceData sample set = the 4 layer cells
    around it: edge-u, edge-v, diagonal (L-rule: counted solid whenever both
    edges are solid), and the front cell (the fragment's own column).
    Occupancy comes from the ship's voxel list, which is static in ship space
    -> the field is RIGID under ship translation and yaw, zero drag.
  - corner loss = 0.2 * count * fade, then ONE two-triangle interpolation at
    the fragment's in-plane fract uv with the sodium crease flip (seam2.
    tri_interp). Per-ship corner sums BEFORE interpolation keep big-ship
    interiors flat at 0.8 (summing per-voxel interpolated tents waffles to
    0.6 mid-cell).
Fields sum across ships (vanilla sums per-sample losses), clamped MAX_TOTAL.

Known limitation: the dominant-axis pick pops when a ship pitches/rolls
through 45 deg relative to the receiving face (epsilon-tiebroken, so it is at
least deterministic). Yaw over a floor never flips it.
"""
import numpy as np

from seam2 import quat_rotate, quat_rotate_inv, tri_interp

STRENGTH = 0.2
MAX_TOTAL = 0.8
CAND_RADIUS = 2.5


def _ship_field(fragLocal, nL, cells, collect=None):
    """Loss field of ONE ship, in ship space. fragLocal: fragment in
    ship-local coords shifted so lattice vertices are integers. nL: receiving
    normal in ship space. cells: set of integer (min-corner) voxel cells."""
    absN = np.abs(nL)
    # dominant slice axis, epsilon-tiebroken toward the earliest axis so exact
    # 45-degree ties don't flicker on FP noise
    a = 0
    if absN[1] > absN[a] + 1e-6:
        a = 1
    if absN[2] > absN[a] + 1e-6:
        a = 2
    s = 1.0 if nL[a] >= 0.0 else -1.0
    u, v = (a + 1) % 3, (a + 2) % 3

    pa = fragLocal[a]
    # the unit slab directly in FRONT of the fragment plane, along a
    slabLo = pa if s > 0 else pa - 1.0
    slabHi = slabLo + 1.0

    cu, cv = int(np.floor(fragLocal[u])), int(np.floor(fragLocal[v]))
    fu, fv = fragLocal[u] - cu, fragLocal[v] - cv

    corner = np.zeros(4)
    # candidate voxel bands overlapping the slab (at most two have weight > 0)
    for band in (int(np.floor(slabLo)), int(np.floor(slabLo)) + 1):
        w = min(band + 1.0, slabHi) - max(float(band), slabLo)
        if w <= 1e-9:
            continue

        def solid(du, dv):
            cell = [0, 0, 0]
            cell[a] = band
            cell[u] = cu + du
            cell[v] = cv + dv
            return tuple(cell) in cells

        front = solid(0, 0)
        for i, (du, dv) in enumerate(((0, 0), (1, 0), (0, 1), (1, 1))):
            eu = solid(1 if du else -1, 0)
            ev = solid(0, 1 if dv else -1)
            if eu and ev:
                co = True            # L-rule: corner counted as the edges
            else:
                co = solid(1 if du else -1, 1 if dv else -1)
            corner[i] += STRENGTH * (eu + ev + co + front) * w
    if collect is not None:
        collect.append(dict(a=a, s=s, cell=(cu, cv), corner=corner.copy()))
    return tri_interp(corner, fu, fv)


def ship_ao(frag, normal, occluders, self_ship=-1, collect=None):
    """occluders: iterable of (voxelCenterWorld, quat, shipIndex), grouped by
    ship (contiguous runs, as VsShipOccluderList populates per ship)."""
    frag = np.asarray(frag, float)
    normal = np.asarray(normal, float)
    total = 0.0

    # group into per-ship runs
    runs = []
    for vox, q, ship in occluders:
        if runs and runs[-1][0] == ship:
            runs[-1][2].append(np.asarray(vox, float))
        else:
            runs.append((ship, np.asarray(q, float), [np.asarray(vox, float)]))

    for ship, q, voxels in runs:
        if ship == self_ship:
            continue
        anchor = voxels[0]
        # lattice coords: anchor voxel center -> 0.5,0.5,0.5 so vertices land
        # on integers
        fragLocal = quat_rotate_inv(q, frag - anchor) + 0.5
        nL = quat_rotate_inv(q, normal)
        # No distance prefilter: the 3x3-cells-by-2-bands sampling window IS
        # the exact support of the field, so occupancy just gets collected and
        # far voxels naturally contribute nothing.
        cells = {tuple(np.round(quat_rotate_inv(q, vx - anchor)).astype(int))
                 for vx in voxels}
        total += _ship_field(fragLocal, nL, cells, collect)
    return min(total, MAX_TOTAL)
