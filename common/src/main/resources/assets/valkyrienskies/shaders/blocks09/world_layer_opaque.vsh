#version 330 core

// VS world chunk vertex shader, for Sodium 0.9.
//
// Swapped in for sodium's own chunk shader on non-ship chunks whenever dynamicShipToWorldLighting is on,
// so ships above the world can shadow and illuminate the blocks beneath them. See
// ../blocks/world_layer_opaque.vsh for the 0.5 copy; the differences are the same ones listed in
// blocks09/block_layer_opaque.vsh — globals block instead of chunk_matrices, paired fog distances, whole
// material bits instead of a mip bias, and the section fade-in.
//
// Unlike the 0.5 pair, one file serves both loaders: 0.9's u_TexCoordShrink lives in the globals block on
// Fabric and Forge alike, so there is no Embeddium-shaped variant to keep separate.

#import <sodium:include/fog.glsl>
#import <sodium:include/globals.glsl>
#import <sodium:include/chunk_vertex.glsl>

uniform vec3 u_RegionOffset;
uniform isamplerBuffer u_SectionTimeInfo;
uniform int u_CurrentTime;
uniform uint u_RegionID;

// Fractional part of the camera's world position (each component in [0, 1)). Sodium's vertex `position`
// is camera-relative using the EXACT camera, so we shift it back by frac(camera) to get
// vertex - floor(camera). Without this, floor() at the fragment shifts by +/-1 as the camera drifts
// through integer block boundaries — producing visible "jumping" of the occlusion.
uniform vec3 u_VsCameraFrac;

out vec4 v_Color;
out vec2 v_TexCoord;
out vec2 v_LightCoord;
// Camera-relative world position. FSH does
// `worldBlock = ivec3(floor(v_CameraRelWorldPos)) + u_VsRenderOrigin`.
out vec3 v_CameraRelWorldPos;
// World-space face normal decoded from the alpha-byte face slot. Flat-interpolated since all 4 vertices
// of a quad share the same face.
flat out vec3 v_WorldNormal;
// 1 when the source quad opted into directional shade and AO; 0 for fluids (slot 6, FACE_UNSHADED) and
// emissive/fullbright quads (slot 7).
flat out int v_IsShaded;

flat out uint v_Material;

out vec2 v_FragDistance;
out float v_FadeFactor;

uvec3 _get_relative_chunk_coord(uint pos) {
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

// Map the 3-bit face slot (see VsVertexFlagPacker) to its world-space surface normal. World blocks are
// axis-aligned, so the slot direction IS the world-space normal — no transform needed (unlike ship
// blocks, which need u_TransformMatrix to lift the shipyard normal into world). Slot 6 (UNSHADED) and 7
// (FULLBRIGHT) get an "up" placeholder; the FSH won't sample for AO on these faces because v_IsShaded
// gates it off.
vec3 vs_faceSlotToWorldNormal(uint slot) {
    if (slot == 0u) return vec3(0.0, -1.0, 0.0);
    if (slot == 1u) return vec3(0.0,  1.0, 0.0);
    if (slot == 2u) return vec3(0.0,  0.0, -1.0);
    if (slot == 3u) return vec3(0.0,  0.0,  1.0);
    if (slot == 4u) return vec3(-1.0, 0.0, 0.0);
    if (slot == 5u) return vec3( 1.0, 0.0, 0.0);
    return vec3(0.0, 1.0, 0.0);
}

void main() {
    _vert_init();

    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

    v_FragDistance = getFragDistance(position);

    // See the ship VSH: computed unconditionally so the three uniforms behind it stay live and
    // DefaultShaderInterface's required binds resolve.
    int chunkFade = texelFetch(u_SectionTimeInfo, int((u_RegionID * 256u) + _draw_id)).r;
    float fade = clamp(float(u_CurrentTime - chunkFade) * u_FadePeriodInv, 0.0, 1.0);
    v_FadeFactor = (chunkFade < 0) ? 1.0 : fade;

    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    // Sodium's `position` here is camera-relative using the EXACT camera position. Add the fractional
    // part of the camera back in so the value we pass downstream is `vertex - floor(camera)` — that way
    // the FSH's `floor(v_CameraRelWorldPos) + u_VsRenderOrigin` = floor(vertex) exactly, independent of
    // where the camera sits within its current integer block.
    v_CameraRelWorldPos = position + u_VsCameraFrac;

    // Decode the alpha-byte flag layout (see VsVertexFlagPacker):
    //   bits 0-2: AO level (0..5) -> AO = level * 0.2
    //   bits 3-5: face slot (0=DOWN..5=EAST, 6=UNSHADED, 7=FULLBRIGHT)
    //   bits 6-7: resolverType — unused for world blocks (always 0)
    uint aoByte = uint(_vert_color.a * 255.0 + 0.5);
    uint aoLevel = aoByte & 7u;
    uint faceSlot = (aoByte >> 3u) & 7u;
    // v_Color.a carries pure vanilla AO (no shade). The FSH combines it additively with ship-AO loss and
    // applies face shade after.
    float aoFloat = float(aoLevel) * 0.2;
    v_Color = vec4(_vert_color.rgb, aoFloat);
    v_WorldNormal = vs_faceSlotToWorldNormal(faceSlot);
    v_IsShaded = (faceSlot < 6u) ? 1 : 0;

    // _vert_tex_light_coord is already normalised to [0, 1] by chunk_vertex.glsl; we sample u_LightTex
    // per-fragment in the FSH rather than here, so it goes across as-is.
    v_LightCoord = _vert_tex_light_coord;
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord;

    v_Material = _material_params;
}
