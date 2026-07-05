"""Drift from reducing MAX_PARTNERS (top-N cross-ship claim partners kept).
Fewer partners => smaller ship-directory rows, fewer host lattices a source can
spawn, less register pressure. Only dense multi-ship piles are affected."""
import numpy as np
from seam2 import quat_rotate
import seam5

rng = np.random.default_rng(11)
def rq():
    x = rng.normal(size=4); return x/np.linalg.norm(x)

def gen(maxships):
    nrm = np.zeros(3); ax = rng.integers(3); nrm[ax]=rng.choice([-1,1])
    frag = rng.uniform(-3,3,3); frag[ax]=round(frag[ax])
    occs=[]
    for ship in range(rng.integers(1,maxships+1)):
        q=rq(); origin=rng.uniform(-3,3,3); cells={(0,0,0)}
        for _ in range(rng.integers(0,5)):
            base=list(cells)[rng.integers(len(cells))]
            d=np.zeros(3,int); d[rng.integers(3)]=rng.choice([-1,1])
            cells.add(tuple(np.array(base)+d))
        for c in cells:
            occs.append((origin+quat_rotate(q,np.array(c,float)),q,ship+1))
    return frag,nrm,occs,int(rng.integers(0,3))

for maxships in (3, 6):
    cases=[gen(maxships) for _ in range(5000)]
    base=seam5.MAX_PARTNERS
    seam5.MAX_PARTNERS=4
    ref=[seam5.ship_ao(*c) for c in cases]
    print(f"--- up to {maxships} ships/scene ---")
    for mp in (4,3,2,1):
        seam5.MAX_PARTNERS=mp
        diffs=np.array([abs(seam5.ship_ao(*c)-r) for c,r in zip(cases,ref)])
        print(f"  MAX_PARTNERS={mp}: max={diffs.max():.3e} mean={diffs.mean():.3e} p99={np.percentile(diffs,99):.3e}")
    seam5.MAX_PARTNERS=base
