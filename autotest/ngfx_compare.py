#!/usr/bin/env python3
"""Compare Nsight GPU-Trace exports and report what the seam AO actually costs.

The exported files are TSV with one metric per row and one column PER FRAME. Roughly half of those
frames are dead: the trace keeps sampling after the fixture has stopped drawing, and those columns
must be excluded or every number is diluted.

That exclusion is not uniform, which is the trap this script exists to avoid. Dead frames write
`NaN` into the counter rows -- filtered out by the float check below -- but a literal `0` into the
`.sum` rows. So the counters came out right while draw counts silently averaged in a few hundred
zeros: 67.2 draws/frame reported against a true 128.0. Both runs were diluted so the comparison
still pointed the right way, but the absolute numbers were meaningless.

The fix is one live-frame mask, taken from the draw count, applied to everything.

  usage: python3 autotest/ngfx_compare.py <ao-on-dir> <ao-off-dir>
"""
import os
import statistics
import sys

DRAWS = "fe__draw_count.sum"

KEYS = [
    ("registers allocated, 3D shaders", "tpc__sm_rf_registers_allocated_shader_3d_realtime.avg.pct_of_peak_sustained_elapsed", "%"),
    ("pixel-shader warps active",       "tpc__warps_active_shader_ps_realtime.avg.pct_of_peak_sustained_elapsed", "%"),
    ("warps inactive while SM active",  "tpc__warps_inactive_sm_active_realtime.avg.pct_of_peak_sustained_elapsed", "%"),
    ("SM throughput",                   "sm__throughput.avg.pct_of_peak_sustained_elapsed", "%"),
    ("L1TEX throughput",                "l1tex__throughput.avg.pct_of_peak_sustained_elapsed", "%"),
    ("VAF (vertex attr) throughput",    "vaf__throughput.avg.pct_of_peak_sustained_elapsed", "%"),
    ("raster throughput",               "raster__throughput.avg.pct_of_peak_sustained_elapsed", "%"),
    ("draws per frame",                 DRAWS, ""),
    ("compute dispatches per frame",    "gr__dispatch_count.sum", ""),
    ("GR engine active",                "gr__cycles_active.avg.pct_of_peak_sustained_elapsed", "%"),
]


def _num(tok):
    """float, or None for blanks and the NaN/inf the export writes for uncollected samples."""
    try:
        v = float(tok)
    except ValueError:
        return None
    return v if v == v and abs(v) != float("inf") else None


def load(path):
    """-> (rows: metric-suffix -> [per-frame values], frame_times: [ms])."""
    rows = {}
    frame = os.path.join(path, "BASE", "GPUTRACE_FRAME.xls")
    if not os.path.exists(frame):
        return None
    with open(frame) as fh:
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2:
                rows[parts[0]] = [_num(p) for p in parts[1:]]

    times = []
    ft = os.path.join(path, "BASE", "FRAME.xls")
    if os.path.exists(ft):
        with open(ft) as fh:
            for line in fh:
                parts = line.rstrip("\n").split("\t")
                if parts and parts[0] == "GPU frame time":
                    times = [_num(p) for p in parts[1:]]
                    break
    return rows, times


def find(rows, suffix):
    for k, v in rows.items():
        if k.endswith(suffix):
            return v
    return None


def live_mask(rows):
    """Frames that actually drew something. Everything else is post-fixture idle."""
    draws = find(rows, DRAWS)
    if draws is None:
        return None
    return [d is not None and d > 0 for d in draws]


def mean_live(vals, mask):
    if vals is None:
        return None
    got = [v for v, m in zip(vals, mask) if m and v is not None]
    return statistics.fmean(got) if got else None


def summarise(name, path):
    loaded = load(path)
    if loaded is None:
        return None
    rows, times = loaded
    mask = live_mask(rows)
    if mask is None:
        return None
    live = sum(mask)
    ft = [t for t, m in zip(times, mask) if m and t is not None] if times else []
    if ft:
        ft_sorted = sorted(ft)
        print(f"{name}: {live} live frames of {len(mask)} sampled, "
              f"{statistics.fmean(ft):.3f} ms mean / {ft_sorted[len(ft_sorted) // 2]:.3f} ms median "
              f"({1000.0 / statistics.fmean(ft):.1f} fps)")
    else:
        print(f"{name}: {live} live frames of {len(mask)} sampled")
    return rows, mask, ft


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    a = summarise("AO on ", sys.argv[1])
    b = summarise("AO off", sys.argv[2])
    if a is None or b is None:
        print("missing GPUTRACE_FRAME.xls in one of the runs -- did the trace export?")
        return 2
    on_rows, on_mask, on_ft = a
    off_rows, off_mask, off_ft = b

    print()
    print(f"{'metric (live frames only)':34s} {'AO off':>10s} {'AO on':>10s} {'delta':>10s}")
    print("-" * 68)
    for label, suffix, unit in KEYS:
        x = mean_live(find(off_rows, suffix), off_mask)
        y = mean_live(find(on_rows, suffix), on_mask)
        if x is None or y is None:
            print(f"{label:34s} {'(absent)':>10s}")
            continue
        print(f"{label:34s} {x:10.3f} {y:10.3f} {y - x:+10.3f}{unit}")

    # The scene must be the same for the comparison to mean anything. It is not automatically: the
    # fixture waits a fixed number of ticks, but chunk building is asynchronous, so how much of the
    # world is built when the trace opens varies between runs. Compare scene.png from each output
    # directory before trusting a difference this flags.
    dx = mean_live(find(off_rows, DRAWS), off_mask)
    dy = mean_live(find(on_rows, DRAWS), on_mask)
    if dx and dy:
        drift = abs(dy - dx) / max(dx, 1.0)
        verdict = "  (fine)" if drift < 0.05 else "  -- NOT the same scene, compare scene.png"
        print(f"\nsame scene? draws per frame differ by {drift * 100:.1f}%{verdict}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
