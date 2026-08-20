#!/usr/bin/env python3
"""Numeric simulation of the rewired seam AO (mirrors the GLSL exactly).
Identity ship rotation for these cases; q = (0,0,0,1)."""
import numpy as np

REACH, MATCH_REACH, STRENGTH, MERGE_RANGE, MAX_TOTAL = 1.0, 2.0, 0.2, 2.0, 0.8
FACE_DOT_MIN, CAND_RADIUS = 0.5, 2.5

def local_face(c, nrm):
    a = np.abs(nrm)
    u = np.array([0,1,0.]) if a[0] > 0.5 else np.array([1,0,0.])
    v = np.array([0,1,0.]) if a[2] > 0.5 else np.array([0,0,1.])
    return [c - u*0.5 - v*0.5, c + u*0.5 - v*0.5, c - u*0.5 + v*0.5, c + u*0.5 + v*0.5]

def dist_tri(p, a, b, c):
    ba, pa = b-a, p-a; cb, pb = c-b, p-b; ac, pc = a-c, p-c
    nor = np.cross(ba, ac)
    def seg(e, pe):
        t = np.clip(np.dot(e, pe)/max(np.dot(e, e), 1e-8), 0, 1)
        return np.dot(e*t-pe, e*t-pe)
    s = np.sign(np.dot(np.cross(ba,nor),pa)) + np.sign(np.dot(np.cross(cb,nor),pb)) + np.sign(np.dot(np.cross(ac,nor),pc))
    if s < 2.0:
        return np.sqrt(min(seg(ba,pa), seg(cb,pb), seg(ac,pc)))
    return np.sqrt(np.dot(nor,pa)**2/np.dot(nor,nor))

def seam_ao(frag, normal, occluders):
    absN = np.abs(normal)
    uA = np.array([0,1,0.]) if absN[0] > 0.5 else np.array([1,0,0.])
    vA = np.array([0,1,0.]) if absN[2] > 0.5 else np.array([0,0,1.])
    uC = np.floor(np.dot(frag,uA)) + 0.5
    vC = np.floor(np.dot(frag,vA)) + 0.5
    nP = np.floor(np.dot(frag,normal) + 0.5)
    faceC = normal*nP + uA*uC + vA*vC
    A = local_face(faceC, normal)
    total = 0.0
    for vox in occluders:
        vox = np.asarray(vox, float)
        if np.linalg.norm(vox - faceC) > CAND_RADIUS: continue
        front = np.dot(normal, vox - faceC)
        if front < 1e-4: continue
        tsl = faceC - vox
        L = np.linalg.norm(tsl)
        if L < 1e-5: continue
        aL = np.abs(tsl)
        if aL[0] >= aL[1] and aL[0] >= aL[2]: nB = np.array([np.sign(tsl[0]),0,0]); mx = aL[0]
        elif aL[1] >= aL[2]:                  nB = np.array([0,np.sign(tsl[1]),0]); mx = aL[1]
        else:                                 nB = np.array([0,0,np.sign(tsl[2])]); mx = aL[2]
        if mx/L < FACE_DOT_MIN: continue
        B = [vox + c for c in local_face(nB*0.5, nB)]
        best0, bi0, bj0 = 1e30, -1, -1
        for ai in range(4):
            for bj in range(4):
                d = np.linalg.norm(A[ai]-B[bj])
                if d < best0: best0, bi0, bj0 = d, ai, bj
        best1, bi1, bj1 = 1e30, -1, -1
        for ai in range(4):
            if ai == bi0: continue
            for bj in range(4):
                if bj == bj0: continue
                d = np.linalg.norm(A[ai]-B[bj])
                if d < best1: best1, bi1, bj1 = d, ai, bj
        if bi1 < 0 or best0 > MATCH_REACH: continue
        bAbs = np.abs(nB)
        bU = np.array([0,1,0.]) if bAbs[0] > 0.5 else np.array([1,0,0.])
        bV = np.array([0,1,0.]) if bAbs[2] > 0.5 else np.array([0,0,1.])
        uH, vH = bU*0.5, bV*0.5
        bFaceC = vox + nB*0.5
        u, v = B[bj0], B[bj1]
        seamMid = 0.5*(u+v); pairVec = v-u
        interior = bFaceC - seamMid
        axis = uH if abs(np.dot(uH,pairVec)) <= abs(np.dot(vH,pairVec)) else vH
        halfStep = axis if np.dot(axis,interior) >= 0 else -axis
        su, sv = u+halfStep, v+halfStep
        M = 0.5*(su+sv)
        t0 = np.clip(best0/MERGE_RANGE, 0, 1); t1 = np.clip(best1/MERGE_RANGE, 0, 1)
        m0 = A[bi0] + t0*(M-A[bi0]); m1 = A[bi1] + t1*(M-A[bi1])
        d = min(dist_tri(frag, su, sv, m1), dist_tri(frag, su, m1, m0))
        total += STRENGTH * np.clip(1-d/REACH, 0, 1) * np.clip(front/0.25, 0, 1)
    return min(total, MAX_TOTAL)

up = np.array([0,1,0.])

print("== CASE 1: vanilla parity — block on floor, exposed floor face beside it ==")
print("   floor tops y=1; occluder block center (1.5,1.5,0.5); shaded floor face x in [0,1]")
occ = [(1.5,1.5,0.5)]
for x in [1.0, 0.75, 0.5, 0.25, 0.0]:
    got = seam_ao(np.array([x,1,0.5]), up, occ)
    print(f"   frag x={x:4}: loss={got:.3f}  vanilla={0.2*x:.3f}")

print("== CASE 2: flush neighbor (voxel level WITH the floor) -> vanilla gives 0 ==")
occ = [(1.5,0.5,0.5)]
print(f"   frag (1,1,0.5): loss={seam_ao(np.array([1,1,0.5]), up, occ):.3f}  vanilla=0.000")

print("== CASE 3: corner-diagonal block (vanilla darkens 1 vertex) ==")
occ = [(1.5,1.5,1.5)]
for f in [(1,1,1),(0.75,1,0.75),(0.5,1,0.5),(1,1,0.5)]:
    got = seam_ao(np.array(f,dtype=float), up, occ)
    van = 0.2*f[0]*f[2]  # bilinear of loss 0.2 at corner vertex
    print(f"   frag {f}: loss={got:.3f}  vanilla={van:.3f}")

print("== CASE 4: s_s 1-block gap, fields on the floor BETWEEN (merging) ==")
print("   ships at x[0,1] and x[2,3] on floor tops y=1, frag on gap floor")
occ = [(0.5,1.5,0.5),(2.5,1.5,0.5)]
for x in [1.0, 1.25, 1.5, 1.75, 2.0]:
    got = seam_ao(np.array([x,1,0.5]), up, occ)
    print(f"   gap floor x={x:4}: loss={got:.3f}   (vanilla-adjacent both sides: {0.2*(2-x)+0.2*(x-1):.3f} at edges)")

print("== CASE 5: s_s 1-gap, facing SIDE face of ship A (merge across blank) ==")
sideN = np.array([1,0,0.])
occ = [(2.5,0.5,0.5)]   # other ship only (own ship skipped by index)
for f in [(1,0.5,0.5),(1,0.9,0.5),(1,0.5,0.1)]:
    got = seam_ao(np.array(f,dtype=float), sideN, occ)
    print(f"   frag {f}: loss={got:.3f}")

print("== CASE 6: adjacent s b touching (no gap), A side face buried check n/a;")
print("   A top-face edge next to B (same height, tops flush) -> vanilla 0 ==")
occ = [(1.5,0.5,0.5)]  # B beside A (both y in [0,1]), frag on A's top face
print(f"   frag (1,1,0.5) on A top: loss={seam_ao(np.array([1,1,0.5]), up, occ):.3f}  vanilla=0.000")
