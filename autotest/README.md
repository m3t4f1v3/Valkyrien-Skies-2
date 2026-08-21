# In-game autotests

Script-driven headless runs of the dev client, driven by `VsAutoTestHarness`. Each `.txt` is a list
of instructions; `run.sh` launches the client under gamescope's headless backend (real GPU, so
compute shaders work — Xvfb's llvmpipe does not), plays the script, collects screenshots into
`forge/run/screenshots/`, and takes the process down as soon as the harness writes its result.

```
autotest/run.sh autotest/<script>.txt
```

Useful environment variables (see the comments in `run.sh` for why each exists):

| variable | effect |
|---|---|
| `AUTOTEST_VS_CONFIG` | space-separated `key=value` pairs forced into the VS client toml, and re-checked after the run |
| `AUTOTEST_GRADLE_ARGS` | e.g. `-Psodium_runtime=legacy` (Embeddium, what the user runs) or `backport` (Sodium 0.9) |
| `AUTOTEST_UNCAP_FPS` | required for any perf number — with vsync every configuration reads exactly 60 |
| `AUTOTEST_RES_W` / `_H` | fragment cost scales with pixels; the default 854x480 is about a ninth of 1080p |
| `AUTOTEST_TIMEOUT` | seconds before the run is abandoned (default 900) |

## Ship ambient occlusion

The seam AO's situations come from the Python verification suite in `claude-scratchpad/`
(`verify4.py`, `verify5.py`, `scenes.py`, `probe_support.py`). These fixtures reproduce those same
situations in the running game, and `autotest/score_ao.py` checks the same invariants.

```
autotest/ao_all.sh [legacy|backport]      # run all five, score them, and compare against seam5
```

They run with `debugFloodPaint=5` (`VS_DEBUG_SEAM_AO`), which replaces the shaded colour with the AO
loss in the red channel — so the scorer reads the loss *field* directly rather than trying to detect
a few levels of darkening in a shaded frame.

| fixture | situation | from |
|---|---|---|
| `ao_parity.txt` | `row3` / `L` / `2x2` built as one ship vs split across ships | `verify5:scene_aligned` |
| `ao_rigid.txt` | sub-block translation 0 / 0.25 / 0.5; yaw 0 / 22.5 / 45 at contact | `verify4:scene_translate`, `:scene_rotate` |
| `ao_merge.txt` | touching pair at lateral offset 0 / 0.25 / 0.5; straight \| gap \| yawed | `verify5:scene_pair`, `:scene_rot` |
| `ao_contact.txt` | flush → hover sweep → past support radius; sunk | seam3 band rule, `probe_support.py` |
| `ao_misalign.txt` | neighbour a few degrees off / a fraction of a block off the lattice | `seam5.pair_claim` (partial-claim regime) |
| `ao_shipship.txt` | cross-ship AO applies, self-occlusion does not | the ship path's two halves |
| `ao_merge_toggle.txt` | `shipAmbientOcclusionMerging` on vs off | the merge's defining property |
| `ao_ab.txt` | plain on/off A/B of `shipAmbientOcclusion` in one client | — |

What each group is actually asserting:

- **parity** — splitting an aligned layout across separate ships must give the same field as building
  it as one ship. This is the property the cross-ship responsibility weighting exists to produce.
- **rigidity** — the field must translate and rotate *rigidly* with the occluder ship. The rejected
  receiver-anchored variant sampled on the world grid, so an off-grid ship made the shadow drag.
  Scored as `verify4` scored it: the field total across the sweep, where the shipped algorithm holds
  to <=0.02 and the drifting one jumped 0.16-0.20. Not by registering two frames and diffing them
  (re-registering needs a resample, and a resample blurs by about the amount of drag being looked
  for), and not by loss histogram either — that was tried and measures binning noise, since a few
  hundred lit pixels over 64 bins move across bin edges for any sub-pixel shift.
- **merging** — two touching separate ships must look like the same two cells as one ship, and the
  one-block-gap merge bridge must survive the partner rotating.
- **ship-to-ship** — one fixture, two opposite assertions, which is what makes each trustworthy: a
  column resting on a *separate* ship's plate must shade it (242,863 px), and the identical shape
  built as *one* ship must not (0 px), because the mesher already baked that occlusion into
  `v_Color.a`. The cross case is what stops the self case passing vacuously — a dead render path or a
  compiled-out shader would zero both, and this pair catches that.
- **merge toggle** — `shipAmbientOcclusionMerging=false` must actually change the field, not just the
  code path: with merging on, an aligned split matches the single ship exactly (0 px); with it off the
  ships stamp independently and their shadows double up (56,408 px). A switch that silently did
  nothing would pass the first half and fail the second.
- **contact** — the band weighting through flush → hover → past support. A flush ship casts full AO
  and it fades linearly to exactly zero one block up.
- **misalignment vs the reference** (`autotest/ref_merge.py`) — the checks above are all
  self-consistency, and none of them can tell a correct merge from one that degrades *wrongly* once
  alignment is lost. This one integrates the loss over the floor for each configuration and compares
  the curve against `seam5` computing the same integral, normalised so only the shape is compared and
  the pixels-per-block scale drops out. It needs `claude-scratchpad/` present (it is gitignored); if
  the reference is missing it exits 2 rather than passing.

### Looking at the paint

The painted frames are close to black on screen — the loss beside a one-block ship peaks around 0.2
of a 0..1 channel — so they are readable as measurements but not as pictures.

```
python3 autotest/paint_panels.py     # -> forge/run/screenshots/panels/*.png
```

crops each group of frames to one shared window and maps the loss through a colormap on a shared
scale. No smoothing and no per-frame normalisation: the numbers under the colours are the same ones
`score_ao.py` and `ref_merge.py` scored. The dark rectangle in the middle of each cell is the ship's
own footprint hiding the floor, not an absence of AO.

### Scene hooks

The AO situations are comparisons between arrangements of several small ships, which the older
single-`lightShip` hooks cannot express:

- `run ao_ship <slot> <ox> <oy> <oz> <cells>` — build a ship from an explicit cell list, e.g.
  `0:0:0,1:0:0,1:0:1` for an L. Stone throughout; these fixtures measure AO, not light.
- `run ao_pose <slot> <dx> <dy> <dz> [yawDeg]` — offset from where the ship spawned, and yaw it.
  Sub-block offsets are the point.
- `paint <0..5>` — switch the debug paint at runtime, so a fixture can take a masking shot (`paint 0`,
  ordinary render) and a measuring shot (`paint 5`, the loss field) of the same frame. `ref_merge.py`
  uses the mask to keep the ships' own pixels out of a floor integral.
- `run ao_clear` — take every scene ship out of frame. The core API has no ship deletion, so this
  teleports them away; population filters on viewport visibility, so a moved-away ship contributes
  no occluders and cannot widen the seam bounds.

### Measurement discipline

Things that have gone wrong here before and cost whole runs:

- **Occluders must be ADJACENT to the surface they shade.** The seam pass stamps only the unit slab
  in front of the face, weighted `max(0, 1 - |voxel centre - band centre|)`, so a gap of one block
  gives weight exactly 0.00. Three fixtures in a row were written with a one-block gap and read as
  "the AO does nothing" when the shader was behaving correctly. Put the occluder *touching* the
  receiving surface.
- **Put the ship *on* the ground.** Vanilla AO stamps only the unit slab directly in front of the
  face, so a ship hovering even one block up casts exactly zero and an on/off comparison measures
  nothing. `ao_contact`'s hover profile is the check that placement is right — if the ship were
  buried, the profile would not be monotone.
- Hide the HUD (`hud off`) and use spectator for any elevated camera; creative just falls.
- Never threshold a colour channel loosely: grey stone reads as "green", the midnight sky reads as
  "blue".
- Check a fixture actually shows what it claims before trusting a number out of it.
