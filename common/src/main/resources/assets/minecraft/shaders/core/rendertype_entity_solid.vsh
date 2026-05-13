#version 150

#moj_import <light.glsl>
#moj_import <fakelight.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform int FogShape;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec4 overlayColor;
out vec4 lightMapColor;
out vec2 texCoord0;
out vec4 normal;

#if defined(VS_DYNAMIC_LIGHT) || defined(VS_DYNAMIC_SHADE)
uniform mat4 u_LocalToCameraRel;
uniform vec3 u_VsRenderOrigin;

uniform usamplerBuffer u_VsLightSections;
uniform usamplerBuffer u_VsLightLut;

const uint VS_BLOCKS_PER_SECTION = 18u * 18u * 18u;
const uint VS_LIGHT_SIZE_BYTES = VS_BLOCKS_PER_SECTION;
const uint VS_SOLID_SIZE_BYTES = ((VS_BLOCKS_PER_SECTION + 31u) / 32u) * 4u;
const uint VS_SOLID_START_INTS = 0u;
const uint VS_LIGHT_START_INTS = VS_SOLID_SIZE_BYTES / 4u;
const uint VS_SECTION_SIZE_INTS = (VS_SOLID_SIZE_BYTES + VS_LIGHT_SIZE_BYTES) / 4u;

uint vs_indexLut(uint i) { return texelFetch(u_VsLightLut, int(i)).r; }
uint vs_indexLight(uint i) { return texelFetch(u_VsLightSections, int(i)).r; }

bool vs_nextLightLut(uint base, int coord, out uint next) {
    int start = int(vs_indexLut(base));
    uint size = vs_indexLut(base + 1u);
    int idx = coord - start;
    if (idx < 0 || idx >= int(size)) return true;
    next = vs_indexLut(base + 2u + uint(idx));
    return false;
}

bool vs_chunkCoordToLightSectionIndex(ivec3 sectionPos, out uint index) {
    uint first;
    if (vs_nextLightLut(0u, sectionPos.y, first) || first == 0u) {
        return true;
    }

    uint second;
    if (vs_nextLightLut(first, sectionPos.x, second) || second == 0u) {
        return true;
    }

    uint sectionIndex;
    if (vs_nextLightLut(second, sectionPos.z, sectionIndex) || sectionIndex == 0u) {
        return true;
    }

    index = sectionIndex - 1u;

    return false;
}

bool vs_lightColorAt(vec3 worldPos, out ivec2 light) {
    ivec3 blockPos = ivec3(floor(worldPos));
    uvec3 blockInSectionPos = uvec3((blockPos & 0xF) + 1);
    uint sectionIndex;
    if (vs_chunkCoordToLightSectionIndex(blockPos >> 4, sectionIndex)) {
        return false;
    }
    uint sectionOffset = sectionIndex * VS_SECTION_SIZE_INTS;
    uint byteOffset = blockInSectionPos.x + blockInSectionPos.z * 18u + blockInSectionPos.y * 18u * 18u;

    uint uintOffset = byteOffset >> 2u;
    uint bitOffset = (byteOffset & 3u) << 3u;

    uint raw = vs_indexLight(sectionOffset + VS_LIGHT_START_INTS + uintOffset);
    uint block = (raw >> bitOffset) & 0xFu;
    uint sky = (raw >> (bitOffset + 4u)) & 0xFu;

    light = ivec2(block, sky);
    return true;
}
#endif

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);

    vertexColor = Color * vanillaShadeFromNormal(normalize(IViewRotMat * mat3(ModelViewMat) * Normal));
#if defined(VS_DYNAMIC_LIGHT) || defined(VS_DYNAMIC_SHADE)
    vec3 worldPosVertex = (u_LocalToCameraRel * vec4(Position, 1.0)).xyz + u_VsRenderOrigin;
    ivec2 light = ivec2(0);
    if(vs_lightColorAt(worldPosVertex, light)) {
        lightMapColor = texelFetch(Sampler2, ivec2(max(light.x, UV2.x / 16), min(light.y, UV2.y / 16)), 0);
    } else {
        lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    }
#else
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
#endif
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;

    normal = ProjMat * ModelViewMat * vec4(Normal, 0.0);
}

