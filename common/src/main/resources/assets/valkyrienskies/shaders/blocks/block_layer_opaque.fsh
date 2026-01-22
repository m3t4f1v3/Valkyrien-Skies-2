#version 330 core

#import <sodium:include/fog.glsl>

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
in float v_FragDistance; // The fragment's distance from the camera

in float v_MaterialMipBias;
in float v_MaterialAlphaCutoff;
in vec3 v_WorldPos;
in mat4 v_RotationMatrix;

uniform sampler2D u_BlockTex; // The block texture

uniform vec4 u_FogColor; // The color of the shader fog
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog

out vec4 fragColor; // The output fragment for the color framebuffer

#define MINECRAFT_LIGHT_X   (0.6)
#define MINECRAFT_LIGHT_Z   (0.8)
#define MINECRAFT_LIGHT_Y   (0.5)


// yeah yeah i know i should import this but cba atm thats a job for cleanup me
float vanillaShadeFromNormal(vec3 n) {
    vec3 an = abs(n);

    float yShade = n.y > 0.0 ? 1.0 : MINECRAFT_LIGHT_Y;

    float shade =
    an.x * MINECRAFT_LIGHT_X +
    an.z * MINECRAFT_LIGHT_Z +
    an.y * yShade;

    return shade / (an.x + an.y + an.z);
}

void main() {
    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) {
        discard;
    }
#endif

    // Apply per-vertex color
    diffuseColor.rgb *= v_Color.rgb;

    vec3 fdx = dFdx(v_WorldPos);
    vec3 fdy = dFdy(v_WorldPos);
    vec3 n = normalize(cross(fdx, fdy));
    n = (v_RotationMatrix * vec4(n, 0.0)).xyz;
    // n *= 0.5;
    // n += vec3(0.5);
    float shade = vanillaShadeFromNormal(n);
    diffuseColor.rgb *= shade;

    // Apply ambient occlusion "shade"
    // diffuseColor.rgb *= v_Color.a;

    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
}
