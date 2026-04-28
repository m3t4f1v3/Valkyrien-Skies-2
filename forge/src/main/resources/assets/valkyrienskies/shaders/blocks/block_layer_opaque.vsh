#version 330 core

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#import <sodium:include/chunk_material.glsl>
uniform mat4 u_TransformMatrix;
uniform mat4 u_LocalToCameraRel;
uniform ivec3 u_VsRenderOrigin;
uniform usamplerBuffer u_VsLightSections;
uniform usamplerBuffer u_VsLightLut;

out vec4 v_Color;
out vec2 v_TexCoord;
out mat4 v_TransformMatrix;

out float v_MaterialMipBias;
#ifdef USE_FRAGMENT_DISCARD
out float v_MaterialAlphaCutoff;
#endif

#ifdef USE_FOG
out float v_FragDistance;
#endif

uniform int u_FogShape;
uniform vec3 u_RegionOffset;

uniform sampler2D u_LightTex; // The light map texture sampler

vec4 _sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

vec4 _sample_lightmap_smooth(sampler2D lightMap, vec2 uv) {
    return texture(lightMap, clamp(uv, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

// --- Flywheel-style light LUT lookup ----------------------------------------
const uint VS_BLOCKS_PER_SECTION = 18u * 18u * 18u;
const uint VS_LIGHT_SECTION_SIZE_INTS = (VS_BLOCKS_PER_SECTION + 3u) / 4u;
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
    uint raw = vs_indexLight(sectionOffset + uintOffset);
    uint b = (raw >> bitOffset) & 0xFu;
    uint s = (raw >> (bitOffset + 4u)) & 0xFu;
    return uvec2(b, s);
}

bool vs_lightFetch(ivec3 worldBlockPos, out vec2 lightCoord) {
    uint sectionIndex;
    if (vs_chunkCoordToSectionIndex(worldBlockPos >> 4, sectionIndex)) return false;
    uint sectionOffset = sectionIndex * VS_LIGHT_SECTION_SIZE_INTS;
    uvec3 blockInSectionPos = uvec3((worldBlockPos & 0xF) + 1);
    lightCoord = vec2(vs_lightAt(sectionOffset, blockInSectionPos)) * VS_LIGHT_NORMALIZER;
    return true;
}
// ----------------------------------------------------------------------------

void main() {
    _vert_init();

    // Transform the chunk-local vertex position into world model space
    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif

    // Transform the vertex position into model-view-projection space
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    // World-space lighting lookup
    vec3 cameraRelWorldPos = (u_LocalToCameraRel * vec4(position, 1.0)).xyz;
    ivec3 worldBlockPos = ivec3(floor(cameraRelWorldPos)) + u_VsRenderOrigin;

    vec2 vsLightCoord;
    vec4 lightSample;
    if (vs_lightFetch(worldBlockPos, vsLightCoord)) {
        // VS ship baked light comes from the shipyard storage location and is
        // mostly meaningless at the ship's rendered position. Use world sky
        // light at the rendered location; keep baked block light so ship-
        // internal emissives (torches on the ship) still glow.
        vec2 bakedNorm = vec2(_vert_tex_light_coord) / 256.0;
        vec2 combined = vec2(
            max(vsLightCoord.x, bakedNorm.x),
            vsLightCoord.y
        );
        lightSample = _sample_lightmap_smooth(u_LightTex, combined);
    } else {
        lightSample = _sample_lightmap(u_LightTex, _vert_tex_light_coord);
    }

    v_Color = _vert_color * lightSample;
    v_TexCoord = _vert_tex_diffuse_coord;

    v_MaterialMipBias = _material_mip_bias(_material_params);
    v_TransformMatrix = u_TransformMatrix;
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
}
