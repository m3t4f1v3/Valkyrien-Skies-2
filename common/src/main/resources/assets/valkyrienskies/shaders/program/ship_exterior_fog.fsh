#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D SceneDepthSampler;
uniform sampler2D InteriorMaskSampler;
uniform vec3 FogColor;
uniform vec2 FogParams;
uniform float ExteriorWaterGate;
uniform float SkyFogStrength;
uniform mat4 InverseProjMat;
uniform vec3 CameraWorldPos;
uniform vec3 CameraLookVector;
uniform vec3 CameraUpVector;
uniform vec3 CameraLeftVector;

in vec2 texCoord;
out vec4 fragColor;

vec3 reconstructViewPos(float depth) {
    vec4 clipPos = vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 viewPos = InverseProjMat * clipPos;
    return viewPos.xyz / viewPos.w;
}

vec3 reconstructWorldPos(float depth) {
    vec3 viewPos = reconstructViewPos(depth);
    return CameraWorldPos
        - CameraLeftVector * viewPos.x
        + CameraUpVector * viewPos.y
        - CameraLookVector * viewPos.z;
}

void main() {
    vec4 sceneColor = texture(DiffuseSampler, texCoord);
    float sceneDepth = texture(SceneDepthSampler, texCoord).r;
    vec4 interiorMask = texture(InteriorMaskSampler, texCoord);
    float dryFraction = interiorMask.r;
    float exteriorGate = clamp(ExteriorWaterGate, 0.0, 1.0);

    if (sceneDepth >= 1.0) {
        vec3 skyWorldPos = reconstructWorldPos(0.99999);
        vec3 skyRayDir = normalize(skyWorldPos - CameraWorldPos);
        float downwardBias = clamp((-skyRayDir.y + 0.15) / 0.5, 0.0, 1.0);
        float skyGate = clamp(exteriorGate * mix(0.95, 1.0, downwardBias) * SkyFogStrength, 0.0, 1.0);
        vec3 skyFoggedColor = mix(sceneColor.rgb, FogColor, skyGate);
        fragColor = vec4(skyFoggedColor, 1.0);
        return;
    }

    vec3 viewPos = reconstructViewPos(sceneDepth);
    float sceneDistance = length(viewPos);
    float dryDistance = clamp(dryFraction, 0.0, 1.0) * sceneDistance;
    float fogDistance = max(0.0, sceneDistance - dryDistance - FogParams.y);
    float fogAmount = 1.0 - exp(-FogParams.x * fogDistance);
    fogAmount *= max(0.0, 1.0 - clamp(dryFraction, 0.0, 1.0));
    fogAmount *= exteriorGate;
    vec3 foggedColor = mix(sceneColor.rgb, FogColor, fogAmount);
    fragColor = vec4(foggedColor, 1.0);
}
