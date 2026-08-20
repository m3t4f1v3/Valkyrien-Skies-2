#version 330 core

// VS ship chunk vertex shader, for Sodium 0.9.
//
// Differences from the 0.5 copy in ../blocks, all forced by Sodium's own changes:
//   * the matrices, fog and atlas parameters moved into the u_Globals block (globals.glsl), so
//     chunk_matrices.glsl is gone and u_TexCoordShrink is no longer a standalone uniform;
//   * getFragDistance now returns both distance metrics at once, and fog takes the pair;
//   * chunk_material.glsl lost _material_mip_bias, so the material bits are passed to the FSH whole,
//     the way Sodium's own shader now does it;
//   * the section fade-in was added, which the FSH needs to feed into the fog blend.
//
// Everything below that is the same VS pipeline: the mesher packs AO level, face slot and biome
// resolver into the vertex colour's alpha byte (see VsVertexFlagPacker), and this shader decodes them.
//
// One shader for both loaders: 0.9 is a single codebase, unlike Sodium 0.5 vs Embeddium.

#import <sodium:include/fog.glsl>
#import <sodium:include/globals.glsl>
#import <sodium:include/chunk_vertex.glsl>

// u_TransformMatrix: ship-to-world matrix, used to lift the per-quad face normal into world space.
// Needed when shade is on, when the world-light pipeline runs, or when ship-on-ship AO projects
// occluders onto this face. ShipThing.bindUniform must agree with this gate.
#if defined(VS_DYNAMIC_LIGHT) || defined(VS_DYNAMIC_SHADE) || defined(VS_SHIP_ON_SHIP)
uniform mat4 u_TransformMatrix;
#endif
// u_LocalToCameraRel: maps sodium's chunk-local pos into camera-relative world space. The VSH uses it
// to emit v_CameraRelWorldPos (consumed by the FSH light/AO paths) and to compute the per-vertex
// worldPos for the biome lookup. ShipThing binds the matching uniform only when one of those consumers
// is on.
#if defined(VS_DYNAMIC_LIGHT) || defined(VS_DYNAMIC_BIOME) || defined(VS_SHIP_ON_SHIP)
uniform mat4 u_LocalToCameraRel;
uniform ivec3 u_VsRenderOrigin;
#endif
#ifdef VS_DYNAMIC_BIOME
uniform usamplerBuffer u_VsBiomeSections;
uniform usamplerBuffer u_VsBiomeLut;
#endif

out vec4 v_Color;
out vec2 v_TexCoord;
out vec2 v_BakedLightCoord;
// Camera-relative WORLD pos; FSH reads it (+ u_VsRenderOrigin) to recover absolute world block coords
// for the world-light lookup.
#if defined(VS_DYNAMIC_LIGHT) || defined(VS_SHIP_ON_SHIP)
out vec3 v_CameraRelWorldPos;
#endif
// World-space surface normal recovered from the per-quad face slot via u_TransformMatrix. Used by
// world-light per-axis interp, directional shade, and ship-on-ship AO — declared if any of those are on.
#if defined(VS_DYNAMIC_LIGHT) || defined(VS_DYNAMIC_SHADE) || defined(VS_SHIP_ON_SHIP)
flat out vec3 v_WorldNormal;
#endif
// Decoded VS vertex flags packed by the BlockRenderer mixin into the alpha byte of the vertex colour.
// See VsVertexFlagPacker for the packing contract:
//   alpha bits 0-2: AO level (0..5 mapped to 0/0.2/0.4/0.6/0.8/1.0)
//   alpha bits 3-5: face slot (0 DOWN, 1 UP, 2 N, 3 S, 4 W, 5 E, 6 UNSHADED, 7 FULLBRIGHT)
//   alpha bits 6-7: resolverType (0 none, 1 grass, 2 foliage, 3 water)
flat out int v_ResolverType;
flat out int v_IsShaded;
flat out int v_IsFullbright;
// Rasterizer-blended world-biome RGB sampled at each vertex. vec3(1.0) for non-biome quads so the FSH
// multiply is a no-op.
out vec3 v_VertexBiomeTint;

// Material bits, forwarded whole. 0.9's chunk_material.glsl no longer exposes a mip bias, and the alpha
// cutoff is cheap enough to unpack at the fragment.
flat out uint v_Material;

out vec2 v_FragDistance;
// Section fade-in, 0 while a freshly built section is still appearing. Fed to _linearFog, which uses it
// to fade the section in from the fog colour.
out float v_FadeFactor;

uniform vec3 u_RegionOffset;
uniform isamplerBuffer u_SectionTimeInfo;
uniform int u_CurrentTime;
uniform uint u_RegionID;

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

#ifdef VS_DYNAMIC_BIOME
// ===== Per-vertex biome color lookup =====================================
// Mirrors the FSH's lookup; layout is fixed by VsShipBiomeColorStorage:
//   3 resolvers * 16x16 colors = 768 R32UI ints (3072 B) per section.
const uint VS_BIOME_CELLS_PER_RESOLVER = 256u;
const uint VS_BIOME_SECTION_SIZE_INTS = 768u;

uint vs_indexBiomeLut(uint i) { return texelFetch(u_VsBiomeLut, int(i)).r; }
uint vs_indexBiome(uint i) { return texelFetch(u_VsBiomeSections, int(i)).r; }

bool vs_nextBiomeLut(uint base, int coord, out uint next) {
    int start = int(vs_indexBiomeLut(base));
    uint size = vs_indexBiomeLut(base + 1u);
    int idx = coord - start;
    if (idx < 0 || idx >= int(size)) return true;
    next = vs_indexBiomeLut(base + 2u + uint(idx));
    return false;
}

bool vs_chunkCoordToBiomeSectionIndex(ivec3 sectionPos, out uint index) {
    uint first;
    if (vs_nextBiomeLut(0u, sectionPos.y, first) || first == 0u) return true;
    uint second;
    if (vs_nextBiomeLut(first, sectionPos.x, second) || second == 0u) return true;
    uint sectionIndex;
    if (vs_nextBiomeLut(second, sectionPos.z, sectionIndex) || sectionIndex == 0u) return true;
    index = sectionIndex - 1u;
    return false;
}

vec3 vs_unpackRgb8(uint v) {
    return vec3(
        float( v        & 0xFFu),
        float((v >>  8u) & 0xFFu),
        float((v >> 16u) & 0xFFu)
    ) * (1.0 / 255.0);
}

vec3 vs_biomeColorAt(vec3 worldPos, int resolverSlot) {
    ivec3 blockPos = ivec3(floor(worldPos));
    uint sectionIndex;
    if (vs_chunkCoordToBiomeSectionIndex(blockPos >> 4, sectionIndex)) {
        return vec3(1.0);
    }
    uint sectionOffset = sectionIndex * VS_BIOME_SECTION_SIZE_INTS;
    ivec2 cell = blockPos.xz & 15;
    uint cellOffset = uint(cell.x) + uint(cell.y) * 16u;
    uint addr = sectionOffset
              + uint(resolverSlot) * VS_BIOME_CELLS_PER_RESOLVER
              + cellOffset;
    return vs_unpackRgb8(vs_indexBiome(addr));
}
#endif

// Convert face slot (see packing contract above) to a shipyard-space normal. FACE_UNSHADED returns +Y
// just so the value is finite; the FSH gates on v_IsShaded before using v_WorldNormal.
vec3 vs_faceSlotToNormal(uint slot) {
    if (slot == 0u) return vec3(0.0, -1.0, 0.0);
    if (slot == 1u) return vec3(0.0,  1.0, 0.0);
    if (slot == 2u) return vec3(0.0,  0.0, -1.0);
    if (slot == 3u) return vec3(0.0,  0.0,  1.0);
    if (slot == 4u) return vec3(-1.0, 0.0, 0.0);
    if (slot == 5u) return vec3( 1.0, 0.0, 0.0);
    return vec3(0.0, 1.0, 0.0);
}
// =========================================================================

void main() {
    _vert_init();

    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

#if defined(VS_DYNAMIC_LIGHT) || defined(VS_SHIP_ON_SHIP)
    // Camera-relative world position. The fragment adds u_VsRenderOrigin to recover the absolute world
    // block position; per-fragment interpolation means each fragment lands inside a block (not at a
    // corner), avoiding the float-precision flicker that per-vertex lookups had at section faces.
    v_CameraRelWorldPos = (u_LocalToCameraRel * vec4(position, 1.0)).xyz;
#endif

    v_FragDistance = getFragDistance(position);

    // Section fade-in. Computed unconditionally: u_SectionTimeInfo, u_CurrentTime and u_RegionID are
    // bound as required uniforms by DefaultShaderInterface, and GLSL would strip any of them that no
    // live code path reads, which makes the bind throw at link time.
    int chunkFade = texelFetch(u_SectionTimeInfo, int((u_RegionID * 256u) + _draw_id)).r;
    float fade = clamp(float(u_CurrentTime - chunkFade) * u_FadePeriodInv, 0.0, 1.0);
    v_FadeFactor = (chunkFade < 0) ? 1.0 : fade;

    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    v_BakedLightCoord = _vert_tex_light_coord;
    // Decode the alpha layout: ao(3) | face(3) | resolver(2). AO uses a custom mapping where the integer
    // level maps to ao = level * 0.2, which exactly captures sodium's discrete AO values
    // {0, 0.2, 0.4, 0.6, 0.8, 1.0}.
    uint aoByte = uint(_vert_color.a * 255.0 + 0.5);
    uint aoLevel = aoByte & 7u;
    uint faceSlot = (aoByte >> 3u) & 7u;
    v_ResolverType = int((aoByte >> 6u) & 3u);
    // faceSlot 0-5 = shaded cardinal, 6 = unshaded (fluids etc.), 7 = fullbright (emissive)
    v_IsShaded = (faceSlot < 6u) ? 1 : 0;
    v_IsFullbright = (faceSlot == 7u) ? 1 : 0;
    float aoFloat = float(aoLevel) * 0.2;
    v_Color = vec4(_vert_color.rgb, aoFloat);

#if defined(VS_DYNAMIC_LIGHT) || defined(VS_DYNAMIC_SHADE) || defined(VS_SHIP_ON_SHIP)
    // World-space surface normal: shipyard-space face direction transformed by the ship-to-world matrix.
    // All four vertices of a quad share the same face slot, so flat-interpolating this is exact.
    vec3 shipyardNormal = vs_faceSlotToNormal(faceSlot);
    v_WorldNormal = normalize((u_TransformMatrix * vec4(shipyardNormal, 0.0)).xyz);
#endif

#ifdef VS_DYNAMIC_BIOME
    // Per-vertex biome lookup at the absolute world position. Linear-blended across the quad by the
    // rasterizer for smooth biome transitions; vec3(1.0) when the quad isn't biome-tinted so the FSH
    // multiply is a no-op.
    vec3 worldPosVertex = (u_LocalToCameraRel * vec4(position, 1.0)).xyz + vec3(u_VsRenderOrigin);
    v_VertexBiomeTint = (v_ResolverType > 0)
            ? vs_biomeColorAt(worldPosVertex, v_ResolverType - 1)
            : vec3(1.0);
#else
    v_VertexBiomeTint = vec3(1.0);
#endif

    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord;

    v_Material = _material_params;
}
