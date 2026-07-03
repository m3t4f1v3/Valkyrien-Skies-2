#version 330 core
#define USE_FRAGMENT_DISCARD
#define VS_DYNAMIC_LIGHT
#define VS_DYNAMIC_BIOME
#define VS_DYNAMIC_SHADE
#define VS_SHIP_ON_SHIP


// --- stub for sodium:include/fog.glsl ---
vec4 _linearFog(vec4 color, float dist, vec4 fogColor, float fogStart, float fogEnd) {
    float f = clamp((fogEnd - dist) / max(fogEnd - fogStart, 1e-5), 0.0, 1.0);
    return vec4(mix(fogColor.rgb, color.rgb, f), color.a);
}
// --- end stub ---


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
    #define VS_FETCH_SOLID(x, y, z, i) {          bool flag = vs_isSolid(sectionOffset, uvec3(blockInSectionPos + ivec3(x, y, z)));          ret |= uint(flag) << uint(i);      }
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
    #define VS_FETCH_LIGHT(_x, _y, _z, i) {          uvec2 light = vs_lightAt(sectionOffset, uvec3(blockInSectionPos + ivec3(_x, _y, _z)));          lights[i] = (light.x) | ((light.y) << 10u) | (uint((solidMask & (1u << uint(i))) == 0u) << 20u);      }
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
    #define VS_SUM_CORNER(_x, _y, _z, i) {          uint corner = vs_index3x3x3(_x, _y, _z);          summed[i] = lights[c00 + corner] + lights[c01 + corner] + lights[c10 + corner] + lights[c11 + corner];      }
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

    #define VS_ADJUST_CORNER(i) {          uint corner = summed[VS_CORNER_INDEX(i)];          uint validCount = corner >> 20u;          adjusted[i].xy = vec2(corner & VS_LOWER_10_BITS, (corner >> 10u) & VS_LOWER_10_BITS) * normalizers[validCount];          adjusted[i].z = float(validCount);      }
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

// ===== Ship-on-ship AO seam matching (PER-FRAGMENT) ======================
// Same seam algorithm as the world FSH (ws_seamAoFrag), evaluated per-fragment
// rather than baked at vertices. The one difference from the world path: the
// block face this fragment sits on is axis-aligned in SHIPYARD space, not
// world space, so "Square A" is rebuilt from the interpolated shipyard-local
// fragment position (floor() finds the voxel there) and then lifted to world
// space with selfRot = mat3(u_TransformMatrix) — the "ship mat" the algorithm
// multiplies the cardinal half-steps by.
// Occluders scanned per fragment. The buffer holds up to MAX_OCCLUDERS (1024)
// solid voxels of every nearby ship, so a cap of 64 examined only the first
// 64 — a given face's real neighbor usually sits past that index and was never
// even tested, so it never matched no matter the geometry. (The real fix for
// very dense scenes is a per-fragment spatial cull; until then, scan more.)
const int VS_SEAM_OCCLUDER_LOOP_CAP = 256;
// Falloff reach. Vanilla/sodium smooth AO bilinearly interpolates 4 per-vertex
// values across the face (sodium AoNeighborInfo.calculateCornerWeights), so
// one occluding neighbor produces a gradient spanning EXACTLY one block from
// the shared edge and zero past it. Gaps are bridged by the merge lerp below
// (moving the quad), NOT by widening the falloff. REACH is also the merge-lerp
// range (t = pairDist / REACH): at one block of separation A's corners have
// fully collapsed onto B's face, which is exactly where the field must have
// died for the next cell's from-zero reconstruction to line up (cell-border
// continuity).
const float VS_SEAM_REACH = 1.0;
// How far apart the two squares' nearest corners can be and still count as a
// merge (the seam-match gate). The merge lerp has fully collapsed the quad
// onto the occluder face well before this; the gate just bounds the scan.
const float VS_SEAM_MATCH_REACH = 2.0;
// Darkening at the seam edge. Vanilla samples each neighbor cell as
// getShadeBrightness() — 0.2 for a solid block, 1.0 for air — and averages 4
// samples per vertex (sodium AoFaceData: ao[v] = (e+e+c+ca)*0.25), so ONE
// solid neighbor lowers the vertex multiplier by exactly 0.2.
const float VS_SEAM_STRENGTH = 0.2;
// Vanilla's AO floor is a 0.2 multiplier (all four samples solid), i.e. at
// most 0.8 of the light lost. Occluder contributions SUM (that's what the
// 4-sample average does per extra solid neighbor), then clamp to this.
const float VS_SEAM_MAX_TOTAL = 0.8;
// Coarse prefilter radius; matches VsShipOccluderList.SEAM_CANDIDATE_RADIUS.
// The exact best0 <= REACH test below does the real gating, so keep this
// generous or corner/diagonal neighbors get dropped before they're checked.
const float VS_SEAM_CANDIDATE_RADIUS = 2.5;
// Falloff cutoff: the raw product falloff is remapped so anything below
// CUTOFF becomes 0 and [CUTOFF, 1] rescales to [0, 1] (continuous -- a hard
// step would draw a visible iso-contour ring). 0.0 = identity, the
// vanilla-matched 1-block ramp; kept as a tunable.
const float VS_SEAM_CUTOFF = 0.0;
// DEBUG: world-space radius of the blue dot drawn at each seam-square vertex,
// and half-thickness of the orange outline drawn on the merge quad.
const float VS_DBG_VERTEX_RADIUS = 0.06;
const float VS_DBG_EDGE_RADIUS = 0.02;

vec3 vs_seamQuatRotate(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

// The 4 corners of a unit-cube face centered at c with outward normal nrm.
void vs_seamLocalFace(vec3 c, vec3 nrm, out vec3 c00, out vec3 c01, out vec3 c10, out vec3 c11) {
    vec3 a = abs(nrm);
    vec3 u = a.x > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 v = a.z > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
    c00 = c - u * 0.5 - v * 0.5;
    c01 = c + u * 0.5 - v * 0.5;
    c10 = c - u * 0.5 + v * 0.5;
    c11 = c + u * 0.5 + v * 0.5;
}

// DEBUG: distance from point p to segment ab (for the merge-quad outline).
float vs_distToSeg(vec3 p, vec3 a, vec3 b) {
    vec3 ab = b - a;
    float t = clamp(dot(p - a, ab) / max(dot(ab, ab), 1e-8), 0.0, 1.0);
    return distance(p, a + t * ab);
}

// Product falloff of the box [lo, hi], evaluated at the 4 receiving-face
// corners. The loss field is sampled PER CORNER and interpolated at the
// fragment by vs_seamInterp: evaluating the product directly at the fragment
// is true bilinear (curved hyperbolic iso-contours), whereas the rasterizer
// interpolates vanilla's per-vertex AO linearly over the face's two TRIANGLES
// with a sharp straight crease -- corner sampling reproduces that exactly.
vec4 vs_seamCornerFalloff(vec3 lo, vec3 hi, vec3 aLoc[4]) {
    vec4 r;
    for (int ci = 0; ci < 4; ci++) {
        vec3 ex = max(max(lo - aLoc[ci], aLoc[ci] - hi), vec3(0.0));
        vec3 w = clamp(vec3(1.0) - ex / VS_SEAM_REACH, vec3(0.0), vec3(1.0));
        r[ci] = clamp((w.x * w.y * w.z - VS_SEAM_CUTOFF)
                / (1.0 - VS_SEAM_CUTOFF), 0.0, 1.0);
    }
    return r;
}

// Vanilla-style interpolation of the 4 corner losses across the face:
// linear over the quad's two triangles. Sodium picks the split diagonal
// (ModelQuadOrientation.orientByBrightness, NORMAL iff br[0]+br[2] >
// br[1]+br[3]) so the crease runs through the opposite corner pair with the
// greater brightness -- in loss terms the SMALLER loss sum. The two splits
// coincide identically when the sums tie, so the flip is continuous.
// c = (L00, L10, L01, L11) matching vs_seamLocalFace's output order.
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
    vec3 absN = abs(shipyardNormal);
    vec3 uAxis = absN.x > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 vAxis = absN.z > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);

    // Square A = the unit block face this fragment sits on, built in shipyard
    // space where floor() finds the voxel cell, then lifted to world.
    float uCenter = floor(dot(fragShipyardPos, uAxis)) + 0.5;
    float vCenter = floor(dot(fragShipyardPos, vAxis)) + 0.5;
    float nPlane  = floor(dot(fragShipyardPos, shipyardNormal) + 0.5);
    vec3 faceCenterLocal = shipyardNormal * nPlane + uAxis * uCenter + vAxis * vCenter;
    vec3 faceCenterWorld = fragWorldPos + selfRot * (faceCenterLocal - fragShipyardPos);
    vec3 worldNA = normalize(selfRot * shipyardNormal);

    vec3 a00l, a01l, a10l, a11l;
    vs_seamLocalFace(faceCenterLocal, shipyardNormal, a00l, a01l, a10l, a11l);
    vec3 Acorners[4] = vec3[](
        fragWorldPos + selfRot * (a00l - fragShipyardPos),
        fragWorldPos + selfRot * (a01l - fragShipyardPos),
        fragWorldPos + selfRot * (a10l - fragShipyardPos),
        fragWorldPos + selfRot * (a11l - fragShipyardPos));

    // Loss accumulates PER FACE CORNER (Acorners order: 00,10,01,11) and is
    // interpolated at the fragment with the vanilla two-triangle rule at the
    // end -- per-fragment evaluation of exactly what the rasterizer would do
    // with per-vertex AO, sharp diagonal creases included.
    vec4 cornerLoss = vec4(0.0);
    int n = min(u_VsShipOccluderCount, VS_SEAM_OCCLUDER_LOOP_CAP);
    for (int i = 0; i < n; i++) {
        vec4 voxel = texelFetch(u_VsShipOccluders, i * 2);
        vec4 q     = texelFetch(u_VsShipOccluders, i * 2 + 1);
        // voxel.w = raw packed bits: bits 0-15 dense ship index, bit 16 hint.
        int voxelShipIndex = floatBitsToInt(voxel.w) & 0xFFFF;
        // Skip the ship's OWN voxels — intra-ship self-shadow is already baked
        // into v_Color.a, so counting it here again double-darkens. (This is also
        // the only use of selfShipIndex/u_VsCurrentShipIndex; keep it here so the
        // uniform isn't dead-stripped, which NPEs sodium's bindUniform at link.)
        if (voxelShipIndex == selfShipIndex) continue;
        if (distance(voxel.xyz, faceCenterWorld) > VS_SEAM_CANDIDATE_RADIUS) continue;

        // Vanilla only samples the ONE layer of cells in FRONT of the face
        // plane (AoFaceData offsets by the face direction before sampling):
        // a voxel flush with or behind the plane — e.g. level with a floor
        // block — casts no AO onto it. Gate on the occluder center being in
        // front; the contribution below also ramps over the first quarter
        // block so a rotating ship voxel crossing the plane fades in instead
        // of popping.
        float frontness = dot(worldNA, voxel.xyz - faceCenterWorld);
        if (frontness < 1e-4) continue;

        // This face's square, normal and the fragment lifted into the occluder
        // ship's LOCAL frame: square B's corners are analytic there, the
        // subtend cardinals are the plain <0.5,0>/<0,0.5> ship axes, and the
        // falloff box is axis-aligned. (Distances are rotation-invariant, so
        // matching in local space picks the same pairs as world space.)
        vec3 aLoc[4] = vec3[](
            vs_sosQuatRotateInv(q, Acorners[0] - voxel.xyz),
            vs_sosQuatRotateInv(q, Acorners[1] - voxel.xyz),
            vs_sosQuatRotateInv(q, Acorners[2] - voxel.xyz),
            vs_sosQuatRotateInv(q, Acorners[3] - voxel.xyz));
        vec3 towardSelfLocal = vs_sosQuatRotateInv(q, faceCenterWorld - voxel.xyz);
        vec3 normalALocal = vs_sosQuatRotateInv(q, worldNA);
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
            vs_seamLocalFace(normalB * 0.5, normalB, b00, b01, b10, b11);
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
                faceLoss = VS_SEAM_STRENGTH * frontW
                        * vs_seamCornerFalloff(lo, hi, aLoc);
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
                if (bi1 < 0 || best0 > VS_SEAM_MATCH_REACH) continue;

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
                vec3 m0 = mix(aLoc[bi0], M, clamp(best0 / VS_SEAM_REACH, 0.0, 1.0));
                vec3 m1 = mix(aLoc[bi1], M, clamp(best1 / VS_SEAM_REACH, 0.0, 1.0));

#ifdef VS_DEBUG_SEAM_AO
                // DEBUG: blue = A corners; orange = merge-quad outline (the
                // falloff source); green = the subtended line; pink = M.
                {
                    vec3 wSu = voxel.xyz + vs_seamQuatRotate(q, su);
                    vec3 wSv = voxel.xyz + vs_seamQuatRotate(q, sv);
                    vec3 wM0 = voxel.xyz + vs_seamQuatRotate(q, m0);
                    vec3 wM1 = voxel.xyz + vs_seamQuatRotate(q, m1);
                    float e = min(min(vs_distToSeg(fragWorldPos, wSu, wSv), vs_distToSeg(fragWorldPos, wSv, wM1)),
                                  min(vs_distToSeg(fragWorldPos, wM1, wM0), vs_distToSeg(fragWorldPos, wM0, wSu)));
                    for (int k = 0; k < 4; k++)
                        if (distance(fragWorldPos, Acorners[k]) < VS_DBG_VERTEX_RADIUS) dbgVertex = 1.0;
                    if (e < VS_DBG_EDGE_RADIUS) dbgVertex = 2.0;
                    if (vs_distToSeg(fragWorldPos, wSu, wSv) < VS_DBG_EDGE_RADIUS) dbgVertex = 3.0;
                    if (distance(fragWorldPos, voxel.xyz + vs_seamQuatRotate(q, M)) < VS_DBG_VERTEX_RADIUS) dbgVertex = 4.0;
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
                faceLoss = VS_SEAM_STRENGTH * frontW
                        * vs_seamCornerFalloff(lo, hi, aLoc);
            }
            contrib = max(contrib, faceLoss);
        }
        // Vanilla SUMS per-sample losses (each solid sample subtracts 0.2 in
        // the 4-sample vertex average), so accumulate additively across
        // voxels; the clamp below matches vanilla's 0.2 multiplier floor.
        cornerLoss += contrib;
    }
    cornerLoss = min(cornerLoss, vec4(VS_SEAM_MAX_TOTAL));
    vec2 uv = fract(vec2(dot(fragShipyardPos, uAxis), dot(fragShipyardPos, vAxis)));
    return vs_seamInterp(cornerLoss, uv);
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
