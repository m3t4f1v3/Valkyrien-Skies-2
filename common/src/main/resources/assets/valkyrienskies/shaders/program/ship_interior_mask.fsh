#version 150

uniform sampler2D SceneDepthSampler;
uniform mat4 InverseProjMat;
uniform mat4 InverseViewMat;
uniform vec3 CameraWorldPos;
uniform int ValkyrienAir_ShipCount;
uniform vec4 ValkyrienAir_ShipAabbMin0; uniform vec4 ValkyrienAir_ShipAabbMax0; uniform vec4 ValkyrienAir_GridSize0; uniform mat4 ValkyrienAir_WorldToShip0; uniform sampler2D ValkyrienAir_Mask0;
uniform vec4 ValkyrienAir_ShipAabbMin1; uniform vec4 ValkyrienAir_ShipAabbMax1; uniform vec4 ValkyrienAir_GridSize1; uniform mat4 ValkyrienAir_WorldToShip1; uniform sampler2D ValkyrienAir_Mask1;
uniform vec4 ValkyrienAir_ShipAabbMin2; uniform vec4 ValkyrienAir_ShipAabbMax2; uniform vec4 ValkyrienAir_GridSize2; uniform mat4 ValkyrienAir_WorldToShip2; uniform sampler2D ValkyrienAir_Mask2;
uniform vec4 ValkyrienAir_ShipAabbMin3; uniform vec4 ValkyrienAir_ShipAabbMax3; uniform vec4 ValkyrienAir_GridSize3; uniform mat4 ValkyrienAir_WorldToShip3; uniform sampler2D ValkyrienAir_Mask3;
uniform vec4 ValkyrienAir_ShipAabbMin4; uniform vec4 ValkyrienAir_ShipAabbMax4; uniform vec4 ValkyrienAir_GridSize4; uniform mat4 ValkyrienAir_WorldToShip4; uniform sampler2D ValkyrienAir_Mask4;
uniform vec4 ValkyrienAir_ShipAabbMin5; uniform vec4 ValkyrienAir_ShipAabbMax5; uniform vec4 ValkyrienAir_GridSize5; uniform mat4 ValkyrienAir_WorldToShip5; uniform sampler2D ValkyrienAir_Mask5;
uniform vec4 ValkyrienAir_ShipAabbMin6; uniform vec4 ValkyrienAir_ShipAabbMax6; uniform vec4 ValkyrienAir_GridSize6; uniform mat4 ValkyrienAir_WorldToShip6; uniform sampler2D ValkyrienAir_Mask6;
uniform vec4 ValkyrienAir_ShipAabbMin7; uniform vec4 ValkyrienAir_ShipAabbMax7; uniform vec4 ValkyrienAir_GridSize7; uniform mat4 ValkyrienAir_WorldToShip7; uniform sampler2D ValkyrienAir_Mask7;
uniform vec4 ValkyrienAir_ShipAabbMin8; uniform vec4 ValkyrienAir_ShipAabbMax8; uniform vec4 ValkyrienAir_GridSize8; uniform mat4 ValkyrienAir_WorldToShip8; uniform sampler2D ValkyrienAir_Mask8;

in vec2 texCoord;
out vec4 fragColor;

const int VA_MASK_TEX_WIDTH_SHIFT = 12;
const int VA_MASK_TEX_WIDTH_MASK = (1 << VA_MASK_TEX_WIDTH_SHIFT) - 1;
const int VA_OCC_WORDS_PER_VOXEL = 16;

vec3 reconstructWorldPos(float depth) {
    vec4 clipPos = vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 viewPos = InverseProjMat * clipPos;
    viewPos /= viewPos.w;
    // modelViewMatrix at this stage is rotation + bob-view + damage-tilt, with no world translation
    // (world geometry is rendered camera-relative). Inverting gets us a camera-relative world pos
    // that correctly undoes the bob / tilt offset; add CameraWorldPos to land in actual world space.
    vec3 cameraRelative = (InverseViewMat * vec4(viewPos.xyz, 1.0)).xyz;
    return CameraWorldPos + cameraRelative;
}

uint va_fetchWord(sampler2D tex, int wordIndex) {
    ivec2 coord = ivec2(wordIndex & VA_MASK_TEX_WIDTH_MASK, wordIndex >> VA_MASK_TEX_WIDTH_SHIFT);
    vec4 raw = texelFetch(tex, coord, 0) * 255.0;
    uvec4 bytes = uvec4(round(raw));
    return bytes.r | (bytes.g << 8u) | (bytes.b << 16u) | (bytes.a << 24u);
}

bool va_testAir(sampler2D mask, int voxelIdx, ivec3 isize) {
    int volume = isize.x * isize.y * isize.z;
    int occBase = volume * VA_OCC_WORDS_PER_VOXEL;
    int wordIndex = occBase + (voxelIdx >> 5);
    int bit = voxelIdx & 31;
    uint word = va_fetchWord(mask, wordIndex);
    return ((word >> uint(bit)) & 1u) != 0u;
}

bool va_sceneInShipPocket(vec3 worldPos, vec4 aabbMin, vec4 aabbMax, vec4 gridSize, mat4 worldToShip, sampler2D mask) {
    if (gridSize.x <= 0.0) return false;
    if (worldPos.x < aabbMin.x || worldPos.x > aabbMax.x) return false;
    if (worldPos.y < aabbMin.y || worldPos.y > aabbMax.y) return false;
    if (worldPos.z < aabbMin.z || worldPos.z > aabbMax.z) return false;
    vec3 shipPos = (worldToShip * vec4(worldPos, 1.0)).xyz;
    if (shipPos.x < 0.0 || shipPos.y < 0.0 || shipPos.z < 0.0) return false;
    if (shipPos.x >= gridSize.x || shipPos.y >= gridSize.y || shipPos.z >= gridSize.z) return false;
    ivec3 isize = ivec3(gridSize.xyz);
    ivec3 voxel = ivec3(floor(shipPos));
    int voxelIdx = voxel.x + isize.x * (voxel.y + isize.y * voxel.z);
    return va_testAir(mask, voxelIdx, isize);
}

void main() {
    float sceneDepth = texture(SceneDepthSampler, texCoord).r;
    if (sceneDepth >= 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // Bias the sample toward the camera by a small fraction of a voxel so surfaces sitting right on
    // an air-voxel face (hull walls viewed from inside a pocket) reliably resolve into the air voxel
    // instead of jittering between the air voxel and its non-air neighbor with depth precision. That
    // jitter is what reads as z-fighting in the fog.
    vec3 sceneWorldPos = reconstructWorldPos(sceneDepth);
    vec3 viewDir = normalize(sceneWorldPos - CameraWorldPos);
    vec3 biasedPos = sceneWorldPos - viewDir * 0.05;

    // Direct mask lookup: R = 1 iff the scene fragment's voxel is marked interior in some ship's
    // mask. The fog shader reads this as a binary gate to skip pixels that are inside an air pocket.
    float sceneInPocket = 0.0;
    if (ValkyrienAir_ShipCount > 0 && va_sceneInShipPocket(biasedPos, ValkyrienAir_ShipAabbMin0, ValkyrienAir_ShipAabbMax0, ValkyrienAir_GridSize0, ValkyrienAir_WorldToShip0, ValkyrienAir_Mask0)) sceneInPocket = 1.0;
    if (ValkyrienAir_ShipCount > 1 && va_sceneInShipPocket(biasedPos, ValkyrienAir_ShipAabbMin1, ValkyrienAir_ShipAabbMax1, ValkyrienAir_GridSize1, ValkyrienAir_WorldToShip1, ValkyrienAir_Mask1)) sceneInPocket = 1.0;
    if (ValkyrienAir_ShipCount > 2 && va_sceneInShipPocket(biasedPos, ValkyrienAir_ShipAabbMin2, ValkyrienAir_ShipAabbMax2, ValkyrienAir_GridSize2, ValkyrienAir_WorldToShip2, ValkyrienAir_Mask2)) sceneInPocket = 1.0;
    if (ValkyrienAir_ShipCount > 3 && va_sceneInShipPocket(biasedPos, ValkyrienAir_ShipAabbMin3, ValkyrienAir_ShipAabbMax3, ValkyrienAir_GridSize3, ValkyrienAir_WorldToShip3, ValkyrienAir_Mask3)) sceneInPocket = 1.0;
    if (ValkyrienAir_ShipCount > 4 && va_sceneInShipPocket(biasedPos, ValkyrienAir_ShipAabbMin4, ValkyrienAir_ShipAabbMax4, ValkyrienAir_GridSize4, ValkyrienAir_WorldToShip4, ValkyrienAir_Mask4)) sceneInPocket = 1.0;
    if (ValkyrienAir_ShipCount > 5 && va_sceneInShipPocket(biasedPos, ValkyrienAir_ShipAabbMin5, ValkyrienAir_ShipAabbMax5, ValkyrienAir_GridSize5, ValkyrienAir_WorldToShip5, ValkyrienAir_Mask5)) sceneInPocket = 1.0;
    if (ValkyrienAir_ShipCount > 6 && va_sceneInShipPocket(biasedPos, ValkyrienAir_ShipAabbMin6, ValkyrienAir_ShipAabbMax6, ValkyrienAir_GridSize6, ValkyrienAir_WorldToShip6, ValkyrienAir_Mask6)) sceneInPocket = 1.0;
    if (ValkyrienAir_ShipCount > 7 && va_sceneInShipPocket(biasedPos, ValkyrienAir_ShipAabbMin7, ValkyrienAir_ShipAabbMax7, ValkyrienAir_GridSize7, ValkyrienAir_WorldToShip7, ValkyrienAir_Mask7)) sceneInPocket = 1.0;
    if (ValkyrienAir_ShipCount > 8 && va_sceneInShipPocket(biasedPos, ValkyrienAir_ShipAabbMin8, ValkyrienAir_ShipAabbMax8, ValkyrienAir_GridSize8, ValkyrienAir_WorldToShip8, ValkyrienAir_Mask8)) sceneInPocket = 1.0;

    fragColor = vec4(sceneInPocket, 0.0, 0.0, 1.0);
}
