"""Drift from reducing MAX_HOSTS in the actual shader transliteration
(fuzz7.glsl_ship_ao) vs seam5. MAX_HOSTS is the pass-2 host-loop cap AND the
size of the mat4 host register slots -- the dominant per-fragment cost/register
multiplier. hostCount is data-dependent so lowering the cap only bites dense
multi-ship-pile fragments; measure how dense it must be to matter."""
import numpy as np
from seam2 import quat_rotate
import seam5, fuzz7

rng = np.random.default_rng(29)
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

for maxships in (3, 5, 8):
    cases=[gen(maxships) for _ in range(4000)]
    ref=[seam5.ship_ao(*c) for c in cases]
    print(f"--- up to {maxships} ships/scene ---")
    base=fuzz7.MAX_HOSTS
    for mh in (4,3,2):
        fuzz7.MAX_HOSTS=mh
        diffs=np.array([abs(fuzz7.glsl_ship_ao(*c)-r) for c,r in zip(cases,ref)])
        print(f"  MAX_HOSTS={mh}: max={diffs.max():.3e} mean={diffs.mean():.3e} p99={np.percentile(diffs,99):.3e}")
    fuzz7.MAX_HOSTS=base
