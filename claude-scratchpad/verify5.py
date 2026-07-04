#!/usr/bin/env python3
"""Visual verification of seam4 cross-ship merging.

  v5_merge_pair.png   two single-block ships at lateral offsets 0/0.25/0.5:
                      seam4 (top, merged) vs seam3 (bottom, independent sums)
  v5_merge_rot.png    ship 0 deg | 1-block gap | ship rotated 0/22.5/45
  v5_aligned.png      cross-ship aligned splits vs vanilla (parity heatmaps)
"""
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

from seam3 import ship_ao as s3
from seam4 import ship_ao as s4
from viz import vanilla_floor_loss, draw_block_outline, IDENT, yaw_quat

UP = np.array([0, 1, 0.])


def img(fn, occ, extent, res=160, buried=()):
    x0, x1, z0, z1 = extent
    xs = np.linspace(x0 + 1e-4, x1 - 1e-4, res)
    zs = np.linspace(z0 + 1e-4, z1 - 1e-4, res)
    out = np.full((res, res), np.nan)
    for i, z in enumerate(zs):
        for j, x in enumerate(xs):
            if (int(np.floor(x)), int(np.floor(z))) in buried:
                continue
            out[i, j] = fn(np.array([x, 1.0, z]), UP, occ)
    return out


def show(ax, m, extent, vmax=0.4):
    return ax.imshow(m, origin="lower", extent=extent, vmin=0, vmax=vmax,
                     cmap="inferno")


def scene_pair():
    ext = (0.5, 6.5, 1, 6)
    fig, axs = plt.subplots(2, 3, figsize=(14, 9))
    fig.suptitle("two SEPARATE 1-block ships, side by side: seam4 merged (top)"
                 " vs seam3 independent (bottom)")
    for col, off in enumerate((0.0, 0.25, 0.5)):
        occ = [((2.5, 1.5, 3.5), IDENT, 1), ((3.5, 1.5, 3.5 + off), IDENT, 2)]
        buried = {(2, 3), (3, 3 + int(off > 0.5))} if off == 0 else set()
        show(axs[0, col], img(s4, occ, ext, buried=buried), ext)
        show(axs[1, col], img(s3, occ, ext, buried=buried), ext)
        for row in (0, 1):
            draw_block_outline(axs[row, col], 2, 3)
            draw_block_outline(axs[row, col], 3, 3 + off)
            axs[row, col].set_title(
                f"z offset {off} ({'seam4' if row == 0 else 'seam3'})")
    fig.savefig("v5_merge_pair.png", dpi=90)
    plt.close(fig)


def scene_rot():
    ext = (0, 7, 1, 6)
    fig, axs = plt.subplots(2, 3, figsize=(15, 9))
    fig.suptitle("ship 0 deg | gap | ship rotated: seam4 (top) vs seam3 (bottom)")
    for col, deg in enumerate((0, 22.5, 45)):
        occ = [((2.5, 1.5, 3.5), IDENT, 1), ((4.5, 1.5, 3.5), yaw_quat(deg), 2)]
        for row, fn in ((0, s4), (1, s3)):
            show(axs[row, col], img(fn, occ, ext), ext)
            draw_block_outline(axs[row, col], 2, 3)
            draw_block_outline(axs[row, col], 4, 3, yaw_quat(deg))
            axs[row, col].set_title(
                f"yaw {deg} ({'seam4' if row == 0 else 'seam3'})")
    fig.savefig("v5_merge_rot.png", dpi=90)
    plt.close(fig)


def scene_aligned():
    cases = [
        ("row3 A|B|C", [([(2, 3)], 1), ([(3, 3)], 2), ([(4, 3)], 3)], (0, 7, 1, 6)),
        ("L A|B", [([(3, 3), (4, 3)], 1), ([(4, 4)], 2)], (1, 7, 1, 7)),
        ("2x2 A|B", [([(3, 3), (4, 4)], 1), ([(4, 3), (3, 4)], 2)], (1, 7, 1, 7)),
    ]
    fig, axs = plt.subplots(2, 3, figsize=(14, 9))
    fig.suptitle("cross-ship ALIGNED splits: seam4 (top) vs vanilla single-grid"
                 " (bottom) -- must be identical")
    for col, (name, ships, ext) in enumerate(cases):
        occ, cells = [], set()
        for cs, idx in ships:
            for cx, cz in cs:
                occ.append(((cx + .5, 1.5, cz + .5), IDENT, idx))
                cells.add((cx, cz))
        m = img(s4, occ, ext, buried=cells)
        show(axs[0, col], m, ext)
        x0, x1, z0, z1 = ext
        xs = np.linspace(x0 + 1e-4, x1 - 1e-4, 160)
        zs = np.linspace(z0 + 1e-4, z1 - 1e-4, 160)
        van = np.array([[vanilla_floor_loss(x, z, cells) for x in xs] for z in zs])
        show(axs[1, col], van, ext)
        d = np.nanmax(np.abs(m - van))
        axs[0, col].set_title(f"{name} seam4 (max|diff|={d:.1e})")
        axs[1, col].set_title(f"{name} vanilla")
        for cx, cz in cells:
            draw_block_outline(axs[0, col], cx, cz)
            draw_block_outline(axs[1, col], cx, cz)
    fig.savefig("v5_aligned.png", dpi=90)
    plt.close(fig)


if __name__ == "__main__":
    scene_pair()
    scene_rot()
    scene_aligned()
    print("saved: v5_merge_pair.png v5_merge_rot.png v5_aligned.png")
