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

uniform samplerBuffer ShipTransforms;
uniform int ShipIndex;

uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform vec3 ChunkOffset;
uniform int FogShape;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec4 normal;

mat4 vs_shipModelView() {
    int b = ShipIndex * 4;
    return mat4(
        texelFetch(ShipTransforms, b + 0),
        texelFetch(ShipTransforms, b + 1),
        texelFetch(ShipTransforms, b + 2),
        texelFetch(ShipTransforms, b + 3));
}

void main() {
    mat4 modelView = vs_shipModelView();
    vec3 pos = Position + ChunkOffset;
    gl_Position = ProjMat * modelView * vec4(pos, 1.0);

    vertexDistance = fog_distance(modelView, pos, FogShape);
    texCoord0 = UV0;
    normal = ProjMat * modelView * vec4(Normal, 0.0);

    if (Color.a == 0.0) {
        vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
        vertexColor.a = 1.0;
    } else {
        vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
        vec3 worldNormal = normalize(IViewRotMat * mat3(modelView) * Normal);
        float shade = vanillaShadeFromNormal(worldNormal);
        vertexColor.rgb *= shade;
    }
}
