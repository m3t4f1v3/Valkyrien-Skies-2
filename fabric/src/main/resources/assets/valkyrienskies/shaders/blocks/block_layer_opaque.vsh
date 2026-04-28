#version 330 core

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#import <sodium:include/chunk_material.glsl>

uniform mat4 u_TransformMatrix;
uniform mat4 u_LocalToCameraRel;

out vec4 v_Color;
out vec2 v_TexCoord;
out vec3 v_WorldPos;
out vec3 v_CameraRelWorldPos;
out vec2 v_BakedLightCoord;
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
uniform vec2 u_TexCoordShrink;

uvec3 _get_relative_chunk_coord(uint pos) {
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();

    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

    v_WorldPos = position;
    // Camera-relative world position. The fragment adds u_VsRenderOrigin to
    // recover the absolute world block position; per-fragment interpolation
    // means each fragment lands inside a block (not at a corner), avoiding the
    // float-precision flicker that per-vertex lookups had at section faces.
    v_CameraRelWorldPos = (u_LocalToCameraRel * vec4(position, 1.0)).xyz;

#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif

    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    v_BakedLightCoord = _vert_tex_light_coord;
    // Carry _vert_color (incl. baked AO in alpha) through. The fragment shader
    // does the actual lightmap sample so it can do world-space smooth lookup
    // with proper AO computed from world neighbors at the rendered position.
    v_Color = _vert_color;

    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord;

    v_MaterialMipBias = _material_mip_bias(_material_params);
    v_TransformMatrix = u_TransformMatrix;
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
}
