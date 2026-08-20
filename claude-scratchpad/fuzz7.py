#!/usr/bin/env python3
"""Transliteration of the OPTIMIZED GLSL structure for seam5:
CPU side  : per-ship directory (quat, anchor, r, top-4 partner claims),
            sub-run headers (bounding sphere, start, count, shipIdx),
            global bounds.
shader    : bounds early-out; pass1 sub-run headers -> source ships;
            host slots = sources + their claim partners (<=4, deduped);
            pass2 per host: rescan headers, sphere-cull, weight by
            r / r*claim, tent-inject voxels, quadrant corners, interp.
Fuzzed against seam5.ship_ao. (fuzz6.py is the user's separate baked-delta
experiment -- unrelated.)"""
import numpy as np
from seam2 import quat_rotate_inv, quat_rotate
from seam5 import (ship_ao, pair_claim, STRENGTH, MAX_TOTAL, SUPPORT,
                   MAX_PARTNERS)

SUBRUN_MAX = 3          # tiny in the fuzz to exercise multi-sub-run paths
MAX_HOSTS = 4


def cpu_build(occluders):
    """Model of the Java per-frame pass: ship directory + sub-run headers."""
    ships = {}          # shipIdx -> dict(q, anchor, voxels, aabb)
    order = []
    for vox, q, ship in occluders:
        vox = np.asarray(vox, float)
        if ship not in ships:
            ships[ship] = dict(q=np.asarray(q, float), anchor=vox, voxels=[])
            order.append(ship)
        ships[ship]["voxels"].append(vox)
    for s in ships.values():
        vs = np.array(s["voxels"])
        s["aabb"] = (vs.min(axis=0), vs.max(axis=0))

    # per-pair claims, top-MAX_PARTNERS per ship, r from retained claims
    for si in order:
        s = ships[si]
        cl = []
        for ti in order:
            if ti == si:
                continue
            t = ships[ti]
            c = pair_claim(s["q"], s["anchor"], t["q"], t["anchor"],
                           s["aabb"], t["aabb"])
            if c > 0.0:
                cl.append((c, ti))
        cl.sort(key=lambda e: -e[0])
        s["partners"] = cl[:MAX_PARTNERS]
        s["r"] = 1.0 / (1.0 + sum(c for c, _ in s["partners"]))

    # sub-run headers from buffer order: split on ship change or SUBRUN_MAX
    headers = []        # (center, radius, start, count, shipIdx)
    i = 0
    n = len(occluders)
    while i < n:
        ship = occluders[i][2]
        j = i
        pts = []
        while j < n and occluders[j][2] == ship and j - i < SUBRUN_MAX:
            pts.append(np.asarray(occluders[j][0], float))
            j += 1
        pts = np.array(pts)
        lo, hi = pts.min(axis=0), pts.max(axis=0)
        center = 0.5 * (lo + hi)
        radius = max(np.linalg.norm(p - center) for p in pts)
        headers.append((center, radius, i, j - i, ship))
        i = j

    lo = np.min([np.asarray(o[0], float) for o in occluders], axis=0)
    hi = np.max([np.asarray(o[0], float) for o in occluders], axis=0)
    bc = 0.5 * (lo + hi)
    br = max(np.linalg.norm(np.asarray(o[0], float) - bc) for o in occluders)
    return ships, headers, (bc, br)


def glsl_interp(c, u, v):
    if c[0] + c[3] <= c[1] + c[2]:
        if u >= v:
            return c[0] + (c[1] - c[0]) * u + (c[3] - c[1]) * v
        return c[0] + (c[3] - c[2]) * u + (c[2] - c[0]) * v
    if u + v <= 1.0:
        return c[0] + (c[1] - c[0]) * u + (c[2] - c[0]) * v
    return c[3] + (c[2] - c[3]) * (1 - u) + (c[1] - c[3]) * (1 - v)


def glsl_ship_ao(frag, normal, occluders, self_ship=-1):
    ships, headers, (bc, br) = cpu_build(occluders)
    if np.linalg.norm(frag - bc) > br + SUPPORT:
        return 0.0

    # pass 1: source ships (any sub-run sphere within support)
    sources = []
    for center, radius, start, count, ship in headers:
        if np.linalg.norm(frag - center) > radius + SUPPORT:
            continue
        if ship not in sources:
            sources.append(ship)

    # host slots = sources + their partners, deduped, capped
    hosts = []
    for s in sources:
        if s not in hosts and len(hosts) < MAX_HOSTS:
            hosts.append(s)
        for c, t in ships[s]["partners"]:
            if t not in hosts and len(hosts) < MAX_HOSTS:
                hosts.append(t)

    def claim_of(owner, host):
        for c, t in ships[owner]["partners"]:
            if t == host:
                return c
        return 0.0

    total = 0.0
    for h in hosts:
        hd = ships[h]
        q, anchor = hd["q"], hd["anchor"]
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

        occ = np.zeros(18)
        for center, radius, start, count, ship in headers:
            if np.linalg.norm(frag - center) > radius + SUPPORT:
                continue
            if ship == self_ship:
                continue
            wBase = ships[ship]["r"] * (1.0 if ship == h else claim_of(ship, h))
            if wBase <= 0.0:
                continue
            for k in range(start, start + count):
                vw = np.asarray(occluders[k][0], float)
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
                            occ[b * 9 + (dv + 1) * 3 + (du + 1)] += wBase * ta * tu * tv
        occ = np.minimum(occ, 1.0)

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
        total += glsl_interp(corner, fu, fv)
    return min(total, MAX_TOTAL)


if __name__ == "__main__":
    rng = np.random.default_rng(31)

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
        for ship in range(rng.integers(1, 4)):
            q = rq()
            origin = rng.uniform(-3, 3, 3)
            cells = {(0, 0, 0)}
            for _ in range(rng.integers(0, 5)):
                base = list(cells)[rng.integers(len(cells))]
                d = np.zeros(3, int)
                d[rng.integers(3)] = rng.choice([-1, 1])
                cells.add(tuple(np.array(base) + d))
            for c in cells:
                occs.append((origin + quat_rotate(q, np.array(c, float)),
                             q, ship + 1))
        self_ship = int(rng.integers(0, 3))
        worst = max(worst, abs(ship_ao(frag, nrm, occs, self_ship) -
                               glsl_ship_ao(frag, nrm, occs, self_ship)))
    print(f"optimized-GLSL transliteration vs seam5: max |diff| over 20k = {worst:.2e}")
