#!/usr/bin/env python3
"""Visual verification of seam3 (occluder-lattice, "no drag" AO).

  v4_translate.png  the same ship at x-offsets 0 / 0.25 / 0.5: the shadow must
                    be the SAME shape rigidly translated (no lattice drag),
                    unlike seam2 (bottom row) which redistributes over world
                    vertices.
  v4_rotate.png     ship at yaw 0 / 22.5 / 45 ON the floor: shadow rotates
                    rigidly with the ship, creases follow the SHIP lattice.
  v4_scenes.png     single-parity, 1-gap merge, 3x1 row: vs vanilla reference.
"""
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

import seam2
from seam3 import ship_ao
from viz import vanilla_floor_loss, draw_block_outline, IDENT, yaw_quat

UP = np.array([0, 1, 0.])


def img(fn, occ, extent, res=170, buried=()):
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


def scene_translate():
    ext = (1, 6, 1, 6)
    fig, axs = plt.subplots(2, 3, figsize=(14, 9))
    fig.suptitle("shadow rigidity under off-grid translation: seam3 (top) vs "
                 "seam2 receiver-anchored (bottom)")
    for col, off in enumerate((0.0, 0.25, 0.5)):
        occ = [((3.5 + off, 2.0, 3.5), IDENT, 1)]
        show(axs[0, col], img(ship_ao, occ, ext), ext)
        show(axs[1, col], img(seam2.seam_ao, occ, ext), ext)
        for row in (0, 1):
            draw_block_outline(axs[row, col], 3 + off, 3)
            axs[row, col].set_title(f"x offset {off} ({'seam3' if row == 0 else 'seam2'})")
    fig.savefig("v4_translate.png", dpi=90)
    plt.close(fig)


def scene_rotate():
    ext = (1, 6, 1, 6)
    fig, axs = plt.subplots(1, 3, figsize=(14, 4.6))
    fig.suptitle("seam3: ship yawing ON the floor (contact) -- shadow and "
                 "creases rotate rigidly with the ship")
    for ax, deg in zip(axs, (0, 22.5, 45)):
        occ = [((3.5, 1.5, 3.5), yaw_quat(deg), 1)]
        show(ax, img(ship_ao, occ, ext), ext)
        draw_block_outline(ax, 3, 3, yaw_quat(deg))
        ax.set_title(f"yaw {deg} deg")
    fig.savefig("v4_rotate.png", dpi=90)
    plt.close(fig)


def scene_layouts():
    fig, axs = plt.subplots(2, 3, figsize=(14, 9))
    fig.suptitle("seam3 (top) vs vanilla reference (bottom): aligned layouts")
    cases = [
        ("single", {(3, 3)}, (1, 6, 1, 6)),
        ("s_s gap", {(2, 3), (4, 3)}, (0, 7, 1, 6)),
        ("row3", {(2, 3), (3, 3), (4, 3)}, (0, 7, 1, 6)),
    ]
    for col, (name, cells, ext) in enumerate(cases):
        occ = [((cx + .5, 1.5, cz + .5), IDENT, i + 1)
               for i, (cx, cz) in enumerate(sorted(cells))]
        m = img(ship_ao, occ, ext, buried=cells)
        show(axs[0, col], m, ext)
        x0, x1, z0, z1 = ext
        xs = np.linspace(x0 + 1e-4, x1 - 1e-4, 170)
        zs = np.linspace(z0 + 1e-4, z1 - 1e-4, 170)
        van = np.array([[vanilla_floor_loss(x, z, cells) for x in xs] for z in zs])
        show(axs[1, col], van, ext)
        d = np.nanmax(np.abs(m - van)) if not np.all(np.isnan(m - van)) else 0.0
        axs[0, col].set_title(f"{name} (max|diff|={d:.1e})")
        axs[1, col].set_title(f"{name} vanilla")
        for cx, cz in cells:
            draw_block_outline(axs[0, col], cx, cz)
            draw_block_outline(axs[1, col], cx, cz)
    fig.savefig("v4_scenes.png", dpi=90)
    plt.close(fig)


if __name__ == "__main__":
    scene_translate()
    scene_rotate()
    scene_layouts()
    print("saved: v4_translate.png v4_rotate.png v4_scenes.png")
