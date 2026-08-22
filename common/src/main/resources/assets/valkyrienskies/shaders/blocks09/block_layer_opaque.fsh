#version 330 core

// VS ship chunk fragment shader, for Sodium 0.9. See ../blocks/block_layer_opaque.fsh for the 0.5 copy;
// the VS lighting, biome and AO logic below is identical, and only the interface with Sodium changed:
//   * fog colour and distances come from the u_Globals block, and _linearFog now takes both distance
//     metrics plus the section fade factor;
//   * the atlas is sampled through Sodium's nearest/RGSS filters rather than a mip-biased texture()
//     call, because _material_mip_bias no longer exists;
//   * the alpha cutoff is unpacked here from the material bits instead of arriving as a varying.

#import <sodium:include/fog.glsl>
#import <sodium:include/globals.glsl>
#import <sodium:include/chunk_material.glsl>

in vec4 v_Color;            // RGB = sodium-baked vertex color; .a = AO (decoded in VSH)
in vec2 v_TexCoord;
in vec2 v_FragDistance;     // cylindrical (.x) and spherical (.y) distance from the camera
in float v_FadeFactor;
in vec2 v_BakedLightCoord;   // _vert_tex_light_coord (baked from shipyard storage)
flat in uint v_Material;
flat in int v_ResolverType;  // 0 none, 1 grass, 2 foliage, 3 water
flat in int v_IsShaded;      // 0 unshaded (skip directional shade), 1 shaded
flat in int v_IsFullbright;  // 1 if the quad was tagged emissive in its source model JSON
in vec3 v_VertexBiomeTint;   // rasterizer-blended world biome RGB, vec3(1.0) on non-biome quads
#if defined(VS_DYNAMIC_LIGHT) || defined(VS_SHIP_ON_SHIP)
in vec3 v_CameraRelWorldPos; // camera-relative WORLD pos; +u_VsRenderOrigin == absolute world pos
#endif
#if defined(VS_DYNAMIC_LIGHT) || defined(VS_DYNAMIC_SHADE) || defined(VS_SHIP_ON_SHIP)
flat in vec3 v_WorldNormal;  // world-space surface normal recovered from face slot in the VSH
#endif
#ifdef VS_SHIP_ON_SHIP
// This fragment's shipyard-local position + shipyard-space face normal. vs_seamAoFrag rebuilds the
// block face this fragment sits on in the shipyard frame, where floor() finds block boundaries, then
// lifts it to world space through the ship rotation.
in vec3 v_ShipyardPos;
flat in vec3 v_ShipyardNormal;
#endif

uniform sampler2D u_BlockTex;
uniform sampler2D u_LightTex;


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
// Manhattan distance is taken in the emitter's owning-ship local frame (offset rotated by q^-1) so the
// octahedral light bubble rotates with the hull.
uniform samplerBuffer u_VsShipEmitters;
uniform int u_VsShipEmitterCount;
// Per-frame solid ship voxel list (same 2-texel layout, position .w = 0). Used for ship-to-ship AO so
// one ship's voxels can cast smooth-tracking octagonal shadows on another ship's surface (and on the
// same ship's own concave faces).
#ifdef VS_SHIP_AO
// Per-frame list of solid ship voxel CENTERS in world space, paired with the
// voxel's owning-ship rotation quaternion (see VsShipOccluderList). Consumed
// PER-FRAGMENT by vs_seamAoFrag below for ship-on-ship AO seam matching.
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
#ifdef VS_SHIP_AO
// Ship-to-world matrix of the ship being rendered; mat3() lifts this quad's shipyard-space half-steps
// and face corners into world space for the seam match. Declared in the FSH as well as the VSH -- a
// uniform is per-program, so both stages read the same location.
uniform mat4 u_TransformMatrix;
#endif // VS_SHIP_AO
#ifdef VS_SHIP_AO
// Dense per-frame index of the ship being rendered; occluder voxels carrying
// this index are skipped (same-ship AO is already baked into v_Color.a, so
// counting it again would double-darken).
uniform int u_VsCurrentShipIndex;
#endif // VS_SHIP_AO

// Inverse-rotate v by quaternion q (apply q^-1 = (-q.xyz, q.w) to v) so the SDF / distance metrics line
// up with the owning ship's axes.
vec3 vs_sosQuatRotateInv(vec4 q, vec3 v) {
    vec3 qNeg = -q.xyz;
    return v + 2.0 * cross(qNeg, cross(qNeg, v) + q.w * v);
}
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

// ===== Atlas sampling ========================================================
// Copied from Sodium 0.9's own chunk FSH so the ship surface filters identically to the world's. 0.5
// took a mip bias per material; 0.9 replaced that with an explicit nearest reconstruction plus optional
// rotated-grid supersampling, both driven by u_TexelSize / u_UseRGSS from the globals block.

vec4 vs_sampleNearest(sampler2D sampler, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;

    texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);

    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(sampler, uv, du, dv);
}

vec4 vs_sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return vs_sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

vec4 vs_sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);

    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

    float minPixelSize = min(pixelSize.x, pixelSize.y);

    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);

    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);

    float effectiveDerivative = sqrt(minDerivative * maxDerivative);

    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );

    vec4 rgssColor = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColor += textureLod(source, sampleUV, mipLevelExact);
    }
    rgssColor *= 0.25;

    vec4 nearestColor = vs_sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);

    return mix(nearestColor, rgssColor, blendFactor);
}

// MC's lightmap texture uses GL's default GL_REPEAT wrap; at UV=0 a LINEAR sample blends pixel 15
// (bright) into pixel 0 (dark), giving the wrong color for sky=0 in caves. Clamp to pixel-center range
// to match sodium's baked vertex format (which clamps the packed light byte to [8, 248] for the same
// reason). Outside the VS_DYNAMIC_LIGHT block because the off-path still needs to clamp
// v_BakedLightCoord with these bounds.
const float VS_UV_MIN = 1.0 / 32.0;
const float VS_UV_MAX = 31.0 / 32.0;

#ifdef VS_DYNAMIC_LIGHT
// ===== Flywheel-style smooth light + AO ======================================
// Layout matches Flywheel's light_lut.glsl: each section is
//   [solid bits (732 B = 183 ints)] [light bytes (5832 B = 1458 ints)]
// for a total of 6564 bytes / 1641 ints per section. Solid bit + light byte at the same in-section
// position N use the same offset formula below.
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
    // Inner-face correction: if a corner has zero valid blocks, pull from the opposite corner via the
    // bit-flip given by oppositeMask. uint() casts so the ternary branches are both uint (strict GLSL
    // refuses int^uint).
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

// Single-block world-light lookup at worldPos. Used as a fallback so we can still get the correct
// sky-light (e.g. 0 in a cave) when the smooth lookup can't run — without this, callers fall back to the
// shipyard's baked sky-light, which is ~max because the shipyard is an open-sky void.
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
// Loop bounds for the per-fragment scans. Should be <= the corresponding MAX_* constants in the Java
// lists; 128 covers most real ship setups.
const int VS_SOS_EMITTER_LOOP_CAP = 128;

// Max distance-attenuated contribution from any ship emitter (incl. own ship) at this fragment's world
// position. Manhattan falloff is taken in the emitter's owning-ship frame so the octahedral light bubble
// rotates with the hull.
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
#ifdef VS_SHIP_AO
// ===== Ship-on-ship AO (PER-FRAGMENT, occluder-lattice, cross-ship merge) ==========
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
//    (vs_seamInterp). Hosts SUM (vanilla sums per-sample losses), clamped to
//    the 0.2-multiplier floor.
const int VS_SEAM_SUBRUN_LOOP_CAP = 32;
const int VS_SEAM_MAX_HOSTS = 4;
// Spatial grid over SUB-RUNS: buffer is GRID_CELLS header texels (offset,count)
// then a flat sub-run-index list packed 4/texel. Must match VsShipOccluderList.
const int VS_SEAM_GRID_DIM = 8;
const int VS_SEAM_GRID_CELLS = 8 * 8 * 8;
// Safety bound on sub-runs examined per cell.
const int VS_SEAM_CELL_LOOP_CAP = 512;
// Darkening per solid sample: vanilla getShadeBrightness() is 0.2 for a solid
// block, and each solid sample lowers the 4-sample vertex average by 0.2.
const float VS_SEAM_STRENGTH = 0.2;
// Vanilla's AO floor is a 0.2 multiplier: at most 0.8 of the light lost.
const float VS_SEAM_MAX_TOTAL = 0.8;
// Finite support for the tent-injected voxel field: a voxel farther than this
// (world distance) from the fragment contributes exactly zero, so it is culled
// with no fade. The tent product (three half-width-1 tents over u/v/normal
// cells) collapses to 0 well before the theoretical 3x2.5-box corner (~4.33),
// so 3.7 is measured drift-free vs the 4.5 the field was originally sized for
// (see claude-scratchpad/probe_support.py). Smaller = fewer voxels survive the
// cull and a tighter grid binning, both per-fragment wins. MUST match
// VsShipOccluderList.SEAM_SUPPORT (grid fattening) and seam5.SUPPORT.
const float VS_SEAM_SUPPORT = 3.7;
// Column alignment of a rotation at 45 deg (min before o_rot hits 0); matches
// seam5.OROT_LO = 1/sqrt(2). Used for the ship->world lattice merge claim.
const float VS_SEAM_OROT_LO = 0.70710678;
// DEBUG: world-space radius of the dot drawn at each sampled lattice corner.
const float VS_DBG_VERTEX_RADIUS = 0.06;

vec3 vs_seamQuatRotate(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

// Inverse-rotation matrix R(q)^T, so Rinv * v == vs_sosQuatRotateInv(q, v).
// Built ONCE per host lattice and reused for the fragment, the normal and every
// stamped voxel: a mat3*vec3 (~15 flops) is roughly half a per-voxel quaternion
// double-cross (~30), and the voxel loop is the shader's hot path.
mat3 vs_seamRotInvMat(vec4 q) {
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

float vs_seamDistSq(vec3 a, vec3 b) {
    vec3 d = a - b;
    return dot(d, d);
}

// How strongly a ship (quat q, world anchor) merges into the WORLD-aligned
// lattice (host 0): the same o_rot x o_trans as the ship-ship claim, taken
// against the identity/world lattice. o_rot = mean per-column max|component|
// of the ship's rotation, remapped from [1/sqrt2, 1] (1 axis-aligned, 0 at
// 45 deg); o_trans = how close the ship's voxel centers sit to world cell
// centers. Rotated or off-grid ships get ~0 (they do NOT smear into the
// world lattice); axis-aligned on-grid ships get ~1 (they coincide with it).
float vs_seamWorldAlign(vec4 q, vec3 anchor) {
    float x = q.x, y = q.y, z = q.z, w = q.w;
    vec3 c0 = vec3(1.0 - 2.0 * (y * y + z * z), 2.0 * (x * y + z * w),       2.0 * (x * z - y * w));
    vec3 c1 = vec3(2.0 * (x * y - z * w),       1.0 - 2.0 * (x * x + z * z), 2.0 * (y * z + x * w));
    vec3 c2 = vec3(2.0 * (x * z + y * w),       2.0 * (y * z - x * w),       1.0 - 2.0 * (x * x + y * y));
    float m = (max(max(abs(c0.x), abs(c0.y)), abs(c0.z))
             + max(max(abs(c1.x), abs(c1.y)), abs(c1.z))
             + max(max(abs(c2.x), abs(c2.y)), abs(c2.z))) / 3.0;
    float oRot = clamp((m - VS_SEAM_OROT_LO) / (1.0 - VS_SEAM_OROT_LO), 0.0, 1.0);
    vec3 f = abs(fract(anchor) - vec3(0.5));
    return oRot * (1.0 - f.x) * (1.0 - f.y) * (1.0 - f.z);
}

float vs_seamClaim(int ownerShip, int hostShip) {
    if (hostShip == 0) {
        if (ownerShip <= 0) return 0.0;
        vec4 q = texelFetch(u_VsSeamShipDir, ownerShip * 6);
        vec3 anchor = texelFetch(u_VsSeamShipDir, ownerShip * 6 + 1).xyz;
        return vs_seamWorldAlign(q, anchor);
    }
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
void vs_seamAddHost(int shipIdx, inout int hostCount,
        inout ivec4 hostShip, inout mat4 hostQ, inout mat4 hostAnchor,
        inout vec4 hostR) {
    if (shipIdx < 0) return;
    if (shipIdx == 0) {
        for (int h = 0; h < VS_SEAM_MAX_HOSTS; h++) {
            if (h >= hostCount) break;
            if (hostShip[h] == 0) return;
        }
        if (hostCount >= VS_SEAM_MAX_HOSTS) return;
        hostShip[hostCount] = 0;
        hostQ[hostCount] = vec4(0.0, 0.0, 0.0, 1.0);
        // World lattice offset half a block so WORLD block cells (and
        // world-aligned ship voxels) land on cell centers, not vertices.
        hostAnchor[hostCount] = vec4(0.5, 0.5, 0.5, 0.0);
        hostR[hostCount] = 0.0;
        hostCount++;
        return;
    }
    for (int h = 0; h < VS_SEAM_MAX_HOSTS; h++) {
        if (h >= hostCount) break;
        if (hostShip[h] == shipIdx) return;
    }
    if (hostCount >= VS_SEAM_MAX_HOSTS) return;

    vec4 ar = texelFetch(u_VsSeamShipDir, shipIdx * 6 + 1);
    hostShip[hostCount] = shipIdx;
    hostQ[hostCount] = texelFetch(u_VsSeamShipDir, shipIdx * 6);
    hostAnchor[hostCount] = vec4(ar.xyz, 0.0);
    hostR[hostCount] = ar.w;               // responsibility r, cached for pass 2
    hostCount++;
}

mat3 vs_seamStamp(vec3 c, int a, int u, int v, int cu, int cv) {
    vec3 uCenter = vec3(float(cu) - 0.5, float(cu) + 0.5, float(cu) + 1.5);
    vec3 vCenter = vec3(float(cv) - 0.5, float(cv) + 0.5, float(cv) + 1.5);
    vec3 tu = max(vec3(0.0), vec3(1.0) - abs(vec3(c[u]) - uCenter));
    vec3 tv = max(vec3(0.0), vec3(1.0) - abs(vec3(c[v]) - vCenter));
    return mat3(tv * tu.x, tv * tu.y, tv * tu.z);
}

mat3 vs_seamClampOcc(mat3 m) {
    m[0] = min(m[0], vec3(1.0));
    m[1] = min(m[1], vec3(1.0));
    m[2] = min(m[2], vec3(1.0));
    return m;
}

float vs_seamCorner(float qA, float qB, float qC, float qD) {
    float bonus = max(0.0, min(qA, qD) - max(qB, qC))
                + max(0.0, min(qB, qC) - max(qA, qD));
    return VS_SEAM_STRENGTH * (qA + qB + qC + qD + bonus);
}

vec4 vs_seamCorners(mat3 occ) {
    return vec4(
        vs_seamCorner(occ[0][0], occ[1][0], occ[0][1], occ[1][1]),
        vs_seamCorner(occ[1][0], occ[2][0], occ[1][1], occ[2][1]),
        vs_seamCorner(occ[0][1], occ[1][1], occ[0][2], occ[1][2]),
        vs_seamCorner(occ[1][1], occ[2][1], occ[1][2], occ[2][2])
    );
}

float vs_seamAoFrag(vec3 fragShipyardPos, vec3 fragWorldPos, vec3 shipyardNormal,
                    mat3 selfRot, int selfShipIndex, out float dbgVertex) {
    dbgVertex = 0.0;
    vec3 worldNA = normalize(selfRot * shipyardNormal);

    if (u_VsShipOccluderCount <= 0 || u_VsSeamRunCount <= 0) return 0.0;
    float globalR = u_VsSeamBounds.w + VS_SEAM_SUPPORT;
    if (vs_seamDistSq(fragWorldPos, u_VsSeamBounds.xyz) > globalR * globalR) return 0.0;

    // ---- pass 1: nearby source runs -> host lattices ----------------------
    mat4 hostQ;
    mat4 hostAnchor;
    ivec4 hostShip = ivec4(0);
    vec4 hostR = vec4(0.0);
    int hostCount = 0;
    hostShip[hostCount] = 0;
    hostQ[hostCount] = vec4(0.0, 0.0, 0.0, 1.0);
    hostAnchor[hostCount] = vec4(0.5, 0.5, 0.5, 0.0);  // world cells centered
    hostCount++;
    // This fragment's grid cell + its sub-run list (offset,count). Each near
    // sub-run's owner ship (and its claim partners) becomes a host; the world
    // host in slot 0 receives ship voxels via claims in pass 2.
    ivec3 gi = ivec3(floor((fragWorldPos - u_VsSeamGridOrigin) * u_VsSeamGridInvCell));
    if (any(lessThan(gi, ivec3(0))) || any(greaterThanEqual(gi, ivec3(VS_SEAM_GRID_DIM)))) return 0.0;
    int cell = (gi.z * VS_SEAM_GRID_DIM + gi.y) * VS_SEAM_GRID_DIM + gi.x;
    ivec2 cellOC = floatBitsToInt(texelFetch(u_VsSeamGrid, cell)).xy;  // (offset, count)

    for (int i = 0; i < VS_SEAM_CELL_LOOP_CAP; i++) {
        if (i >= cellOC.y) break;
        int e = cellOC.x + i;
        int ri = floatBitsToInt(texelFetch(u_VsSeamGrid, VS_SEAM_GRID_CELLS + (e >> 2)))[e & 3];

        // The meta fetch stays BEHIND the distance cull. Issuing it alongside head to break the
        // dependent chain was measured and was 3.4% SLOWER (6.17 -> 6.37 ms/frame, against 0.3%
        // run-to-run variance): most runs in a cell are rejected here, and in pass 2 this loop runs
        // once per host lattice, so the speculative load is paid several times over for each one
        // that is used. Latency-bound does not mean bandwidth is free.
        vec4 head = texelFetch(u_VsSeamRuns, ri * 2);
        float runR = head.w + VS_SEAM_SUPPORT;
        if (vs_seamDistSq(fragWorldPos, head.xyz) > runR * runR) continue;
        int owner = floatBitsToInt(texelFetch(u_VsSeamRuns, ri * 2 + 1).z) & 0xFFFF;
        if (owner <= 0) continue;
        bool known = false;
        for (int o = 0; o < VS_SEAM_MAX_HOSTS; o++) {
            if (o >= hostCount) break;
            if (hostShip[o] == owner) { known = true; break; }
        }
        if (known) continue;

        vs_seamAddHost(owner, hostCount, hostShip, hostQ, hostAnchor, hostR);
#ifndef VS_SEAM_NO_MERGE
        // Partner hosts exist only to receive another ship's material, so with nothing claimed this
        // skips two directory fetches per new host plus up to four more inside the addHost calls.
        vec4 claims = texelFetch(u_VsSeamShipDir, owner * 6 + 2);
        ivec4 partners = floatBitsToInt(texelFetch(u_VsSeamShipDir, owner * 6 + 3));
        if (claims.x > 0.0) vs_seamAddHost(partners.x, hostCount, hostShip, hostQ, hostAnchor, hostR);
        if (claims.y > 0.0) vs_seamAddHost(partners.y, hostCount, hostShip, hostQ, hostAnchor, hostR);
        if (claims.z > 0.0) vs_seamAddHost(partners.z, hostCount, hostShip, hostQ, hostAnchor, hostR);
        if (claims.w > 0.0) vs_seamAddHost(partners.w, hostCount, hostShip, hostQ, hostAnchor, hostR);
#endif
    }
    // Slot 0 (world host) is always present; if no SHIP host was added, no ship
    // voxel is near, so nothing would stamp anywhere -> bail.
    if (hostCount <= 1) return 0.0;

    // Cache claim(owner host -> lattice host) and each host's normalizer so
    // pass 2 needs no ship-directory fetches. The world host (index 0) is a
    // real gated claim (vs_seamWorldAlign), NOT a flat 1.0, and it is folded
    // into each ship's normalizer r_block = r_ship / (1 + r_ship*claimWorld)
    // so a ship's material is CONSERVED across its own lattice, its ship
    // partners AND the world lattice (no double-darkening near terrain /
    // grid-aligned neighbours). Rotated/off-grid ships get claimWorld ~ 0, so
    // r_block ~ r_ship and they never smear into the world lattice.
#ifdef VS_SEAM_NO_MERGE
    // No merging: every lattice keeps its own material and hosts nothing foreign, so the claim
    // matrix is the identity. The world pseudo-host (slot 0) exists ONLY to receive foreign shares,
    // so it has nothing to give and is zeroed -- pass 2's `wBase <= 0` test drops it immediately.
    mat4 hostClaim = mat4(1.0);
    for (int o = 0; o < VS_SEAM_MAX_HOSTS; o++) {
        if (o >= hostCount) break;
        if (hostShip[o] <= 0) hostR[o] = 0.0;
    }
#else
    mat4 hostClaim = mat4(0.0);
    for (int o = 0; o < VS_SEAM_MAX_HOSTS; o++) {
        if (o >= hostCount) break;
        int os = hostShip[o];
        float claimWorld = os > 0 ? vs_seamWorldAlign(hostQ[o], hostAnchor[o].xyz) : 0.0;
        float rShip = hostR[o];   // stored r = 1/(1 + Σ ship claims); world = 0
        hostR[o] = os > 0 ? rShip / (1.0 + rShip * claimWorld) : 0.0;
        vec4 claims = texelFetch(u_VsSeamShipDir, os * 6 + 2);
        ivec4 partners = floatBitsToInt(texelFetch(u_VsSeamShipDir, os * 6 + 3));
        for (int h = 0; h < VS_SEAM_MAX_HOSTS; h++) {
            if (h >= hostCount) break;
            int hs = hostShip[h];
            float c = 0.0;
            if (hs == 0) c = claimWorld;
            else if (os == hs) c = 1.0;
            else if (os > 0) {
                if (partners.x == hs) c = claims.x;
                else if (partners.y == hs) c = claims.y;
                else if (partners.z == hs) c = claims.z;
                else if (partners.w == hs) c = claims.w;
            }
            hostClaim[o][h] = c;
        }
    }
#endif

    // ---- pass 2: one vanilla field per host lattice, summed ---------------
    float total = 0.0;
    for (int hi = 0; hi < VS_SEAM_MAX_HOSTS; hi++) {
        if (hi >= hostCount) break;
        vec4 q = hostQ[hi];
        vec3 anchor = hostAnchor[hi].xyz;
        mat3 Rinv = vs_seamRotInvMat(q);   // Rinv * v == vs_sosQuatRotateInv(q, v)
        // fragment in this lattice, shifted so VERTICES land on integers
        // (voxel centers sit at k + 0.5)
        vec3 fragL = Rinv * (fragWorldPos - anchor) + 0.5;
        vec3 nL = Rinv * worldNA;

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
        for (int i = 0; i < VS_SEAM_CELL_LOOP_CAP; i++) {
            if (i >= cellOC.y) break;
            int e = cellOC.x + i;
            int ri = floatBitsToInt(texelFetch(u_VsSeamGrid, VS_SEAM_GRID_CELLS + (e >> 2)))[e & 3];

            // Kept behind the cull -- see the note in pass 1 on why hoisting it lost time.
            vec4 head = texelFetch(u_VsSeamRuns, ri * 2);
            float runR = head.w + VS_SEAM_SUPPORT;
            if (vs_seamDistSq(fragWorldPos, head.xyz) > runR * runR) continue;
            vec4 meta = texelFetch(u_VsSeamRuns, ri * 2 + 1);
            int owner = floatBitsToInt(meta.z) & 0xFFFF;
#ifdef VS_SEAM_NO_MERGE
            // THE early-out for the unmerged path: a run can only contribute to its own ship's
            // lattice, so a run belonging to any other ship is rejected here on one integer compare
            // -- before the host-slot search, the weight lookup and the whole per-voxel loop below.
            if (owner != hostShip[hi]) continue;
#endif
            if (owner == selfShipIndex) continue;  // baked already
            int os = -1;
            for (int o = 0; o < VS_SEAM_MAX_HOSTS; o++) {
                if (o >= hostCount) break;
                if (hostShip[o] == owner) { os = o; break; }
            }
            float wBase;
            if (os >= 0) {
                wBase = hostR[os] * hostClaim[os][hi];
            } else {
                vec4 oq = texelFetch(u_VsSeamShipDir, owner * 6);
                vec4 oar = texelFetch(u_VsSeamShipDir, owner * 6 + 1);
                float cw = vs_seamWorldAlign(oq, oar.xyz);
                float rBlock = oar.w / (1.0 + oar.w * cw);
                wBase = rBlock * vs_seamClaim(owner, hostShip[hi]);
            }
            if (wBase <= 0.0) continue;

            int start = floatBitsToInt(meta.x);
            int cnt = floatBitsToInt(meta.y);
            for (int k = 0; k < VS_SEAM_SUBRUN_LOOP_CAP; k++) {
                if (k >= cnt) break;
                vec4 vox = texelFetch(u_VsShipOccluders, (start + k) * 2);
                // a voxel past SUPPORT of the fragment stamps exactly 0.
                if (vs_seamDistSq(vox.xyz, fragWorldPos) > VS_SEAM_SUPPORT * VS_SEAM_SUPPORT) continue;
                vec3 c = Rinv * (vox.xyz - anchor) + 0.5;
                mat3 stamp = vs_seamStamp(c, a, u, v, cu, cv);
                // fold the owner->host weight into the two scalar band tents so
                // the mat3 is scaled once per band instead of once for wBase and
                // again for each ta.
                float ta0 = wBase * max(0.0, 1.0 - abs(c[a] - bandC0));
                float ta1 = wBase * max(0.0, 1.0 - abs(c[a] - bandC1));
                occ0 += stamp * ta0;
                occ1 += stamp * ta1;
            }
        }
        occ0 = vs_seamClampOcc(occ0);
        occ1 = vs_seamClampOcc(occ1);

        vec4 corner = vs_seamCorners(occ0) * w0 + vs_seamCorners(occ1) * (1.0 - w0);
        total += vs_seamInterp(corner, uv);
#ifdef VS_DEBUG_SEAM_AO
        // dots at the 4 sampled lattice corners of this host on the face
        if (any(greaterThan(corner, vec4(0.001)))) {
            for (int ci = 0; ci < 4; ci++) {
                vec3 lp;
                lp[a] = pa;
                lp[u] = float(cu + (ci & 1));
                lp[v] = float(cv + (ci >> 1));
                vec3 wp = anchor + vs_seamQuatRotate(q, lp - 0.5);
                if (distance(fragWorldPos, wp) < VS_DBG_VERTEX_RADIUS) dbgVertex = 1.0;
            }
        }
#endif
    }
    return min(total, VS_SEAM_MAX_TOTAL);
}
#endif // VS_SHIP_AO
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
// 0 when the flood could not be produced this frame; the gate then stands down entirely.
uniform int u_VsFloodGridValid;

// Headroom added to the sampled flood before the min(), in light levels. Now that solid taps are
// excluded from the interpolation rather than zero-valued ones, a lit surface reads its true value
// and no headroom is needed; anything above 0 is pure leakage allowance. Kept as a named constant
// because it is the first knob to reach for if surfaces ever read a shade too dark.
// (The gate's slack constant is gone: ship-on-ship light is the flood itself now, not a second field
// that has to be reconciled with it.)

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
    // The whole byte, in 16ths of a level. This is a second copy of the world shader's sampler and it
    // was left masking 4 bits when the pack pass moved to 16ths, so every ship-on-ship flood read was
    // taking the low nibble of a fixed-point value -- near zero for anything close to a whole level.
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

#endif // VS_SHIP_ON_SHIP

// (No biome helpers in the FSH — biome lookup happens per-vertex in the VSH and arrives via the
// v_VertexBiomeTint varying. The FSH just multiplies it.)

void main() {
    vec4 diffuseColor = u_UseRGSS
            ? vs_sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize)
            : vs_sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < _material_alpha_cutoff(v_Material)) {
        discard;
    }
#endif

#if defined(VS_DYNAMIC_LIGHT) || defined(VS_DYNAMIC_SHADE) || defined(VS_SHIP_ON_SHIP)
    // World-space normal: the VSH packs the per-quad face direction into the alpha byte, decodes it to a
    // shipyard-space normal, and transforms by u_TransformMatrix. v_WorldNormal is `flat`-interpolated
    // so all 4 quad vertices contribute the same value — exact for a flat quad and free of the
    // dFdx/dFdy precision artifacts on small triangles.
    vec3 worldN = v_WorldNormal;
#endif

    // Fullbright / emissive: the mesher mixin reads the quad's own emissive flag and packs the
    // FULLBRIGHT face slot. v_IsFullbright is the decoded flag — no fragment-time heuristic.
    bool isFullbright = v_IsFullbright != 0;
    bool isShade = v_IsShaded != 0;

    vec2 lightCoord;
    float aoMultiplier;
#ifdef VS_DYNAMIC_LIGHT
    // Absolute world position of the fragment (camera-relative + integer origin).
    vec3 worldPos = v_CameraRelWorldPos + vec3(u_VsRenderOrigin);
    // Default-init so the compiler can prove the values are defined when we skip the smooth-fetch branch
    // (vs_lightSmooth doesn't write its out param on early-return paths).
    VsLightAo vsLight;
    vsLight.light = vec2(0.0);
    vsLight.ao = 1.0;
    if (isFullbright) {
        // Skip the world-light lookup entirely; fullbright = max lightmap, no AO, no directional shade.
        lightCoord = vec2(VS_UV_MAX);
        aoMultiplier = 1.0;
    } else if (vs_lightSmooth(worldPos, worldN, vsLight)) {
        // World-space lighting + AO at the ship's rendered location.
        // Block-light: max with baked so ship-internal torches still glow (they live in shipyard, the
        //   world engine doesn't see them here).
        // Sky-light: take from world (baked sky is from shipyard, irrelevant to the ship's actual
        //   location).
        // AO: combine world-external AO with the ship-internal AO baked into v_Color.a by the mesher.
        lightCoord = vec2(
            max(vsLight.light.x, v_BakedLightCoord.x),
            vsLight.light.y
        );
        aoMultiplier = vsLight.ao * v_Color.a;
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
    // Dynamic lighting disabled: just use the shipyard-baked lightmap. Caves and torches in the world
    // will not affect the ship; the shipyard's open-sky bake means everything looks brightly lit even
    // underground.
    if (isFullbright) {
        lightCoord = vec2(VS_UV_MAX);
        aoMultiplier = 1.0;
    } else {
        lightCoord = clamp(v_BakedLightCoord, VS_UV_MIN, VS_UV_MAX);
        aoMultiplier = v_Color.a;
    }
#endif

    // BlockColors-baked vertex tint multiplied by the rasterizer-blended world-biome tint sampled
    // per-vertex in the VSH. v_VertexBiomeTint is vec3(1.0) for non-biome quads, so this is a no-op
    // there. For biome-tinted quads the mesher mixin already white-d out v_Color.rgb so the multiply
    // yields just the world-biome color smoothly blended across the quad's vertices.
    vec3 vertTint = v_Color.rgb * v_VertexBiomeTint;

#ifdef VS_SHIP_ON_SHIP
    // Ship-on-ship: the world-from-ship storage holds every ship's voxels projected into world coords
    // (and dilated emitter values). Reading it at this fragment's world block lets nearby ships shadow /
    // illuminate this ship's surface. Skipped for fullbright quads (already at max lightmap) and reuses
    // the same world-position varyings as VS_DYNAMIC_LIGHT.
    float sosSeamLoss = 0.0;
    float dbgSeamVertex = 0.0;
    float vsDbgSosLight = 0.0;
    if (!isFullbright) {
        // Ship emitters anywhere (own ship + other ships). The emitter list stores world-space FLOAT
        // coords, so as a ship slides sub-block the distance from each fragment varies continuously —
        // the lit area tracks ship motion without any block-grid quantization.
        vec3 sosWorldPos = v_CameraRelWorldPos + vec3(u_VsRenderOrigin);
#ifdef VS_FLOOD_GRID
        // Ship light on another ship's surface, straight out of the flood -- the same field, sampled
        // the same way, that lights world terrain.
        //
        // This used to be the per-fragment emitter falloff clamped by the flood, and it inherited every
        // failure of that hybrid: the falloff is Manhattan in the EMITTER's ship frame while the flood
        // counts in world axes, so the clamp cut a rotated ship's light along a straight line, and the
        // falloff itself has no idea a hull is in the way. With the flood carrying 16ths of a level
        // there is nothing left to reconcile: propagation exactly as vanilla does it, occlusion
        // included, sampled with sub-block precision.
        // Sampled half a block OUT along the surface normal, not at the surface itself.
        //
        // The sampler drops taps that the packed SOLID bitmap marks as blocker, and that bitmap is
        // terrain only -- ships contribute coverage instead. So on a ship surface nothing excludes the
        // taps that fall inside the ship's own body, and the value a face reads is a blend of the air
        // in front of it and the interior behind it. The interior part swings as another ship moves
        // past, which is why ship-to-ship light flickered where terrain did not: measured on a dragged
        // ship, single-frame steps of 153/255 on receiver pixels against a still-frame floor of 1.
        // Offsetting by half a block puts all eight taps in the air the light is actually in.
        float sosFlood = vsf_floodTrilinear(sosWorldPos + worldN * 0.5);
        float sosLight = u_VsFloodGridValid != 0 ? max(sosFlood, 0.0) : 0.0;
#else
        // No compute support: fall back to the unoccluded emitter falloff, as this path always was.
        float sosLight = vs_sosEmitterLight(sosWorldPos);
#endif
        vsDbgSosLight = sosLight;
        if (sosLight > 0.0) {
            lightCoord.x = max(lightCoord.x, (sosLight + 0.5) / 16.0);
        }

        // Ship-to-ship AO seam matching: nearby, possibly differently-rotated ship voxels darkening
        // this face across a seam. Evaluated in each occluder ship's own lattice and merged, so the
        // shadow tracks each hull's rotation independently instead of drifting on the world grid.
#ifdef VS_SHIP_AO
        if (isShade) {
            float seamVertex = 0.0;
            float seamLoss = vs_seamAoFrag(v_ShipyardPos, sosWorldPos, v_ShipyardNormal,
                mat3(u_TransformMatrix), u_VsCurrentShipIndex, seamVertex);
            sosSeamLoss = seamLoss;
            dbgSeamVertex = seamVertex;
        }
#endif // VS_SHIP_AO
    }
#endif

    vec4 lightSample = texture(u_LightTex, lightCoord);
    diffuseColor.rgb *= vertTint * lightSample.rgb;

#ifdef VS_SHIP_ON_SHIP
    // Stack the ship-to-ship AO with the existing aoMultiplier additively (in occlusion-loss space) —
    // same compounding rule the world FSH uses for vanilla x ship AO so two op cells at a corner sum to
    // loss 0.4 instead of 0.64. Floor at 0.2 matches sodium's deepest opaque AO.
    aoMultiplier = max(0.2, aoMultiplier - sosSeamLoss);
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


#ifdef VS_DYNAMIC_SHADE
    // Directional shade (vanilla "side darkening"): applied only when the quad opted in AND we didn't
    // detect fullbright above. aoMultiplier is already 1.0 for fullbright so the else-branch is a no-op
    // there too. Disabled: just AO-darken; ship sides won't have the vanilla direction-based brightness
    // drop.
    if (isShade && !isFullbright) {
        diffuseColor.rgb *= vanillaShadeFromNormal(worldN) * aoMultiplier;
    } else {
        diffuseColor.rgb *= aoMultiplier;
    }
#else
    diffuseColor.rgb *= aoMultiplier;
#endif

    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, v_FadeFactor);

#ifdef VS_DEBUG_SHIP_LIGHT
    // ===== Ship-chunk light source paint =========================================================
    // Which of the two block-light sources is lighting this ship fragment?
    //
    //   RED   = v_BakedLightCoord.x, the SHIPYARD-baked lightmap, baked at mesh-compile time.
    //   GREEN = the world-sampled light at the ship's rendered position.
    //
    // The shipyard lightmap is not occlusion-aware -- probed on the client it is a plain
    // 15-minus-manhattan falloff, with solid stone reading 13 and 14 -- so if a ship's outer hull shows
    // RED far from an enclosed emitter, its own lamp is shining through solid blocks. That is the thing
    // to confirm before rewiring self-lighting onto the flood.
    //
    // Written OVER the finished fragColor, never as an early return: returning early lets the compiler
    // drop the texture reads above and sodium's bindUniform then throws on the missing sampler. The
    // 1e-4 term keeps them all live.
    {
        float dbgBaked = max(0.0, v_BakedLightCoord.x * 16.0 - 0.5);
#ifdef VS_DYNAMIC_LIGHT
        float dbgWorld = max(0.0, vsLight.light.x * 16.0 - 0.5);
#else
        float dbgWorld = 0.0;
#endif
        // Constant blue marks "this fragment is a ship chunk". Without it an unlit hull paints pure
        // black and is indistinguishable from the night sky behind it, so a shot showing no leak and a
        // shot with the ship out of frame look identical -- which is exactly how the first attempt at
        // this measurement went.
#if VS_DEBUG_SHIP_LIGHT == 4
        // Mode 4 isolates the two ship-on-ship terms so a drag can say which one is unstable:
        // RED = the ship-to-ship LIGHT received, GREEN = the ship-to-ship AO applied to it.
#ifdef VS_SHIP_ON_SHIP
        fragColor = vec4(clamp(vsDbgSosLight / 15.0, 0.0, 1.0),
                         clamp(1.0 - sosSeamLoss, 0.0, 1.0),
                         0.20, 1.0) + fragColor * 1.0e-4;
#else
        fragColor = vec4(0.0, 0.0, 0.20, 1.0) + fragColor * 1.0e-4;
#endif
#else
        fragColor = vec4(clamp(dbgBaked / 15.0, 0.0, 1.0),
                         clamp(dbgWorld / 15.0, 0.0, 1.0),
                         0.20, 1.0) + fragColor * 1.0e-4;
#endif
    }
#endif

}
