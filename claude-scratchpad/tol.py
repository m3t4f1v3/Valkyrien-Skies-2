import numpy as np
from seam2 import quat_rotate
import seam4, seam5

rng = np.random.default_rng(7)
def rq():
    x = rng.normal(size=4); return x/np.linalg.norm(x)

def gen():
    nrm = np.zeros(3); ax = rng.integers(3); nrm[ax]=rng.choice([-1,1])
    frag = rng.uniform(-3,3,3); frag[ax]=round(frag[ax])
    occs=[]
    for ship in range(rng.integers(1,4)):
        q=rq(); origin=rng.uniform(-3,3,3); cells={(0,0,0)}
        for _ in range(rng.integers(0,5)):
            base=list(cells)[rng.integers(len(cells))]
            d=np.zeros(3,int); d[rng.integers(3)]=rng.choice([-1,1])
            cells.add(tuple(np.array(base)+d))
        for c in cells:
            occs.append((origin+quat_rotate(q,np.array(c,float)),q,ship+1))
    return frag,nrm,occs,int(rng.integers(0,3))

diffs=[]
for _ in range(8000):
    f,n,o,s=gen()
    a=seam4.ship_ao(f,n,o,s); b=seam5.ship_ao(f,n,o,s)
    diffs.append(abs(a-b))
diffs=np.array(diffs)
print(f"seam5 vs seam4: max={diffs.max():.3e} mean={diffs.mean():.3e} p99={np.percentile(diffs,99):.3e} p999={np.percentile(diffs,99.9):.3e}")
