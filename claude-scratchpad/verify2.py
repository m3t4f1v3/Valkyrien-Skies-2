#!/usr/bin/env python3
"""Visual + numeric verification of seam2 (the spec algorithm) against the
vanilla/sodium reference and against seam.py (the current GLSL variant).

Outputs:
  v2_scene1_single.png  single block on floor: seam2 vs vanilla vs diff
  v2_scene2_gap.png     s_s one-block gap merging
  v2_scene3_rot.png     rotated block, octagon should follow the ship
  v2_slide.png          ship sliding away: continuity in ship position + f choice
  stdout                cell-border discontinuity metric, old vs new
"""
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

import seam as old
import seam2 as new
from seam2 import f_linear, f_smooth
from viz import vanilla_floor_loss, draw_block_outline, IDENT, yaw_quat

UP = np.array([0, 1, 0.])


def floor_img(fn, occ, extent, res=150, buried=(), **kw):
    x0, x1, z0, z1 = extent
    xs = np.linspace(x0 + 1e-4, x1 - 1e-4, res)
    zs = np.linspace(z0 + 1e-4, z1 - 1e-4, res)
    img = np.full((res, res), np.nan)
    for i, z in enumerate(zs):
        for j, x in enumerate(xs):
            if (int(np.floor(x)), int(np.floor(z))) in buried:
                continue
            img[i, j] = fn(np.array([x, 1.0, z]), UP, occ, **kw)
    return img


def show(ax, img, extent, vmax=0.4):
    return ax.imshow(img, origin="lower", extent=extent, vmin=0, vmax=vmax,
                     cmap="inferno")


def vanilla_img(cells, extent, res=150):
    x0, x1, z0, z1 = extent
    xs = np.linspace(x0 + 1e-4, x1 - 1e-4, res)
    zs = np.linspace(z0 + 1e-4, z1 - 1e-4, res)
    return np.array([[vanilla_floor_loss(x, z, cells) for x in xs] for z in zs])


def overlay_debug2(ax, occ, probe, normal, self_ship=-1):
    dbg = []
    new.seam_ao(np.asarray(probe, float), np.asarray(normal, float), occ,
                self_ship, collect=dbg)
    for g in dbg:
        quad = np.array([p[[0, 2]] for p in g["quad"]] + [g["quad"][0][[0, 2]]])
        ax.plot(quad[:, 0], quad[:, 1], color="orange", lw=1.6)
        if g["sub"] is not None:
            seg = np.array([g["sub"][0][[0, 2]], g["sub"][1][[0, 2]]])
            ax.plot(seg[:, 0], seg[:, 1], color="lime", lw=1.4)
            ax.plot(g["M"][0], g["M"][2], "o", color="violet", ms=5)
        for a, b in g["pairs"]:
            ax.plot([a[0], b[0]], [a[2], b[2]], color="deepskyblue", lw=0.9, ls=":")


# ---- scene 1: single block, parity with vanilla ----------------------------
def scene_single():
    occ = [((3.5, 1.5, 3.5), IDENT, 1)]
    ext = (1, 6, 1, 6)
    buried = {(3, 3)}
    van = vanilla_img({(3, 3)}, ext)
    img = floor_img(new.seam_ao, occ, ext, buried=buried)
    van_m = van.copy(); van_m[np.isnan(img)] = np.nan
    d = img - van_m
    print(f"single block vs vanilla: max|diff|={np.nanmax(np.abs(d)):.4f}  "
          f"mean|diff|={np.nanmean(np.abs(d)):.4f}")
    fig, axs = plt.subplots(1, 3, figsize=(14, 4.6))
    fig.suptitle("seam2 (spec): single block on floor vs vanilla")
    show(axs[0], img, ext); axs[0].set_title("seam2")
    show(axs[1], van, ext); axs[1].set_title("vanilla reference")
    imd = axs[2].imshow(img - van, origin="lower", extent=ext,
                        vmin=-0.2, vmax=0.2, cmap="seismic")
    axs[2].set_title("seam2 - vanilla")
    for ax in axs:
        draw_block_outline(ax, 3, 3)
    overlay_debug2(axs[0], occ, (2.9, 1, 3.5), UP)
    fig.colorbar(imd, ax=axs[2])
    fig.savefig("v2_scene1_single.png", dpi=90)
    plt.close(fig)


# ---- scene 2: gap merging ---------------------------------------------------
def scene_gap():
    occ = [((2.5, 1.5, 3.5), IDENT, 1), ((4.5, 1.5, 3.5), IDENT, 2)]
    ext = (0, 7, 1, 6)
    buried = {(2, 3), (4, 3)}
    img = floor_img(new.seam_ao, occ, ext, buried=buried)
    van = vanilla_img({(2, 3), (4, 3)}, ext)
    mask = np.isnan(img)
    van_m = van.copy(); van_m[mask] = np.nan
    d = img - van_m
    print(f"s_s gap vs vanilla: max|diff|={np.nanmax(np.abs(d)):.4f}  "
          f"mean|diff|={np.nanmean(np.abs(d)):.4f}")
    fig, axs = plt.subplots(1, 4, figsize=(18, 4.2))
    fig.suptitle("seam2: s_s one-block gap, floor shadow merging")
    show(axs[0], img, ext)
    axs[0].set_title("seam2, both ships")
    show(axs[1], van, ext)
    axs[1].set_title("vanilla (same layout, one grid)")
    imd = axs[2].imshow(d, origin="lower", extent=ext,
                        vmin=-0.2, vmax=0.2, cmap="seismic")
    axs[2].set_title("seam2 - vanilla")
    fig.colorbar(imd, ax=axs[2])
    show(axs[3], floor_img(new.seam_ao, occ[:1], ext, buried={(2, 3)}), ext)
    axs[3].set_title("left ship only")
    for ax in axs:
        draw_block_outline(ax, 2, 3); draw_block_outline(ax, 4, 3)
    overlay_debug2(axs[0], occ, (3.5, 1, 3.5), UP)
    fig.savefig("v2_scene2_gap.png", dpi=90)
    plt.close(fig)


# ---- scene 3: rotated ship --------------------------------------------------
def scene_rot():
    fig, axs = plt.subplots(1, 3, figsize=(14, 4.6))
    fig.suptitle("seam2: rotated block 0.5 above floor")
    for ax, deg in zip(axs, (0, 22.5, 45)):
        occ = [((3.5, 2.0, 3.5), yaw_quat(deg), 1)]
        show(ax, floor_img(new.seam_ao, occ, (1, 6, 1, 6)), (1, 6, 1, 6))
        draw_block_outline(ax, 3, 3, yaw_quat(deg))
        ax.set_title(f"yaw {deg} deg")
        overlay_debug2(ax, occ, (2.8, 1, 3.5), UP)
    fig.savefig("v2_scene3_rot.png", dpi=90)
    plt.close(fig)


# ---- border discontinuity metric -------------------------------------------
def border_jump(fn, occ, extent, eps=1e-4, res=160, **kw):
    """Max |loss| jump across integer cell borders on the floor."""
    x0, x1, z0, z1 = extent
    worst = 0.0
    for bx in range(int(np.ceil(x0)) + 1, int(np.floor(x1))):
        for z in np.linspace(z0 + eps, z1 - eps, res):
            lo = fn(np.array([bx - eps, 1.0, z]), UP, occ, **kw)
            hi = fn(np.array([bx + eps, 1.0, z]), UP, occ, **kw)
            worst = max(worst, abs(hi - lo))
    for bz in range(int(np.ceil(z0)) + 1, int(np.floor(z1))):
        for x in np.linspace(x0 + eps, x1 - eps, res):
            lo = fn(np.array([x, 1.0, bz - eps]), UP, occ, **kw)
            hi = fn(np.array([x, 1.0, bz + eps]), UP, occ, **kw)
            worst = max(worst, abs(hi - lo))
    return worst


def metrics():
    cases = {
        "aligned single":  [((3.5, 1.5, 3.5), IDENT, 1)],
        "offset 0.3":      [((3.8, 1.5, 3.5), IDENT, 1)],
        "yaw 22.5":        [((3.5, 2.0, 3.5), yaw_quat(22.5), 1)],
        "yaw 45":          [((3.5, 2.0, 3.5), yaw_quat(45), 1)],
        "hover 0.3":       [((3.5, 1.8, 3.5), IDENT, 1)],
        "hover+offset":    [((3.8, 1.8, 3.5), IDENT, 1)],
    }
    print(f"{'case':>16} | {'seam.py (GLSL now)':>19} | {'seam2 linear':>13} | {'seam2 smooth':>13}")
    for name, occ in cases.items():
        j_old = border_jump(old.seam_ao, occ, (1, 6, 1, 6))
        j_lin = border_jump(new.seam_ao, occ, (1, 6, 1, 6), f=f_linear)
        j_smo = border_jump(new.seam_ao, occ, (1, 6, 1, 6), f=f_smooth)
        print(f"{name:>16} | {j_old:19.4f} | {j_lin:13.4f} | {j_smo:13.4f}")


# ---- ship slide: continuity in ship position + f choice ---------------------
def scene_slide():
    gaps = np.linspace(0, 1.6, 81)
    xs = np.linspace(1.5 + 1e-4, 4 - 1e-4, 220)
    fig, axs = plt.subplots(1, 3, figsize=(15, 4.4))
    fig.suptitle("block sliding off the floor cell: loss along z=3.5 vs gap "
                 "(want: no vertical banding, smooth in both axes)")
    for ax, (fname, f) in zip(axs[:2], [("f linear", f_linear), ("f smoothstep", f_smooth)]):
        img = np.zeros((len(gaps), len(xs)))
        for i, g in enumerate(gaps):
            occ = [((4.5 + g, 1.5, 3.5), IDENT, 1)]
            for j, x in enumerate(xs):
                img[i, j] = new.seam_ao(np.array([x, 1.0, 3.5]), UP, occ, f=f)
        ax.imshow(img, origin="lower", aspect="auto",
                  extent=(1.5, 4, 0, 1.6), vmin=0, vmax=0.25, cmap="inferno")
        ax.set_xlabel("frag x (block near edge at 4+gap)")
        ax.set_ylabel("gap")
        ax.set_title(fname)
    # parity at gap 0 vs vanilla
    occ0 = [((4.5, 1.5, 3.5), IDENT, 1)]
    axs[2].plot(xs, [vanilla_floor_loss(x, 3.5, {(4, 3)}) for x in xs],
                "k--", lw=2, label="vanilla, block adjacent")
    for fname, f, c in [("linear", f_linear, "tab:orange"),
                        ("smoothstep", f_smooth, "tab:cyan")]:
        axs[2].plot(xs, [new.seam_ao(np.array([x, 1.0, 3.5]), UP, occ0, f=f)
                         for x in xs], c, lw=1.4, label=f"seam2 {fname}, gap 0")
    for g, ls in ((0.5, ":"), (1.0, "-.")):
        occg = [((4.5 + g, 1.5, 3.5), IDENT, 1)]
        axs[2].plot(xs, [new.seam_ao(np.array([x, 1.0, 3.5]), UP, occg)
                         for x in xs], "tab:orange", ls=ls, lw=1,
                    label=f"linear, gap {g}")
    axs[2].legend(fontsize=7); axs[2].set_title("cross sections")
    axs[2].set_xlabel("frag x"); axs[2].set_ylabel("loss")
    fig.savefig("v2_slide.png", dpi=90)
    plt.close(fig)


if __name__ == "__main__":
    metrics()
    scene_single()
    scene_gap()
    scene_rot()
    scene_slide()
    print("saved: v2_scene1_single.png v2_scene2_gap.png v2_scene3_rot.png v2_slide.png")
