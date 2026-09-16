#version 150
// layout(location) on a fragment output is GLSL 3.30; this is 150, and the extension that
// backports it is on every driver that can run the game. Without it the two outputs are
// assigned locations by the linker, which is free to put the motion vectors in the colour
// attachment.
#extension GL_ARB_explicit_attrib_location : enable

#moj_import <fog.glsl>
#moj_import <vs_ship_glow_grid.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
noperspective in vec2 v_VsMotion;
flat in int v_VsShip;
in vec4 normal;

in vec3 valkyrienair_CamRelPos;
in vec2 v_VsLightCoordRaw;
in vec3 v_VsWorldNormal;

uniform int u_VsShipGlowEnabled;
uniform vec3 u_VsShipLightCameraPos;

layout(location = 0) out vec4 fragColor;
// Ship motion vectors. Only bound as a draw buffer inside ShipBatchRenderer.drawLayer;
// writes from terrain, which shares this shader, are discarded.
layout(location = 1) out vec4 vsMotion;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.5) {
        discard;
    }
    if (u_VsShipGlowEnabled != 0) {
        vec3 vsWorldPos = valkyrienair_CamRelPos + u_VsShipLightCameraPos;
        float vsShipGlow = vs_shipGlowSmooth(vsWorldPos, v_VsWorldNormal);
        if (vsShipGlow > 0.0) {
            float vsBoostedU = max(v_VsLightCoordRaw.x, (vsShipGlow + 0.5) / 16.0);
            vec3 vsBase = texture(Sampler2, v_VsLightCoordRaw).rgb;
            vec3 vsBoosted = texture(Sampler2, vec2(vsBoostedU, v_VsLightCoordRaw.y)).rgb;
            color.rgb *= vsBoosted / max(vsBase, vec3(1.0 / 255.0));
        }
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
    // .a carries this fragment's depth so the compositor can tell a car pixel that
    // survived to the front from one that was later overdrawn by something nearer.
    vsMotion = vec4(v_VsMotion, float(v_VsShip), gl_FragCoord.z);
}
