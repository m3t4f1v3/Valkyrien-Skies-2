"""Faithful Python port of the seam-AO fragment logic in
block_layer_opaque.fsh / world_layer_opaque.fsh (they share the algorithm).

Every step mirrors the GLSL 1:1 so a defect here IS a defect in the shader.
Returns both the loss and the per-occluder debug geometry so the visualizer
can overlay quads / subtend lines / midpoints.
"""
import numpy as np

REACH = 1.0
MATCH_REACH = 2.0
STRENGTH = 0.2
MERGE_RANGE = 2.0
MAX_TOTAL = 0.8
CAND_RADIUS = 2.5
FACE_DOT_MIN = 0.5


def quat_rotate(q, v):
    # v + 2*cross(q.xyz, cross(q.xyz, v) + q.w*v)   (q = [x,y,z,w])
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


def seam_ao(frag, normal, occluders, self_ship=-1, collect=None):
    """frag/normal in world space (world-shader variant: shipyard==world,
    selfRot==identity). occluders: list of (pos3, quat4, ship_index)."""
    absN = np.abs(normal)
    uA = np.array([0, 1, 0.]) if absN[0] > 0.5 else np.array([1, 0, 0.])
    vA = np.array([0, 1, 0.]) if absN[2] > 0.5 else np.array([0, 0, 1.])
    uC = np.floor(np.dot(frag, uA)) + 0.5
    vC = np.floor(np.dot(frag, vA)) + 0.5
    nP = np.floor(np.dot(frag, normal) + 0.5)
    faceC = normal * nP + uA * uC + vA * vC
    A = local_face(faceC, normal)

    total = 0.0
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
        aL = np.abs(tsl)
        if aL[0] >= aL[1] and aL[0] >= aL[2]:
            nB = np.array([np.sign(tsl[0]), 0, 0]); mx = aL[0]
        elif aL[1] >= aL[2]:
            nB = np.array([0, np.sign(tsl[1]), 0]); mx = aL[1]
        else:
            nB = np.array([0, 0, np.sign(tsl[2])]); mx = aL[2]
        if mx / L < FACE_DOT_MIN:
            continue
        B = [vox + quat_rotate(q, c) for c in local_face(nB * 0.5, nB)]

        # pass 1: nearest pair
        best0, bi0, bj0 = 1e30, -1, -1
        for ai in range(4):
            for bj in range(4):
                d = np.linalg.norm(A[ai] - B[bj])
                if d < best0:
                    best0, bi0, bj0 = d, ai, bj
        # pass 2: nearest disjoint pair
        best1, bi1, bj1 = 1e30, -1, -1
        for ai in range(4):
            if ai == bi0:
                continue
            for bj in range(4):
                if bj == bj0:
                    continue
                d = np.linalg.norm(A[ai] - B[bj])
                if d < best1:
                    best1, bi1, bj1 = d, ai, bj
        if bi1 < 0 or best0 > MATCH_REACH:
            continue

        bAbs = np.abs(nB)
        bU = np.array([0, 1, 0.]) if bAbs[0] > 0.5 else np.array([1, 0, 0.])
        bV = np.array([0, 1, 0.]) if bAbs[2] > 0.5 else np.array([0, 0, 1.])
        uH = quat_rotate(q, bU * 0.5)
        vH = quat_rotate(q, bV * 0.5)

        a0, b0 = A[bi0], B[bj0]
        a1, b1 = A[bi1], B[bj1]
        mid0, mid1 = 0.5 * (a0 + b0), 0.5 * (a1 + b1)

        def half_step(a, b, mid_this, mid_other):
            pv = b - a
            seam = mid_other - mid_this
            pu, pvd = abs(np.dot(uH, pv)), abs(np.dot(vH, pv))
            if abs(pu - pvd) > 1e-4:
                ax = uH if pu <= pvd else vH
            else:
                ax = uH if abs(np.dot(uH, seam)) >= abs(np.dot(vH, seam)) else vH
            return ax if np.dot(ax, seam) >= 0 else -ax

        h0 = half_step(a0, b0, mid0, mid1)
        h1 = half_step(a1, b1, mid1, mid0)
        M0, M1 = mid0 + h0, mid1 + h1

        t0 = np.clip(best0 / MERGE_RANGE, 0, 1)
        t1 = np.clip(best1 / MERGE_RANGE, 0, 1)
        qa0 = a0 + t0 * (M1 - a0)
        qb0 = b0 + t0 * (M1 - b0)
        qa1 = a1 + t1 * (M0 - a1)
        qb1 = b1 + t1 * (M0 - b1)

        loc = [quat_rotate_inv(q, p - vox) for p in (qa0, qb0, qa1, qb1)]
        lo = np.min(loc, axis=0)
        hi = np.max(loc, axis=0)
        fl = quat_rotate_inv(q, frag - vox)
        ex = np.maximum(np.maximum(lo - fl, fl - hi), 0.0)
        dFrag = ex.sum()
        contrib = STRENGTH * np.clip(1 - dFrag / REACH, 0, 1) * np.clip(front / 0.25, 0, 1)
        total += contrib

        if collect is not None and contrib >= 0:
            collect.append(dict(vox=vox, A=A, B=B,
                                pairs=((a0, b0), (a1, b1)),
                                sub0=(a0 + h0, b0 + h0), sub1=(a1 + h1, b1 + h1),
                                M=(M0, M1), quad=(qa0, qb0, qb1, qa1),
                                contrib=contrib, d=(best0, best1)))
    return min(total, MAX_TOTAL)
