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
// Buffer texture (RGBA32F) — TWO texels per ship emitter:
//   texel 2i:   vec4(worldX, worldY, worldZ, lightLevel)
//   texel 2i+1: vec4(qx, qy, qz, qw)   ship-to-world rotation quaternion
// The quaternion's inverse rotates the world-frame fragment-to-emitter
// offset into the emitter's owning ship local frame, so the Manhattan
// light bubble visibly rotates with the hull.
uniform samplerBuffer u_VsShipEmitters;
uniform int u_VsShipEmitterCount;

// Per-frame list of solid ship voxel CENTERS in world space, paired with the
// voxel's owning-ship rotation quaternion (see VsShipOccluderList). Consumed
// PER-FRAGMENT by ws_seamAoFrag below for ship-to-world AO seam matching.
uniform samplerBuffer u_VsShipOccluders;
uniform int u_VsShipOccluderCount;

// External-world fluid culling for ship air pockets. These uniforms are populated by
// ShipWaterPocketExternalWaterCull when this shader is used for Sodium's translucent fluid pass.
uniform float ValkyrienAir_CullEnabled;
uniform float ValkyrienAir_IsShipPass;
uniform vec3 ValkyrienAir_CameraWorldPos;
uniform sampler2D ValkyrienAir_FluidMask;

uniform vec4 ValkyrienAir_ShipAabbMin0;
uniform vec4 ValkyrienAir_ShipAabbMax0;
uniform vec4 ValkyrienAir_GridSize0;
uniform mat4 ValkyrienAir_WorldToShip0;
uniform sampler2D ValkyrienAir_Mask0;

uniform vec4 ValkyrienAir_ShipAabbMin1;
uniform vec4 ValkyrienAir_ShipAabbMax1;
uniform vec4 ValkyrienAir_GridSize1;
uniform mat4 ValkyrienAir_WorldToShip1;
uniform sampler2D ValkyrienAir_Mask1;

uniform vec4 ValkyrienAir_ShipAabbMin2;
uniform vec4 ValkyrienAir_ShipAabbMax2;
uniform vec4 ValkyrienAir_GridSize2;
uniform mat4 ValkyrienAir_WorldToShip2;
uniform sampler2D ValkyrienAir_Mask2;

uniform vec4 ValkyrienAir_ShipAabbMin3;
uniform vec4 ValkyrienAir_ShipAabbMax3;
uniform vec4 ValkyrienAir_GridSize3;
uniform mat4 ValkyrienAir_WorldToShip3;
uniform sampler2D ValkyrienAir_Mask3;

uniform vec4 ValkyrienAir_ShipAabbMin4;
uniform vec4 ValkyrienAir_ShipAabbMax4;
uniform vec4 ValkyrienAir_GridSize4;
uniform mat4 ValkyrienAir_WorldToShip4;
uniform sampler2D ValkyrienAir_Mask4;

uniform vec4 ValkyrienAir_ShipAabbMin5;
uniform vec4 ValkyrienAir_ShipAabbMax5;
uniform vec4 ValkyrienAir_GridSize5;
uniform mat4 ValkyrienAir_WorldToShip5;
uniform sampler2D ValkyrienAir_Mask5;

uniform vec4 ValkyrienAir_ShipAabbMin6;
uniform vec4 ValkyrienAir_ShipAabbMax6;
uniform vec4 ValkyrienAir_GridSize6;
uniform mat4 ValkyrienAir_WorldToShip6;
uniform sampler2D ValkyrienAir_Mask6;

uniform vec4 ValkyrienAir_ShipAabbMin7;
uniform vec4 ValkyrienAir_ShipAabbMax7;
uniform vec4 ValkyrienAir_GridSize7;
uniform mat4 ValkyrienAir_WorldToShip7;
uniform sampler2D ValkyrienAir_Mask7;

uniform vec4 ValkyrienAir_ShipAabbMin8;
uniform vec4 ValkyrienAir_ShipAabbMax8;
uniform vec4 ValkyrienAir_GridSize8;
uniform mat4 ValkyrienAir_WorldToShip8;
uniform sampler2D ValkyrienAir_Mask8;
// Inverse-rotate v by quaternion q (i.e., apply q^-1 = (-q.xyz, q.w) to v).
// Used by vs_shipEmitterLight to express world-frame offsets in the
// emitter's owning ship local frame.
vec3 vs_quatRotateInv(vec4 q, vec3 v) {
    vec3 qNeg = -q.xyz;
    return v + 2.0 * cross(qNeg, cross(qNeg, v) + q.w * v);
}

out vec4 fragColor;

const float WS_UV_MIN = 1.0 / 32.0;
const float WS_UV_MAX = 31.0 / 32.0;

// DEBUG: when defined, replace the final color with a red tint proportional to
// this fragment's AO loss (vanilla baked + ship-on-ship seam correction, both
// folded into v_Color.a per-vertex in the VSH). Lets you park a ship voxel next
// to a real solid block and check the seam AO matches vanilla's darkening
// shape. Comment out the define to return to normal rendering.
#define VS_DEBUG_SEAM_AO

const int VA_MASK_TEX_WIDTH_SHIFT = 12;
const int VA_MASK_TEX_WIDTH_MASK = (1 << VA_MASK_TEX_WIDTH_SHIFT) - 1;
const int VA_SUB = 8;
const int VA_OCC_WORDS_PER_VOXEL = 16;
const float VA_WORLD_SAMPLE_EPS = 0.0001;

bool va_isFluidUv(vec2 uv) {
    return texture(ValkyrienAir_FluidMask, uv).r > 0.5;
}

uint va_fetchWord(sampler2D tex, int wordIndex) {
    ivec2 coord = ivec2(wordIndex & VA_MASK_TEX_WIDTH_MASK, wordIndex >> VA_MASK_TEX_WIDTH_SHIFT);
    vec4 raw = texelFetch(tex, coord, 0) * 255.0;
    uvec4 bytes = uvec4(round(raw));
    return bytes.r | (bytes.g << 8u) | (bytes.b << 16u) | (bytes.a << 24u);
}

bool va_testAir(sampler2D mask, int voxelIdx, ivec3 isize) {
    int volume = isize.x * isize.y * isize.z;
    int occBase = volume * VA_OCC_WORDS_PER_VOXEL;
    int wordIndex = occBase + (voxelIdx >> 5);
    int bit = voxelIdx & 31;
    uint word = va_fetchWord(mask, wordIndex);
    return ((word >> uint(bit)) & 1u) != 0u;
}

bool va_testOcc(sampler2D mask, int voxelIdx, int subIdx) {
    int wordIndex = voxelIdx * VA_OCC_WORDS_PER_VOXEL + (subIdx >> 5);
    int bit = subIdx & 31;
    uint word = va_fetchWord(mask, wordIndex);
    return ((word >> uint(bit)) & 1u) != 0u;
}

bool va_shouldDiscardForShip(vec3 worldPos, vec4 aabbMin, vec4 aabbMax, vec4 gridSize, mat4 worldToShip, sampler2D mask) {
    if (gridSize.x <= 0.0) return false;
    if (worldPos.x < aabbMin.x || worldPos.x > aabbMax.x) return false;
    if (worldPos.y < aabbMin.y || worldPos.y > aabbMax.y) return false;
    if (worldPos.z < aabbMin.z || worldPos.z > aabbMax.z) return false;

    vec3 localPos = (worldToShip * vec4(worldPos, 1.0)).xyz;
    vec3 size = gridSize.xyz;
    if (localPos.x < 0.0 || localPos.y < 0.0 || localPos.z < 0.0) return false;
    if (localPos.x >= size.x || localPos.y >= size.y || localPos.z >= size.z) return false;

    ivec3 v = ivec3(floor(localPos));
    ivec3 isize = ivec3(size);
    int voxelIdx = v.x + isize.x * (v.y + isize.y * v.z);

    ivec3 sv = ivec3(floor(fract(localPos) * float(VA_SUB)));
    sv = clamp(sv, ivec3(0), ivec3(VA_SUB - 1));
    int subIdx = sv.x + VA_SUB * (sv.y + VA_SUB * sv.z);

    if (va_testOcc(mask, voxelIdx, subIdx)) return true;
    if (va_testAir(mask, voxelIdx, isize)) return true;
    return false;
}

bool va_shouldDiscardFluid(vec3 worldPos) {
    return va_shouldDiscardForShip(worldPos, ValkyrienAir_ShipAabbMin0, ValkyrienAir_ShipAabbMax0, ValkyrienAir_GridSize0, ValkyrienAir_WorldToShip0, ValkyrienAir_Mask0) ||
        va_shouldDiscardForShip(worldPos, ValkyrienAir_ShipAabbMin1, ValkyrienAir_ShipAabbMax1, ValkyrienAir_GridSize1, ValkyrienAir_WorldToShip1, ValkyrienAir_Mask1) ||
        va_shouldDiscardForShip(worldPos, ValkyrienAir_ShipAabbMin2, ValkyrienAir_ShipAabbMax2, ValkyrienAir_GridSize2, ValkyrienAir_WorldToShip2, ValkyrienAir_Mask2) ||
        va_shouldDiscardForShip(worldPos, ValkyrienAir_ShipAabbMin3, ValkyrienAir_ShipAabbMax3, ValkyrienAir_GridSize3, ValkyrienAir_WorldToShip3, ValkyrienAir_Mask3) ||
        va_shouldDiscardForShip(worldPos, ValkyrienAir_ShipAabbMin4, ValkyrienAir_ShipAabbMax4, ValkyrienAir_GridSize4, ValkyrienAir_WorldToShip4, ValkyrienAir_Mask4) ||
        va_shouldDiscardForShip(worldPos, ValkyrienAir_ShipAabbMin5, ValkyrienAir_ShipAabbMax5, ValkyrienAir_GridSize5, ValkyrienAir_WorldToShip5, ValkyrienAir_Mask5) ||
        va_shouldDiscardForShip(worldPos, ValkyrienAir_ShipAabbMin6, ValkyrienAir_ShipAabbMax6, ValkyrienAir_GridSize6, ValkyrienAir_WorldToShip6, ValkyrienAir_Mask6) ||
        va_shouldDiscardForShip(worldPos, ValkyrienAir_ShipAabbMin7, ValkyrienAir_ShipAabbMax7, ValkyrienAir_GridSize7, ValkyrienAir_WorldToShip7, ValkyrienAir_Mask7) ||
        va_shouldDiscardForShip(worldPos, ValkyrienAir_ShipAabbMin8, ValkyrienAir_ShipAabbMax8, ValkyrienAir_GridSize8, ValkyrienAir_WorldToShip8, ValkyrienAir_Mask8);
}

// Loop bound for the per-fragment emitter scan. Should match
// VsShipEmitterList.MAX_EMITTERS — 1024 entries fit but is excessive per
// fragment; 128 is plenty for typical scenes (ships rarely have that many
// torches in the inner radius). Excess emitters in the buffer beyond this
// cap are silently ignored at fragment time.
const int VS_EMITTER_LOOP_CAP = 128;

// Distance-attenuated max ship-emitter contribution at this fragment's
// world position. Manhattan distance in the emitter's owning-ship frame
// so the octahedral light bubble visibly rotates with the hull.
float vs_shipEmitterLight(vec3 worldPos) {
    float maxLight = 0.0;
    int n = min(u_VsShipEmitterCount, VS_EMITTER_LOOP_CAP);
    for (int i = 0; i < n; i++) {
        vec4 e = texelFetch(u_VsShipEmitters, i * 2);
        vec4 q = texelFetch(u_VsShipEmitters, i * 2 + 1);
        vec3 offset_ship = vs_quatRotateInv(q, worldPos - e.xyz);
        float dist = abs(offset_ship.x) + abs(offset_ship.y) + abs(offset_ship.z);
        float light = max(0.0, e.w - dist);
        maxLight = max(maxLight, light);
    }
    return maxLight;
}

// ===== Ship-to-world AO (PER-FRAGMENT, occluder-lattice, cross-ship merge) ==========
// Vanilla AO evaluated in occluder-SHIP lattices, sliced by this face, per
// fragment -- with CROSS-SHIP MERGING. One gather pass collects the voxels
// within WS_SEAM_R_GATHER of the fragment (grouped by ship: the buffer is
// populated one ship at a time by VsShipOccluderList.populateFromShip).
// Every ship's lattice then acts as a HOST: its own voxels occupy their exact
// cells, and voxels of OTHER nearby ships are soft-injected as tent-spread
// occupancy. Each voxel carries a responsibility weight
//     r = 1 / (1 + sum of claims by other ships)
// (claim = proximity gate * claimant fade * lattice alignment), so its
// material totals ~1 across every lattice that renders it: two ships parked
// grid-aligned each carry the merged single-grid pattern at half strength and
// their fields SUM to exactly the vanilla result -- including cross-ship
// L-rule corners -- while ships pulled apart or rotated away separate back
// into independent rigid fields, continuously. Isolated ships reduce to the
// plain occluder-lattice field (rigid under translation/yaw: no drag).
//
// Per host lattice the field is vanilla AoFaceData, made continuous:
//  - voxel BANDS along the dominant normal axis are weighted by overlap with
//    the unit slab in front of the face (flush contact = 1, hover/sink fade
//    linearly, behind-face = 0, multi-deck lerps between layers);
//  - per face corner the count is the 4 QUADRANT occupancies around the
//    lattice vertex plus a diagonal-pair bonus
//        max(0, min(diag1) - max(diag2)) + max(0, min(diag2) - max(diag1))
//    which equals vanilla's edge+edge+L-rule-corner+front on every hard
//    front-air config but is label-symmetric -- the labeled form is only
//    cross-cell consistent for hard air fronts and would jump when the
//    fragment's lattice cell changes under a partially covering voxel;
//  - the 4 corner losses are interpolated with the vanilla two-triangle rule
//    (ws_seamInterp). Hosts SUM (vanilla sums per-sample losses), clamped to
//    the 0.2-multiplier floor.
const int WS_SEAM_OCCLUDER_LOOP_CAP = 256;
// Darkening per solid sample: vanilla getShadeBrightness() is 0.2 for a solid
// block, and each solid sample lowers the 4-sample vertex average by 0.2.
const float WS_SEAM_STRENGTH = 0.2;
// Vanilla's AO floor is a 0.2 multiplier: at most 0.8 of the light lost.
const float WS_SEAM_MAX_TOTAL = 0.8;
// Gather radius around the fragment. The ALIGNED field's support ends at 2.6
// blocks; misaligned tent bleed reaches farther with tiny values and is cut
// by the support fade below, which hits 0 before the gather boundary so
// nothing pops when a voxel crosses it.
const float WS_SEAM_R_GATHER = 3.5;
const float WS_SEAM_FADE_LO = 2.8;
const float WS_SEAM_FADE_HI = 3.4;
// Merge gate on the voxel-pair distance: 1 below G_FULL so every strongly
// interacting pair merges with FULL claims (partial claims skew the per-host
// corner proportions, the crease flips disagree between hosts, and the
// aligned-limit exactness breaks; the 1-block-gap pair is 2.0 apart).
const float WS_SEAM_G_FULL = 2.2;
const float WS_SEAM_G_ZERO = 3.0;
// Fixed-size gather storage. 18 voxels can matter at once (3x3 window x 2
// bands); RUNS caps how many distinct ships merge at one fragment.
const int WS_SEAM_MAX_GATHER = 20;
const int WS_SEAM_MAX_RUNS = 4;
// DEBUG: world-space radius of the dot drawn at each sampled lattice corner.
const float WS_DBG_VERTEX_RADIUS = 0.06;

vec3 ws_seamQuatRotate(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

// Vanilla-style interpolation of the 4 corner losses across the face:
// linear over the quad's two triangles. Sodium picks the split diagonal
// (ModelQuadOrientation.orientByBrightness, NORMAL iff br[0]+br[2] >
// br[1]+br[3]) so the crease runs through the opposite corner pair with the
// greater brightness -- in loss terms the SMALLER loss sum. The two splits
// coincide identically when the sums tie, so the flip is continuous.
// c = (L00, L10, L01, L11).
float ws_seamInterp(vec4 c, vec2 uv) {
    if (c.x + c.w <= c.y + c.z) {           // crease through 00-11
        return uv.x >= uv.y
            ? c.x + (c.y - c.x) * uv.x + (c.w - c.y) * uv.y
            : c.x + (c.w - c.z) * uv.x + (c.z - c.x) * uv.y;
    } else {                                // crease through 10-01
        return uv.x + uv.y <= 1.0
            ? c.x + (c.y - c.x) * uv.x + (c.z - c.x) * uv.y
            : c.w + (c.z - c.w) * (1.0 - uv.x) + (c.y - c.w) * (1.0 - uv.y);
    }
}

float ws_seamAoFrag(vec3 fragWorldPos, vec3 normal, int selfShipIndex, out float dbgVertex) {
    dbgVertex = 0.0;

    int n = min(u_VsShipOccluderCount, WS_SEAM_OCCLUDER_LOOP_CAP);

    // ---- gather: nearby voxels, grouped into per-ship runs ----------------
    vec4 runQ[WS_SEAM_MAX_RUNS];
    vec3 runAnchor[WS_SEAM_MAX_RUNS];
    int runShip[WS_SEAM_MAX_RUNS];
    int runCount = 0;
    vec3 voxPos[WS_SEAM_MAX_GATHER];
    int voxRun[WS_SEAM_MAX_GATHER];
    int voxCount = 0;
    int lastShip = 0x7FFFFFFF;
    int lastSlot = -1;
    for (int i = 0; i < n; i++) {
        vec4 vox = texelFetch(u_VsShipOccluders, i * 2);
        int shipIdx = floatBitsToInt(vox.w) & 0xFFFF;
        if (shipIdx != lastShip) {
            lastShip = shipIdx;
            lastSlot = -1;
        }
        if (distance(vox.xyz, fragWorldPos) > WS_SEAM_R_GATHER) continue;
        if (lastSlot < 0) {
            if (runCount == WS_SEAM_MAX_RUNS) continue;
            lastSlot = runCount++;
            runQ[lastSlot] = texelFetch(u_VsShipOccluders, i * 2 + 1);
            runAnchor[lastSlot] = vox.xyz;
            runShip[lastSlot] = shipIdx;
        }
        if (voxCount == WS_SEAM_MAX_GATHER) continue;
        voxPos[voxCount] = vox.xyz;
        voxRun[voxCount] = lastSlot;
        voxCount++;
    }
    if (runCount == 0) return 0.0;

    // support fade by fragment distance
    float fadeV[WS_SEAM_MAX_GATHER];
    for (int vi = 0; vi < voxCount; vi++)
        fadeV[vi] = clamp((WS_SEAM_FADE_HI - distance(voxPos[vi], fragWorldPos))
                / (WS_SEAM_FADE_HI - WS_SEAM_FADE_LO), 0.0, 1.0);

    // ---- responsibility r per voxel: 1 / (1 + sum of other ships' claims) -
    float rv[WS_SEAM_MAX_GATHER];
    for (int vi = 0; vi < voxCount; vi++) {
        float csum = 0.0;
        for (int si = 0; si < runCount; si++) {
            if (si == voxRun[vi]) continue;
            float gf = 0.0;
            for (int wi = 0; wi < voxCount; wi++) {
                if (voxRun[wi] != si) continue;
                float g = clamp((WS_SEAM_G_ZERO - distance(voxPos[vi], voxPos[wi]))
                        / (WS_SEAM_G_ZERO - WS_SEAM_G_FULL), 0.0, 1.0);
                gf = max(gf, g * fadeV[wi]);
            }
            if (gf > 0.0) {
                vec3 c = vs_quatRotateInv(runQ[si], voxPos[vi] - runAnchor[si]) + 0.5;
                vec3 fr = abs(fract(c) - 0.5);
                csum += gf * (1.0 - fr.x) * (1.0 - fr.y) * (1.0 - fr.z);
            }
        }
        rv[vi] = 1.0 / (1.0 + csum);
    }

    // ---- one vanilla field per host lattice, summed -----------------------
    float total = 0.0;
    for (int si = 0; si < runCount; si++) {
        vec4 q = runQ[si];
        // fragment in this lattice, shifted so VERTICES land on integers
        // (voxel centers sit at k + 0.5)
        vec3 fragL = vs_quatRotateInv(q, fragWorldPos - runAnchor[si]) + 0.5;
        vec3 nL = vs_quatRotateInv(q, normal);

        // dominant slice axis, epsilon-tiebroken (deterministic on exact
        // ties; pops only if a ship pitches/rolls through 45 deg vs the face)
        vec3 absN = abs(nL);
        int a = 0;
        if (absN.y > absN.x + 1e-6) a = 1;
        if (absN.z > absN[a] + 1e-6) a = 2;
        int u = (a + 1) % 3;
        int v = (a + 2) % 3;
        float s = nL[a] >= 0.0 ? 1.0 : -1.0;

        // the unit slab directly in FRONT of the face along a; the (at most
        // two) voxel bands overlapping it, weighted by overlap length
        float pa = fragL[a];
        float slabLo = s > 0.0 ? pa : pa - 1.0;
        int b0 = int(floor(slabLo));
        float w0 = clamp(float(b0) + 1.0 - slabLo, 0.0, 1.0);

        int cu = int(floor(fragL[u]));
        int cv = int(floor(fragL[v]));
        vec2 uv = vec2(fragL[u] - float(cu), fragL[v] - float(cv));

        // soft occupancy of the 3x3-cells-by-2-bands window in this lattice:
        // own voxels at their exact cells, foreign voxels tent-spread and
        // scaled by their claimed share
        float occ[18];
        for (int k = 0; k < 18; k++) occ[k] = 0.0;
        for (int vi = 0; vi < voxCount; vi++) {
            int owner = voxRun[vi];
            if (runShip[owner] == selfShipIndex) continue;  // baked already
            float wgt;
            if (owner == si) {
                wgt = rv[vi] * fadeV[vi];
            } else {
                float gf = 0.0;
                for (int wi = 0; wi < voxCount; wi++) {
                    if (voxRun[wi] != si) continue;
                    float g = clamp((WS_SEAM_G_ZERO - distance(voxPos[vi], voxPos[wi]))
                            / (WS_SEAM_G_ZERO - WS_SEAM_G_FULL), 0.0, 1.0);
                    gf = max(gf, g * fadeV[wi]);
                }
                if (gf <= 0.0) continue;
                vec3 cc = vs_quatRotateInv(q, voxPos[vi] - runAnchor[si]) + 0.5;
                vec3 fr = abs(fract(cc) - 0.5);
                wgt = rv[vi] * gf * fadeV[vi]
                        * (1.0 - fr.x) * (1.0 - fr.y) * (1.0 - fr.z);
            }
            if (wgt <= 0.0) continue;
            vec3 c = vs_quatRotateInv(q, voxPos[vi] - runAnchor[si]) + 0.5;
            for (int b = 0; b < 2; b++) {
                float ta = max(0.0, 1.0 - abs(c[a] - (float(b0 + b) + 0.5)));
                if (ta <= 0.0) continue;
                for (int dv = -1; dv <= 1; dv++) {
                    float tv = max(0.0, 1.0 - abs(c[v] - (float(cv + dv) + 0.5)));
                    if (tv <= 0.0) continue;
                    for (int du = -1; du <= 1; du++) {
                        float tu = max(0.0, 1.0 - abs(c[u] - (float(cu + du) + 0.5)));
                        if (tu <= 0.0) continue;
                        occ[b * 9 + (dv + 1) * 3 + (du + 1)] += wgt * ta * tu * tv;
                    }
                }
            }
        }
        for (int k = 0; k < 18; k++) occ[k] = min(occ[k], 1.0);

        // per corner: 4 quadrant occupancies + the diagonal-pair bonus
        vec4 corner = vec4(0.0);
        for (int b = 0; b < 2; b++) {
            float w = b == 0 ? w0 : 1.0 - w0;
            if (w <= 0.0) continue;
            int base = b * 9;
            for (int ci = 0; ci < 4; ci++) {
                int du = ci & 1;
                int dv = ci >> 1;
                float qA = occ[base + dv * 3 + du];
                float qB = occ[base + dv * 3 + du + 1];
                float qC = occ[base + (dv + 1) * 3 + du];
                float qD = occ[base + (dv + 1) * 3 + du + 1];
                float bonus = max(0.0, min(qA, qD) - max(qB, qC))
                            + max(0.0, min(qB, qC) - max(qA, qD));
                corner[ci] += WS_SEAM_STRENGTH * (qA + qB + qC + qD + bonus) * w;
            }
        }
        total += ws_seamInterp(corner, uv);
#ifdef VS_DEBUG_SEAM_AO
        // dots at the 4 sampled lattice corners of this host on the face
        if (any(greaterThan(corner, vec4(0.001)))) {
            for (int ci = 0; ci < 4; ci++) {
                vec3 lp;
                lp[a] = pa;
                lp[u] = float(cu + (ci & 1));
                lp[v] = float(cv + (ci >> 1));
                vec3 wp = runAnchor[si] + ws_seamQuatRotate(q, lp - 0.5);
                if (distance(fragWorldPos, wp) < WS_DBG_VERTEX_RADIUS) dbgVertex = 1.0;
            }
        }
#endif
    }
    return min(total, WS_SEAM_MAX_TOTAL);
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

    if (ValkyrienAir_CullEnabled > 0.5 && ValkyrienAir_IsShipPass < 0.5 && va_isFluidUv(v_TexCoord)) {
        vec3 cullWorldPos = v_CameraRelWorldPos + floor(ValkyrienAir_CameraWorldPos) + vec3(0.0, -VA_WORLD_SAMPLE_EPS, 0.0);
        if (va_shouldDiscardFluid(cullWorldPos)) {
            discard;
        }
    }

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

    // Combined AO + shade. v_Color.a is vanilla's baked AO; the ship-to-world
    // seam AO is computed per-fragment here and subtracted in. Floor 0.2
    // matches sodium's deepest opaque AO.
    float ao = v_Color.a;
    float dbgSeamVertex = 0.0;
    if (v_IsShaded == 1) {
        float seamVertex = 0.0;
        float seamLoss = ws_seamAoFrag(worldPos, v_WorldNormal, -1, seamVertex);
        ao = max(0.2, ao - seamLoss);
        dbgSeamVertex = seamVertex;
        float shade = 1.0;
        if (v_WorldNormal.y < -0.5)       shade = 0.5; // DOWN
        else if (abs(v_WorldNormal.y) > 0.5) shade = 1.0; // UP
        else if (abs(v_WorldNormal.x) > 0.5) shade = 0.6; // EAST / WEST
        else                                  shade = 0.8; // NORTH / SOUTH
        diffuseColor.rgb *= ao * shade;
    } else {
        diffuseColor.rgb *= ao;
    }

#ifdef VS_DEBUG_SEAM_AO
    // BLUE dot = this fragment sits on a seam-square vertex (for manual
    // checking). RED elsewhere = the AO loss applied. The tiny lightSample term
    // keeps u_LightTex referenced so GLSL dead-code elimination doesn't strip
    // the sampler (sodium's bindUniform NPEs at link time if an active uniform
    // is optimized out).
    float dbgTotalLoss = clamp((1.0 - ao) * 1.25, 0.0, 1.0);
    vec3 dbgCol = vec3(dbgTotalLoss, 0.0, 0.0);
    if (dbgSeamVertex > 3.5)      dbgCol = vec3(1.0, 0.4, 0.7); // pink   = half-step point
    else if (dbgSeamVertex > 2.5) dbgCol = vec3(0.0, 1.0, 0.0); // green  = subtended line
    else if (dbgSeamVertex > 1.5) dbgCol = vec3(1.0, 0.5, 0.0); // orange = subtended ship square
    else if (dbgSeamVertex > 0.5) dbgCol = vec3(0.0, 0.0, 1.0); // blue   = self vertex
    diffuseColor.rgb = dbgCol + lightSample.rgb * 1e-3;
#endif

    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
}
