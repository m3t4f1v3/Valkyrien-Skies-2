#!/usr/bin/env python3
"""Line-for-line transliteration of the planned GLSL loop for seam4 with
the BAKED-lattice delta semantics (world pseudo-ship / self ship): single
gather pass, fixed arrays, dual occupancy (all / baked-only), per-host
F(all) - F(baked). Fuzzed against seam4.ship_ao."""
import numpy as np
from seam2 import quat_rotate_inv, quat_rotate
from seam4 import (ship_ao, STRENGTH, MAX_TOTAL, R_GATHER, FADE_LO, FADE_HI,
                   G_FULL, G_ZERO)

MAX_GATHER = 20
MAX_RUNS = 4


def glsl_interp(c, u, v):
    if c[0] + c[3] <= c[1] + c[2]:
        if u >= v:
            return c[0] + (c[1] - c[0]) * u + (c[3] - c[1]) * v
        return c[0] + (c[3] - c[2]) * u + (c[2] - c[0]) * v
    if u + v <= 1.0:
        return c[0] + (c[1] - c[0]) * u + (c[2] - c[0]) * v
    return c[3] + (c[2] - c[3]) * (1 - u) + (c[1] - c[3]) * (1 - v)


def glsl_ship_ao(frag, normal, occluders, baked_ship=-1):
    n = len(occluders)

    # ---- gather pass -------------------------------------------------------
    runQ = [None] * MAX_RUNS
    runAnchor = [None] * MAX_RUNS
    runShip = [0] * MAX_RUNS
    runCount = 0
    voxPos = [None] * MAX_GATHER
    voxRun = [0] * MAX_GATHER
    voxCount = 0
    lastShip = None
    lastSlot = -1
    for i in range(n):
        pos, q, shipIdx = occluders[i]
        pos = np.asarray(pos, float)
        if shipIdx != lastShip:
            lastShip = shipIdx
            lastSlot = -1
        if np.linalg.norm(pos - frag) > R_GATHER:
            continue
        if lastSlot < 0:
            if runCount == MAX_RUNS:
                continue
            lastSlot = runCount
            runQ[lastSlot] = np.asarray(q, float)
            runAnchor[lastSlot] = pos
            runShip[lastSlot] = shipIdx
            runCount += 1
        if voxCount == MAX_GATHER:
            continue
        voxPos[voxCount] = pos
        voxRun[voxCount] = lastSlot
        voxCount += 1

    def lat(p, si):
        return quat_rotate_inv(runQ[si], p - runAnchor[si]) + 0.5

    def align(vi, si):
        c = lat(voxPos[vi], si)
        o = 1.0
        for ci in c:
            o *= 1.0 - abs((ci - np.floor(ci)) - 0.5)
        return o

    fade = [0.0] * MAX_GATHER
    for vi in range(voxCount):
        fade[vi] = np.clip((FADE_HI - np.linalg.norm(voxPos[vi] - frag))
                           / (FADE_HI - FADE_LO), 0.0, 1.0)

    # ---- responsibility r per voxel ---------------------------------------
    rv = [1.0] * MAX_GATHER
    for vi in range(voxCount):
        csum = 0.0
        if runShip[voxRun[vi]] == baked_ship:
            rv[vi] = 1.0
            continue
        for si in range(runCount):
            if si == voxRun[vi]:
                continue
            gf = 0.0
            for wi in range(voxCount):
                if voxRun[wi] != si:
                    continue
                g = np.clip((G_ZERO - np.linalg.norm(voxPos[vi] - voxPos[wi]))
                            / (G_ZERO - G_FULL), 0.0, 1.0)
                gf = max(gf, g * fade[wi])
            if gf > 0.0:
                csum += gf * align(vi, si)
        rv[vi] = 1.0 / (1.0 + csum)

    # ---- per-host fields ---------------------------------------------------
    total = 0.0
    for si in range(runCount):
        fragL = lat(frag, si)
        nL = quat_rotate_inv(runQ[si], normal)
        absN = np.abs(nL)
        a = 0
        if absN[1] > absN[a] + 1e-6:
            a = 1
        if absN[2] > absN[a] + 1e-6:
            a = 2
        u, v = (a + 1) % 3, (a + 2) % 3
        s = 1.0 if nL[a] >= 0.0 else -1.0
        pa = fragL[a]
        slabLo = pa if s > 0.0 else pa - 1.0
        b0 = int(np.floor(slabLo))
        w0 = np.clip(b0 + 1.0 - slabLo, 0.0, 1.0)
        cu = int(np.floor(fragL[u]))
        cv = int(np.floor(fragL[v]))
        fu, fv = fragL[u] - cu, fragL[v] - cv

        occAll = np.zeros(18)                   # [band*9 + (dv+1)*3 + (du+1)]
        occBaked = np.zeros(18)
        for vi in range(voxCount):
            owner = voxRun[vi]
            isBaked = runShip[owner] == baked_ship
            if isBaked:
                wgt = fade[vi]
            elif owner == si:
                wgt = rv[vi] * fade[vi]
            else:
                gf = 0.0
                for wi in range(voxCount):
                    if voxRun[wi] != si:
                        continue
                    g = np.clip((G_ZERO - np.linalg.norm(voxPos[vi] - voxPos[wi]))
                                / (G_ZERO - G_FULL), 0.0, 1.0)
                    gf = max(gf, g * fade[wi])
                if gf <= 0.0:
                    continue
                wgt = rv[vi] * gf * align(vi, si) * fade[vi]
            if wgt <= 0.0:
                continue
            c = lat(voxPos[vi], si)
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
                        m = wgt * ta * tu * tv
                        occAll[b * 9 + (dv + 1) * 3 + (du + 1)] += m
                        if isBaked:
                            occBaked[b * 9 + (dv + 1) * 3 + (du + 1)] += m
        occAll = np.minimum(occAll, 1.0)
        occBaked = np.minimum(occBaked, 1.0)

        def corners(occ):
            corner = np.zeros(4)
            for b, w in ((0, w0), (1, 1.0 - w0)):
                if w <= 0.0:
                    continue
                base = b * 9
                for ci, (du, dv) in enumerate(((0, 0), (1, 0), (0, 1), (1, 1))):
                    qA = occ[base + dv * 3 + du]
                    qB = occ[base + dv * 3 + du + 1]
                    qC = occ[base + (dv + 1) * 3 + du]
                    qD = occ[base + (dv + 1) * 3 + du + 1]
                    bonus = (max(0.0, min(qA, qD) - max(qB, qC))
                             + max(0.0, min(qB, qC) - max(qA, qD)))
                    corner[ci] += STRENGTH * (qA + qB + qC + qD + bonus) * w
            return corner

        total += (glsl_interp(corners(occAll), fu, fv)
                  - glsl_interp(corners(occBaked), fu, fv))
    return min(total, MAX_TOTAL)


if __name__ == "__main__":
    rng = np.random.default_rng(23)

    def rq():
        x = rng.normal(size=4)
        return x / np.linalg.norm(x)

    worst = 0.0
    for trial in range(20000):
        nrm = np.zeros(3)
        ax = rng.integers(3)
        nrm[ax] = rng.choice([-1, 1])
        frag = rng.uniform(-3, 3, 3)
        frag[ax] = round(frag[ax])
        occs = []
        nships = rng.integers(1, 4)
        for ship in range(nships):
            q = rq() if ship > 0 else np.array([0., 0., 0., 1.])  # ship 0 = world, identity
            origin = rng.uniform(-3, 3, 3)
            if ship == 0:
                origin = np.floor(origin) + 0.5
            cells = {(0, 0, 0)}
            for _ in range(rng.integers(0, 4)):
                base = list(cells)[rng.integers(len(cells))]
                d = np.zeros(3, int)
                d[rng.integers(3)] = rng.choice([-1, 1])
                cells.add(tuple(np.array(base) + d))
            for c in cells:
                occs.append((origin + quat_rotate(q, np.array(c, float)),
                             q, ship))  # ship index 0 == world pseudo-ship
        baked = int(rng.integers(-1, nships))  # -1 none, 0 world, 1.. ship
        worst = max(worst, abs(ship_ao(frag, nrm, occs, baked) -
                               glsl_ship_ao(frag, nrm, occs, baked)))
    print(f"GLSL transliteration vs seam4: max |diff| over 20k fuzz = {worst:.2e}")
