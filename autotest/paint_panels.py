#!/usr/bin/env python3
"""Compose the debug-painted merge screenshots into panels you can actually look at.

The fixtures capture with debugFloodPaint=5, where the world shader replaces the shaded colour with
the AO loss in the red channel. That is the right thing to MEASURE — it is the loss field itself
rather than a few levels of darkening — but it is close to black on screen, because the loss over a
floor next to a one-block ship peaks around 0.2 of a 0..1 channel.

So these panels do two things and nothing else: crop every frame of a group to one shared window
(so the frames are directly comparable), and map the loss through a colormap on a shared scale. No
smoothing, no per-frame normalisation — the numbers under the colours are the same numbers
autotest/score_ao.py and autotest/ref_merge.py scored.

The layouts mirror the scratchpad figures they come from: verify5's scene_pair and scene_rot.

  usage: python3 autotest/paint_panels.py [screenshot-dir] [out-dir]
"""
import os
import sys

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from PIL import Image

SHOTS = sys.argv[1] if len(sys.argv) > 1 else "forge/run/screenshots"
OUT = sys.argv[2] if len(sys.argv) > 2 else "forge/run/screenshots/panels"

# The paint keeps a 1e-3 trace of the real colour so the driver cannot dead-strip the samplers.
TRACE = 3
# The paint writes clamp((1 - ao) * 1.25), so a channel value maps back to loss by dividing by 1.25.
PAINT_GAIN = 1.25


def loss(name):
    path = os.path.join(SHOTS, name + ".png")
    if not os.path.exists(path):
        return None
    a = np.asarray(Image.open(path).convert("RGB")).astype(float)
    red = a[:, :, 0].copy()
    red[red <= TRACE] = 0.0
    return red / 255.0 / PAINT_GAIN


def shared_window(frames, margin=26):
    """One crop box covering every frame's shadow, so the panel's cells are comparable."""
    ys, xs = [], []
    for f in frames:
        y, x = np.nonzero(f > 0)
        if len(y):
            ys.append(y)
            xs.append(x)
    if not ys:
        h, w = frames[0].shape
        return 0, h, 0, w
    y = np.concatenate(ys)
    x = np.concatenate(xs)
    h, w = frames[0].shape
    return (max(int(y.min()) - margin, 0), min(int(y.max()) + margin, h),
            max(int(x.min()) - margin, 0), min(int(x.max()) + margin, w))


def panel(fname, title, cells, subtitle=None):
    """cells: list of (screenshot-name, caption). Missing shots are reported, not skipped silently."""
    frames, captions = [], []
    for name, cap in cells:
        f = loss(name)
        if f is None:
            print(f"  missing: {name}")
            continue
        frames.append(f)
        captions.append(cap)
    if not frames:
        print(f"  {fname}: nothing to draw")
        return None

    y0, y1, x0, x1 = shared_window(frames)
    vmax = max(float(f.max()) for f in frames) or 1.0

    n = len(frames)
    fig, axs = plt.subplots(1, n, figsize=(3.5 * n, 4.1))
    if n == 1:
        axs = [axs]
    fig.suptitle(title + ("\n" + subtitle if subtitle else ""), fontsize=11)
    im = None
    for ax, f, cap in zip(axs, frames, captions):
        im = ax.imshow(f[y0:y1, x0:x1], cmap="inferno", vmin=0.0, vmax=vmax,
                       interpolation="nearest")
        ax.set_title(cap, fontsize=9)
        ax.set_xticks([])
        ax.set_yticks([])
    cbar = fig.colorbar(im, ax=axs, fraction=0.025, pad=0.02)
    cbar.set_label("AO loss (0 = unshadowed)", fontsize=8)
    out = os.path.join(OUT, fname)
    fig.savefig(out, dpi=110, bbox_inches="tight")
    plt.close(fig)
    print(f"  wrote {out}")
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"reading {SHOTS}")

    panel("merge_pair.png",
          "Cross-ship merging: two SEPARATE one-block ships, seen from above",
          [("aom_single", "one ship alone\n(reference)"),
           ("aom_two_one_ship", "both cells as ONE ship\n(what merging should look like)"),
           ("aom_pair_z000", "two ships, touching"),
           ("aom_pair_z025", "two ships, +0.25 off-grid"),
           ("aom_pair_z050", "two ships, +0.5 off-grid")],
          "verify5:scene_pair. Merged, the touching pair matches the one-ship case pixel for pixel; "
          "un-merged it would read as two overlapping single-block shadows.")

    panel("merge_gap_rotated.png",
          "Merge bridge across a one-block gap, partner rotating",
          [("aom_single", "one ship alone\n(reference)"),
           ("aom_gaprot_00", "gap, partner at 0 deg"),
           ("aom_gaprot_225", "gap, partner at 22.5 deg"),
           ("aom_gaprot_45", "gap, partner at 45 deg")],
          "verify5:scene_rot. The bridge across the gap is what the user protected when CUTOFF=0.5 "
          "was rejected; it has to survive the partner turning.")

    panel("merge_misalign_yaw.png",
          "Slight ROTATIONAL misalignment: the partial-claim regime",
          [(f"aox_rot_{d:02d}", f"{d} deg") for d in (0, 3, 6, 9, 12, 15)],
          "seam5's o_rot term. Scored against the reference in autotest/ref_merge.py: "
          "max|game-ref| = 0.0198 over this sweep.")

    panel("merge_misalign_offgrid.png",
          "Slight OFF-GRID offset: the partial-claim regime",
          [("aox_rot_00", "aligned")] +
          [(f"aox_off_{int(o * 10):02d}", f"+{o} blocks") for o in (0.1, 0.2, 0.3, 0.4, 0.5)],
          "seam5's o_trans term. Scored against the reference in autotest/ref_merge.py: "
          "max|game-ref| = 0.0255 over this sweep.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
