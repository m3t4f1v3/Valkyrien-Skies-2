#version 150

#moj_import <light.glsl>
#moj_import <fakelight.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 PreviousModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform vec3 ChunkOffset;
uniform int FogShape;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
noperspective out vec2 v_VsMotion;
flat out int v_VsShip;
out vec4 normal;

void main() {
    vec3 pos = Position + ChunkOffset;
    vec4 currentClip = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vec4 previousClip = ProjMat * PreviousModelViewMat * vec4(pos, 1.0);
    gl_Position = currentClip;
    // A vertex the camera has moved past divides by a w on its way through zero and comes back
    // as an enormous vector -- one pixel of it is enough, because a consumer looking for the
    // longest vector on the frame finds that one. 0.05 is Minecraft's near plane and w here is
    // view-space depth in blocks, so this is exactly "was it in front of the camera last
    // frame"; a small epsilon is not, and let a corner of a car that swept past the third-person
    // camera report a vector a fifth of the screen long.
    v_VsMotion = previousClip.w > 0.05
        ? currentClip.xy / currentClip.w - previousClip.xy / previousClip.w
        : vec2(0.0);
    v_VsShip = 1;

    vertexDistance = fog_distance(ModelViewMat, pos, FogShape);
    texCoord0 = UV0;
    normal = ProjMat * ModelViewMat * vec4(Normal, 0.0);

    if (Color.a == 0.0) {
        vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
        vertexColor.a = 1.0;
    } else {
        vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
        vec3 worldNormal = normalize(IViewRotMat * mat3(ModelViewMat) * Normal);
        float shade = vanillaShadeFromNormal(worldNormal);
        vertexColor.rgb *= shade;
    }
}
