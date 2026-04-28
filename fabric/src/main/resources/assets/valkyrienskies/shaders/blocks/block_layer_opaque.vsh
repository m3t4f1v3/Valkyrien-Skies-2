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
// uniform mat4 u_NormalMatrix;

out vec4 v_Color;
out vec2 v_TexCoord;
out vec3 v_WorldPos;
out mat4 v_TransformMatrix;
// out mat4 v_NormalMatrix;

out float v_MaterialMipBias;
#ifdef USE_FRAGMENT_DISCARD
out float v_MaterialAlphaCutoff;
#endif

#ifdef USE_FOG
out float v_FragDistance;
#endif

uniform int u_FogShape;
uniform vec3 u_RegionOffset;
uniform vec2 u_TexCoordShrink;

uniform sampler2D u_LightTex; // The light map texture sampler

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

// --- Flywheel-style light LUT lookup ----------------------------------------
// Layout mirrors Flywheel's light_lut.glsl: a 3-level (Y -> X -> Z) span tree
// stored as uint texels in u_VsLightLut, plus packed light section data in
// u_VsLightSections (one byte per block in an 18x18x18 volume, low nibble =
// block light, high nibble = sky light).
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

    v_WorldPos = position;

#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif

    // Transform the vertex position into model-view-projection space
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    // Reconstruct the absolute world block position so we can look up
    // world-space lighting for this vertex regardless of the ship's transform.
    vec3 cameraRelWorldPos = (u_LocalToCameraRel * vec4(position, 1.0)).xyz;
    ivec3 worldBlockPos = ivec3(floor(cameraRelWorldPos)) + u_VsRenderOrigin;

    vec2 vsLightCoord;
    vec4 lightSample;
    if (vs_lightFetch(worldBlockPos, vsLightCoord)) {
        // For VS ships the baked sodium light comes from the ship's STORAGE
        // location (in the shipyard far from the visible world) and is mostly
        // meaningless for the ship's actual rendered location. Use the world
        // sky light at the rendered location, but keep ship-internal block
        // light from the baked value (so torches on the ship still glow even
        // when the world's light engine doesn't see them at the rendered pos).
        vec2 combined = vec2(
            max(vsLightCoord.x, _vert_tex_light_coord.x),
            vsLightCoord.y
        );
        lightSample = texture(u_LightTex, combined);
    } else {
        lightSample = texture(u_LightTex, _vert_tex_light_coord);
    }

    v_Color = _vert_color * lightSample;

    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord; // FMA for precision

    v_MaterialMipBias = _material_mip_bias(_material_params);
    v_TransformMatrix = u_TransformMatrix;
    // v_NormalMatrix = u_NormalMatrix;
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
}
