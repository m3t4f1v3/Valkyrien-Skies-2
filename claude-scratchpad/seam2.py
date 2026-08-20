"""Spec-faithful seam AO (the algorithm as described, quaternion-general).

Differs from seam.py (the current GLSL) in the merge construction:
  seam.py : BOTH cross-pairs get half-stepped (two midpoints M0/M1, half-step
            runs ALONG the seam toward the other pair), each pair lerps toward
            the OTHER pair's midpoint, falloff = occluder-local AABB distance.
  here    : per the spec -- the subtend line is the two B-SIDE matched
            vertices shifted by the ship-space half-step whose axis has the
            SMALLER |dot| with the B-pair vector (i.e. perpendicular to the
            seam, into the occluder face interior). One midpoint M. The two
            A-SIDE vertices lerp toward M by t = f(their pair distance).
            Falloff = Euclidean distance to the merged quad (su, sv, m1, m0).

f is pluggable ("some function of the pair wise distance"). Default:
f(d) = clamp(d / REACH). Rationale: by pair distance REACH the A-side has
fully collapsed onto the occluder face, so the merged quad sits >= REACH away
from any fragment whose own cell no longer touches the occluder -- the
contribution dies exactly where the neighboring cell's reconstruction starts
from zero, which is what makes the field continuous across cell borders and
exactly vanilla at flush contact.

Falloff: AABB of the merged quad in OCCLUDER-LOCAL space, per-axis exterior
offsets combined MULTIPLICATIVELY: w = prod_i clamp(1 - ex_i/REACH), evaluated
at the 4 CORNERS of the receiving face (per corner: max over occluder faces,
sum over voxels, clamp MAX_TOTAL), then interpolated at the fragment with the
vanilla two-triangle rule (tri_interp). Evaluating the product directly at the
fragment gives TRUE bilinear -- curved hyperbolic iso-contours -- whereas the
game's rasterizer interpolates per-vertex AO linearly over two triangles with
a sharp crease; corner sampling + tri_interp reproduces that per fragment.
(Euclidean distance instead of the product rounds corners radially; summing
the exteriors -- Manhattan, as the old GLSL did -- decays diagonals twice as
fast as vanilla.)
"""
import numpy as np

REACH = 1.0
MATCH_REACH = 2.0
STRENGTH = 0.2
MAX_TOTAL = 0.8
CAND_RADIUS = 2.5
# Falloff cutoff: the raw product falloff w is remapped so anything below
# CUTOFF becomes 0 and [CUTOFF, 1] rescales to [0, 1] (continuous -- a hard
# step would draw a visible iso-contour ring). 0.0 = identity (user-confirmed
# correct); kept as a tunable.
CUTOFF = 0.0


def quat_rotate(q, v):
    xyz = q[:3]
    return v + 2.0 * np.cross(xyz, np.cross(xyz, v) + q[3] * v)


def quat_rotate_inv(q, v):
    xyz = -q[:3]
    return v + 2.0 * np.cross(xyz, np.cross(xyz, v) + q[3] * v)


def local_face(c, nrm):
    a = np.abs(nrm)
    u = np.array([0, 1, 0.]) if a[0] > 0.5 else np.array([1, 0, 0.])
    v = np.array([0, 1, 0.]) if a[2] > 0.5 else np.array([0, 0, 1.])
    return [c - u * 0.5 - v * 0.5, c + u * 0.5 - v * 0.5,
            c - u * 0.5 + v * 0.5, c + u * 0.5 + v * 0.5]


def aabb_falloff(p, pts, vox, q):
    """prod_i clamp(1 - ex_i/REACH) where ex = per-axis exterior offset of the
    evaluation point p from the AABB of pts, everything in occluder-local
    space. p is a receiving-face CORNER (the field is sampled per corner and
    interpolated vanilla-style, see tri_interp)."""
    loc = [quat_rotate_inv(q, c - vox) for c in pts]
    lo = np.min(loc, axis=0)
    hi = np.max(loc, axis=0)
    fl = quat_rotate_inv(q, p - vox)
    ex = np.maximum(np.maximum(lo - fl, fl - hi), 0.0)
    w = float(np.prod(np.clip(1 - ex / REACH, 0, 1)))
    return float(np.clip((w - CUTOFF) / (1.0 - CUTOFF), 0.0, 1.0))


def tri_interp(c, u, v):
    """Vanilla/sodium-style interpolation of 4 corner values across the face:
    the rasterizer interpolates per-vertex AO linearly over the quad's two
    TRIANGLES (sharp crease along the split diagonal), not bilinearly. Sodium
    (ModelQuadOrientation.orientByBrightness) puts the crease through the
    opposite corner pair with the greater brightness -- in loss terms, the
    SMALLER loss sum. The two splits coincide identically when the sums tie,
    so the flip is continuous. c = (L00, L10, L01, L11), u/v in [0, 1]."""
    L00, L10, L01, L11 = c
    if L00 + L11 <= L10 + L01:  # crease through 00-11
        if u >= v:
            return L00 + (L10 - L00) * u + (L11 - L10) * v
        return L00 + (L11 - L01) * u + (L01 - L00) * v
    if u + v <= 1.0:            # crease through 10-01
        return L00 + (L10 - L00) * u + (L01 - L00) * v
    return L11 + (L01 - L11) * (1 - u) + (L10 - L11) * (1 - v)


def f_linear(d):
    return np.clip(d / REACH, 0.0, 1.0)


def f_smooth(d):
    t = np.clip(d / REACH, 0.0, 1.0)
    return t * t * (3 - 2 * t)


def seam_ao(frag, normal, occluders, self_ship=-1, collect=None, f=f_linear):
    absN = np.abs(normal)
    uA = np.array([0, 1, 0.]) if absN[0] > 0.5 else np.array([1, 0, 0.])
    vA = np.array([0, 1, 0.]) if absN[2] > 0.5 else np.array([0, 0, 1.])
    uC = np.floor(np.dot(frag, uA)) + 0.5
    vC = np.floor(np.dot(frag, vA)) + 0.5
    nP = np.floor(np.dot(frag, normal) + 0.5)
    faceC = normal * nP + uA * uC + vA * vC
    A = local_face(faceC, normal)

    # Loss is accumulated PER RECEIVING-FACE CORNER (A order: 00,10,01,11) and
    # interpolated at the fragment with the vanilla two-triangle rule at the
    # end -- per-fragment evaluation of exactly what the rasterizer would do
    # with per-vertex AO, sharp diagonal creases included.
    corner = np.zeros(4)
    for vox, q, ship in occluders:
        vox = np.asarray(vox, float)
        q = np.asarray(q, float)
        if ship == self_ship:
            continue
        if np.linalg.norm(vox - faceC) > CAND_RADIUS:
            continue
        front = np.dot(normal, vox - faceC)
        if front < 1e-4:
            continue
        tsl = quat_rotate_inv(q, faceC - vox)
        L = np.linalg.norm(tsl)
        if L < 1e-5:
            continue

        # Consider EVERY occluder face whose outward normal points toward the
        # fragment's cell (up to 3) and keep the max contribution. A discrete
        # dominant-axis pick flips between adjacent receiving cells (e.g. at
        # yaw 45 the cardinal cells see the bottom face, diagonal cells a side
        # face) and each flip is a visible border step; max of the per-face
        # fields stays continuous when the argmax switches, and in aligned
        # cases the candidate faces share the seam edge so they tie exactly.
        frontW = np.clip(front / 0.25, 0, 1)
        contrib = np.zeros(4)
        bestg = None
        for axis in range(3):
            if abs(tsl[axis]) < 1e-6:
                continue
            nB = np.zeros(3)
            nB[axis] = np.sign(tsl[axis])
            B = [vox + quat_rotate(q, c) for c in local_face(nB * 0.5, nB)]
            nBw = quat_rotate(q, nB)

            # Parallel facing face (occluder hovering over/against this
            # face): there is no seam -- every corner pair ties and the
            # 2-pair pick is a loop-order artifact. The correct merged quad
            # is B's face itself (the footprint shadow), which matches the
            # neighboring cells' seam curtains at the borders.
            if np.dot(normal, nBw) < -0.9:
                c = np.array([STRENGTH * aabb_falloff(A[i], B, vox, q) * frontW
                              for i in range(4)])
                if c.sum() > contrib.sum():
                    bestg = dict(vox=vox, A=A, B=B, pairs=(), sub=None,
                                 M=None, m=(), quad=(B[0], B[1], B[3], B[2]),
                                 d=())
                contrib = np.maximum(contrib, c)
                continue

            # the two closest vertex pairs, disjoint (== ascending sort,
            # first two that share no vertex)
            # Epsilon-strict comparisons: on mathematically-equal distances the
            # EARLIEST candidate in loop order wins deterministically, instead
            # of FP noise deciding (which flickers frame to frame and diverges
            # between world-space and ship-local evaluations of the same
            # geometry).
            best0, bi0, bj0 = 1e30, -1, -1
            for ai in range(4):
                for bj in range(4):
                    d = np.linalg.norm(A[ai] - B[bj])
                    if d < best0 - 1e-6:
                        best0, bi0, bj0 = d, ai, bj
            best1, bi1, bj1 = 1e30, -1, -1
            for ai in range(4):
                if ai == bi0:
                    continue
                for bj in range(4):
                    if bj == bj0:
                        continue
                    d = np.linalg.norm(A[ai] - B[bj])
                    if d < best1 - 1e-6:
                        best1, bi1, bj1 = d, ai, bj
            if bi1 < 0 or best0 > MATCH_REACH:
                continue

            # ship-space half-steps on B's face plane
            bAbs = np.abs(nB)
            bU = np.array([0, 1, 0.]) if bAbs[0] > 0.5 else np.array([1, 0, 0.])
            bV = np.array([0, 1, 0.]) if bAbs[2] > 0.5 else np.array([0, 0, 1.])
            uH = quat_rotate(q, bU * 0.5)
            vH = quat_rotate(q, bV * 0.5)

            # subtend the B-side matched vertices: step along the ship axis
            # with the SMALLER |dot| against the B-pair vector, signed into
            # the face
            b0v, b1v = B[bj0], B[bj1]
            pairVec = b1v - b0v
            # DIAGONAL pairs tie this test exactly (|dot| equal both axes), so
            # prefer bU within epsilon rather than letting FP noise pick.
            du = abs(np.dot(uH, pairVec))
            dv = abs(np.dot(vH, pairVec))
            ax = uH if du <= dv + 1e-6 else vH
            bFaceC = vox + quat_rotate(q, nB * 0.5)
            # Sign: into the face interior. For DIAGONAL pairs the seam
            # midpoint IS the face center (interior ~ 0, the sign would be FP
            # noise -> flicker), so fall back to "toward the matched A
            # corners", then to +ax.
            interior = bFaceC - 0.5 * (b0v + b1v)
            sgn = np.dot(ax, interior)
            if abs(sgn) < 1e-6:
                sgn = np.dot(ax, 0.5 * (A[bi0] + A[bi1]) - 0.5 * (b0v + b1v))
            if abs(sgn) < 1e-6:
                sgn = 1.0
            h = ax if sgn >= 0 else -ax
            su, sv = b0v + h, b1v + h
            M = 0.5 * (su + sv)

            # A-side vertices lerp toward M by f(own pair distance)
            t0, t1 = f(best0), f(best1)
            m0 = A[bi0] + t0 * (M - A[bi0])
            m1 = A[bi1] + t1 * (M - A[bi1])

            c = np.array([STRENGTH * aabb_falloff(A[i], (su, sv, m0, m1), vox, q)
                          * frontW for i in range(4)])
            if c.sum() > contrib.sum():
                bestg = dict(vox=vox, A=A, B=B,
                             pairs=((A[bi0], b0v), (A[bi1], b1v)),
                             sub=(su, sv), M=M, m=(m0, m1),
                             quad=(su, sv, m1, m0), d=(best0, best1))
            contrib = np.maximum(contrib, c)

        corner += contrib
        if collect is not None and bestg is not None:
            bestg["contrib"] = contrib
            collect.append(bestg)
    corner = np.minimum(corner, MAX_TOTAL)
    return tri_interp(corner, np.dot(frag, uA) - (uC - 0.5),
                      np.dot(frag, vA) - (vC - 0.5))
