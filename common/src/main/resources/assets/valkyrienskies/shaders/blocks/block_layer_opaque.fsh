#version 330 core

#import <sodium:include/fog.glsl>

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
in float v_FragDistance; // The fragment's distance from the camera

in float v_MaterialMipBias;
in float v_MaterialAlphaCutoff;
in vec3 v_WorldPos;
in mat4 v_TransformMatrix;

uniform sampler2D u_BlockTex; // The block texture

uniform vec4 u_FogColor; // The color of the shader fog
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog

out vec4 fragColor; // The output fragment for the color framebuffer

#define MINECRAFT_LIGHT_X   (0.6)
#define MINECRAFT_LIGHT_Z   (0.8)
#define MINECRAFT_LIGHT_Y   (0.5)


// from Flywheel/common/src/backend/resources/assets/flywheel/flywheel/internal/diffuse.glsl
float vanillaShadeFromNormal(vec3 normal) {
    vec3 n2 = normal * normal * vec3(.6, .25, .8);
    return min(n2.x + n2.y * (3. + normal.y) + n2.z, 1.);
}

float diffuseNether(vec3 normal) {
    vec3 n2 = normal * normal * vec3(.6, .9, .8);
    return min(n2.x + n2.y + n2.z, 1.);
}

void main() {
    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) {
        discard;
    }
#endif

#ifdef USE_VANILLA_COLOR_FORMAT
    // Apply per-vertex color. AO shade is applied ahead of time on the CPU.
    diffuseColor *= v_Color;
#else
    // Apply per-vertex color
    diffuseColor.rgb *= v_Color.rgb;

    vec3 fdx = dFdx(v_WorldPos);
    vec3 fdy = dFdy(v_WorldPos);
    
    vec3 rawN = cross(fdx, fdy);
    float len2 = dot(rawN, rawN);
    float dx2  = dot(fdx, fdx);
    float dy2  = dot(fdy, fdy);

    float denom = dx2 * dy2 + 1e-20;
    float quality = len2 / denom;
    mat3 rot = mat3(v_TransformMatrix);
    vec3 trans = v_TransformMatrix[3].xyz;
    

    // rawN = nan -> rawN != rawN
    if (quality < 5e-2 || !all(equal(rawN, rawN))) {
        // Apply ambient occlusion "shade"
        diffuseColor.rgb *= v_Color.a;
    } else {
        vec3 n = normalize(rawN);
        n = (rot * n).xyz;

        float shade = vanillaShadeFromNormal(n);
        diffuseColor.rgb *= shade;
    }
#endif

    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
}
