"""Visual tests for the seam AO: renders floor/side-face heatmaps of the
shader-port loss next to a true vanilla/sodium AO reference, with the debug
geometry (merge quad, subtend lines, midpoints) overlaid."""
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from seam import seam_ao, quat_rotate
from seam2 import tri_interp

IDENT = np.array([0., 0., 0., 1.])


def yaw_quat(deg):
    r = np.radians(deg) / 2
    return np.array([0., np.sin(r), 0., np.cos(r)])


# ---------------- vanilla reference (sodium AoFaceData, UP faces) ----------
def vanilla_floor_loss(x, z, solid_cells):
    """Loss on the floor plane y=1 at world (x, 1, z). solid_cells: set of
    (cx, cz) cells occupied in the layer ABOVE the floor (y in [1,2])."""
    cx, cz = int(np.floor(x)), int(np.floor(z))
    if (cx, cz) in solid_cells:
        return np.nan  # buried face, not rendered
    fx, fz = x - cx, z - cz

    def sample(dx, dz):
        return 0.2 if (cx + dx, cz + dz) in solid_cells else 1.0

    def vertex(sx, sz):
        ex = sample(1 if sx else -1, 0)
        ez = sample(0, 1 if sz else -1)
        if ex == 0.2 and ez == 0.2:
            co = ex  # L-rule: corner treated as the edge sample
        else:
            co = sample(1 if sx else -1, 1 if sz else -1)
        ca = 1.0  # cell in front of the face (air here)
        return (ex + ez + co + ca) * 0.25

    v00, v10 = vertex(0, 0), vertex(1, 0)
    v01, v11 = vertex(0, 1), vertex(1, 1)
    # The rasterizer interpolates the per-vertex AO linearly over the quad's
    # two TRIANGLES (sharp crease), not bilinearly. Sodium orients the split
    # (ModelQuadOrientation.orientByBrightness) so the crease runs through the
    # opposite corner pair with the greater brightness sum.
    loss = (1 - v00, 1 - v10, 1 - v01, 1 - v11)
    return tri_interp(loss, fx, fz)


# ---------------- rendering helpers ----------------------------------------
def render_floor(ax, occluders, extent, res=220, self_ship=-1, buried=()):
    (x0, x1, z0, z1) = extent
    xs = np.linspace(x0 + 1e-4, x1 - 1e-4, res)
    zs = np.linspace(z0 + 1e-4, z1 - 1e-4, res)
    img = np.zeros((res, res))
    up = np.array([0, 1, 0.])
    for i, z in enumerate(zs):
        for j, x in enumerate(xs):
            if (int(np.floor(x)), int(np.floor(z))) in buried:
                img[i, j] = np.nan
                continue
            img[i, j] = seam_ao(np.array([x, 1.0, z]), up, occluders, self_ship)
    im = ax.imshow(img, origin="lower", extent=(x0, x1, z0, z1),
                   vmin=0, vmax=0.4, cmap="inferno")
    return im


def render_vanilla_floor(ax, solid_cells, extent, res=220):
    (x0, x1, z0, z1) = extent
    xs = np.linspace(x0 + 1e-4, x1 - 1e-4, res)
    zs = np.linspace(z0 + 1e-4, z1 - 1e-4, res)
    img = np.zeros((res, res))
    for i, z in enumerate(zs):
        for j, x in enumerate(xs):
            img[i, j] = vanilla_floor_loss(x, z, solid_cells)
    im = ax.imshow(img, origin="lower", extent=(x0, x1, z0, z1),
                   vmin=0, vmax=0.4, cmap="inferno")
    return im


def overlay_debug(ax, occluders, probe, normal, self_ship=-1, plane="xz"):
    """Run one probe fragment, draw the collected geometry projected on the
    given plane ('xz' floor view or 'zy' side view)."""
    ix, iy = (0, 2) if plane == "xz" else (2, 1)
    dbg = []
    seam_ao(np.asarray(probe, float), np.asarray(normal, float),
            occluders, self_ship, collect=dbg)
    for g in dbg:
        quad = np.array([p[[ix, iy]] for p in g["quad"]] +
                        [g["quad"][0][[ix, iy]]])
        ax.plot(quad[:, 0], quad[:, 1], color="orange", lw=1.6)
        for s in (g["sub0"], g["sub1"]):
            seg = np.array([s[0][[ix, iy]], s[1][[ix, iy]]])
            ax.plot(seg[:, 0], seg[:, 1], color="lime", lw=1.2)
        for M in g["M"]:
            ax.plot(M[ix], M[iy], "o", color="violet", ms=5)
        for a in g["A"]:
            ax.plot(a[ix], a[iy], "o", color="deepskyblue", ms=3)
        for b in g["B"]:
            ax.plot(b[ix], b[iy], "s", color="red", ms=3, mfc="none")


def draw_block_outline(ax, cx, cz, q=None, color="w"):
    c = np.array([cx + 0.5, 0, cz + 0.5])
    pts = []
    for dx, dz in ((-0.5, -0.5), (0.5, -0.5), (0.5, 0.5), (-0.5, 0.5), (-0.5, -0.5)):
        p = np.array([dx, 0, dz])
        if q is not None:
            p = quat_rotate(q, p)
        pts.append((c + p)[[0, 2]])
    pts = np.array(pts)
    ax.plot(pts[:, 0], pts[:, 1], color=color, lw=1.0, ls="--")
