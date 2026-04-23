#version 150

uniform sampler2D SceneColorSampler;
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
    vec4 sceneColor = texture(SceneColorSampler, texCoord);
    float sceneDepth = texture(SceneDepthSampler, texCoord).r;
    float interiorMask = texture(InteriorMaskSampler, texCoord).r;
    vec3 viewPos = reconstructViewPos(sceneDepth);

    float fogDistance = max(0.0, length(viewPos) - FogParams.y);
    float fogAmount = 1.0 - exp(-fogDistance * FogParams.x);
    fogAmount *= (1.0 - interiorMask);

    float clampedFog = clamp(fogAmount, 0.0, 1.0);
    fragColor = vec4(interiorMask, clampedFog, sceneDepth >= 0.9999 ? 1.0 : 0.0, 1.0);
}
