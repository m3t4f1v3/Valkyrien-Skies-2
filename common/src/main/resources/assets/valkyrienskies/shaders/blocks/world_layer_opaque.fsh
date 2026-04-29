#version 330 core

#import <sodium:include/fog.glsl>

// VS-modified copy of sodium's stock chunk FSH. The only effect added on top of
// vanilla world rendering is "ship lights brighten the world": each ship-emitter
// is fed in as an entry in u_VsShipEmitters (vec4 = worldPos + lightLevel) and
// we max-merge the distance-attenuated contribution into the world's lightmap
// UV. No occlusion (sky shadowing or wall attenuation) — those approximations
// were causing visible artifacts so they're removed.
//
// Sub-block precision: emitter world coords are stored as floats, so as a ship
// moves smoothly the lit area on the ground tracks it continuously. The old
// BFS-over-block-grid approach quantized emitter positions to floor() and made
// the lit region jump per-block.

in vec4 v_Color;            // RGB = chunk-mesher tinted color, .a = pure vanilla AO (no shade)
in vec2 v_TexCoord;
in vec2 v_LightCoord;       // _vert_tex_light_coord (vanilla world lightmap UV)
in vec3 v_CameraRelWorldPos;// world pos relative to camera; + u_VsRenderOrigin = absolute
// Decoded face data from the VSH (see VsVertexFlagPacker). Flat
// interpolated, since face slot is shared by all 4 vertices of a quad.
flat in vec3 v_WorldNormal; // exact world-space face normal, axis-aligned ±X/±Y/±Z
flat in int v_IsShaded;     // 0 for fluids and emissive/fullbright quads
in float v_FragDistance;
in float v_MaterialMipBias;
in float v_MaterialAlphaCutoff;

uniform sampler2D u_BlockTex;
uniform sampler2D u_LightTex;
uniform vec4 u_FogColor;
uniform float u_FogStart;
uniform float u_FogEnd;

uniform ivec3 u_VsRenderOrigin;
// Buffer texture (RGBA32F) — each texel is one ship emitter's
//   (worldX, worldY, worldZ, lightLevel).
uniform samplerBuffer u_VsShipEmitters;
uniform int u_VsShipEmitterCount;

// Per-frame list of solid ship voxel CENTERS in world space (post-ship-
// transform). Each texel: vec4(worldX, worldY, worldZ, neighborMask).
// neighborMask is a 6-bit ship-frame neighbor map packed as a small float
// (0..63) — bit 0/1 = ∓X, bit 2/3 = ∓Y, bit 4/5 = ∓Z. Used by the AO
// loop's corner-correction term to recognize "this voxel is part of a
// row" and fill the bilinear corner cell that pure Manhattan misses. See
// VsShipOccluderList.NMASK_* for the canonical bit layout.
uniform samplerBuffer u_VsShipOccluders;
uniform int u_VsShipOccluderCount;

out vec4 fragColor;

const float WS_UV_MIN = 1.0 / 32.0;
const float WS_UV_MAX = 31.0 / 32.0;

// Loop bound for the per-fragment ship-occluder scan. Should match
// VsShipOccluderList.MAX_OCCLUDERS — 1024 fits but is excessive per
// fragment; 128 covers typical scenes (a single mid-size ship's solid
// voxels), excess entries beyond this cap are silently ignored.
const int VS_OCCLUDER_LOOP_CAP = 128;

// Per-fragment ship AO via voxel-position iteration.
//
// For each ship voxel center (in world coords, stored as a continuous
// float — so the position smoothly tracks the ship's transform including
// rotation), compute its contribution to this fragment's AO based on:
//   • d_n (component along the face normal): how far the voxel is in
//     the outward direction. Voxels in the half-space behind the face
//     (d_n <= 0) are skipped.
//   • d_p (length of the in-plane component): how far the voxel is
//     laterally from the fragment's projected position. Voxels too far
//     to one side don't shadow this fragment.
// Smooth falloff in both directions; sum contributions, clamp to 1.
//
// This replaces the cell-storage-based AO that operated on grid-aligned
// world cells — that approach quantized voxel positions to cells and
// the AO pattern could only morph between cell-aligned configs. With
// the voxel list, every voxel's exact transformed position contributes,
// so the AO shape rotates and translates continuously with the ship.
float ws_shipAo(vec3 worldPosWorld, vec3 nf) {
    int n = min(u_VsShipOccluderCount, VS_OCCLUDER_LOOP_CAP);

    // Build face-local 2D axes (u, v) from the face normal. Axis-aligned
    // faces only — fine for cube blocks.
    vec3 absNf = abs(nf);
    vec3 uAxis = absNf.x > 0.5 ? vec3(0, 1, 0) : vec3(1, 0, 0);
    vec3 vAxis = absNf.z > 0.5 ? vec3(0, 1, 0) : vec3(0, 0, 1);

    float fragU = dot(worldPosWorld, uAxis);
    float fragV = dot(worldPosWorld, vAxis);

    // Map face-plane U/V back to a ship-frame axis index (0=X, 1=Y, 2=Z)
    // so we can index into each voxel's neighbor mask. uAxis/vAxis above
    // pick from {X, Y, Z}\{normal}; this mirrors that choice. Works for
    // axis-aligned ships (the common case); rotated ships' ship-frame
    // axes don't line up with world U/V, so the mask bits we read here
    // would be wrong — for those, the corner correction below silently
    // either over- or under-fires, but the dominant Manhattan term is
    // still correct.
    int uIdx = absNf.x > 0.5 ? 1 : 0;
    int vIdx = absNf.z > 0.5 ? 1 : 2;

    float occlusion = 0.0;

    for (int i = 0; i < n; i++) {
        vec4 voxel = texelFetch(u_VsShipOccluders, i);
        vec3 d = voxel.xyz - worldPosWorld;
        float d_n = dot(d, nf);
        if (d_n <= 0.0 || d_n >= 1.5) continue;
        float fn = 1.0 - smoothstep(0.5, 1.5, d_n);

        vec3 voxelProj = voxel.xyz - d_n * nf;
        float du = dot(voxelProj, uAxis) - fragU;
        float dv = dot(voxelProj, vAxis) - fragV;

        // Manhattan-distance SDF of the voxel's face-plane box. Reproduces
        // vanilla MC's exact AO shape:
        //   - inside the voxel's 1×1 footprint: full 1/3 contribution
        //     (vanilla "occluder reduces face-vertex AO by 1/3").
        //   - axially adjacent cells: linear 1/3 → 0 ramp over 1 cell.
        //   - diagonally adjacent cells: triangular falloff cut by the
        //     45° Manhattan iso-line (loss > 0 only where |Δu|+|Δv|<1
        //     beyond the box corner) — that's exactly vanilla's clean-
        //     triangle corner for an isolated occluder.
        //
        // Constants from the SDF reference:
        //   - halfSize 0.5: voxel is a unit cell on the face plane.
        //   - threshold 1.0 (= 2 × halfSize): tent reaches 0 at distance
        //     1 cell from the box, matching vanilla's 1-cell AO reach.
        //
        // Single-sample per voxel (no 4-corner sampling needed): the SDF
        // already encodes the full 1×1 footprint. Voxel position is
        // continuous, so the shape translates AND rotates with voxels —
        // no per-cell decomposition, no world-grid anchoring.
        float dU = abs(du) - 0.5;
        float dV = abs(dv) - 0.5;
        float manhattan = max(0.0, 1.0 - max(dU, 0.0) - max(dV, 0.0));
        occlusion += (1.0 / 3.0) * fn * manhattan;

        // Neighbor-aware corner correction. The Manhattan tent above
        // matches vanilla AO for an ISOLATED occluder, but rows of
        // adjacent voxels reveal a flaw: each voxel contributes a clean
        // octagon, and adjacent octagons meet along a 45° iso-line
        // instead of merging into a uniform strip. Specifically, for a
        // row casting AO on the face beside it, the row's corner blocks
        // contribute 0 at face centers (their Manhattan tents die at
        // sdfMan=1) — vanilla's bilinear interp puts ~1/12 there, so
        // every interior face has a triangular slice of bright space
        // carved out where the corner cell should be uniformly dark.
        //
        // Fix: when the voxel has a ship-frame neighbor in the
        // perpendicular direction toward the fragment's quadrant
        // (i.e., the neighbor that "completes the L" with this voxel
        // pointing into the fragment's face), add the bilinear-vs-
        // Manhattan extra term in the corner cell. Gating on the
        // neighbor mask keeps this from over-firing for isolated
        // diagonal blocks (whose Manhattan answer is already what the
        // user wants — a clean octagonal shadow).
        if (dU > 0.0 && dV > 0.0) {
            uint mask = uint(voxel.w + 0.5);
            uint towardU = (du < 0.0)
                ? ((mask >> uint(uIdx * 2 + 1)) & 1u)  // fragment +U → check +U neighbor
                : ((mask >> uint(uIdx * 2))     & 1u); // fragment -U → check -U neighbor
            uint towardV = (dv < 0.0)
                ? ((mask >> uint(vIdx * 2 + 1)) & 1u)
                : ((mask >> uint(vIdx * 2))     & 1u);
            if ((towardU | towardV) != 0u) {
                float fU = clamp(1.0 - dU, 0.0, 1.0);
                float fV = clamp(1.0 - dV, 0.0, 1.0);
                float bilinear = fU * fV;
                occlusion += (1.0 / 3.0) * fn * max(0.0, bilinear - manhattan);
            }
        }
    }

    occlusion = clamp(occlusion, 0.0, 1.0);
    return mix(0.2, 1.0, 1.0 - occlusion);
}
// Loop bound for the per-fragment emitter scan. Should match
// VsShipEmitterList.MAX_EMITTERS — 1024 entries fit but is excessive per
// fragment; 128 is plenty for typical scenes (ships rarely have that many
// torches in the inner radius). Excess emitters in the buffer beyond this
// cap are silently ignored at fragment time.
const int VS_EMITTER_LOOP_CAP = 128;

// Distance-attenuated max ship-emitter contribution at this fragment's world
// position. Vanilla-style 1-per-block falloff. No occlusion: light passes
// through blocks (we'd need a per-fragment ray-march to do better, which is
// too expensive on weaker hardware).
float vs_shipEmitterLight(vec3 worldPos) {
    float maxLight = 0.0;
    int n = min(u_VsShipEmitterCount, VS_EMITTER_LOOP_CAP);
    for (int i = 0; i < n; i++) {
        vec4 e = texelFetch(u_VsShipEmitters, i);
        // Manhattan distance roughly matches vanilla's BFS step cost (1 per
        // axis-aligned step). Euclidean would give a rounder falloff but
        // doesn't match how vanilla light propagates. Pick whichever you
        // prefer — Manhattan keeps "torch reach = 14 blocks" visually exact.
        float dx = abs(worldPos.x - e.x);
        float dy = abs(worldPos.y - e.y);
        float dz = abs(worldPos.z - e.z);
        float dist = dx + dy + dz;
        float light = max(0.0, e.w - dist);
        maxLight = max(maxLight, light);
    }
    return maxLight;
}

void main() {
    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) {
        discard;
    }
#endif

    vec2 lightCoord = v_LightCoord;
    vec3 worldPos = v_CameraRelWorldPos + vec3(u_VsRenderOrigin);

    // Ship emitters: max-merge their distance-attenuated contribution into the
    // block-light UV. Sub-block-precise because the emitter coords are floats.
    float shipLight = vs_shipEmitterLight(worldPos);
    if (shipLight > 0.0) {
        // MC packs block-light at U = (lightLevel + 0.5) / 16.
        float shipLightUv = (shipLight + 0.5) / 16.0;
        lightCoord.x = max(lightCoord.x, shipLightUv);
    }

    vec4 lightSample = texture(u_LightTex, clamp(lightCoord, vec2(WS_UV_MIN), vec2(WS_UV_MAX)));

    // Tint × lightmap. AO and shade are applied below as a single combined
    // multiplier so vanilla world AO and ship-to-world AO stack the way
    // vanilla's per-vertex averaging would, instead of multiplying
    // independently.
    diffuseColor.rgb *= v_Color.rgb * lightSample.rgb;

    // Combined AO + shade. v_Color.a is PURE vanilla AO (no shade); ship
    // AO comes from per-fragment ws_shipAo() with dynamic triangle split.
    // Combine the two AO sources additively — vanilla's per-vertex
    // averaging compounds overlap that way (two op cells at a corner →
    // loss 0.2 + 0.2 = 0.4, not 0.8 × 0.8 = 0.64). Floor 0.2 matches
    // sodium's deepest AO value for opaque blocks. Face shade (UP=1,
    // DOWN=0.5, N/S=0.8, E/W=0.6) is applied after, mirroring sodium's
    // applySidedBrightness step. Slot 6/7 (unshaded/fullbright) skip
    // both AO and shade via v_IsShaded.
    float ao = v_Color.a;
    float shipAo = (v_IsShaded == 1)
            ? ws_shipAo(v_CameraRelWorldPos + vec3(u_VsRenderOrigin), v_WorldNormal)
            : 1.0;
    if (v_IsShaded == 1) {
        float combined = max(0.2, ao - (1.0 - shipAo));
        float shade = 1.0;
        if (v_WorldNormal.y < -0.5)       shade = 0.5; // DOWN
        else if (abs(v_WorldNormal.y) > 0.5) shade = 1.0; // UP
        else if (abs(v_WorldNormal.x) > 0.5) shade = 0.6; // EAST / WEST
        else                                  shade = 0.8; // NORTH / SOUTH
        diffuseColor.rgb *= combined * shade;
    } else {
        diffuseColor.rgb *= ao;
    }

    // DEBUG: visualize ship AO loss vs vanilla AO loss as separate channels
    // so a real solid block (vanilla AO baked into v_Color.a) and a ship
    // voxel (per-fragment ws_shipAo()) can be placed side-by-side and
    // compared.
    //
    // RED   — ship AO loss (1 - shipAo, scaled).
    // GREEN — vanilla world AO loss (1 - v_Color.a, scaled).
    //
    // If the two formulas produce the same darkening, equivalent setups
    // produce visually identical shapes. Where they disagree, you'll see
    // pure red (ship darker) or pure green (vanilla darker).
    //
    // Tiny +lightSample.rgb keeps u_LightTex alive against GLSL dead-code
    // elimination — without it the compiler strips the texture sample
    // and sodium's bindUniform throws NPE at link time.
    {
        // DEBUG: red = ship AO loss; green = vanilla AO loss.
        float dbgVanillaLoss = clamp((1.0 - v_Color.a) * 1.25, 0.0, 1.0);
        float dbgShipLoss = clamp((1.0 - shipAo) * 1.25, 0.0, 1.0);
        diffuseColor.rgb = vec3(dbgShipLoss, dbgVanillaLoss, 0.0)
                + lightSample.rgb * 1e-3;
    }

    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
}
