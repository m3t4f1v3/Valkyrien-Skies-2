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

#ifdef VS_SHIP_AO
// Per-frame list of solid ship voxel CENTERS in world space, paired with the
// voxel's owning-ship rotation quaternion (see VsShipOccluderList). Consumed
// PER-FRAGMENT by ws_seamAoFrag below for ship-to-world AO seam matching.
uniform samplerBuffer u_VsShipOccluders;
uniform int u_VsShipOccluderCount;
uniform samplerBuffer u_VsSeamRuns;
uniform int u_VsSeamRunCount;
uniform samplerBuffer u_VsSeamShipDir;
uniform vec4 u_VsSeamBounds;
// Coarse spatial grid over SUB-RUNS: cell = floor((worldPos - origin)*invCell).
// Buffer = per-cell (offset,count) headers then a flat sub-run-index list, so a
// fragment processes only the sub-runs actually near it, not a big ship's whole
// run list. See VsShipOccluderList.
uniform samplerBuffer u_VsSeamGrid;
uniform vec3 u_VsSeamGridOrigin;
uniform vec3 u_VsSeamGridInvCell;
#endif // VS_SHIP_AO

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

#ifdef VS_FLOOD_GRID
// ===== Flooded ship light: the occlusion gate ============================
//
// The emitter loop above is a pure distance falloff — it has no idea a hull or a hillside is in the
// way, so ship light shines straight through walls. The compute passes under
// assets/valkyrienskies/shaders/compute flood the same emitters through the world's block grid,
// stopping at ship voxels and terrain, and write the result into the section buffer sampled here.
//
// The flood is used only to GATE the emitter field, not to replace it. In open air the two agree
// (both are the light level minus a Manhattan-ish distance) so min() barely changes anything and the
// emitter list keeps its sub-block-precise, continuously-tracking falloff — the property that made
// the grid unusable as the primary source in the first place. Behind a wall the flood is 0 and the
// emitter's contribution is cut, which is exactly the case the emitter list gets wrong.
uniform usamplerBuffer u_VsWorldFromShipSections;
uniform usamplerBuffer u_VsWorldFromShipLut;
// 0 when the flood could not be produced this frame; the gate then stands down entirely rather
// than reading a grid nothing refreshed.
uniform int u_VsFloodGridValid;

// (The old fixed visibility threshold is gone -- the gate is now a ratio against the unoccluded
// reference, which is what lets a rotated ship's pool survive past the flood's Manhattan reach.)

// Must match VsWorldFromShipLightStorage's packed layout.
const uint VSF_SECTION_SIZE_INTS = 1641u;
const uint VSF_LIGHT_START_INTS = 183u;

bool vsf_nextLut(uint base, int coord, out uint next) {
    int start = int(texelFetch(u_VsWorldFromShipLut, int(base)).r);
    uint size = texelFetch(u_VsWorldFromShipLut, int(base) + 1).r;
    int idx = coord - start;
    if (idx < 0 || idx >= int(size)) return true;
    next = texelFetch(u_VsWorldFromShipLut, int(base + 2u + uint(idx))).r;
    return false;
}

bool vsf_section(ivec3 sectionPos, out uint sectionOffset) {
    uint first;
    if (vsf_nextLut(0u, sectionPos.y, first) || first == 0u) return false;
    uint second;
    if (vsf_nextLut(first, sectionPos.x, second) || second == 0u) return false;
    uint index;
    if (vsf_nextLut(second, sectionPos.z, index) || index == 0u) return false;
    sectionOffset = (index - 1u) * VSF_SECTION_SIZE_INTS;
    return true;
}

/** True when the packed blocker bitmap marks this voxel opaque (a ship voxel or world terrain). */
bool vsf_solidAt(uint sectionOffset, ivec3 blockInSection) {
    uint bitOffset = uint(blockInSection.x)
        + uint(blockInSection.z) * 18u
        + uint(blockInSection.y) * 324u;
    uint word = texelFetch(u_VsWorldFromShipSections, int(sectionOffset + (bitOffset >> 5u))).r;
    return (word & (1u << (bitOffset & 31u))) != 0u;
}

float vsf_lightAt(uint sectionOffset, ivec3 blockInSection) {
    uint byteOffset = uint(blockInSection.x)
        + uint(blockInSection.z) * 18u
        + uint(blockInSection.y) * 324u;
    uint raw = texelFetch(u_VsWorldFromShipSections,
        int(sectionOffset + VSF_LIGHT_START_INTS + (byteOffset >> 2u))).r;
    // The whole byte, in 16ths of a level. A 4-bit read here was the grid lock: the field could only
    // take 16 values, so its contours snapped to cell boundaries and the lit pattern jumped a block at
    // a time as a ship drifted.
    return float((raw >> ((byteOffset & 3u) << 3u)) & 0xFFu) * (1.0 / 16.0);
}

// Trilinearly sampled flooded light, or -1 when this fragment sits outside every tracked section.
// The caller must leave the emitter field alone in that case rather than gate it to zero: "no data"
// means the flood simply never covered here, not that the spot is dark.
float vsf_floodTrilinear(vec3 worldPos) {
    vec3 p = worldPos - 0.5;              // grid values live at cell centres
    ivec3 base = ivec3(floor(p));
    vec3 f = p - vec3(base);

    ivec3 baseSection = base >> 4;
    uint baseOffset;
    if (!vsf_section(baseSection, baseOffset)) return -1.0;

    // SOLID taps are dropped and the rest renormalised. A fragment sits ON a block face, so half its
    // taps land inside the block behind it; averaging those in would halve the value at every lit
    // surface and the gate would then eat light that belongs there. Excluding them by the blocker
    // bitmap — rather than by "the tap read zero", which was the earlier rule — keeps genuinely dark
    // air taps in the average, so light still falls off and still stops at walls. Dropping zero taps
    // instead let a single lit tap carry a shadowed fragment, which leaked light around corners.
    float acc = 0.0;
    float weight = 0.0;
    for (int i = 0; i < 8; i++) {
        ivec3 step = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        vec3 w3 = mix(vec3(1.0) - f, f, vec3(step));
        float w = w3.x * w3.y * w3.z;
        if (w <= 0.0) continue;
        ivec3 c = base + step;
        uint offset = baseOffset;
        // All eight taps usually land in one section; only re-walk the LUT when one doesn't.
        if ((c >> 4) != baseSection && !vsf_section(c >> 4, offset)) continue;
        ivec3 inSection = (c & 15) + 1;
        if (vsf_solidAt(offset, inSection)) continue;
        acc += w * vsf_lightAt(offset, inSection);
        weight += w;
    }
    // Every tap opaque: the fragment is buried, so there is no light here.
    return weight > 0.0 ? acc / weight : 0.0;
}
#endif // VS_FLOOD_GRID

out vec4 fragColor;

const float WS_UV_MIN = 1.0 / 32.0;
const float WS_UV_MAX = 31.0 / 32.0;

// DEBUG: when defined, replace the final color with a red tint proportional to
// this fragment's AO loss (vanilla baked + ship-on-ship seam correction, both
// folded into v_Color.a per-vertex in the VSH). Lets you park a ship voxel next
// to a real solid block and check the seam AO matches vanilla's darkening
// shape. Comment out the define to return to normal rendering.
// Left ON upstream, which paints every terrain fragment black: uncomment to use it.
// #define VS_DEBUG_SEAM_AO

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

#ifdef VS_SHIP_AO
// ===== Ship-to-world AO (PER-FRAGMENT, occluder-lattice, cross-ship merge) ==========
// Vanilla AO evaluated in occluder-SHIP lattices, sliced by this face, per
// fragment -- with CROSS-SHIP MERGING. The CPU precomputes sub-run bounding
// spheres plus a per-ship directory (pose, material responsibility r, top-4
// claim partners). The shader first discovers nearby source runs, expands them
// to host lattices through the directory, then only scans sub-runs whose sphere
// can touch this fragment's finite support.
//
// Per host lattice the field is vanilla AoFaceData, made continuous:
//  - voxel BANDS along the dominant normal axis are weighted by overlap with
//    the unit slab in front of the face (flush contact = 1, hover/sink fade
//    linearly, behind-face = 0, multi-deck lerps between layers);
//  - per face corner the count is the 4 QUADRANT occupancies around the
//    lattice vertex plus a diagonal-pair bonus
//        max(0, min(diag1) - max(diag2)) + max(0, min(diag2) - max(diag1));
//  - the 4 corner losses are interpolated with the vanilla two-triangle rule
//    (ws_seamInterp). Hosts SUM (vanilla sums per-sample losses), clamped to
//    the 0.2-multiplier floor.
const int WS_SEAM_SUBRUN_LOOP_CAP = 32;
const int WS_SEAM_MAX_HOSTS = 4;
// Spatial grid over SUB-RUNS: buffer is GRID_CELLS header texels (offset,count)
// then a flat sub-run-index list packed 4/texel. Must match VsShipOccluderList.
const int WS_SEAM_GRID_DIM = 8;
const int WS_SEAM_GRID_CELLS = 8 * 8 * 8;
// Safety bound on sub-runs examined per cell.
const int WS_SEAM_CELL_LOOP_CAP = 512;
// Darkening per solid sample: vanilla getShadeBrightness() is 0.2 for a solid
// block, and each solid sample lowers the 4-sample vertex average by 0.2.
const float WS_SEAM_STRENGTH = 0.2;
// Vanilla's AO floor is a 0.2 multiplier: at most 0.8 of the light lost.
const float WS_SEAM_MAX_TOTAL = 0.8;
// Finite support for the tent-injected voxel field: a voxel farther than this
// (world distance) from the fragment contributes exactly zero, so it is culled
// with no fade. The tent product (three half-width-1 tents over u/v/normal
// cells) collapses to 0 well before the theoretical 3x2.5-box corner (~4.33),
// so 3.7 is measured drift-free vs the 4.5 the field was originally sized for
// (see claude-scratchpad/probe_support.py). Smaller = fewer voxels survive the
// cull and a tighter grid binning, both per-fragment wins. MUST match
// VsShipOccluderList.SEAM_SUPPORT (grid fattening) and seam5.SUPPORT.
const float WS_SEAM_SUPPORT = 3.7;
// DEBUG: world-space radius of the dot drawn at each sampled lattice corner.
const float WS_DBG_VERTEX_RADIUS = 0.06;

vec3 ws_seamQuatRotate(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

// Inverse-rotation matrix R(q)^T, so Rinv * v == vs_quatRotateInv(q, v). Built
// ONCE per host lattice and reused for the fragment, the normal and every
// stamped voxel: a mat3*vec3 (~15 flops) is roughly half a per-voxel
// quaternion double-cross (~30), and the voxel loop is the shader's hot path.
mat3 ws_seamRotInvMat(vec4 q) {
    float x = q.x, y = q.y, z = q.z, w = q.w;
    // columns = rows of the forward rotation matrix R
    return mat3(
        1.0 - 2.0 * (y * y + z * z), 2.0 * (x * y - z * w),       2.0 * (x * z + y * w),
        2.0 * (x * y + z * w),       1.0 - 2.0 * (x * x + z * z), 2.0 * (y * z - x * w),
        2.0 * (x * z - y * w),       2.0 * (y * z + x * w),       1.0 - 2.0 * (x * x + y * y));
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

float ws_seamDistSq(vec3 a, vec3 b) {
    vec3 d = a - b;
    return dot(d, d);
}

float ws_seamClaim(int ownerShip, int hostShip) {
    if (ownerShip == hostShip) return 1.0;
    if (ownerShip <= 0 || hostShip <= 0) return 0.0;
    vec4 claims = texelFetch(u_VsSeamShipDir, ownerShip * 6 + 2);
    ivec4 partners = floatBitsToInt(texelFetch(u_VsSeamShipDir, ownerShip * 6 + 3));
    if (partners.x == hostShip) return claims.x;
    if (partners.y == hostShip) return claims.y;
    if (partners.z == hostShip) return claims.z;
    if (partners.w == hostShip) return claims.w;
    return 0.0;
}

// Host slots live in built-in mat4/ivec4/vec4 (NOT arrays), so dynamic
// indexing stays in registers instead of spilling to local memory. hostQ /
// hostAnchor columns are the 4 slots' quats / (anchor,0); hostShip / hostR are
// per-slot components.
void ws_seamAddHost(int shipIdx, inout int hostCount,
        inout ivec4 hostShip, inout mat4 hostQ, inout mat4 hostAnchor,
        inout vec4 hostR) {
    if (shipIdx <= 0) return;
    for (int h = 0; h < WS_SEAM_MAX_HOSTS; h++) {
        if (h >= hostCount) break;
        if (hostShip[h] == shipIdx) return;
    }
    if (hostCount >= WS_SEAM_MAX_HOSTS) return;

    vec4 ar = texelFetch(u_VsSeamShipDir, shipIdx * 6 + 1);
    hostShip[hostCount] = shipIdx;
    hostQ[hostCount] = texelFetch(u_VsSeamShipDir, shipIdx * 6);
    hostAnchor[hostCount] = vec4(ar.xyz, 0.0);
    hostR[hostCount] = ar.w;               // responsibility r, cached for pass 2
    hostCount++;
}

mat3 ws_seamStamp(vec3 c, int a, int u, int v, int cu, int cv) {
    vec3 uCenter = vec3(float(cu) - 0.5, float(cu) + 0.5, float(cu) + 1.5);
    vec3 vCenter = vec3(float(cv) - 0.5, float(cv) + 0.5, float(cv) + 1.5);
    vec3 tu = max(vec3(0.0), vec3(1.0) - abs(vec3(c[u]) - uCenter));
    vec3 tv = max(vec3(0.0), vec3(1.0) - abs(vec3(c[v]) - vCenter));
    return mat3(tv * tu.x, tv * tu.y, tv * tu.z);
}

mat3 ws_seamClampOcc(mat3 m) {
    m[0] = min(m[0], vec3(1.0));
    m[1] = min(m[1], vec3(1.0));
    m[2] = min(m[2], vec3(1.0));
    return m;
}

float ws_seamCorner(float qA, float qB, float qC, float qD) {
    float bonus = max(0.0, min(qA, qD) - max(qB, qC))
                + max(0.0, min(qB, qC) - max(qA, qD));
    return WS_SEAM_STRENGTH * (qA + qB + qC + qD + bonus);
}

vec4 ws_seamCorners(mat3 occ) {
    return vec4(
        ws_seamCorner(occ[0][0], occ[1][0], occ[0][1], occ[1][1]),
        ws_seamCorner(occ[1][0], occ[2][0], occ[1][1], occ[2][1]),
        ws_seamCorner(occ[0][1], occ[1][1], occ[0][2], occ[1][2]),
        ws_seamCorner(occ[1][1], occ[2][1], occ[1][2], occ[2][2])
    );
}

float ws_seamAoFrag(vec3 fragWorldPos, vec3 normal, int selfShipIndex, out float dbgVertex) {
    dbgVertex = 0.0;

    if (u_VsShipOccluderCount <= 0 || u_VsSeamRunCount <= 0) return 0.0;
    float globalR = u_VsSeamBounds.w + WS_SEAM_SUPPORT;
    if (ws_seamDistSq(fragWorldPos, u_VsSeamBounds.xyz) > globalR * globalR) return 0.0;

    // ---- pass 1: nearby SHIPS -> host lattices ----------------------------
    // Two-level cull: iterate the ship directory and reject each far ship with
    // ONE bounding-sphere texel; a near ship (and its claim partners) becomes a
    // host lattice. No per-run cache is kept (that array hurt occupancy) --
    // pass 2 re-walks the same cheap two-level scan keyed by owner.
    mat4 hostQ;
    mat4 hostAnchor;
    ivec4 hostShip = ivec4(0);
    vec4 hostR = vec4(0.0);
    int hostCount = 0;
    // This fragment's grid cell + its sub-run list (offset,count).
    ivec3 gi = ivec3(floor((fragWorldPos - u_VsSeamGridOrigin) * u_VsSeamGridInvCell));
    if (any(lessThan(gi, ivec3(0))) || any(greaterThanEqual(gi, ivec3(WS_SEAM_GRID_DIM)))) return 0.0;
    int cell = (gi.z * WS_SEAM_GRID_DIM + gi.y) * WS_SEAM_GRID_DIM + gi.x;
    ivec2 cellOC = floatBitsToInt(texelFetch(u_VsSeamGrid, cell)).xy;  // (offset, count)

    for (int i = 0; i < WS_SEAM_CELL_LOOP_CAP; i++) {
        if (i >= cellOC.y) break;
        int e = cellOC.x + i;
        int ri = floatBitsToInt(texelFetch(u_VsSeamGrid, WS_SEAM_GRID_CELLS + (e >> 2)))[e & 3];

        vec4 head = texelFetch(u_VsSeamRuns, ri * 2);
        float runR = head.w + WS_SEAM_SUPPORT;
        if (ws_seamDistSq(fragWorldPos, head.xyz) > runR * runR) continue;
        int owner = floatBitsToInt(texelFetch(u_VsSeamRuns, ri * 2 + 1).z) & 0xFFFF;
        if (owner <= 0) continue;
        // owner already a host? then its partners are too -- skip the fetches
        bool known = false;
        for (int o = 0; o < WS_SEAM_MAX_HOSTS; o++) {
            if (o >= hostCount) break;
            if (hostShip[o] == owner) { known = true; break; }
        }
        if (known) continue;

        ws_seamAddHost(owner, hostCount, hostShip, hostQ, hostAnchor, hostR);
        vec4 claims = texelFetch(u_VsSeamShipDir, owner * 6 + 2);
        ivec4 partners = floatBitsToInt(texelFetch(u_VsSeamShipDir, owner * 6 + 3));
        if (claims.x > 0.0) ws_seamAddHost(partners.x, hostCount, hostShip, hostQ, hostAnchor, hostR);
        if (claims.y > 0.0) ws_seamAddHost(partners.y, hostCount, hostShip, hostQ, hostAnchor, hostR);
        if (claims.z > 0.0) ws_seamAddHost(partners.z, hostCount, hostShip, hostQ, hostAnchor, hostR);
        if (claims.w > 0.0) ws_seamAddHost(partners.w, hostCount, hostShip, hostQ, hostAnchor, hostR);
    }
    if (hostCount == 0) return 0.0;

    // Cache the claim of every host (as material OWNER) to every host (as
    // lattice), so pass 2 needs zero ship-directory fetches. Owners with
    // nearby runs are always among these hosts (they were added as sources);
    // hostClaim[ownerSlot][hostSlot] = claim, with the diagonal = 1.
    mat4 hostClaim = mat4(0.0);
    for (int o = 0; o < WS_SEAM_MAX_HOSTS; o++) {
        if (o >= hostCount) break;
        int os = hostShip[o];
        vec4 claims = texelFetch(u_VsSeamShipDir, os * 6 + 2);
        ivec4 partners = floatBitsToInt(texelFetch(u_VsSeamShipDir, os * 6 + 3));
        for (int h = 0; h < WS_SEAM_MAX_HOSTS; h++) {
            if (h >= hostCount) break;
            int hs = hostShip[h];
            float c = 0.0;
            if (hs == os) c = 1.0;
            else if (partners.x == hs) c = claims.x;
            else if (partners.y == hs) c = claims.y;
            else if (partners.z == hs) c = claims.z;
            else if (partners.w == hs) c = claims.w;
            hostClaim[o][h] = c;
        }
    }

    // ---- pass 2: one vanilla field per host lattice, summed ---------------
    float total = 0.0;
    for (int hi = 0; hi < WS_SEAM_MAX_HOSTS; hi++) {
        if (hi >= hostCount) break;
        vec4 q = hostQ[hi];
        vec3 anchor = hostAnchor[hi].xyz;
        mat3 Rinv = ws_seamRotInvMat(q);   // Rinv * v == vs_quatRotateInv(q, v)
        // fragment in this lattice, shifted so VERTICES land on integers
        // (voxel centers sit at k + 0.5)
        vec3 fragL = Rinv * (fragWorldPos - anchor) + 0.5;
        vec3 nL = Rinv * normal;

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
        // band centers along the slice axis, loop-invariant across the voxels
        float bandC0 = float(b0) + 0.5;
        float bandC1 = float(b0) + 1.5;

        mat3 occ0 = mat3(0.0);
        mat3 occ1 = mat3(0.0);
        // Stamp the fragment's cell sub-runs into this host lattice, weighted
        // by owner -> host claim. Iterating the CELL's runs (not each ship's
        // whole run list) bounds the cost by nearby geometry, not ship size.
        for (int i = 0; i < WS_SEAM_CELL_LOOP_CAP; i++) {
            if (i >= cellOC.y) break;
            int e = cellOC.x + i;
            int ri = floatBitsToInt(texelFetch(u_VsSeamGrid, WS_SEAM_GRID_CELLS + (e >> 2)))[e & 3];

            vec4 head = texelFetch(u_VsSeamRuns, ri * 2);
            float runR = head.w + WS_SEAM_SUPPORT;
            if (ws_seamDistSq(fragWorldPos, head.xyz) > runR * runR) continue;
            vec4 meta = texelFetch(u_VsSeamRuns, ri * 2 + 1);
            int owner = floatBitsToInt(meta.z) & 0xFFFF;
            if (owner == selfShipIndex) continue;  // baked already
            int os = -1;
            for (int o = 0; o < WS_SEAM_MAX_HOSTS; o++) {
                if (o >= hostCount) break;
                if (hostShip[o] == owner) { os = o; break; }
            }
            float wBase;
            if (os >= 0) {
                wBase = hostR[os] * hostClaim[os][hi];
            } else {
                wBase = texelFetch(u_VsSeamShipDir, owner * 6 + 1).w
                        * ws_seamClaim(owner, hostShip[hi]);
            }
            if (wBase <= 0.0) continue;

            int start = floatBitsToInt(meta.x);
            int cnt = floatBitsToInt(meta.y);
            for (int k = 0; k < WS_SEAM_SUBRUN_LOOP_CAP; k++) {
                if (k >= cnt) break;
                vec4 vox = texelFetch(u_VsShipOccluders, (start + k) * 2);
                // a voxel past SUPPORT of the fragment stamps exactly 0.
                if (ws_seamDistSq(vox.xyz, fragWorldPos) > WS_SEAM_SUPPORT * WS_SEAM_SUPPORT) continue;
                vec3 c = Rinv * (vox.xyz - anchor) + 0.5;
                mat3 stamp = ws_seamStamp(c, a, u, v, cu, cv);
                // fold the owner->host weight into the two scalar band tents so
                // the mat3 is scaled once per band instead of once for wBase and
                // again for each ta.
                float ta0 = wBase * max(0.0, 1.0 - abs(c[a] - bandC0));
                float ta1 = wBase * max(0.0, 1.0 - abs(c[a] - bandC1));
                occ0 += stamp * ta0;
                occ1 += stamp * ta1;
            }
        }
        occ0 = ws_seamClampOcc(occ0);
        occ1 = ws_seamClampOcc(occ1);

        vec4 corner = ws_seamCorners(occ0) * w0 + ws_seamCorners(occ1) * (1.0 - w0);
        total += ws_seamInterp(corner, uv);
#ifdef VS_DEBUG_SEAM_AO
        // dots at the 4 sampled lattice corners of this host on the face
        if (any(greaterThan(corner, vec4(0.001)))) {
            for (int ci = 0; ci < 4; ci++) {
                vec3 lp;
                lp[a] = pa;
                lp[u] = float(cu + (ci & 1));
                lp[v] = float(cv + (ci >> 1));
                vec3 wp = anchor + ws_seamQuatRotate(q, lp - 0.5);
                if (distance(fragWorldPos, wp) < WS_DBG_VERTEX_RADIUS) dbgVertex = 1.0;
            }
        }
#endif
    }
    return min(total, WS_SEAM_MAX_TOTAL);
}
#endif // VS_SHIP_AO

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
    // Ship light on world terrain, straight out of the flood -- see the 0.9 copy of this shader for
    // why this is not a gate over the emitter falloff any more.
#ifdef VS_FLOOD_GRID
    float shipFlood = vsf_floodTrilinear(worldPos);
    float shipLight = u_VsFloodGridValid != 0 ? max(shipFlood, 0.0) : 0.0;
#else
    // No compute support: fall back to the unoccluded emitter falloff.
    float shipLight = vs_shipEmitterLight(worldPos);
#endif
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
#ifdef VS_SHIP_AO
        float seamVertex = 0.0;
        float seamLoss = ws_seamAoFrag(worldPos, v_WorldNormal, -1, seamVertex);
        ao = max(0.2, ao - seamLoss);
        dbgSeamVertex = seamVertex;
#endif // VS_SHIP_AO
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
