#!/usr/bin/env python3
"""Merge-verification scenes for seam2: shadows of two INDEPENDENT grids
(different rotations / offsets / heights) merging on a common world floor.
There is no vanilla reference for these -- verification is (a) visual
smoothness of the merged field, (b) cell-border jump metric, (c) continuity
of the loss as the partner ship rotates/slides.

Outputs:
  v3_merge_0_45.png    ship at 0 deg | one-block gap | ship at 45 deg
  v3_merge_sweep.png   right ship yaw 0/15/30/45, left fixed
  v3_merge_offset.png  misaligned translation (half-block z slide, x creep)
  v3_merge_height.png  right ship floating 0.4 above the left
  stdout               border-jump + angle-continuity metrics
"""
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

import seam2 as new
from viz import IDENT, yaw_quat, draw_block_outline
from verify2 import floor_img, show, overlay_debug2, border_jump

UP = np.array([0, 1, 0.])
EXT = (0, 7, 1, 6)


def panel_row(title, n, size=4.4):
    fig, axs = plt.subplots(1, n, figsize=(size * n, size))
    fig.suptitle(title)
    return fig, (axs if n > 1 else [axs])


# ---- scene A: 0 deg | gap | 45 deg -----------------------------------------
def scene_0_45():
    left = ((2.5, 1.5, 3.5), IDENT, 1)
    right = ((4.5, 1.5, 3.5), yaw_quat(45), 2)
    fig, axs = panel_row("merge: ship 0 deg | gap | ship 45 deg (floor y=1)", 3)
    show(axs[0], floor_img(new.seam_ao, [left, right], EXT, buried={(2, 3)}), EXT)
    axs[0].set_title("both ships (merged)")
    show(axs[1], floor_img(new.seam_ao, [left], EXT, buried={(2, 3)}), EXT)
    axs[1].set_title("left only (0 deg)")
    show(axs[2], floor_img(new.seam_ao, [right], EXT), EXT)
    axs[2].set_title("right only (45 deg)")
    for ax in axs:
        draw_block_outline(ax, 2, 3)
        draw_block_outline(ax, 4, 3, yaw_quat(45))
    overlay_debug2(axs[0], [left, right], (3.6, 1, 3.5), UP)
    fig.savefig("v3_merge_0_45.png", dpi=90)
    plt.close(fig)
    j = border_jump(new.seam_ao, [left, right], (2.5, 5.5, 2, 5))
    print(f"0|gap|45   max cell-border jump (gap region): {j:.4f}")


# ---- scene B: rotation sweep ------------------------------------------------
def scene_sweep():
    left = ((2.5, 1.5, 3.5), IDENT, 1)
    fig, axs = panel_row("merge while partner rotates (left fixed at 0 deg)", 4)
    for ax, deg in zip(axs, (0, 15, 30, 45)):
        right = ((4.5, 1.5, 3.5), yaw_quat(deg), 2)
        show(ax, floor_img(new.seam_ao, [left, right], EXT, buried={(2, 3)}), EXT)
        draw_block_outline(ax, 2, 3)
        draw_block_outline(ax, 4, 3, yaw_quat(deg))
        ax.set_title(f"right yaw {deg} deg")
    fig.savefig("v3_merge_sweep.png", dpi=90)
    plt.close(fig)

    # continuity in the rotation angle at fixed probes in/around the gap
    probes = [(3.5, 1, 3.5), (3.2, 1, 3.9), (3.8, 1, 3.1), (3.5, 1, 4.3)]
    degs = np.arange(0, 90.01, 0.5)
    worst = 0.0
    for p in probes:
        prev = None
        for deg in degs:
            occ = [left, ((4.5, 1.5, 3.5), yaw_quat(deg), 2)]
            v = new.seam_ao(np.array(p, float), UP, occ)
            if prev is not None:
                worst = max(worst, abs(v - prev))
            prev = v
    print(f"sweep      max loss step per 0.5 deg of partner rotation: {worst:.4f}")


# ---- scene C: misaligned translation ----------------------------------------
def scene_offset():
    left = ((2.5, 1.5, 3.5), IDENT, 1)
    cases = [("aligned", (4.5, 1.5, 3.5)),
             ("z slide 0.5", (4.5, 1.5, 4.0)),
             ("x 4.35, z 3.8", (4.35, 1.5, 3.8))]
    fig, axs = panel_row("merge with misaligned translation (both 0 deg)", 3)
    for ax, (name, c) in zip(axs, cases):
        right = (c, IDENT, 2)
        show(ax, floor_img(new.seam_ao, [left, right], EXT, buried={(2, 3)}), EXT)
        draw_block_outline(ax, 2, 3)
        draw_block_outline(ax, c[0] - 0.5, c[2] - 0.5)
        ax.set_title(name)
        j = border_jump(new.seam_ao, [left, right], (2.5, 5.5, 2, 5))
        print(f"offset     {name:<16} max border jump: {j:.4f}")
    overlay_debug2(axs[1], [left, ((4.5, 1.5, 4.0), IDENT, 2)], (3.6, 1, 3.8), UP)
    fig.savefig("v3_merge_offset.png", dpi=90)
    plt.close(fig)


# ---- scene D: height mismatch ------------------------------------------------
def scene_height():
    left = ((2.5, 1.5, 3.5), IDENT, 1)
    fig, axs = panel_row("merge with height mismatch (right ship floating)", 3)
    for ax, dy in zip(axs, (0.0, 0.4, 0.8)):
        right = ((4.5, 1.5 + dy, 3.5), IDENT, 2)
        buried = {(2, 3)} | ({(4, 3)} if dy == 0 else set())
        show(ax, floor_img(new.seam_ao, [left, right], EXT, buried=buried), EXT)
        draw_block_outline(ax, 2, 3)
        draw_block_outline(ax, 4, 3)
        ax.set_title(f"right raised {dy}")
        j = border_jump(new.seam_ao, [left, right], (2.5, 5.5, 2, 5))
        print(f"height     raised {dy:<4} max border jump: {j:.4f}")
    fig.savefig("v3_merge_height.png", dpi=90)
    plt.close(fig)


if __name__ == "__main__":
    scene_0_45()
    scene_sweep()
    scene_offset()
    scene_height()
    print("saved: v3_merge_0_45.png v3_merge_sweep.png v3_merge_offset.png "
          "v3_merge_height.png")
