#!/usr/bin/env python3
"""Line-for-line transliteration of the planned GLSL loop for the
occluder-lattice AO (seam3): per-ship runs, 3x3x2 occupancy BITMASK instead of
a cell set, integer lattice math. Fuzzed against seam3.ship_ao."""
import numpy as np
from seam2 import quat_rotate_inv
from seam3 import ship_ao, STRENGTH, MAX_TOTAL


def glsl_interp(c, u, v):
    if c[0] + c[3] <= c[1] + c[2]:
        if u >= v:
            return c[0] + (c[1] - c[0]) * u + (c[3] - c[1]) * v
        return c[0] + (c[3] - c[2]) * u + (c[2] - c[0]) * v
    if u + v <= 1.0:
        return c[0] + (c[1] - c[0]) * u + (c[2] - c[0]) * v
    return c[3] + (c[2] - c[3]) * (1 - u) + (c[1] - c[3]) * (1 - v)


def glsl_ship_ao(frag, normal, occluders, self_ship=-1):
    n = len(occluders)
    total = 0.0
    i = 0
    while i < n:
        anchor, q, shipIdx = occluders[i]
        anchor = np.asarray(anchor, float)
        q = np.asarray(q, float)

        fragL = quat_rotate_inv(q, frag - anchor) + 0.5
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
        slabLo = pa if s > 0.0 else pa - 1.0
        b0 = int(np.floor(slabLo))
        w0 = np.clip(b0 + 1.0 - slabLo, 0.0, 1.0)
        w1 = 1.0 - w0
        cu = int(np.floor(fragL[u]))
        cv = int(np.floor(fragL[v]))
        fu, fv = fragL[u] - cu, fragL[v] - cv

        mask = 0
        j = i
        while j < n:
            vox, _, sIdx = occluders[j]
            if sIdx != shipIdx:
                break
            j += 1
            k = np.round(quat_rotate_inv(q, np.asarray(vox, float) - anchor)).astype(int)
            band = 0 if k[a] == b0 else (1 if k[a] == b0 + 1 else -1)
            if band < 0:
                continue
            du, dv = k[u] - cu, k[v] - cv
            if abs(du) > 1 or abs(dv) > 1:
                continue
            mask |= 1 << (band * 9 + (dv + 1) * 3 + (du + 1))

        if shipIdx != self_ship:
            corner = np.zeros(4)
            for b, w in ((0, w0), (1, w1)):
                if w <= 0.0:
                    continue

                def solid(du, dv):
                    return (mask >> (b * 9 + (dv + 1) * 3 + (du + 1))) & 1

                front = solid(0, 0)
                for ci, (du, dv) in enumerate(((0, 0), (1, 0), (0, 1), (1, 1))):
                    eu = solid(1 if du != 0 else -1, 0)
                    ev = solid(0, 1 if dv != 0 else -1)
                    co = 1 if (eu != 0 and ev != 0) else solid(
                        1 if du != 0 else -1, 1 if dv != 0 else -1)
                    corner[ci] += STRENGTH * float(eu + ev + co + front) * w
            total += glsl_interp(corner, fu, fv)
        i = j
    return min(total, MAX_TOTAL)


if __name__ == "__main__":
    rng = np.random.default_rng(17)

    def rq():
        x = rng.normal(size=4)
        return x / np.linalg.norm(x)

    worst = 0.0
    for trial in range(30000):
        nrm = np.zeros(3)
        ax = rng.integers(3)
        nrm[ax] = rng.choice([-1, 1])
        frag = rng.uniform(-3, 3, 3)
        frag[ax] = round(frag[ax])
        occs = []
        for ship in range(rng.integers(1, 4)):
            q = rq()
            origin = rng.uniform(-3, 3, 3)
            # a small random connected-ish cluster of voxel cells
            cells = {(0, 0, 0)}
            for _ in range(rng.integers(0, 5)):
                base = list(cells)[rng.integers(len(cells))]
                d = np.zeros(3, int)
                d[rng.integers(3)] = rng.choice([-1, 1])
                cells.add(tuple(np.array(base) + d))
            from seam2 import quat_rotate
            for c in cells:
                occs.append((origin + quat_rotate(q, np.array(c, float)),
                             q, ship + 1))
        worst = max(worst, abs(ship_ao(frag, nrm, occs) -
                               glsl_ship_ao(frag, nrm, occs)))
    print(f"GLSL transliteration vs seam3: max |diff| over 30k fuzz = {worst:.2e}")
