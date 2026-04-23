#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D SceneDepthSampler;
uniform sampler2D InteriorMaskSampler;
uniform vec3 FogColor;
uniform vec2 FogParams;
uniform mat4 InverseProjMat;

in vec2 texCoord;
out vec4 fragColor;

vec3 reconstructViewPos(float depth) {
    vec4 clipPos = vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 viewPos = InverseProjMat * clipPos;
    return viewPos.xyz / viewPos.w;
}

void main() {
    vec4 sceneColor = texture(DiffuseSampler, texCoord);
    float sceneDepth = texture(SceneDepthSampler, texCoord).r;
    vec4 interiorMask = texture(InteriorMaskSampler, texCoord);
    float dryFraction = interiorMask.r;
    float waterVisible = interiorMask.g;

    if (sceneDepth >= 1.0) {
        vec3 skyFoggedColor = mix(sceneColor.rgb, FogColor, clamp(waterVisible, 0.0, 1.0));
        fragColor = vec4(skyFoggedColor, 1.0);
        return;
    }

    vec3 viewPos = reconstructViewPos(sceneDepth);
    float sceneDistance = length(viewPos);
    float dryDistance = clamp(dryFraction, 0.0, 1.0) * sceneDistance;
    float fogDistance = max(0.0, sceneDistance - dryDistance - FogParams.y);
    float fogAmount = 1.0 - exp(-FogParams.x * fogDistance);
    fogAmount *= max(0.0, 1.0 - clamp(dryFraction, 0.0, 1.0));
    fogAmount *= clamp(waterVisible, 0.0, 1.0);
    vec3 foggedColor = mix(sceneColor.rgb, FogColor, fogAmount);
    fragColor = vec4(foggedColor, 1.0);
}
