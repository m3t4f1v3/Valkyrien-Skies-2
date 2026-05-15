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

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform int FogShape;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec2 texCoord1;
out vec2 texCoord2;
out vec4 normal;

#if defined(VS_DYNAMIC_LIGHT) || defined(VS_DYNAMIC_SHADE)
uniform mat4 u_LocalToCameraRel;
uniform vec3 u_VsRenderOrigin;

uniform int u_isInWorld;

uniform usamplerBuffer u_VsLightSections;
uniform usamplerBuffer u_VsLightLut;

uniform int u_VsShipEmitterCount;
uniform samplerBuffer u_VsShipEmitters;

const uint VS_BLOCKS_PER_SECTION = 18u * 18u * 18u;
const uint VS_LIGHT_SIZE_BYTES = VS_BLOCKS_PER_SECTION;
const uint VS_SOLID_SIZE_BYTES = ((VS_BLOCKS_PER_SECTION + 31u) / 32u) * 4u;
const uint VS_SOLID_START_INTS = 0u;
const uint VS_LIGHT_START_INTS = VS_SOLID_SIZE_BYTES / 4u;
const uint VS_SECTION_SIZE_INTS = (VS_SOLID_SIZE_BYTES + VS_LIGHT_SIZE_BYTES) / 4u;

uint vs_indexLut(uint i) { return texelFetch(u_VsLightLut, int(i)).r; }
uint vs_indexLight(uint i) { return texelFetch(u_VsLightSections, int(i)).r; }

// Inverse-rotate v by quaternion q (i.e., apply q^-1 = (-q.xyz, q.w) to v).
// Used to express world-frame offsets in the owning ship's local frame so
// the SDF / distance metrics line up with the ship's axes.
vec3 vs_quatRotateInv(vec4 q, vec3 v) {
    vec3 qNeg = -q.xyz;
    return v + 2.0 * cross(qNeg, cross(qNeg, v) + q.w * v);
}

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

ivec2 vs_lightColorAt(vec3 worldPos) {
    ivec3 blockPos = ivec3(floor(worldPos));
    uvec3 blockInSectionPos = uvec3((blockPos & 0xF) + 1);
    uint sectionIndex;
    if (vs_chunkCoordToLightSectionIndex(blockPos >> 4, sectionIndex)) {
        return ivec2(0, 15);
    }
    uint sectionOffset = sectionIndex * VS_SECTION_SIZE_INTS;
    uint byteOffset = blockInSectionPos.x + blockInSectionPos.z * 18u + blockInSectionPos.y * 18u * 18u;

    uint uintOffset = byteOffset >> 2u;
    uint bitOffset = (byteOffset & 3u) << 3u;

    uint raw = vs_indexLight(sectionOffset + VS_LIGHT_START_INTS + uintOffset);
    uint block = (raw >> bitOffset) & 0xFu;
    uint sky = (raw >> (bitOffset + 4u)) & 0xFu;

    return ivec2(int(block), int(sky));
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
int vs_shipEmitterLight(vec3 worldPos) {
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
    return int(maxLight + 0.5);
}

#endif

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);

    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    #if defined(VS_DYNAMIC_LIGHT) || defined(VS_DYNAMIC_SHADE)
    vec3 worldPosVertex = (u_LocalToCameraRel * vec4(Position, 1.0)).xyz + u_VsRenderOrigin;
    if(u_isInWorld != 0) {
        ivec2 light = UV2 / 16;
        light.x = max(light.x, vs_shipEmitterLight(worldPosVertex));
        ivec2 lightShip = vs_lightColorAt(worldPosVertex);
        light.x = max(light.x, lightShip.x);
        light.y = min(light.y, lightShip.y);
        vertexColor *= texelFetch(Sampler2, light, 0);
    } else {
        vertexColor *= texelFetch(Sampler2, UV2 / 16, 0);
    }
    #else
    vertexColor *= texelFetch(Sampler2, UV2 / 16, 0);
    #endif
    texCoord0 = UV0;
    texCoord1 = UV1;
    texCoord2 = UV2;
    normal = ProjMat * ModelViewMat * vec4(Normal, 0.0);
}

