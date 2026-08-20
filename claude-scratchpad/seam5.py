"""seam4 semantics restructured for the optimized shader (per-pair claims).

Same field as seam4 (occluder-lattice vanilla AO, cross-ship merging via
responsibility-weighted soft occupancy), but all merge bookkeeping is
FRAME-CONSTANT so the CPU can precompute it and the shader carries no
per-fragment pairwise passes or fade arrays:

  - claims are per SHIP PAIR, not per voxel:
        claim(s -> host t) = gate(pairDist) * o_rot(relative rot) * o_trans
    pairDist  = distance between the ships' voxel-center AABBs (Java proxy
                for nearest approach),
    o_rot     = mean column alignment of the relative rotation matrix,
                remapped from [1/sqrt2, 1] to [0, 1] (1 when the lattices are
                rotation-aligned, 0 at 45 deg),
    o_trans   = prod_i (1 - |fract(anchor offset in t's lattice) - 0.5|).
    r_ship = 1 / (1 + sum_t claim(s, t)) is uniform per ship.
  - no per-fragment fades: culling is by exact support instead. A voxel's
    tent contribution dies within SUPPORT (< 4.5) of the fragment, so source
    voxels beyond that are exactly zero. A ship must be evaluated as HOST
    within SUPPORT + G_ZERO (7.5): its lattice can carry foreign material
    injected at the owner's voxel positions even where its own voxels are far
    (claims require AABBs within G_ZERO, so a claiming host is always inside
    that margin -- nothing is ever lost, and culled content is exactly 0, so
    there is nothing to fade).

Aligned-limit exactness is unchanged: aligned + within G_FULL means gate = 1,
o_rot = o_trans = 1 uniformly, the same half/third splits as seam4. What
changed vs seam4: merge strength is uniform along a ship instead of per-voxel
graded, and single-voxel ships are EXACTLY seam4 (o_trans(anchor) is the old
per-voxel o of that voxel).
"""
import numpy as np

from seam2 import quat_rotate_inv, tri_interp

STRENGTH = 0.2
MAX_TOTAL = 0.8
G_FULL = 2.2      # AABB pair distance below which the merge gate is 1
G_ZERO = 3.0      # ... and above which it is 0
SUPPORT = 3.7     # tent support radius: source voxels beyond this are zero
                  # (trimmed from a conservative 4.5; the tent product is
                  # exactly 0 before this, so it is drift-free -- see
                  # probe_support.py -- while culling more voxels per fragment)
MAX_PARTNERS = 4  # top-N claims kept per ship (ship directory row); r counts
                  # ONLY these so every retained claim share materializes.
                  # 4 keeps a row of 5 mutually-near ships exact; only denser
                  # piles (6+ ships all within the gate) truncate, and they
                  # degrade gracefully (mass stays conserved).
OROT_LO = 1.0 / np.sqrt(2.0)     # column alignment at 45 deg


def _quat_to_mat(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])


def pair_claim(qs, anchors, qt, anchort, aabbs, aabbt):
    """Frame-constant claim of ship s's voxels by host t (CPU model)."""
    # gate: distance between voxel-center AABBs
    gap = np.linalg.norm(np.maximum(
        np.maximum(aabbt[0] - aabbs[1], aabbs[0] - aabbt[1]), 0.0))
    g = np.clip((G_ZERO - gap) / (G_ZERO - G_FULL), 0.0, 1.0)
    if g <= 0.0:
        return 0.0
    # rotation alignment: mean max|component| of the relative matrix columns
    R = _quat_to_mat(qt).T @ _quat_to_mat(qs)
    m = np.mean(np.max(np.abs(R), axis=0))
    o_rot = np.clip((m - OROT_LO) / (1.0 - OROT_LO), 0.0, 1.0)
    # translation alignment of s's anchor in t's lattice
    c = quat_rotate_inv(qt, anchors - anchort) + 0.5
    o_trans = np.prod([1.0 - abs((ci - np.floor(ci)) - 0.5) for ci in c])
    return g * o_rot * o_trans


def ship_ao(frag, normal, occluders, self_ship=-1):
    frag = np.asarray(frag, float)
    normal = np.asarray(normal, float)

    # per-ship runs (buffer order: grouped by ship) -- FULL runs; culling
    # below is by distance, mirroring the shader's sphere tests
    ships = []          # (shipIdx, quat, anchor, [voxels], aabb)
    for vox, q, ship in occluders:
        vox = np.asarray(vox, float)
        if ships and ships[-1][0] == ship:
            ships[-1][3].append(vox)
        else:
            ships.append([ship, np.asarray(q, float), vox, [vox]])
    for s in ships:
        vs = np.array(s[3])
        s.append((vs.min(axis=0), vs.max(axis=0)))

    # frame-constant per-pair claims, truncated to each ship's TOP
    # MAX_PARTNERS partners (the ship-directory row the shader reads); r
    # counts only the retained claims so every share materializes somewhere
    S = len(ships)
    claim = np.zeros((S, S))
    for i in range(S):
        for j in range(S):
            if i == j or ships[i][0] == ships[j][0]:
                continue
            claim[i, j] = pair_claim(ships[i][1], ships[i][2],
                                     ships[j][1], ships[j][2],
                                     ships[i][4], ships[j][4])
    for i in range(S):
        order = np.argsort(claim[i])[::-1]
        claim[i, order[MAX_PARTNERS:]] = 0.0
    r = 1.0 / (1.0 + claim.sum(axis=1))

    total = 0.0
    for si in range(S):
        shipIdx, q, anchor, _, _ = ships[si]
        # host cull, matching the shader's source∪partner discovery: a host
        # is evaluated when its OWN voxels are in support (it is a source) or
        # when a source ship claims it (its lattice carries that share)
        near = any(np.linalg.norm(v - frag) <= SUPPORT for v in ships[si][3])
        if not near:
            near = any(claim[sj, si] > 0.0
                       and any(np.linalg.norm(v - frag) <= SUPPORT
                               for v in ships[sj][3])
                       for sj in range(S))
        if not near:
            continue

        fragL = quat_rotate_inv(q, frag - anchor) + 0.5
        nL = quat_rotate_inv(q, normal)
        absN = np.abs(nL)
        a = 0
        if absN[1] > absN[a] + 1e-6:
            a = 1
        if absN[2] > absN[a] + 1e-6:
            a = 2
        u, v = (a + 1) % 3, (a + 2) % 3
        sgn = 1.0 if nL[a] >= 0.0 else -1.0
        pa = fragL[a]
        slabLo = pa if sgn > 0 else pa - 1.0
        b0 = int(np.floor(slabLo))
        w0 = np.clip(b0 + 1.0 - slabLo, 0.0, 1.0)
        cu, cv = int(np.floor(fragL[u])), int(np.floor(fragL[v]))
        fu, fv = fragL[u] - cu, fragL[v] - cv

        occ = np.zeros((2, 3, 3))               # [band, dv+1, du+1]
        for sj in range(S):
            if ships[sj][0] == self_ship:
                continue                        # own AO is baked
            wBase = r[sj] if sj == si else r[sj] * claim[sj, si]
            if wBase <= 0.0:
                continue
            for vw in ships[sj][3]:
                if np.linalg.norm(vw - frag) > SUPPORT:
                    continue                    # perf only: tents are 0 there
                c = quat_rotate_inv(q, vw - anchor) + 0.5
                for b in (0, 1):
                    ta = max(0.0, 1.0 - abs(c[a] - (b0 + b + 0.5)))
                    if ta <= 0.0:
                        continue
                    for dv in (-1, 0, 1):
                        tv = max(0.0, 1.0 - abs(c[v] - (cv + dv + 0.5)))
                        if tv <= 0.0:
                            continue
                        for du in (-1, 0, 1):
                            tu = max(0.0, 1.0 - abs(c[u] - (cu + du + 0.5)))
                            if tu <= 0.0:
                                continue
                            occ[b, dv + 1, du + 1] += wBase * ta * tu * tv
        occ = np.minimum(occ, 1.0)

        corner = np.zeros(4)
        for b, w in ((0, w0), (1, 1.0 - w0)):
            if w <= 0.0 or not occ[b].any():
                continue
            for ci, (du, dv) in enumerate(((0, 0), (1, 0), (0, 1), (1, 1))):
                qA = occ[b, dv, du]
                qB = occ[b, dv, du + 1]
                qC = occ[b, dv + 1, du]
                qD = occ[b, dv + 1, du + 1]
                bonus = (max(0.0, min(qA, qD) - max(qB, qC))
                         + max(0.0, min(qB, qC) - max(qA, qD)))
                corner[ci] += STRENGTH * (qA + qB + qC + qD + bonus) * w
        total += tri_interp(corner, fu, fv)
    return min(total, MAX_TOTAL)
