"""Occluder-lattice seam AO with CROSS-SHIP merging (extends seam3).

seam3 evaluates one independent vanilla field per ship, then sums. For
aligned convex layouts the sum happens to equal the single-grid vanilla
result, but nearby blocks of DIFFERENT ships never merge: no cross-ship
L-rule corners, and misaligned/rotated neighbors read as two overlapping
shadows instead of one pattern.

Here every ship's lattice can HOST foreign material:

  - each voxel v is soft-injected into every nearby other ship s's lattice as
    tent-spread occupancy (product of per-axis 1 - |distance to cell center|
    over the 3x3x2 window cells), scaled by
      g_s(v)  proximity gate: 1 while v sits within G_FULL of one of s's own
              voxels, fading to 0 at G_ZERO (ships that pull apart separate
              back into independent fields, continuously), and
      o_s(v)  alignment: prod_i (1 - |frac(c_i) - 0.5|), 1 when v sits exactly
              on an s-lattice cell, 0.125 at worst half-offset.
  - each voxel carries a responsibility weight
      r_v = 1 / (1 + sum_s g_s(v) * o_s(v))
    so its material totals ~1 across all lattices that render it: at perfect
    alignment two coincident lattices each carry the merged pattern at half
    strength and SUM to exactly the single-grid vanilla field -- including
    cross-ship L-rule corners (min() soft L-rule is homogeneous in r).
    Isolated ships have r = 1 and reduce to seam3 exactly.
  - per host lattice the field is seam3's: slab-overlap band weights, vanilla
    AoFaceData corner counts (soft: cell occupancy in [0,1], L-rule
    co = max(co, min(eu, ev))), one two-triangle interpolation. Hosts sum,
    clamp MAX_TOTAL.

Self-ship handling (fragment on a ship's own face): the ship's OWN voxels are
excluded from every mask (their AO is baked), but its lattice still hosts
foreign voxels -- otherwise the half of a parked ship's shadow that migrated
into the deck-ship's lattice would vanish.
"""
import numpy as np

from seam2 import quat_rotate_inv, tri_interp

STRENGTH = 0.2
MAX_TOTAL = 0.8
# Everything below is computed from a single per-fragment GATHER of voxels
# within R_GATHER of the fragment, so the shader needs one pass and a small
# register set. Every gathered voxel carries a SUPPORT FADE over
# [FADE_LO, FADE_HI] multiplying all of its contributions (occupancy and
# claims), so nothing pops when a voxel crosses the gather boundary. The
# ALIGNED field's support ends at 2.6 blocks, safely inside FADE_LO, so
# vanilla parity is untouched; only far misaligned tent bleed (tiny values)
# gets faded.
R_GATHER = 3.5
FADE_LO = 2.8
FADE_HI = 3.4
# Merge gate on the pair distance (voxel center to a voxel of the other
# ship): 1 below G_FULL so every strongly-interacting pair merges with FULL
# claims -- partial claims make per-host corner proportions asymmetric, the
# crease flips disagree between hosts, and the aligned-limit exactness breaks.
# (The 1-block-gap pair is 2.0 apart, so G_FULL > 2.0.)
G_FULL = 2.2
G_ZERO = 3.0


def _tent(d):
    return max(0.0, 1.0 - abs(d))


def ship_ao(frag, normal, occluders, self_ship=-1):
    frag = np.asarray(frag, float)
    normal = np.asarray(normal, float)

    # GATHER: per-ship runs of the voxels within R_GATHER of the fragment
    # (buffer order: grouped by ship)
    ships = []          # (shipIdx, quat, anchor, [voxel world centers])
    lastShip = None
    for vox, q, ship in occluders:
        vox = np.asarray(vox, float)
        inR = np.linalg.norm(vox - frag) <= R_GATHER
        if ships and lastShip == ship and ships[-1][0] == ship:
            if inR:
                ships[-1][3].append(vox)
        elif inR:
            ships.append((ship, np.asarray(q, float), vox, [vox]))
        lastShip = ship
    ships = [s for s in ships if s[3]]

    # every gathered voxel, with its owning ship run
    voxels = []         # (world, ownerRunIdx)
    for si, (_, _, _, vs) in enumerate(ships):
        for vx in vs:
            voxels.append((vx, si))

    # ship-lattice coords of everything, once per (thing, ship) pair
    def lat(p, si):
        _, q, anchor, _ = ships[si]
        return quat_rotate_inv(q, p - anchor) + 0.5

    # support fade of each gathered voxel by its fragment distance
    fade = np.array([np.clip((FADE_HI - np.linalg.norm(vw - frag))
                             / (FADE_HI - FADE_LO), 0.0, 1.0)
                     for vw, _ in voxels])

    # per (voxel, host) claim = gate * claimant fade * alignment; r from the
    # sum. gate*fade taken as the max over the host's gathered voxels so
    # every term is continuous in each voxel's position.
    claims = np.zeros((len(voxels), len(ships)))
    for vi, (vw, owner) in enumerate(voxels):
        for wi, (ww, wowner) in enumerate(voxels):
            if wowner == owner:
                continue
            g = np.clip((G_ZERO - np.linalg.norm(vw - ww))
                        / (G_ZERO - G_FULL), 0.0, 1.0)
            gf = g * fade[wi]
            if gf <= claims[vi, wowner] and claims[vi, wowner] > 0.0:
                continue
            claims[vi, wowner] = max(claims[vi, wowner], gf)
    for vi, (vw, owner) in enumerate(voxels):
        for si in range(len(ships)):
            if claims[vi, si] > 0.0:
                c = lat(vw, si)
                o = np.prod([1.0 - abs((ci - np.floor(ci)) - 0.5) for ci in c])
                claims[vi, si] *= o
    r = 1.0 / (1.0 + claims.sum(axis=1))

    total = 0.0
    for si, (shipIdx, q, anchor, _) in enumerate(ships):
        fragL = lat(frag, si)
        nL = quat_rotate_inv(q, normal)
        absN = np.abs(nL)
        a = 0
        if absN[1] > absN[a] + 1e-6:
            a = 1
        if absN[2] > absN[a] + 1e-6:
            a = 2
        u, v = (a + 1) % 3, (a + 2) % 3
        s = 1.0 if nL[a] >= 0.0 else -1.0
        pa = fragL[a]
        slabLo = pa if s > 0 else pa - 1.0
        b0 = int(np.floor(slabLo))
        w0 = np.clip(b0 + 1.0 - slabLo, 0.0, 1.0)
        cu, cv = int(np.floor(fragL[u])), int(np.floor(fragL[v]))
        fu, fv = fragL[u] - cu, fragL[v] - cv

        # soft occupancy of the 3x3x2 window cells in THIS lattice
        occ = np.zeros((2, 3, 3))               # [band, dv+1, du+1]
        for vi, (vw, owner) in enumerate(voxels):
            if ships[owner][0] == self_ship:
                continue                        # own AO is baked, everywhere
            if owner == si:
                weight = r[vi] * fade[vi]       # own lattice: exact cell
            else:
                # foreign: inject the claimed share; the tent spread below
                # sums to 1 per axis, so the voxel's total mass across all
                # lattices stays fade * r * (1 + sum claims) = fade
                weight = r[vi] * claims[vi, si] * fade[vi]
            if weight <= 0.0:
                continue
            c = lat(vw, si)
            for b in (0, 1):
                ta = _tent(c[a] - (b0 + b + 0.5))
                if ta <= 0.0:
                    continue
                for dv in (-1, 0, 1):
                    tv = _tent(c[v] - (cv + dv + 0.5))
                    if tv <= 0.0:
                        continue
                    for du in (-1, 0, 1):
                        tu = _tent(c[u] - (cu + du + 0.5))
                        if tu <= 0.0:
                            continue
                        occ[b, dv + 1, du + 1] += weight * ta * tu * tv
        occ = np.minimum(occ, 1.0)

        corner = np.zeros(4)
        for b, w in ((0, w0), (1, 1.0 - w0)):
            if w <= 0.0 or not occ[b].any():
                continue
            # Per vertex: the 4 quadrant cells around it, plus a diagonal-pair
            # bonus. Equals vanilla AoFaceData (edge + edge + L-rule corner +
            # front) on every hard config with an air front cell, but is
            # SYMMETRIC in the quadrants: vanilla's per-cell edge/corner
            # labeling is only cross-cell consistent when front cells are
            # hard air, and with soft occupancy (a voxel partially covering
            # the fragment's column) the labeled form jumps when the
            # fragment's lattice cell changes.
            for ci, (du, dv) in enumerate(((0, 0), (1, 0), (0, 1), (1, 1))):
                qA = occ[b, dv, du]              # cell (du-1, dv-1)
                qB = occ[b, dv, du + 1]          # cell (du,   dv-1)
                qC = occ[b, dv + 1, du]          # cell (du-1, dv)
                qD = occ[b, dv + 1, du + 1]      # cell (du,   dv)
                bonus = (max(0.0, min(qA, qD) - max(qB, qC))
                         + max(0.0, min(qB, qC) - max(qA, qD)))
                corner[ci] += STRENGTH * (qA + qB + qC + qD + bonus) * w
        total += tri_interp(corner, fu, fv)
    return min(total, MAX_TOTAL)
