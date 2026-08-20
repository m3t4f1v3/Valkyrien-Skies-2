import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from seam import seam_ao
from viz import (IDENT, yaw_quat, render_floor, render_vanilla_floor,
                 overlay_debug, draw_block_outline)

OUT = "."


def panel(title, n=3, size=4.6):
    fig, axs = plt.subplots(1, n, figsize=(size * n, size))
    fig.suptitle(title)
    if n == 1:
        axs = [axs]
    return fig, axs


# ---- scene 1: single ship block resting on the floor ----------------------
def scene_single():
    occ = [((3.5, 1.5, 3.5), IDENT, 1)]
    ext = (1, 6, 1, 6)
    buried = {(3, 3)}
    fig, axs = panel("single block on floor: shader vs vanilla (floor y=1)", 3)
    im = render_floor(axs[0], occ, ext, buried=buried)
    axs[0].set_title("shader port")
    render_vanilla_floor(axs[1], {(3, 3)}, ext)
    axs[1].set_title("vanilla reference")
    # difference panel
    res = 220
    xs = np.linspace(1 + 1e-4, 6 - 1e-4, res)
    zs = np.linspace(1 + 1e-4, 6 - 1e-4, res)
    up = np.array([0, 1, 0.])
    diff = np.zeros((res, res))
    from viz import vanilla_floor_loss
    for i, z in enumerate(zs):
        for j, x in enumerate(xs):
            v = vanilla_floor_loss(x, z, {(3, 3)})
            s = np.nan if (int(x), int(z)) == (3, 3) else seam_ao(np.array([x, 1., z]), up, occ)
            diff[i, j] = s - v
    imd = axs[2].imshow(diff, origin="lower", extent=ext, vmin=-0.2, vmax=0.2, cmap="seismic")
    axs[2].set_title("shader - vanilla")
    for ax in axs:
        draw_block_outline(ax, 3, 3)
    overlay_debug(axs[0], occ, (2.9, 1, 3.5), (0, 1, 0))
    fig.colorbar(im, ax=axs[0]); fig.colorbar(imd, ax=axs[2])
    fig.savefig(f"{OUT}/scene1_single.png", dpi=90)
    plt.close(fig)


# ---- scene 2: s_s with one-block gap on the floor --------------------------
def scene_gap():
    occ = [((2.5, 1.5, 3.5), IDENT, 1), ((4.5, 1.5, 3.5), IDENT, 2)]
    ext = (0, 7, 1, 6)
    buried = {(2, 3), (4, 3)}
    fig, axs = panel("s_s one-block gap: floor shadow merging", 3)
    render_floor(axs[0], occ, ext, buried=buried)
    axs[0].set_title("shader port")
    render_vanilla_floor(axs[1], {(2, 3), (4, 3)}, ext)
    axs[1].set_title("vanilla reference (blocks adjacent)")
    render_floor(axs[2], occ[:1], ext, buried={(2, 3)})
    axs[2].set_title("left block only (no merge partner)")
    for ax in axs:
        draw_block_outline(ax, 2, 3); draw_block_outline(ax, 4, 3)
    overlay_debug(axs[0], occ, (3.5, 1, 3.5), (0, 1, 0))
    fig.savefig(f"{OUT}/scene2_gap.png", dpi=90)
    plt.close(fig)


# ---- scene 3: rotated block over the floor ---------------------------------
def scene_rot():
    fig, axs = panel("rotated block 0.5 above floor: octagon should rotate", 3)
    for ax, deg in zip(axs, (0, 22.5, 45)):
        occ = [((3.5, 2.0, 3.5), yaw_quat(deg), 1)]
        render_floor(ax, occ, (1, 6, 1, 6))
        draw_block_outline(ax, 3, 3, yaw_quat(deg))
        ax.set_title(f"yaw {deg} deg")
        overlay_debug(ax, occ, (2.8, 1, 3.5), (0, 1, 0))
    fig.savefig(f"{OUT}/scene3_rot.png", dpi=90)
    plt.close(fig)


# ---- scene 4: floating s_s, facing side face of ship A ---------------------
def scene_side():
    # ships occupy cells x[2,3] and x[4,5], y[0,1], z[0,1]; no floor.
    occ = [((2.5, 0.5, 0.5), IDENT, 1), ((4.5, 0.5, 0.5), IDENT, 2)]
    fig, axs = panel("floating s_s: +x side face of LEFT ship (view from gap)", 2)
    res = 220
    ys = np.linspace(1e-4, 1 - 1e-4, res)
    zs = np.linspace(1e-4, 1 - 1e-4, res)
    img = np.zeros((res, res))
    nrm = np.array([1, 0, 0.])
    for i, y in enumerate(ys):
        for j, z in enumerate(zs):
            img[i, j] = seam_ao(np.array([3.0, y, z]), nrm, occ, self_ship=1)
    im = axs[0].imshow(img, origin="lower", extent=(0, 1, 0, 1), vmin=0, vmax=0.4, cmap="inferno")
    axs[0].set_title("side-face loss (z right, y up)")
    fig.colorbar(im, ax=axs[0])
    overlay_debug(axs[1], occ, (3.0, 0.5, 0.5), (1, 0, 0), self_ship=1, plane="zy")
    axs[1].set_xlim(-0.5, 1.5); axs[1].set_ylim(-0.5, 1.5)
    axs[1].set_title("debug geometry (z-y projection)")
    axs[1].set_aspect("equal")
    fig.savefig(f"{OUT}/scene4_side.png", dpi=90)
    plt.close(fig)


# ---- scene 5: cross-sections as numbers ------------------------------------
def scene_profiles():
    up = np.array([0, 1, 0.])
    occ1 = [((3.5, 1.5, 3.5), IDENT, 1)]
    print("single block, floor row z=3.5 (through the side):")
    for x in np.arange(2.0, 3.01, 0.125):
        print(f"  x={x:5.3f}: {seam_ao(np.array([x,1.,3.5]), up, occ1):.3f}", end="")
        print(f"  vanilla={0.2*max(0,x-2):.3f}")
    print("single block, diagonal row x=z (through the corner):")
    for t in np.arange(2.0, 3.01, 0.125):
        print(f"  x=z={t:5.3f}: {seam_ao(np.array([t,1.,t]), up, occ1):.3f}")


if __name__ == "__main__":
    scene_single()
    scene_gap()
    scene_rot()
    scene_side()
    scene_profiles()
    print("saved: scene1_single.png scene2_gap.png scene3_rot.png scene4_side.png")
