#version 330 core

#import <sodium:include/fog.glsl>

in vec4 v_Color;            // RGB = sodium-baked vertex color; .a = AO (decoded in VSH)
in vec2 v_TexCoord;
in float v_FragDistance;
in float v_MaterialMipBias;
in float v_MaterialAlphaCutoff;
in vec2 v_BakedLightCoord;   // _vert_tex_light_coord (baked from shipyard storage)
flat in int v_ResolverType;  // 0 none, 1 grass, 2 foliage, 3 water
flat in int v_IsShaded;      // 0 unshaded (skip directional shade), 1 shaded
flat in int v_IsFullbright;  // 1 if BakedQuad was tagged emissive in its source model JSON
in vec3 v_VertexBiomeTint;   // rasterizer-blended world biome RGB, vec3(1.0) on non-biome quads
#if defined(VS_DYNAMIC_LIGHT) || defined(VS_SHIP_ON_SHIP)
in vec3 v_CameraRelWorldPos; // camera-relative WORLD pos; +u_VsRenderOrigin == absolute world pos
#endif
#if defined(VS_DYNAMIC_LIGHT) || defined(VS_DYNAMIC_SHADE)
flat in vec3 v_WorldNormal;  // world-space surface normal recovered from face slot in the VSH
#endif
#ifdef VS_SHIP_ON_SHIP
// This fragment's shipyard-local position + shipyard-space face normal.
// vs_seamAoFrag rebuilds "Square A" (the block face this fragment sits on) in
// the shipyard frame where floor() finds block boundaries, then lifts it to
// world space via u_TransformMatrix.
in vec3 v_ShipyardPos;
flat in vec3 v_ShipyardNormal;
#endif

uniform sampler2D u_BlockTex;
uniform sampler2D u_LightTex;

uniform vec4 u_FogColor;
uniform float u_FogStart;
uniform float u_FogEnd;

#if defined(VS_DYNAMIC_LIGHT) || defined(VS_SHIP_ON_SHIP)
uniform ivec3 u_VsRenderOrigin;
#endif
#ifdef VS_DYNAMIC_LIGHT
uniform usamplerBuffer u_VsLightSections;
uniform usamplerBuffer u_VsLightLut;
#endif
#ifdef VS_SHIP_ON_SHIP
// Per-frame ship-emitter list. TWO RGBA32F texels per emitter:
//   texel 2i:   vec4(worldX, worldY, worldZ, lightLevel)
//   texel 2i+1: vec4(qx, qy, qz, qw)   ship-to-world rotation quaternion
// Manhattan distance is taken in the emitter's owning-ship local frame
// (offset rotated by q^-1) so the octahedral light bubble rotates with
// the hull.
uniform samplerBuffer u_VsShipEmitters;
uniform int u_VsShipEmitterCount;
// Per-frame list of solid ship voxel CENTERS in world space, paired with the
// voxel's owning-ship rotation quaternion (see VsShipOccluderList). Consumed
// PER-FRAGMENT by vs_seamAoFrag below for ship-on-ship AO seam matching.
uniform samplerBuffer u_VsShipOccluders;
uniform int u_VsShipOccluderCount;
// Ship-to-world matrix of the ship being rendered; mat3() lifts this quad's
// shipyard-space half-steps and face corners into world space for the seam
// match ("<0.5,0>*ship mat, <0,0.5>*ship mat").
uniform mat4 u_TransformMatrix;
// Dense per-frame index of the ship being rendered; occluder voxels carrying
// this index are skipped (same-ship AO is already baked into v_Color.a, so
// counting it again would double-darken).
uniform int u_VsCurrentShipIndex;
#endif

out vec4 fragColor;

#define MINECRAFT_LIGHT_X (0.6)
#define MINECRAFT_LIGHT_Z (0.8)
#define MINECRAFT_LIGHT_Y (0.5)

// from Flywheel/common/src/backend/resources/assets/flywheel/flywheel/internal/diffuse.glsl
float vanillaShadeFromNormal(vec3 normal) {
    vec3 n2 = normal * normal * vec3(.6, .25, .8);
    return min(n2.x + n2.y * (3. + normal.y) + n2.z, 1.);
}

// MC's lightmap texture uses GL's default GL_REPEAT wrap; at UV=0 a LINEAR
// sample blends pixel 15 (bright) into pixel 0 (dark), giving the wrong color
// for sky=0 in caves. Clamp to pixel-center range to match sodium's baked
// vertex format (which clamps the packed light byte to [8, 248] for the same
// reason). Outside the VS_DYNAMIC_LIGHT block because the off-path still
// needs to clamp v_BakedLightCoord with these bounds.
const float VS_UV_MIN = 1.0 / 32.0;
const float VS_UV_MAX = 31.0 / 32.0;

// DEBUG: when defined (and VS_SHIP_ON_SHIP is on), replace the final color with
// a red tint proportional to this fragment's AO loss (vanilla baked +
// per-fragment ship-on-ship seam correction). Park a ship voxel next to
// another ship's face and check the seam AO matches. Comment out to render
// normally.
#define VS_DEBUG_SEAM_AO

#ifdef VS_DYNAMIC_LIGHT
// ===== Flywheel-style smooth light + AO ======================================
// Layout matches Flywheel's light_lut.glsl: each section is
//   [solid bits (732 B = 183 ints)] [light bytes (5832 B = 1458 ints)]
// for a total of 6564 bytes / 1641 ints per section. Solid bit + light byte at
// the same in-section position N use the same offset formula below.
const uint VS_BLOCKS_PER_SECTION = 18u * 18u * 18u;
const uint VS_LIGHT_SIZE_BYTES = VS_BLOCKS_PER_SECTION;
const uint VS_SOLID_SIZE_BYTES = ((VS_BLOCKS_PER_SECTION + 31u) / 32u) * 4u;
const uint VS_SOLID_START_INTS = 0u;
const uint VS_LIGHT_START_INTS = VS_SOLID_SIZE_BYTES / 4u;
const uint VS_SECTION_SIZE_INTS = (VS_SOLID_SIZE_BYTES + VS_LIGHT_SIZE_BYTES) / 4u;

const uint VS_COMPLETELY_SOLID = 0x7FFFFFFu;
const float VS_EPSILON = 1e-5;
const uint VS_LOWER_10_BITS = 0x3FFu;
const uint VS_UPPER_10_BITS = 0xFFF00000u;
const float VS_LIGHT_NORMALIZER = 1.0 / 16.0;

uint vs_indexLut(uint i) { return texelFetch(u_VsLightLut, int(i)).r; }
uint vs_indexLight(uint i) { return texelFetch(u_VsLightSections, int(i)).r; }

bool vs_nextLut(uint base, int coord, out uint next) {
    int start = int(vs_indexLut(base));
    uint size = vs_indexLut(base + 1u);
    int idx = coord - start;
    if (idx < 0 || idx >= int(size)) return true;
    next = vs_indexLut(base + 2u + uint(idx));
    return false;
}

bool vs_chunkCoordToSectionIndex(ivec3 sectionPos, out uint index) {
    uint first;
    if (vs_nextLut(0u, sectionPos.y, first) || first == 0u) return true;
    uint second;
    if (vs_nextLut(first, sectionPos.x, second) || second == 0u) return true;
    uint sectionIndex;
    if (vs_nextLut(second, sectionPos.z, sectionIndex) || sectionIndex == 0u) return true;
    index = sectionIndex - 1u;
    return false;
}

uvec2 vs_lightAt(uint sectionOffset, uvec3 blockInSectionPos) {
    uint byteOffset = blockInSectionPos.x + blockInSectionPos.z * 18u + blockInSectionPos.y * 18u * 18u;
    uint uintOffset = byteOffset >> 2u;
    uint bitOffset = (byteOffset & 3u) << 3u;
    uint raw = vs_indexLight(sectionOffset + VS_LIGHT_START_INTS + uintOffset);
    uint b = (raw >> bitOffset) & 0xFu;
    uint s = (raw >> (bitOffset + 4u)) & 0xFu;
    return uvec2(b, s);
}

bool vs_isSolid(uint sectionOffset, uvec3 blockInSectionPos) {
    uint bitOffset = blockInSectionPos.x + blockInSectionPos.z * 18u + blockInSectionPos.y * 18u * 18u;
    uint uintOffset = bitOffset >> 5u;
    uint bitInWordOffset = bitOffset & 31u;
    uint word = vs_indexLight(sectionOffset + VS_SOLID_START_INTS + uintOffset);
    return (word & (1u << bitInWordOffset)) != 0u;
}

uint vs_fetchSolid3x3x3(uint sectionOffset, ivec3 blockInSectionPos) {
    uint ret = 0u;
    #define VS_FETCH_SOLID(x, y, z, i) { \
        bool flag = vs_isSolid(sectionOffset, uvec3(blockInSectionPos + ivec3(x, y, z))); \
        ret |= uint(flag) << uint(i); \
    }
    VS_FETCH_SOLID(-1, -1, -1, 0)  VS_FETCH_SOLID(0, -1, -1, 1)  VS_FETCH_SOLID(1, -1, -1, 2)
    VS_FETCH_SOLID(-1, -1,  0, 3)  VS_FETCH_SOLID(0, -1,  0, 4)  VS_FETCH_SOLID(1, -1,  0, 5)
    VS_FETCH_SOLID(-1, -1,  1, 6)  VS_FETCH_SOLID(0, -1,  1, 7)  VS_FETCH_SOLID(1, -1,  1, 8)
    VS_FETCH_SOLID(-1,  0, -1, 9)  VS_FETCH_SOLID(0,  0, -1,10)  VS_FETCH_SOLID(1,  0, -1,11)
    VS_FETCH_SOLID(-1,  0,  0,12)  VS_FETCH_SOLID(0,  0,  0,13)  VS_FETCH_SOLID(1,  0,  0,14)
    VS_FETCH_SOLID(-1,  0,  1,15)  VS_FETCH_SOLID(0,  0,  1,16)  VS_FETCH_SOLID(1,  0,  1,17)
    VS_FETCH_SOLID(-1,  1, -1,18)  VS_FETCH_SOLID(0,  1, -1,19)  VS_FETCH_SOLID(1,  1, -1,20)
    VS_FETCH_SOLID(-1,  1,  0,21)  VS_FETCH_SOLID(0,  1,  0,22)  VS_FETCH_SOLID(1,  1,  0,23)
    VS_FETCH_SOLID(-1,  1,  1,24)  VS_FETCH_SOLID(0,  1,  1,25)  VS_FETCH_SOLID(1,  1,  1,26)
    return ret;
}

uint[27] vs_fetchLight3x3x3(uint sectionOffset, ivec3 blockInSectionPos, uint solidMask) {
    uint[27] lights;
    #define VS_FETCH_LIGHT(_x, _y, _z, i) { \
        uvec2 light = vs_lightAt(sectionOffset, uvec3(blockInSectionPos + ivec3(_x, _y, _z))); \
        lights[i] = (light.x) | ((light.y) << 10u) | (uint((solidMask & (1u << uint(i))) == 0u) << 20u); \
    }
    VS_FETCH_LIGHT(-1, -1, -1, 0)  VS_FETCH_LIGHT(0, -1, -1, 1)  VS_FETCH_LIGHT(1, -1, -1, 2)
    VS_FETCH_LIGHT(-1, -1,  0, 3)  VS_FETCH_LIGHT(0, -1,  0, 4)  VS_FETCH_LIGHT(1, -1,  0, 5)
    VS_FETCH_LIGHT(-1, -1,  1, 6)  VS_FETCH_LIGHT(0, -1,  1, 7)  VS_FETCH_LIGHT(1, -1,  1, 8)
    VS_FETCH_LIGHT(-1,  0, -1, 9)  VS_FETCH_LIGHT(0,  0, -1,10)  VS_FETCH_LIGHT(1,  0, -1,11)
    VS_FETCH_LIGHT(-1,  0,  0,12)  VS_FETCH_LIGHT(0,  0,  0,13)  VS_FETCH_LIGHT(1,  0,  0,14)
    VS_FETCH_LIGHT(-1,  0,  1,15)  VS_FETCH_LIGHT(0,  0,  1,16)  VS_FETCH_LIGHT(1,  0,  1,17)
    VS_FETCH_LIGHT(-1,  1, -1,18)  VS_FETCH_LIGHT(0,  1, -1,19)  VS_FETCH_LIGHT(1,  1, -1,20)
    VS_FETCH_LIGHT(-1,  1,  0,21)  VS_FETCH_LIGHT(0,  1,  0,22)  VS_FETCH_LIGHT(1,  1,  0,23)
    VS_FETCH_LIGHT(-1,  1,  1,24)  VS_FETCH_LIGHT(0,  1,  1,25)  VS_FETCH_LIGHT(1,  1,  1,26)
    return lights;
}

#define vs_index3x3x3(x, y, z) ((x) + (z) * 3u + (y) * 9u)
#define vs_validCountToAo(validCount) (1.0 - (4.0 - (validCount)) * 0.2)

vec3 vs_lightForDirection(uint[27] lights, vec3 interpolant,
                          uint c00, uint c01, uint c10, uint c11,
                          uint oppositeMask) {
    uint[8] summed;
    #define VS_SUM_CORNER(_x, _y, _z, i) { \
        uint corner = vs_index3x3x3(_x, _y, _z); \
        summed[i] = lights[c00 + corner] + lights[c01 + corner] + lights[c10 + corner] + lights[c11 + corner]; \
    }
    VS_SUM_CORNER(0u, 0u, 0u, 0)
    VS_SUM_CORNER(1u, 0u, 0u, 1)
    VS_SUM_CORNER(0u, 0u, 1u, 2)
    VS_SUM_CORNER(1u, 0u, 1u, 3)
    VS_SUM_CORNER(0u, 1u, 0u, 4)
    VS_SUM_CORNER(1u, 1u, 0u, 5)
    VS_SUM_CORNER(0u, 1u, 1u, 6)
    VS_SUM_CORNER(1u, 1u, 1u, 7)

    vec3[8] adjusted;
    // Inner-face correction: if a corner has zero valid blocks, pull from the
    // opposite corner via the bit-flip given by oppositeMask. uint() casts so
    // the ternary branches are both uint (strict GLSL refuses int^uint).
    #define VS_CORNER_INDEX(i) ((summed[uint(i)] & VS_UPPER_10_BITS) == 0u ? uint(i) ^ oppositeMask : uint(i))

    const float[5] normalizers = float[](0.0, 1.0, 1.0/2.0, 1.0/3.0, 1.0/4.0);

    #define VS_ADJUST_CORNER(i) { \
        uint corner = summed[VS_CORNER_INDEX(i)]; \
        uint validCount = corner >> 20u; \
        adjusted[i].xy = vec2(corner & VS_LOWER_10_BITS, (corner >> 10u) & VS_LOWER_10_BITS) * normalizers[validCount]; \
        adjusted[i].z = float(validCount); \
    }
    VS_ADJUST_CORNER(0) VS_ADJUST_CORNER(1) VS_ADJUST_CORNER(2) VS_ADJUST_CORNER(3)
    VS_ADJUST_CORNER(4) VS_ADJUST_CORNER(5) VS_ADJUST_CORNER(6) VS_ADJUST_CORNER(7)

    vec3 light00 = mix(adjusted[0], adjusted[1], interpolant.x);
    vec3 light01 = mix(adjusted[2], adjusted[3], interpolant.x);
    vec3 light10 = mix(adjusted[4], adjusted[5], interpolant.x);
    vec3 light11 = mix(adjusted[6], adjusted[7], interpolant.x);
    vec3 light0 = mix(light00, light01, interpolant.z);
    vec3 light1 = mix(light10, light11, interpolant.z);
    vec3 light = mix(light0, light1, interpolant.y);

    light.xy = clamp(light.xy * VS_LIGHT_NORMALIZER, VS_UV_MIN, VS_UV_MAX);
    light.z = vs_validCountToAo(light.z);
    return light;
}

struct VsLightAo {
    vec2 light;
    float ao;
};

// Single-block world-light lookup at worldPos. Used as a fallback so we can
// still get the correct sky-light (e.g. 0 in a cave) when the smooth lookup
// can't run — without this, callers fall back to the shipyard's baked
// sky-light, which is ~max because the shipyard is an open-sky void.
bool vs_lightFlat(vec3 worldPos, out vec2 light) {
    ivec3 blockPos = ivec3(floor(worldPos));
    uint sectionIndex;
    if (vs_chunkCoordToSectionIndex(blockPos >> 4, sectionIndex)) {
        return false;
    }
    uint sectionOffset = sectionIndex * VS_SECTION_SIZE_INTS;
    ivec3 blockInSectionPos = (blockPos & 0xF) + 1;
    uvec2 raw = vs_lightAt(sectionOffset, uvec3(blockInSectionPos));
    light = clamp(vec2(raw) * VS_LIGHT_NORMALIZER, VS_UV_MIN, VS_UV_MAX);
    return true;
}

bool vs_lightSmooth(vec3 worldPos, vec3 normal, out VsLightAo lightAoOut) {
    ivec3 blockPos = ivec3(floor(worldPos));
    uint lightSectionIndex;
    if (vs_chunkCoordToSectionIndex(blockPos >> 4, lightSectionIndex)) {
        return false;
    }
    uint sectionOffset = lightSectionIndex * VS_SECTION_SIZE_INTS;
    ivec3 blockInSectionPos = (blockPos & 0xF) + 1;

    uint solid = vs_fetchSolid3x3x3(sectionOffset, blockInSectionPos);
    if (solid == VS_COMPLETELY_SOLID) {
        lightAoOut.light = vec2(VS_UV_MIN);
        lightAoOut.ao = vs_validCountToAo(0.0);
        return true;
    }
    uint[27] lights = vs_fetchLight3x3x3(sectionOffset, blockInSectionPos, solid);
    vec3 interpolant = fract(worldPos);

    vec3 lightX;
    if (normal.x > VS_EPSILON) {
        lightX = vs_lightForDirection(lights, interpolant,
            vs_index3x3x3(1u, 0u, 0u), vs_index3x3x3(1u, 0u, 1u),
            vs_index3x3x3(1u, 1u, 0u), vs_index3x3x3(1u, 1u, 1u), 1u);
    } else if (normal.x < -VS_EPSILON) {
        lightX = vs_lightForDirection(lights, interpolant,
            vs_index3x3x3(0u, 0u, 0u), vs_index3x3x3(0u, 0u, 1u),
            vs_index3x3x3(0u, 1u, 0u), vs_index3x3x3(0u, 1u, 1u), 1u);
    } else {
        lightX = vec3(0.0);
    }

    vec3 lightZ;
    if (normal.z > VS_EPSILON) {
        lightZ = vs_lightForDirection(lights, interpolant,
            vs_index3x3x3(0u, 0u, 1u), vs_index3x3x3(0u, 1u, 1u),
            vs_index3x3x3(1u, 0u, 1u), vs_index3x3x3(1u, 1u, 1u), 2u);
    } else if (normal.z < -VS_EPSILON) {
        lightZ = vs_lightForDirection(lights, interpolant,
            vs_index3x3x3(0u, 0u, 0u), vs_index3x3x3(0u, 1u, 0u),
            vs_index3x3x3(1u, 0u, 0u), vs_index3x3x3(1u, 1u, 0u), 2u);
    } else {
        lightZ = vec3(0.0);
    }

    vec3 lightY;
    if (normal.y > VS_EPSILON) {
        lightY = vs_lightForDirection(lights, interpolant,
            vs_index3x3x3(0u, 1u, 0u), vs_index3x3x3(0u, 1u, 1u),
            vs_index3x3x3(1u, 1u, 0u), vs_index3x3x3(1u, 1u, 1u), 4u);
    } else if (normal.y < -VS_EPSILON) {
        lightY = vs_lightForDirection(lights, interpolant,
            vs_index3x3x3(0u, 0u, 0u), vs_index3x3x3(0u, 0u, 1u),
            vs_index3x3x3(1u, 0u, 0u), vs_index3x3x3(1u, 0u, 1u), 4u);
    } else {
        lightY = vec3(0.0);
    }

    vec3 n2 = normal * normal;
    vec3 lightAo = lightX * n2.x + lightY * n2.y + lightZ * n2.z;
    lightAoOut.light = lightAo.xy;
    lightAoOut.ao = lightAo.z;
    return true;
}

#endif // VS_DYNAMIC_LIGHT

#ifdef VS_SHIP_ON_SHIP
// ===== Ship-on-ship: distance-attenuated emitter list ====================
// Loop bounds for the per-fragment scans. Should be ≤ the corresponding
// MAX_* constants in the Java lists; 128 covers most real ship setups.
const int VS_SOS_EMITTER_LOOP_CAP = 128;

// Inverse-rotate v by quaternion q (apply q^-1 = (-q.xyz, q.w) to v). Used
// by vs_sosEmitterLight to express world-frame offsets in the emitter's
// owning-ship local frame.
vec3 vs_sosQuatRotateInv(vec4 q, vec3 v) {
    vec3 qNeg = -q.xyz;
    return v + 2.0 * cross(qNeg, cross(qNeg, v) + q.w * v);
}

// Max distance-attenuated contribution from any ship emitter (incl. own
// ship) at this fragment's world position. Manhattan falloff is taken in
// the emitter's owning-ship frame so the octahedral light bubble rotates
// with the hull.
float vs_sosEmitterLight(vec3 worldPos) {
    float maxLight = 0.0;
    int n = min(u_VsShipEmitterCount, VS_SOS_EMITTER_LOOP_CAP);
    for (int i = 0; i < n; i++) {
        vec4 e = texelFetch(u_VsShipEmitters, i * 2);
        vec4 q = texelFetch(u_VsShipEmitters, i * 2 + 1);
        vec3 offset_ship = vs_sosQuatRotateInv(q, worldPos - e.xyz);
        float dist = abs(offset_ship.x) + abs(offset_ship.y) + abs(offset_ship.z);
        float light = max(0.0, e.w - dist);
        maxLight = max(maxLight, light);
    }
    return maxLight;
}

// ===== Ship-on-ship AO (PER-FRAGMENT, occluder-lattice, cross-ship merge) ==========
// Vanilla AO evaluated in occluder-SHIP lattices, sliced by this face, per
// fragment -- with CROSS-SHIP MERGING. One gather pass collects the voxels
// within VS_SEAM_R_GATHER of the fragment (grouped by ship: the buffer is
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
//    (vs_seamInterp). Hosts SUM (vanilla sums per-sample losses), clamped to
//    the 0.2-multiplier floor.
const int VS_SEAM_OCCLUDER_LOOP_CAP = 256;
// Darkening per solid sample: vanilla getShadeBrightness() is 0.2 for a solid
// block, and each solid sample lowers the 4-sample vertex average by 0.2.
const float VS_SEAM_STRENGTH = 0.2;
// Vanilla's AO floor is a 0.2 multiplier: at most 0.8 of the light lost.
const float VS_SEAM_MAX_TOTAL = 0.8;
// Gather radius around the fragment. The ALIGNED field's support ends at 2.6
// blocks; misaligned tent bleed reaches farther with tiny values and is cut
// by the support fade below, which hits 0 before the gather boundary so
// nothing pops when a voxel crosses it.
const float VS_SEAM_R_GATHER = 3.5;
const float VS_SEAM_FADE_LO = 2.8;
const float VS_SEAM_FADE_HI = 3.4;
// Merge gate on the voxel-pair distance: 1 below G_FULL so every strongly
// interacting pair merges with FULL claims (partial claims skew the per-host
// corner proportions, the crease flips disagree between hosts, and the
// aligned-limit exactness breaks; the 1-block-gap pair is 2.0 apart).
const float VS_SEAM_G_FULL = 2.2;
const float VS_SEAM_G_ZERO = 3.0;
// Fixed-size gather storage. 18 voxels can matter at once (3x3 window x 2
// bands); RUNS caps how many distinct ships merge at one fragment.
const int VS_SEAM_MAX_GATHER = 20;
const int VS_SEAM_MAX_RUNS = 4;
// DEBUG: world-space radius of the dot drawn at each sampled lattice corner.
const float VS_DBG_VERTEX_RADIUS = 0.06;

vec3 vs_seamQuatRotate(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

// Vanilla-style interpolation of the 4 corner losses across the face:
// linear over the quad's two triangles. Sodium picks the split diagonal
// (ModelQuadOrientation.orientByBrightness, NORMAL iff br[0]+br[2] >
// br[1]+br[3]) so the crease runs through the opposite corner pair with the
// greater brightness -- in loss terms the SMALLER loss sum. The two splits
// coincide identically when the sums tie, so the flip is continuous.
// c = (L00, L10, L01, L11).
float vs_seamInterp(vec4 c, vec2 uv) {
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

float vs_seamAoFrag(vec3 fragShipyardPos, vec3 fragWorldPos, vec3 shipyardNormal,
                    mat3 selfRot, int selfShipIndex, out float dbgVertex) {
    dbgVertex = 0.0;
    vec3 worldNA = normalize(selfRot * shipyardNormal);
    int n = min(u_VsShipOccluderCount, VS_SEAM_OCCLUDER_LOOP_CAP);

    // ---- gather: nearby voxels, grouped into per-ship runs ----------------
    vec4 runQ[VS_SEAM_MAX_RUNS];
    vec3 runAnchor[VS_SEAM_MAX_RUNS];
    int runShip[VS_SEAM_MAX_RUNS];
    int runCount = 0;
    vec3 voxPos[VS_SEAM_MAX_GATHER];
    int voxRun[VS_SEAM_MAX_GATHER];
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
        if (distance(vox.xyz, fragWorldPos) > VS_SEAM_R_GATHER) continue;
        if (lastSlot < 0) {
            if (runCount == VS_SEAM_MAX_RUNS) continue;
            lastSlot = runCount++;
            runQ[lastSlot] = texelFetch(u_VsShipOccluders, i * 2 + 1);
            runAnchor[lastSlot] = vox.xyz;
            runShip[lastSlot] = shipIdx;
        }
        if (voxCount == VS_SEAM_MAX_GATHER) continue;
        voxPos[voxCount] = vox.xyz;
        voxRun[voxCount] = lastSlot;
        voxCount++;
    }
    if (runCount == 0) return 0.0;

    // support fade by fragment distance
    float fadeV[VS_SEAM_MAX_GATHER];
    for (int vi = 0; vi < voxCount; vi++)
        fadeV[vi] = clamp((VS_SEAM_FADE_HI - distance(voxPos[vi], fragWorldPos))
                / (VS_SEAM_FADE_HI - VS_SEAM_FADE_LO), 0.0, 1.0);

    // ---- responsibility r per voxel: 1 / (1 + sum of other ships' claims) -
    float rv[VS_SEAM_MAX_GATHER];
    for (int vi = 0; vi < voxCount; vi++) {
        float csum = 0.0;
        for (int si = 0; si < runCount; si++) {
            if (si == voxRun[vi]) continue;
            float gf = 0.0;
            for (int wi = 0; wi < voxCount; wi++) {
                if (voxRun[wi] != si) continue;
                float g = clamp((VS_SEAM_G_ZERO - distance(voxPos[vi], voxPos[wi]))
                        / (VS_SEAM_G_ZERO - VS_SEAM_G_FULL), 0.0, 1.0);
                gf = max(gf, g * fadeV[wi]);
            }
            if (gf > 0.0) {
                vec3 c = vs_sosQuatRotateInv(runQ[si], voxPos[vi] - runAnchor[si]) + 0.5;
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
        vec3 fragL = vs_sosQuatRotateInv(q, fragWorldPos - runAnchor[si]) + 0.5;
        vec3 nL = vs_sosQuatRotateInv(q, worldNA);

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
                    float g = clamp((VS_SEAM_G_ZERO - distance(voxPos[vi], voxPos[wi]))
                            / (VS_SEAM_G_ZERO - VS_SEAM_G_FULL), 0.0, 1.0);
                    gf = max(gf, g * fadeV[wi]);
                }
                if (gf <= 0.0) continue;
                vec3 cc = vs_sosQuatRotateInv(q, voxPos[vi] - runAnchor[si]) + 0.5;
                vec3 fr = abs(fract(cc) - 0.5);
                wgt = rv[vi] * gf * fadeV[vi]
                        * (1.0 - fr.x) * (1.0 - fr.y) * (1.0 - fr.z);
            }
            if (wgt <= 0.0) continue;
            vec3 c = vs_sosQuatRotateInv(q, voxPos[vi] - runAnchor[si]) + 0.5;
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
                corner[ci] += VS_SEAM_STRENGTH * (qA + qB + qC + qD + bonus) * w;
            }
        }
        total += vs_seamInterp(corner, uv);
#ifdef VS_DEBUG_SEAM_AO
        // dots at the 4 sampled lattice corners of this host on the face
        if (any(greaterThan(corner, vec4(0.001)))) {
            for (int ci = 0; ci < 4; ci++) {
                vec3 lp;
                lp[a] = pa;
                lp[u] = float(cu + (ci & 1));
                lp[v] = float(cv + (ci >> 1));
                vec3 wp = runAnchor[si] + vs_seamQuatRotate(q, lp - 0.5);
                if (distance(fragWorldPos, wp) < VS_DBG_VERTEX_RADIUS) dbgVertex = 1.0;
            }
        }
#endif
    }
    return min(total, VS_SEAM_MAX_TOTAL);
}

#endif // VS_SHIP_ON_SHIP

// (No biome helpers in the FSH — biome lookup happens per-vertex in the VSH
// and arrives via the v_VertexBiomeTint varying. The FSH just multiplies it.)

void main() {
    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) {
        discard;
    }
#endif

#ifdef USE_VANILLA_COLOR_FORMAT
    diffuseColor *= v_Color;
#else
#if defined(VS_DYNAMIC_LIGHT) || defined(VS_DYNAMIC_SHADE)
    // World-space normal: the VSH packs the per-quad face direction into the
    // alpha byte, decodes it to a shipyard-space normal, and transforms by
    // u_TransformMatrix. v_WorldNormal is `flat`-interpolated so all 4 quad
    // vertices contribute the same value — exact for a flat quad and free of
    // the dFdx/dFdy precision artifacts on small triangles.
    vec3 worldN = v_WorldNormal;
#endif

    // Fullbright / emissive: the mesher mixin checks BakedQuad.getVertices()
    // at the LIGHT_INDEX offset and packs the FULLBRIGHT face slot if non-zero
    // (i.e. the source model JSON tagged the quad emissive). v_IsFullbright is
    // the decoded flag — no fragment-time heuristic.
    bool isFullbright = v_IsFullbright != 0;
    bool isShade = v_IsShaded != 0;

    // DEBUG: >0 when this fragment sits on a seam-square vertex (see
    // VS_DEBUG_SEAM_AO). Stays 0 unless the ship-on-ship seam pass runs below.
    float dbgSeamVertex = 0.0;

    vec2 lightCoord;
    float aoMultiplier;
#ifdef VS_DYNAMIC_LIGHT
    // Absolute world position of the fragment (camera-relative + integer origin).
    vec3 worldPos = v_CameraRelWorldPos + vec3(u_VsRenderOrigin);
    // Default-init so the compiler can prove the values are defined when we
    // skip the smooth-fetch branch (vs_lightSmooth doesn't write its out param
    // on early-return paths).
    VsLightAo vsLight;
    vsLight.light = vec2(0.0);
    vsLight.ao = 1.0;
    if (isFullbright) {
        // Skip the world-light lookup entirely; fullbright = max lightmap, no
        // AO, no directional shade.
        lightCoord = vec2(VS_UV_MAX);
        aoMultiplier = 1.0;
    } else if (vs_lightSmooth(worldPos, worldN, vsLight)) {
        // World-space lighting at the ship's rendered location. vsLight.ao
        // (the OLD bilinear-quad AO from the 27-cell solid mask) is
        // ignored.
        // Block-light: max with baked so ship-internal torches still glow
        //   (they live in shipyard, the world engine doesn't see them here).
        // Sky-light: take from world (baked sky is from shipyard, irrelevant
        //   to the ship's actual location).
        lightCoord = vec2(
            max(vsLight.light.x, v_BakedLightCoord.x),
            vsLight.light.y
        );
        // Ship surfaces use sodium's vanilla baked AO directly.
        aoMultiplier = v_Color.a;
    } else {
        // Fallback when the smooth lookup misses (section not tracked yet).
        vec2 flatLight;
        if (vs_lightFlat(worldPos, flatLight)) {
            lightCoord = vec2(
                max(flatLight.x, v_BakedLightCoord.x),
                flatLight.y
            );
        } else {
            lightCoord = clamp(v_BakedLightCoord, VS_UV_MIN, VS_UV_MAX);
        }
        aoMultiplier = v_Color.a;
    }
#else
    // Dynamic lighting disabled: just use the shipyard-baked lightmap. Caves
    // and torches in the world will not affect the ship; the shipyard's open-
    // sky bake means everything looks brightly lit even underground.
    if (isFullbright) {
        lightCoord = vec2(VS_UV_MAX);
        aoMultiplier = 1.0;
    } else {
        lightCoord = clamp(v_BakedLightCoord, VS_UV_MIN, VS_UV_MAX);
        aoMultiplier = v_Color.a;
    }
#endif

    // BlockColors-baked vertex tint multiplied by the rasterizer-blended
    // world-biome tint sampled per-vertex in the VSH. v_VertexBiomeTint is
    // vec3(1.0) for non-biome quads, so this is a no-op there. For biome-
    // tinted quads the mesher mixin already white-d out v_Color.rgb so the
    // multiply yields just the world-biome color smoothly blended across the
    // quad's vertices.
    vec3 vertTint = v_Color.rgb * v_VertexBiomeTint;

#ifdef VS_SHIP_ON_SHIP
    // Ship-fragment lighting from ship emitters, plus per-fragment ship-on-ship
    // AO seam matching (nearby, possibly differently-rotated ship voxels
    // darkening this face across a seam).
    if (!isFullbright) {
        vec3 sosWorldPos = v_CameraRelWorldPos + vec3(u_VsRenderOrigin);
        float sosLight = vs_sosEmitterLight(sosWorldPos);
        if (sosLight > 0.0) {
            lightCoord.x = max(lightCoord.x, (sosLight + 0.5) / 16.0);
        }
        if (isShade) {
            float seamVertex = 0.0;
            float seamLoss = vs_seamAoFrag(v_ShipyardPos, sosWorldPos, v_ShipyardNormal,
                mat3(u_TransformMatrix), u_VsCurrentShipIndex, seamVertex);
            aoMultiplier = max(0.2, aoMultiplier - seamLoss);
            dbgSeamVertex = seamVertex;
        }
    }
#endif

    vec4 lightSample = texture(u_LightTex, lightCoord);
    diffuseColor.rgb *= vertTint * lightSample.rgb;

#ifdef VS_DYNAMIC_SHADE
    // Directional shade (vanilla "side darkening"): applied only when the quad
    // opted in (BakedQuad.isShade()) AND we didn't detect fullbright above.
    // aoMultiplier is already 1.0 for fullbright so the else-branch is a no-op
    // there too. Disabled: just AO-darken; ship sides won't have the vanilla
    // direction-based brightness drop.
    if (isShade && !isFullbright) {
        diffuseColor.rgb *= vanillaShadeFromNormal(worldN) * aoMultiplier;
    } else {
        diffuseColor.rgb *= aoMultiplier;
    }
#else
    diffuseColor.rgb *= aoMultiplier;
#endif

#if defined(VS_SHIP_ON_SHIP) && defined(VS_DEBUG_SEAM_AO)
    // BLUE dot = this fragment sits on a seam-square vertex (for manual
    // checking). RED elsewhere = the AO loss applied. Keep a tiny fraction of
    // the real shaded color so every uniform that feeds it (u_LightTex,
    // u_VsBiomeSections, the occluder buffer, …) stays referenced — otherwise
    // the driver dead-strips them and sodium's bindUniform NPEs at link time.
    float dbgLoss = clamp((1.0 - aoMultiplier) * 1.25, 0.0, 1.0);
    vec3 dbgCol = vec3(dbgLoss, 0.0, 0.0);
    if (dbgSeamVertex > 3.5)      dbgCol = vec3(1.0, 0.4, 0.7); // pink   = half-step point
    else if (dbgSeamVertex > 2.5) dbgCol = vec3(0.0, 1.0, 0.0); // green  = subtended line
    else if (dbgSeamVertex > 1.5) dbgCol = vec3(1.0, 0.5, 0.0); // orange = subtended ship square
    else if (dbgSeamVertex > 0.5) dbgCol = vec3(0.0, 0.0, 1.0); // blue   = self vertex
    diffuseColor.rgb = dbgCol + diffuseColor.rgb * 1e-3;
#endif
#endif

    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
}
