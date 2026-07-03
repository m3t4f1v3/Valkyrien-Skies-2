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

// ===== Ship-to-world AO seam matching (PER-FRAGMENT) =====================
// Evaluated per-fragment, not baked at vertices: the fragment lies in the face
// INTERIOR, so floor() finds this face's block cell without the per-vertex
// corner knife-edge (and without the camera jitter that flickered the old
// per-vertex path), and the darkening becomes a smooth field rather than a
// 4-corner interpolation. The seam geometry mirrors the old VSH correction
// (match this face's square against a nearby ship voxel's facing square, find
// the shared seam edge, subtend it half a block inward), but the final loss is
// keyed off THIS fragment's distance to the subtended target.
// Occluders scanned per fragment. The buffer holds up to MAX_OCCLUDERS (1024)
// solid voxels of every nearby ship, so a cap of 64 examined only the first
// 64 — a given world face's real ship neighbor usually sits past that index and
// was never even tested, so it never matched no matter the geometry. (The real
// fix for very dense scenes is a per-fragment spatial cull; until then, scan
// more.)
const int WS_SEAM_OCCLUDER_LOOP_CAP = 256;
// Falloff reach. Vanilla/sodium smooth AO bilinearly interpolates 4 per-vertex
// values across the face (sodium AoNeighborInfo.calculateCornerWeights), so
// one occluding neighbor produces a gradient spanning EXACTLY one block from
// the shared edge and zero past it. Gaps are bridged by the merge lerp below
// (moving the quad), NOT by widening the falloff. REACH is also the merge-lerp
// range (t = pairDist / REACH): at one block of separation A's corners have
// fully collapsed onto B's face, which is exactly where the field must have
// died for the next cell's from-zero reconstruction to line up (cell-border
// continuity).
const float WS_SEAM_REACH = 1.0;
// How far apart the two squares' nearest corners can be and still count as a
// merge (the seam-match gate). The merge lerp has fully collapsed the quad
// onto the occluder face well before this; the gate just bounds the scan.
const float WS_SEAM_MATCH_REACH = 2.0;
// Darkening at the seam edge. Vanilla samples each neighbor cell as
// getShadeBrightness() — 0.2 for a solid block, 1.0 for air — and averages 4
// samples per vertex (sodium AoFaceData: ao[v] = (e+e+c+ca)*0.25), so ONE
// solid neighbor lowers the vertex multiplier by exactly 0.2.
const float WS_SEAM_STRENGTH = 0.2;
// Vanilla's AO floor is a 0.2 multiplier (all four samples solid), i.e. at
// most 0.8 of the light lost. Occluder contributions SUM (that's what the
// 4-sample average does per extra solid neighbor), then clamp to this.
const float WS_SEAM_MAX_TOTAL = 0.8;
// Coarse prefilter radius; matches VsShipOccluderList.SEAM_CANDIDATE_RADIUS.
// The exact best0 <= REACH test below does the real gating, so keep this
// generous or corner/diagonal neighbors get dropped before they're checked.
const float WS_SEAM_CANDIDATE_RADIUS = 2.5;
// Falloff cutoff: the raw product falloff is remapped so anything below
// CUTOFF becomes 0 and [CUTOFF, 1] rescales to [0, 1] (continuous -- a hard
// step would draw a visible iso-contour ring). 0.0 = identity, the
// vanilla-matched 1-block ramp; kept as a tunable.
const float WS_SEAM_CUTOFF = 0.0;
// DEBUG: world-space radius of the blue dot drawn at each seam-square vertex,
// and half-thickness of the orange outline drawn on the merge quad.
const float WS_DBG_VERTEX_RADIUS = 0.06;
const float WS_DBG_EDGE_RADIUS = 0.02;

vec3 ws_seamQuatRotate(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

// The 4 corners of a unit-cube face centered at c with outward normal nrm.
void ws_seamLocalFace(vec3 c, vec3 nrm, out vec3 c00, out vec3 c01, out vec3 c10, out vec3 c11) {
    vec3 a = abs(nrm);
    vec3 u = a.x > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 v = a.z > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
    c00 = c - u * 0.5 - v * 0.5;
    c01 = c + u * 0.5 - v * 0.5;
    c10 = c - u * 0.5 + v * 0.5;
    c11 = c + u * 0.5 + v * 0.5;
}

// DEBUG: distance from point p to segment ab (for the merge-quad outline).
float ws_distToSeg(vec3 p, vec3 a, vec3 b) {
    vec3 ab = b - a;
    float t = clamp(dot(p - a, ab) / max(dot(ab, ab), 1e-8), 0.0, 1.0);
    return distance(p, a + t * ab);
}

// Product falloff of the box [lo, hi], evaluated at the 4 receiving-face
// corners. The loss field is sampled PER CORNER and interpolated at the
// fragment by ws_seamInterp: evaluating the product directly at the fragment
// is true bilinear (curved hyperbolic iso-contours), whereas the rasterizer
// interpolates vanilla's per-vertex AO linearly over the face's two TRIANGLES
// with a sharp straight crease -- corner sampling reproduces that exactly.
vec4 ws_seamCornerFalloff(vec3 lo, vec3 hi, vec3 aLoc[4]) {
    vec4 r;
    for (int ci = 0; ci < 4; ci++) {
        vec3 ex = max(max(lo - aLoc[ci], aLoc[ci] - hi), vec3(0.0));
        vec3 w = clamp(vec3(1.0) - ex / WS_SEAM_REACH, vec3(0.0), vec3(1.0));
        r[ci] = clamp((w.x * w.y * w.z - WS_SEAM_CUTOFF)
                / (1.0 - WS_SEAM_CUTOFF), 0.0, 1.0);
    }
    return r;
}

// Vanilla-style interpolation of the 4 corner losses across the face:
// linear over the quad's two triangles. Sodium picks the split diagonal
// (ModelQuadOrientation.orientByBrightness, NORMAL iff br[0]+br[2] >
// br[1]+br[3]) so the crease runs through the opposite corner pair with the
// greater brightness -- in loss terms the SMALLER loss sum. The two splits
// coincide identically when the sums tie, so the flip is continuous.
// c = (L00, L10, L01, L11) matching ws_seamLocalFace's output order.
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
    vec3 absN = abs(normal);
    vec3 uAxis = absN.x > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 vAxis = absN.z > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);

    // Square A = the unit block face this fragment sits on (world-axis-aligned).
    float uCenter = floor(dot(fragWorldPos, uAxis)) + 0.5;
    float vCenter = floor(dot(fragWorldPos, vAxis)) + 0.5;
    float nPlane  = floor(dot(fragWorldPos, normal) + 0.5);
    vec3 faceCenter = normal * nPlane + uAxis * uCenter + vAxis * vCenter;

    vec3 A00, A01, A10, A11;
    ws_seamLocalFace(faceCenter, normal, A00, A01, A10, A11);
    vec3 Acorners[4] = vec3[](A00, A01, A10, A11);

    // Loss accumulates PER FACE CORNER (Acorners order: 00,10,01,11) and is
    // interpolated at the fragment with the vanilla two-triangle rule at the
    // end -- per-fragment evaluation of exactly what the rasterizer would do
    // with per-vertex AO, sharp diagonal creases included.
    vec4 cornerLoss = vec4(0.0);
    int n = min(u_VsShipOccluderCount, WS_SEAM_OCCLUDER_LOOP_CAP);
    for (int i = 0; i < n; i++) {
        vec4 voxel = texelFetch(u_VsShipOccluders, i * 2);
        vec4 q     = texelFetch(u_VsShipOccluders, i * 2 + 1);
        // voxel.w = raw packed bits: bits 0-15 dense ship index, bit 16 hint.
        int voxelShipIndex = floatBitsToInt(voxel.w) & 0xFFFF;
        if (voxelShipIndex == selfShipIndex) continue;
        if (distance(voxel.xyz, faceCenter) > WS_SEAM_CANDIDATE_RADIUS) continue;

        // Vanilla only samples the ONE layer of cells in FRONT of the face
        // plane (AoFaceData offsets by the face direction before sampling):
        // a voxel flush with or behind the plane — e.g. level with a floor
        // block — casts no AO onto it. Gate on the occluder center being in
        // front; the contribution below also ramps over the first quarter
        // block so a rotating ship voxel crossing the plane fades in instead
        // of popping.
        float frontness = dot(normal, voxel.xyz - faceCenter);
        if (frontness < 1e-4) continue;

        // This face's square, normal and the fragment lifted into the occluder
        // ship's LOCAL frame: square B's corners are analytic there, the
        // subtend cardinals are the plain <0.5,0>/<0,0.5> ship axes, and the
        // falloff box is axis-aligned. (Distances are rotation-invariant, so
        // matching in local space picks the same pairs as world space.)
        vec3 aLoc[4] = vec3[](
            vs_quatRotateInv(q, Acorners[0] - voxel.xyz),
            vs_quatRotateInv(q, Acorners[1] - voxel.xyz),
            vs_quatRotateInv(q, Acorners[2] - voxel.xyz),
            vs_quatRotateInv(q, Acorners[3] - voxel.xyz));
        vec3 towardSelfLocal = vs_quatRotateInv(q, faceCenter - voxel.xyz);
        vec3 normalALocal = vs_quatRotateInv(q, normal);
        float frontW = clamp(frontness / 0.25, 0.0, 1.0);

        // Evaluate EVERY voxel face whose outward normal points toward this
        // fragment's cell (up to 3) and keep the MAX contribution. A hard
        // dominant-axis pick flips between adjacent receiving cells (at yaw 45
        // the cardinal cells match the voxel's bottom face, the diagonal cells
        // a side face) and every flip is a visible border step; the max of the
        // per-face fields stays continuous when the argmax switches, and in
        // grid-aligned cases the candidate faces share the seam edge and tie
        // exactly, keeping vanilla parity.
        vec4 contrib = vec4(0.0);
        for (int axisI = 0; axisI < 3; axisI++) {
            if (abs(towardSelfLocal[axisI]) < 1e-6) continue;
            vec3 normalB = vec3(0.0);
            normalB[axisI] = sign(towardSelfLocal[axisI]);

            // Square B: that face of the unit voxel, ship-local, offset half a
            // voxel along normalB onto the actual surface so its corners can
            // COINCIDE with A's at the seam ("the same vertex belongs to both
            // squares").
            vec3 b00, b01, b10, b11;
            ws_seamLocalFace(normalB * 0.5, normalB, b00, b01, b10, b11);
            vec3 bLoc[4] = vec3[](b00, b01, b10, b11);

            vec4 faceLoss = vec4(0.0);
            if (dot(normalALocal, normalB) < -0.9) {
                // B faces this face head-on (voxel hovering over/against it):
                // there is no seam -- every corner pair ties and the 2-pair
                // pick below would be a loop-order artifact. The correct merge
                // quad is B's face itself (the footprint shadow), which meets
                // the neighboring cells' seam curtains at the borders.
                vec3 lo = min(min(bLoc[0], bLoc[1]), min(bLoc[2], bLoc[3]));
                vec3 hi = max(max(bLoc[0], bLoc[1]), max(bLoc[2], bLoc[3]));
                faceLoss = WS_SEAM_STRENGTH * frontW
                        * ws_seamCornerFalloff(lo, hi, aLoc);
            } else {
                // Scan the 16 corner pairs; take the two nearest that share no
                // vertex on either square (ascending-sort by distance, first
                // two unique pairs) -- the two endpoints of the seam.
                // Epsilon-strict: on mathematically-equal distances the
                // EARLIEST candidate in loop order wins deterministically,
                // instead of FP noise deciding (which flickers frame to frame
                // as the transforms are re-derived).
                int bi0 = -1, bj0 = -1;
                float best0 = 1e30;
                for (int ai = 0; ai < 4; ai++)
                    for (int bj = 0; bj < 4; bj++) {
                        float dd = distance(aLoc[ai], bLoc[bj]);
                        if (dd < best0 - 1e-6) { best0 = dd; bi0 = ai; bj0 = bj; }
                    }
                int bi1 = -1, bj1 = -1;
                float best1 = 1e30;
                for (int ai = 0; ai < 4; ai++) {
                    if (ai == bi0) continue;
                    for (int bj = 0; bj < 4; bj++) {
                        if (bj == bj0) continue;
                        float dd = distance(aLoc[ai], bLoc[bj]);
                        if (dd < best1 - 1e-6) { best1 = dd; bi1 = ai; bj1 = bj; }
                    }
                }
                if (bi1 < 0 || best0 > WS_SEAM_MATCH_REACH) continue;

                // === SUBTEND (B side) ===
                // Push the two matched B corners half a ship block along the
                // face cardinal (<0.5,0> / <0,0.5> in ship space) that got the
                // SMALLER |dot| with their pair vector (bigger dot -> use the
                // other one), signed into the face interior. The midpoint M of
                // the subtended line is the merge target.
                vec3 bAbsN = abs(normalB);
                vec3 bU = bAbsN.x > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                vec3 bV = bAbsN.z > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
                vec3 sb0 = bLoc[bj0], sb1 = bLoc[bj1];
                vec3 pairVec = sb1 - sb0;
                // DIAGONAL pairs tie this test exactly (|dot| equal on both
                // axes), so prefer bU within epsilon rather than letting FP
                // noise pick.
                float duP = abs(dot(bU, pairVec));
                float dvP = abs(dot(bV, pairVec));
                vec3 axis = (duP <= dvP + 1e-6 ? bU : bV) * 0.5;
                // Sign: into the face interior. For DIAGONAL pairs the
                // seam midpoint IS the face center (interior ~ 0, the sign
                // would be FP noise -> temporal flicker), so fall back to
                // "toward the matched A corners", then to +axis.
                vec3 interior = normalB * 0.5 - 0.5 * (sb0 + sb1);
                float sgn = dot(axis, interior);
                if (abs(sgn) < 1e-6) sgn = dot(axis, 0.5 * (aLoc[bi0] + aLoc[bi1]) - 0.5 * (sb0 + sb1));
                if (abs(sgn) < 1e-6) sgn = 1.0;
                vec3 h = sgn >= 0.0 ? axis : -axis;
                vec3 su = sb0 + h;
                vec3 sv = sb1 + h;
                vec3 M = 0.5 * (su + sv);

                // === MERGE: A's matched corners lerp toward M by
                // t = pairDist / REACH. Touching corners stay put, so the quad
                // spans the seam edge -> vanilla-exact gradient; at one block
                // of separation they have fully collapsed onto B's face, so
                // the field dies exactly where the next cell's reconstruction
                // starts from zero (cell-border continuity).
                vec3 m0 = mix(aLoc[bi0], M, clamp(best0 / WS_SEAM_REACH, 0.0, 1.0));
                vec3 m1 = mix(aLoc[bi1], M, clamp(best1 / WS_SEAM_REACH, 0.0, 1.0));

#ifdef VS_DEBUG_SEAM_AO
                // DEBUG: blue = A corners; orange = merge-quad outline (the
                // falloff source); green = the subtended line; pink = M.
                {
                    vec3 wSu = voxel.xyz + ws_seamQuatRotate(q, su);
                    vec3 wSv = voxel.xyz + ws_seamQuatRotate(q, sv);
                    vec3 wM0 = voxel.xyz + ws_seamQuatRotate(q, m0);
                    vec3 wM1 = voxel.xyz + ws_seamQuatRotate(q, m1);
                    float e = min(min(ws_distToSeg(fragWorldPos, wSu, wSv), ws_distToSeg(fragWorldPos, wSv, wM1)),
                                  min(ws_distToSeg(fragWorldPos, wM1, wM0), ws_distToSeg(fragWorldPos, wM0, wSu)));
                    for (int k = 0; k < 4; k++)
                        if (distance(fragWorldPos, Acorners[k]) < WS_DBG_VERTEX_RADIUS) dbgVertex = 1.0;
                    if (e < WS_DBG_EDGE_RADIUS) dbgVertex = 2.0;
                    if (ws_distToSeg(fragWorldPos, wSu, wSv) < WS_DBG_EDGE_RADIUS) dbgVertex = 3.0;
                    if (distance(fragWorldPos, voxel.xyz + ws_seamQuatRotate(q, M)) < WS_DBG_VERTEX_RADIUS) dbgVertex = 4.0;
                }
#endif

                // === PRODUCT FALLOFF (ship-frame box) ===
                // Per-axis exterior offsets from the merge quad's ship-local
                // bounding box, combined MULTIPLICATIVELY. Vanilla's bilinear
                // per-vertex AO decomposes into sums of products of axis
                // ramps, so the product form reproduces it exactly for
                // grid-aligned cases (summing the offsets -- L1 -- decays
                // diagonals twice as fast as vanilla; Euclidean distance
                // rounds the corners radially).
                vec3 lo = min(min(su, sv), min(m0, m1));
                vec3 hi = max(max(su, sv), max(m0, m1));
                faceLoss = WS_SEAM_STRENGTH * frontW
                        * ws_seamCornerFalloff(lo, hi, aLoc);
            }
            contrib = max(contrib, faceLoss);
        }
        // Vanilla SUMS per-sample losses (each solid sample subtracts 0.2 in
        // the 4-sample vertex average), so accumulate additively across
        // voxels; the clamp below matches vanilla's 0.2 multiplier floor.
        cornerLoss += contrib;
    }
    cornerLoss = min(cornerLoss, vec4(WS_SEAM_MAX_TOTAL));
    vec2 uv = fract(vec2(dot(fragWorldPos, uAxis), dot(fragWorldPos, vAxis)));
    return ws_seamInterp(cornerLoss, uv);
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
