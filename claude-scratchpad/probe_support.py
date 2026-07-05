"""How much does the AO field actually change if we shrink the per-voxel
support/cull radius? seam5 uses SUPPORT=4.5 as both the field support and the
voxel cull. The tent field truly dies at lattice L-inf 2 in (u,v,a); a voxel's
euclidean reach is up to ~2*sqrt3~=3.46 for the corner cell, plus the band
tent reaches 1 more -> real support ~ a bit under 4.5. Measure the clip."""
import importlib
import numpy as np
from seam2 import quat_rotate
import seam5

rng = np.random.default_rng(7)
def rq():
    x = rng.normal(size=4); return x/np.linalg.norm(x)

def gen():
    nrm = np.zeros(3); ax = rng.integers(3); nrm[ax]=rng.choice([-1,1])
    frag = rng.uniform(-3,3,3); frag[ax]=round(frag[ax])
    occs=[]
    for ship in range(rng.integers(1,4)):
        q=rq(); origin=rng.uniform(-3,3,3); cells={(0,0,0)}
        for _ in range(rng.integers(0,6)):
            base=list(cells)[rng.integers(len(cells))]
            d=np.zeros(3,int); d[rng.integers(3)]=rng.choice([-1,1])
            cells.add(tuple(np.array(base)+d))
        for c in cells:
            occs.append((origin+quat_rotate(q,np.array(c,float)),q,ship+1))
    return frag,nrm,occs,int(rng.integers(0,3))

cases=[gen() for _ in range(6000)]
base_support = seam5.SUPPORT
ref=[seam5.ship_ao(*c) for c in cases]

for sup in (4.5, 4.0, 3.7, 3.5, 3.2, 3.0):
    seam5.SUPPORT = sup
    diffs=np.array([abs(seam5.ship_ao(*c)-r) for c,r in zip(cases,ref)])
    print(f"SUPPORT={sup}: max={diffs.max():.3e} mean={diffs.mean():.3e} p99={np.percentile(diffs,99):.3e}")
seam5.SUPPORT = base_support
