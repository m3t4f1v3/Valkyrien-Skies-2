#!/usr/bin/env python3
"""Line-for-line transliteration of the GLSL seam loop (occluder-LOCAL
construction, as spliced into the .fsh files), fuzz-compared against
seam2.seam_ao (world-space reference). Divergence means either a transcription
bug or an FP-noise-dependent branch (tie without an epsilon guard)."""
import numpy as np
from seam2 import (quat_rotate, quat_rotate_inv, local_face, seam_ao,
                   REACH, MATCH_REACH, STRENGTH, MAX_TOTAL, CAND_RADIUS, CUTOFF)


def glsl_interp(c, u, v):
    # ws_seamInterp: c = (L00, L10, L01, L11)
    if c[0] + c[3] <= c[1] + c[2]:
        if u >= v:
            return c[0] + (c[1] - c[0]) * u + (c[3] - c[1]) * v
        return c[0] + (c[3] - c[2]) * u + (c[2] - c[0]) * v
    if u + v <= 1.0:
        return c[0] + (c[1] - c[0]) * u + (c[2] - c[0]) * v
    return c[3] + (c[2] - c[3]) * (1 - u) + (c[1] - c[3]) * (1 - v)


def glsl_seam_ao(frag, normal, occluders):
    absN = np.abs(normal)
    uA = np.array([0, 1, 0.]) if absN[0] > 0.5 else np.array([1, 0, 0.])
    vA = np.array([0, 1, 0.]) if absN[2] > 0.5 else np.array([0, 0, 1.])
    faceC = (normal * np.floor(np.dot(frag, normal) + 0.5)
             + uA * (np.floor(np.dot(frag, uA)) + 0.5)
             + vA * (np.floor(np.dot(frag, vA)) + 0.5))
    A = local_face(faceC, normal)
    cornerLoss = np.zeros(4)
    for vox, q, _ in occluders:
        vox = np.asarray(vox, float)
        q = np.asarray(q, float)
        if np.linalg.norm(vox - faceC) > CAND_RADIUS:
            continue
        front = np.dot(normal, vox - faceC)
        if front < 1e-4:
            continue
        aLoc = [quat_rotate_inv(q, p - vox) for p in A]
        tsl = quat_rotate_inv(q, faceC - vox)
        nAL = quat_rotate_inv(q, normal)
        frontW = np.clip(front / 0.25, 0, 1)
        contrib = np.zeros(4)
        for axisI in range(3):
            if abs(tsl[axisI]) < 1e-6:
                continue
            nB = np.zeros(3)
            nB[axisI] = np.sign(tsl[axisI])
            bLoc = local_face(nB * 0.5, nB)
            if np.dot(nAL, nB) < -0.9:
                lo = np.min(bLoc, axis=0)
                hi = np.max(bLoc, axis=0)
                faceLoss = np.zeros(4)
                for ci in range(4):
                    ex = np.maximum(np.maximum(lo - aLoc[ci], aLoc[ci] - hi), 0.0)
                    w = np.clip(1 - ex / REACH, 0, 1)
                    wp = np.clip((w[0] * w[1] * w[2] - CUTOFF) / (1.0 - CUTOFF), 0.0, 1.0)
                    faceLoss[ci] = STRENGTH * wp * frontW
            else:
                best0, bi0, bj0 = 1e30, -1, -1
                for ai in range(4):
                    for bj in range(4):
                        dd = np.linalg.norm(aLoc[ai] - bLoc[bj])
                        if dd < best0 - 1e-6:
                            best0, bi0, bj0 = dd, ai, bj
                best1, bi1, bj1 = 1e30, -1, -1
                for ai in range(4):
                    if ai == bi0:
                        continue
                    for bj in range(4):
                        if bj == bj0:
                            continue
                        dd = np.linalg.norm(aLoc[ai] - bLoc[bj])
                        if dd < best1 - 1e-6:
                            best1, bi1, bj1 = dd, ai, bj
                if bi1 < 0 or best0 > MATCH_REACH:
                    continue
                bAbs = np.abs(nB)
                bU = np.array([0, 1, 0.]) if bAbs[0] > 0.5 else np.array([1, 0, 0.])
                bV = np.array([0, 1, 0.]) if bAbs[2] > 0.5 else np.array([0, 0, 1.])
                sb0, sb1 = bLoc[bj0], bLoc[bj1]
                pairVec = sb1 - sb0
                du = abs(np.dot(bU, pairVec))
                dv = abs(np.dot(bV, pairVec))
                axis = (bU if du <= dv + 1e-6 else bV) * 0.5
                interior = nB * 0.5 - 0.5 * (sb0 + sb1)
                sgn = np.dot(axis, interior)
                if abs(sgn) < 1e-6:
                    sgn = np.dot(axis, 0.5 * (aLoc[bi0] + aLoc[bi1]) - 0.5 * (sb0 + sb1))
                if abs(sgn) < 1e-6:
                    sgn = 1.0
                h = axis if sgn >= 0 else -axis
                su, sv = sb0 + h, sb1 + h
                M = 0.5 * (su + sv)
                m0 = aLoc[bi0] + np.clip(best0 / REACH, 0, 1) * (M - aLoc[bi0])
                m1 = aLoc[bi1] + np.clip(best1 / REACH, 0, 1) * (M - aLoc[bi1])
                lo = np.min([su, sv, m0, m1], axis=0)
                hi = np.max([su, sv, m0, m1], axis=0)
                faceLoss = np.zeros(4)
                for ci in range(4):
                    ex = np.maximum(np.maximum(lo - aLoc[ci], aLoc[ci] - hi), 0.0)
                    w = np.clip(1 - ex / REACH, 0, 1)
                    wp = np.clip((w[0] * w[1] * w[2] - CUTOFF) / (1.0 - CUTOFF), 0.0, 1.0)
                    faceLoss[ci] = STRENGTH * wp * frontW
            contrib = np.maximum(contrib, faceLoss)
        cornerLoss += contrib
    cornerLoss = np.minimum(cornerLoss, MAX_TOTAL)
    fu = np.dot(frag, uA)
    fv = np.dot(frag, vA)
    return glsl_interp(cornerLoss, fu - np.floor(fu), fv - np.floor(fv))


if __name__ == "__main__":
    rng = np.random.default_rng(11)

    def rq():
        v = rng.normal(size=4)
        return v / np.linalg.norm(v)

    worst = 0.0
    for trial in range(30000):
        nrm = np.zeros(3)
        ax = rng.integers(3)
        nrm[ax] = rng.choice([-1, 1])
        frag = rng.uniform(-3, 3, 3)
        frag[ax] = round(frag[ax])
        occs = [(rng.uniform(-3, 3, 3), rq(), i + 1) for i in range(rng.integers(1, 4))]
        worst = max(worst, abs(seam_ao(frag, nrm, occs) - glsl_seam_ao(frag, nrm, occs)))
    print(f"GLSL transliteration vs seam2 reference: max |diff| over 30k fuzz = {worst:.2e}")
