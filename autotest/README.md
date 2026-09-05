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
| `ao_lod.txt` | `shipAmbientOcclusionMergeDistance` swept from no fade to fully faded | `seam_lod.py` |
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
- **merge distance LOD** — merging is faded out with distance, because each merged partner costs the
  fragment shader another host lattice while the seam it buys is a block or two wide. Two assertions.
  First, the far end of the fade must *be* the unmerged path, not resemble it: zeroing a claim makes
  responsibility `r = 1/(1 + Σ claims)` equal 1 and every off-diagonal weight zero, which is exactly
  what `shipAmbientOcclusionMerging=false` does, so the two frames must match to the pixel — they do
  (0.00000). Second, the walk between must not jump, which is what separates a genuine fade from a
  partner being dropped whole; sampled in half-blocks the profile is
  `0.03 → 0.12 → 0.25 → 0.43 → 0.57 → 0.74 → 1.0` and never travels backwards.

  Two traps this fixture is built around. It sweeps the *configured* distance with the camera parked
  close rather than flying the camera out — by the time the LOD engages for real, a block covers
  about four pixels and there is no field left to measure. And it uses **three** ships: two aligned
  single-block ships render identically merged and unmerged, exactly, because the corner rule is
  linear in occupancy and with one voxel each neither the per-cell clamp nor the diagonal-pair bonus
  ever engages to break that linearity. The first version of this fixture used two and produced ten
  byte-identical screenshots; the vacuity guard in `merge_lod()` is what caught it.
- **misalignment vs the reference** (`autotest/ref_merge.py`) — the checks above are all
  self-consistency, and none of them can tell a correct merge from one that degrades *wrongly* once
  alignment is lost. This one integrates the loss over the floor for each configuration and compares
  the curve against `seam5` computing the same integral, normalised so only the shape is compared and
  the pixels-per-block scale drops out. It needs `claude-scratchpad/` present (it is gitignored); if
  the reference is missing it exits 2 rather than passing.

### What the AO actually costs

Two scripts, and they disagree about what is hot because they are different scenes — which is the
point.

`perf_aoprof.sh` runs `ao_cover.txt` (**one** ship, on purpose) with the shader returning early at
each stage, so a stage's cost is the difference between consecutive rows:

```
ao off   0.92 ms | stage 0  1.78 (+0.86, floor) | stage 1  1.80 | stage 2  1.69
stage 3  2.71 ms (+1.02, pass 1 host selection) | full  3.48 ms (+0.77, pass 2 voxel stamping)
```

A third of the AO's cost is the **floor** — code and uniforms present, nothing executing. Stage 2
reading faster than stage 0 puts the noise around 0.1 ms, so these are one significant figure.

`perf_aomerge.sh` runs `ao_fleet.txt` (49 touching ships, every interior hull holding four claims):

```
merge off 11.59 ms | no LOD 28.57 ms | LOD 192 29.67 ms | LOD faded 11.63 ms
```

Merging is **17 ms of a 28.6 ms frame**, because the host count multiplies pass 1 and pass 2
together and a long sub-run list is walked once per host. The distance LOD gives essentially all of
it back. `LOD 192` is the control — the shipped default with the camera 15 blocks out, where nothing
should fade — and 29.67 against 28.57 is inside this rig's ~3% drift.

The lesson for anyone optimising this: measure on `ao_fleet` as well as `ao_cover`. Single-ship
numbers describe the merge's cheapest case and will point at the wrong thing.

### The cost is occupancy, not arithmetic

At 3840x2160 on `ao_fleet` the stage table originally read:

```
ao off  2.69 ms | stage 0  34.84 (+32.15) | stage 3  111.11 (+78) | full  100.00
```

Two things in that are worth internalising. **Stage 0 executes nothing** — it returns before any AO
code runs, under a condition the compiler cannot fold — yet it cost 32 ms of a 100 ms frame against
2.7 ms with the AO compiled out. And pass 1, which walks a handful of sub-runs and adds at most four
hosts, cost 78 ms. Neither number is explicable as work done; both are the shader being unable to
hide memory latency because too few warps are resident.

The cause was two `mat4`s caching each host's quaternion and anchor — 32 registers held live across
the whole function for values pass 2 reads once per host. Re-fetching those two texels per host
instead (8 fetches per fragment, outside every loop) took the frame from **100.00 ms to ~40 ms**,
with pass 1 collapsing from +78 ms to +1.4 ms. The suite's numbers were byte-identical before and
after, which is what a pure register change should look like.

Two follow-ups from that, both measured:

- **Occupancy is quantised, so nibbling does nothing.** Shrinking `hostClaim` from a `mat4` to a
  per-host `vec4` — a further 12 registers, by the same reasoning — measured *worse* (44.05 ms
  against 39.53) because the earlier cut had already crossed the threshold that mattered, and the
  per-host refetches were then paid for nothing. Attack register pressure in steps big enough to
  cross a threshold, or not at all.
- **Re-measuring an identical build gave 42.19 against 39.53**, a 6.7% spread. At 25-30 fps the
  `getFps()` sample is small; treat differences under ~10% here as noise and quote wins accordingly.

Porting the same change to the **ship** shaders took the fleet from ~40 ms to **21.88 ms** — 100.00
to 21.88 overall, 4.6x. The ship shaders need one extra step: host slot 0 there is not a ship but the
WORLD lattice, which has no row in the ship directory (row 0 holds the ship *count*), so the pose is
recovered through `vs_seamHostPose` with a branch rather than a plain fetch. Verified pixel-identical
on `ao_shipship.txt`: 0 px differ, max channel delta 0.

### Precomputing the occupancy field

`shipAmbientOcclusionPrecompute` (off by default) makes the WORLD shader read the occupancy each
fragment would otherwise accumulate, instead of stamping every nearby ship voxel into it.

The identity it rests on: every tap pass 2 evaluates sits at a lattice cell *centre*, so the
occupancy depends only on which cell a fragment is in, never on the fragment. `seam_precompute.py`
checks that to 0.0 max error for the tent, and `ao_precomp.txt` checks it in the game — one client,
one scene, only the shader variant differing, compared pixel by pixel:

```
aligned             loop=15476.2  field=15476.2  max|d|=0.0000  px_differ=0
off-lattice+17deg   loop=17428.2  field=17428.2  max|d|=0.0000  px_differ=0
```

On `ao_fleet200` at 4K: **25.86 -> 18.18 ms**, with the field costing 0.07 ms/frame of extra CPU
(0.647 against 0.577). The floor does not move, because the floor is the *ship* program and this is
world-only.

**The ship path too, by subtraction.** A ship skips its own material (`owner == selfShipIndex`),
which is a property of the DRAW, not of the cell, so no single field per host can serve every draw.
But occupancy is LINEAR in its sources -- the clamp only happens later, in the fragment -- so the
fragment can read the host's aggregate and subtract the one source it must not see. Hence `G(host)`
plus `F(source -> host)` for the six targets a source can reach (itself, its four claim partners,
the world lattice), and `occ = G(t) - F(self -> t)`.

Its weights are not the world path's, either: a ship shares material with the world lattice as well
as with ship partners, so `r` is renormalised to `r / (1 + r * claimWorld)` and the world lattice is
a host in its own right. Same voxels, different numbers, so it is a second field set rather than a
second reader of the first. `vs_seamWorldAlign` therefore exists twice, once in GLSL and once in
Java, and they have to agree.

Measured back to back, three interleaved pairs on `ao_fleet200` at 4K, world precompute on in all
six (`-Pvs_precompship` switches only the ship half):

```
loop  ship path:  17.34, 17.34, 17.14 ms
field ship path:  15.87, 15.62, 15.87 ms
```

1.48 ms, and the two groups do not overlap. An earlier cross-sitting comparison of the same two
configurations read 18.29 against 18.18 and looked like nothing -- the machine was simply slower that
hour. This is why the rule is back to back or not at all.

**The splat runs on the CPU**, not in a compute shader. It is voxels x targets x 8 cells -- at most
1024 x 6 x 8 -- which is nothing next to `buildSeamData`'s O(ships²) claim pass, and it keeps this
first cut free of dispatches, SSBO aliasing and barriers. Both field sets together cost 0.79 ms/frame
at 200 ships against 0.577 for none. Moving it to compute is an optimisation, not a correctness
question.

Three traps, all of which produced a silently wrong or dead path before they were found:

- The precompute variant *adds* `u_VsSeamOcc` / `u_VsSeamOccDesc` and *drops* `u_VsShipOccluders`
  (nothing reads voxels per fragment any more). `WorldThing` and `ShipThing` bind by name in their
  constructors and throw on either mismatch.
- `u_VsShipOccluderCount` survives that drop, because the early-out still tests it. Its setter had to
  stop being guarded as a pair with the sampler -- otherwise the count stays zero and the AO silently
  never runs at all.
- **`needsListRebind()` is once per FRAME, not once per program.** Both the ship and world draw
  branches bind under it, so whichever draws first is the only one that binds anything. That is
  harmless for the buffers both branches bind identically, and exactly wrong for one only a single
  branch binds: the ship field set went to units nothing had bound, the sampler read zero, and the
  ship AO vanished while the world's kept working. `SodiumCompat.bindSeamOccFields()` now binds both
  sets from both branches. The symptom was the whole ship AO being *absent*, not subtly wrong, which
  is what `-Pvs_seamsub=0` (show the aggregate without subtracting) was added to tell apart from a
  subtraction that cancels.

### Bisecting the floor: `perf_aocut.sh`

`perf_aoprof.sh` cannot explain the floor, by construction — its stages keep register allocation
identical so they compare as work, and the floor is not work. `perf_aocut.sh` does the opposite:
every row runs `-Pvs_aoprof=4` so nothing executes, and `-Pvs_aocut=N` varies how much code the
compiler sees. On the fleet at 4K:

```
cut 0  12.94 ms  everything compiled in    | cut 2 + ship body gone   2.29 ms
cut 1  11.95 ms  minus world voxel stamp   | cut 3 + ship body gone   2.18 ms
cut 2  12.35 ms  minus all world pass 2    | ao off                   2.24 ms
```

Stripping the world shader's code changes nothing. Stripping the **ship** shader's AO body collapses
the floor to "ao off" exactly. So the floor is entirely the ship program's occupancy — in a scene
where ships are about 6% of the pixels — and the world program contributes essentially none of it
once its registers were fixed. That is where the next optimisation belongs.

### What the ship program's floor was made of

Bisecting inside the ship shader with `-Pvs_aocutship` (same idea, separate knob), on the fleet at 4K
with nothing executing:

```
ship cut 0  12.94 ms  all in     | cut 2  3.75 ms  minus all pass 2
ship cut 1   6.19 ms  minus stamp| cut 3  2.48 ms  minus the whole body      (ao off 2.24)
```

Two hypotheses tested and rejected before the right one, both measured rather than reasoned away:

- **Dynamic vector indexing.** `c[a]`, `c[u]`, `c[v]` with a runtime axis, three times per voxel.
  Permuting the axes into the matrix once per host (`RinvP`) so the loop could use static `.x/.y/.z`
  measured 14.15 ms floor against 12.94, and 22.39 full against 21.88. Reverted.
- **Loop unrolling.** Dropping `VS_SEAM_MAX_HOSTS` from 4 to 1 takes the floor from 12.94 to 4.96 ms,
  which looked like the compiler unrolling the host loop four times. But bounding that loop by the
  runtime `hostCount` instead measured 13.57 — no gain. It is not the trip count.

What MAX_HOSTS=1 *also* shrinks is the **state width**: `hostClaim` from a `mat4` to one live
component, `hostShip` and `hostR` from four to one. Replacing the `mat4 hostClaim` with a per-host
`vec4` (caching only `claimWorld`, which is the expensive part, and re-reading two directory texels
per host) took the floor to **8.88 ms** and the frame to **18.52 ms**. Pixel-identical on
`ao_shipship.txt`.

The same change in the **world** shader measured *worse* (44.05 against 39.53). That is not a
contradiction: the world shader is already past the occupancy threshold where registers stop buying
anything, so it pays the extra fetches for nothing, while the ship shader is still below it. Whether
a register cut helps is a property of which side of the cliff the shader is on, and that has to be
measured per shader rather than assumed from another one.

**Where the rule stops.** Splitting `occ0`/`occ1` into one `mat3` walked twice -- 18 live registers
down to 9, the largest cut left -- measured 14.85 ms floor against 8.88, and 24.00 full against
18.52. Register cuts pay here when their cost is a few extra fetches *outside* the voxel loop, and
lose when they multiply the loop itself. Doubling the loop's fetches and stamp arithmetic swamps
anything nine registers buy back.

For scale, the ship fragments are **29.7% of the frame** in this scene (2.46 Mpx of 8.29), measured
by differencing a `debugFloodPaint=3` frame against an ordinary one rather than estimated from block
sizes -- an estimate from geometry gave 6%, which is off by nearly 5x and would have pointed the next
optimisation at the wrong shader. `ao_fleet.txt` takes both shots after its fps samples.

Two traps in writing cut levels, both of which crashed a run before working:

- **Uniforms get stripped and `bindUniform` throws.** The null guards in `WorldThing`/`ShipThing` are
  on the *setters*; the **constructors** bind unconditionally. Every uniform behind the AO feature
  must stay statically referenced, including non-obvious ones: `u_VsCurrentShipIndex` is passed as an
  argument at the call site, so removing the body makes the parameter unused, the argument dead, and
  the uniform vanish.
- **Use `textureSize`, not `texelFetch`, in the keepalive.** Both keep a sampler active, but four
  fetches hold ~16 registers live and can pin the shader at the same occupancy tier as the code just
  removed — making every cut level read the same for the wrong reason. `textureSize` returns an int
  and touches no memory. Put the keepalive somewhere no provably-taken `return` precedes it, too: at
  cut 3 the compiler proves `hostCount == 0`, takes the early return, and drops the keepalive with
  everything after it.

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

- **The first client launch after a rebuild runs ~30% slow.** Same build, same config, repeated:
  18.18, 13.95, 13.57 ms. Every row of an A/B that changes a `-P` property rebuilds, so every row is
  a cold run — which is fine when both sides are cold and catastrophic when they are not. A
  `VS_SEAM_MAX_HOSTS` change was measured at **27%** this way (17.33 against 12.71) and turned out to
  be **0%** with both sides warm (13.51/13.57/13.33 against 13.57/13.95/14.35). `perf_aoprof.sh` and
  `perf_aocut.sh` now launch twice per row and report the second; anything measured by hand needs the
  same treatment. Late in a long session the rig degrades further — a config that measured 13.39 ms
  produced a 37.50 ms run minutes later — so a single number is worth very little on its own.
- **The client config is shared mutable state between runs, unless you stop it.** `AUTOTEST_VS_CONFIG`
  writes only the keys it names; every other key keeps whatever the *previous* run left in the toml.
  This produced an impossible result — a 4K run with merging **on** measuring faster than the same
  scene with merging **off** — because the merging-on run inherited
  `shipAmbientOcclusionMergeDistance=12.0` from the last row of `perf_aomerge.sh` and so was fully
  faded, i.e. not merging at all. Nothing in the log said so. `run.sh` now applies a `VS_CFG_BASE`
  covering every render-affecting key before `AUTOTEST_VS_CONFIG`, and echoes all of them, so a run
  starts from a known state and its configuration is on the record.
- **Resolution is not what you asked for, it is what the log says.** `AUTOTEST_W/H` only reaches the
  client if fullscreen fits inside gamescope's nested display; otherwise Minecraft keeps 854x480
  silently. Every `fps` sample now carries the real framebuffer size (`fps SETTLED: 83 @ 1920x1080`)
  and `perf_frag.sh` takes its pixel count from that rather than from the requested size — it is a
  slope against pixels, so a wrong pixel count there draws a plausible straight line through the
  wrong points.

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

## GPU profiling, and what it is worth

`ngfx_capture.sh` records the client's real launch (argv, environment, cwd from `/proc`), and
`ngfx_sdk.sh` replays that under Nsight with the `ao_trace` fixture, which starts and stops the trace
itself through the shim in `ngfx-shim/`. `ngfx_compare.py` reads the export.

Two things about the numbers, both learned the hard way:

**Mask to live frames.** The trace keeps sampling after the fixture stops drawing, and about half the
columns are dead. Dead frames write `NaN` into counter rows but a literal `0` into `.sum` rows, so
the counters looked right while draws per frame silently averaged in hundreds of zeros -- 67.2
reported against a true 128.0. `ngfx_compare.py` now takes one live-frame mask from the draw count
and applies it to everything.

**A/B back to back or not at all.** Two traces of the *same build* differ by 0.3%. The same build
measured 70 minutes apart differed by 3%. A 5.8% "win" from widening a texelFetch was chased,
mirrored into four shaders and written up before a proper A/B showed the change was worth 0.06% --
the whole effect had been rig drift between two traces taken 5 minutes apart. Never compare against a
stored number from an earlier session; re-measure the baseline in the same sitting as the candidate.

**Prove the scenes match.** `run scene` logs rendered chunk counts and `hasRenderedAllChunks`, and
`ngfx_compare.py` flags a draws-per-frame gap over 5%. AO-on and AO-off traces disagreed by 43% on
draws with compute dispatches matching to three decimals -- same ship, different amount of world,
because chunk building is asynchronous and the faster configuration reaches the trace window with
less terrain uploaded. A fixed tick wait does not settle a scene; check that it did.
