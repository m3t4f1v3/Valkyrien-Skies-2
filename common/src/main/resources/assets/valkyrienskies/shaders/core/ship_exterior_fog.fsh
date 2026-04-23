#version 150

uniform sampler2D SceneColorSampler;
uniform sampler2D SceneDepthSampler;
uniform sampler2D InteriorMaskSampler;
uniform vec2 ScreenSize;
uniform vec3 FogColor;
uniform vec2 FogParams;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 sceneColor = texture(SceneColorSampler, texCoord);
    float sceneDepth = texture(SceneDepthSampler, texCoord).r;
    float interiorMask = texture(InteriorMaskSampler, texCoord).r;

    float pseudoDistance = max(0.0, sceneDepth - FogParams.y);
    float fogAmount = 1.0 - exp(-pseudoDistance * 48.0 * FogParams.x);
    fogAmount *= (1.0 - interiorMask);

    fragColor = vec4(mix(sceneColor.rgb, FogColor, clamp(fogAmount, 0.0, 1.0)), sceneColor.a);
}
