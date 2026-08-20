#version 330 core

#import <sodium:include/fog.glsl>
#import <sodium:include/globals.glsl>
#import <sodium:include/chunk_material.glsl>

// VS-modified copy of sodium 0.9's stock chunk FSH. The only effect added on top of vanilla world
// rendering is "ship lights brighten the world" plus ship-cast AO: each ship-emitter is fed in as an
// entry in u_VsShipEmitters (vec4 = worldPos + lightLevel) and we max-merge the distance-attenuated
// contribution into the world's lightmap UV; each solid ship voxel is fed in through u_VsShipOccluders
// and casts a Manhattan tent on the surface below it.
//
// Sub-block precision: emitter and occluder world coords are stored as floats, so as a ship moves
// smoothly the lit and shadowed areas on the ground track it continuously.
//
// See ../blocks/world_layer_opaque.fsh for the 0.5 copy — the VS logic is identical, only the interface
// with Sodium changed (globals block, paired fog distances, nearest/RGSS atlas sampling, material bits
// unpacked here).

in vec4 v_Color;            // RGB = chunk-mesher tinted color, .a = pure vanilla AO (no shade)
in vec2 v_TexCoord;
in vec2 v_LightCoord;       // _vert_tex_light_coord (vanilla world lightmap UV)
in vec3 v_CameraRelWorldPos;// world pos relative to camera; + u_VsRenderOrigin = absolute
// Decoded face data from the VSH (see VsVertexFlagPacker). Flat interpolated, since face slot is shared
// by all 4 vertices of a quad.
flat in vec3 v_WorldNormal; // exact world-space face normal, axis-aligned +/-X/+/-Y/+/-Z
flat in int v_IsShaded;     // 0 for fluids and emissive/fullbright quads
flat in uint v_Material;
in vec2 v_FragDistance;
in float v_FadeFactor;

uniform sampler2D u_BlockTex;
uniform sampler2D u_LightTex;

uniform ivec3 u_VsRenderOrigin;
// Buffer texture (RGBA32F) — TWO texels per ship emitter:
//   texel 2i:   vec4(worldX, worldY, worldZ, lightLevel)
//   texel 2i+1: vec4(qx, qy, qz, qw)   ship-to-world rotation quaternion
// The quaternion's inverse rotates the world-frame fragment-to-emitter offset into the emitter's owning
// ship local frame, so the Manhattan light bubble visibly rotates with the hull.
#ifndef VS_FLOOD_GRID
uniform samplerBuffer u_VsShipEmitters;
uniform int u_VsShipEmitterCount;
#endif

// Per-frame list of solid ship voxel CENTERS in world space, paired with each voxel's owning-ship
// rotation quaternion. Two RGBA32F texels per voxel:
//   texel 2i:   vec4(worldX, worldY, worldZ, 0)
//   texel 2i+1: vec4(qx, qy, qz, qw)   ship-to-world rotation
// The shader applies the inverse rotation to the fragment-to-voxel offset so the Manhattan SDF runs in
// the voxel's ship-local frame. That makes each voxel's octagonal shadow rotate with its ship instead of
// staying world-axis-aligned.
uniform samplerBuffer u_VsShipOccluders;
uniform int u_VsShipOccluderCount;

// Inverse-rotate v by quaternion q (i.e., apply q^-1 = (-q.xyz, q.w) to v). Used to express world-frame
// offsets in the owning ship's local frame so the SDF / distance metrics line up with the ship's axes.
vec3 vs_quatRotateInv(vec4 q, vec3 v) {
    vec3 qNeg = -q.xyz;
    return v + 2.0 * cross(qNeg, cross(qNeg, v) + q.w * v);
}

out vec4 fragColor;

const float WS_UV_MIN = 1.0 / 32.0;
const float WS_UV_MAX = 31.0 / 32.0;

// ===== Atlas sampling ========================================================
// Copied from Sodium 0.9's own chunk FSH so world blocks drawn through this shader filter exactly like
// the ones drawn through sodium's.

vec4 ws_sampleNearest(sampler2D sampler, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;

    texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);

    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(sampler, uv, du, dv);
}

vec4 ws_sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return ws_sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

vec4 ws_sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);

    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

    float minPixelSize = min(pixelSize.x, pixelSize.y);

    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);

    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);

    float effectiveDerivative = sqrt(minDerivative * maxDerivative);

    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );

    vec4 rgssColor = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColor += textureLod(source, sampleUV, mipLevelExact);
    }
    rgssColor *= 0.25;

    vec4 nearestColor = ws_sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);

    return mix(nearestColor, rgssColor, blendFactor);
}

// Loop bound for the per-fragment ship-occluder scan. Should match VsShipOccluderList.MAX_OCCLUDERS —
// 1024 fits but is excessive per fragment; 128 covers typical scenes (a single mid-size ship's solid
// voxels), excess entries beyond this cap are silently ignored.
const int VS_OCCLUDER_LOOP_CAP = 128;

// Per-fragment ship AO via voxel-position iteration.
//
// For each ship voxel center (in world coords, stored as a continuous float — so the position smoothly
// tracks the ship's transform including rotation), compute its contribution to this fragment's AO based
// on:
//   * d_n (component along the face normal): how far the voxel is in the outward direction. Voxels in
//     the half-space behind the face (d_n <= 0) are skipped.
//   * the in-plane offset: how far the voxel is laterally from the fragment's projected position.
// Smooth falloff in both directions; sum contributions, clamp to 1.
//
// This replaces the cell-storage-based AO that operated on grid-aligned world cells — that approach
// quantized voxel positions to cells and the AO pattern could only morph between cell-aligned configs.
// With the voxel list, every voxel's exact transformed position contributes, so the AO shape rotates and
// translates continuously with the ship.
float ws_shipAo(vec3 worldPosWorld, vec3 nf) {
    int n = min(u_VsShipOccluderCount, VS_OCCLUDER_LOOP_CAP);

    float occlusionManhattan = 0.0;
    float occlusionCorner = 0.0;
    int cornerContributors = 0;

    for (int i = 0; i < n; i++) {
        // Two texels per voxel: position (with payload in .w) and the owning ship's rotation quaternion.
        // Apply q^-1 to the fragment-to-voxel offset and to the world face normal to get both into the
        // voxel's ship-local frame; pick face-local U/V from the rotated normal so the SDF axes track
        // the ship.
        vec4 voxel = texelFetch(u_VsShipOccluders, i * 2);
        vec4 q = texelFetch(u_VsShipOccluders, i * 2 + 1);

        vec3 d_world = voxel.xyz - worldPosWorld;
        vec3 d_ship = vs_quatRotateInv(q, d_world);
        vec3 nf_ship = vs_quatRotateInv(q, nf);

        float d_n = dot(d_ship, nf_ship);
        if (d_n <= 0.0 || d_n >= 1.5) continue;
        float fn = 1.0 - smoothstep(0.5, 1.5, d_n);

        // Build a stable orthonormal face basis from the SHIP-frame normal. The old threshold picker
        // could choose the same axis for U and V when nf_ship was diagonal in X/Z, collapsing the
        // footprint into a one-dimensional strip and producing very long shadows.
        vec3 helper = abs(nf_ship.y) < 0.9 ? vec3(0, 1, 0) : vec3(1, 0, 0);
        vec3 uAxis = normalize(cross(helper, nf_ship));
        vec3 vAxis = cross(nf_ship, uAxis);
        float du = dot(d_ship, uAxis);
        float dv = dot(d_ship, vAxis);

        // Manhattan-distance SDF of the voxel's face-plane box. Reproduces vanilla MC's exact AO shape
        // for an isolated occluder:
        //   - inside the voxel's 1x1 footprint: full 1/3 contribution.
        //   - axially adjacent cells: linear 1/3 -> 0 ramp over 1 cell.
        //   - diagonally adjacent cells: triangular falloff cut by the 45 degree Manhattan iso-line —
        //     vanilla's clean-triangle corner.
        //
        // halfSize 0.5: voxel is a unit cell on the face plane. tent reaches 0 at distance 1 cell from
        // the box (matching vanilla's 1-cell AO reach). Voxel position is continuous, so the shape
        // translates AND rotates with voxels — no per-cell decomposition, no world-grid anchoring.
        float dU = abs(du) - 0.5;
        float dV = abs(dv) - 0.5;
        float manhattan = max(0.0, 1.0 - max(dU, 0.0) - max(dV, 0.0));

        // Diagonal corner-cell extra for the "X X" case (two voxels with a 1-cell gap between them).
        // Manhattan alone falls to 0 along |du|+|dv|=1, so each X contributes 0 at the gap-front
        // fragment (dU=0.5, dV=0.5 from each), leaving a bright wedge where vanilla has continuous AO.
        // The corner-extra term promotes the tent to bilinear (1-dU)(1-dV) inside the diagonal cell,
        // filling the gap to 1/12 per voxel. Factors clamp at 0/1 so distant voxels contribute nothing.
        //
        // Gated below by `cornerContributors >= 2`: the corner cell is filled only when at least two
        // voxels are themselves landing a cornerExtra contribution at this fragment — the X-X-gap
        // signature. An isolated voxel triggers at most one corner contributor (its own diagonal cell)
        // so the fill is dropped and the clean Manhattan octagon is preserved. Adjacent voxels (XX, no
        // gap) also drop the fill: only the diagonal voxel of the pair has cornerExtra > 0; the axial
        // voxel's contribution goes to Manhattan, doesn't bump cornerContributors, so the single corner
        // contributor isn't enough to fill.
        float fU = clamp(1.0 - dU, 0.0, 1.0);
        float fV = clamp(1.0 - dV, 0.0, 1.0);
        float cornerExtra = max(0.0, fU * fV - manhattan);

        occlusionManhattan += (1.0 / 3.0) * fn * manhattan;
        float contribC = (1.0 / 3.0) * fn * cornerExtra;
        occlusionCorner += contribC;
        if (contribC > 0.0) {
            cornerContributors++;
        }
    }

    float occlusion = occlusionManhattan
            + (cornerContributors >= 2 ? occlusionCorner : 0.0);
    occlusion = clamp(occlusion, 0.0, 1.0);
    return mix(0.2, 1.0, 1.0 - occlusion);
}

// Loop bound for the per-fragment emitter scan. Should match VsShipEmitterList.MAX_EMITTERS — 1024
// entries fit but is excessive per fragment; 128 is plenty for typical scenes (ships rarely have that
// many torches in the inner radius). Excess emitters in the buffer beyond this cap are silently ignored
// at fragment time.
const int VS_EMITTER_LOOP_CAP = 128;

// Distance-attenuated max ship-emitter contribution at this fragment's world position, as a Manhattan
// falloff measured in the emitter's OWN SHIP frame, so the octahedral light bubble turns with the hull.
//
// It is tempting to measure this in world axes instead, on the reasoning that light which has left the
// ship travels the world grid — and that does make the field agree with the flood, which is a world-grid
// BFS. But it also pins the bubble to the world axes while the ship turns underneath it, which reads as
// the lighting sliding off the ship. The bubble turning with the hull is the intended look.
//
// Keeping the ship frame means this field and the flood no longer agree in open air, so the gate below
// cannot be a min() against it. See the gate for how the two are reconciled.//
// Returns BOTH metrics. .x is the ship-frame value, the brightness actually drawn. .y is the same
// falloff measured in world axes -- not drawn, but it is the metric the flood uses, so it is what the
// flood's output has to be judged against. The gate below needs both.
#ifndef VS_FLOOD_GRID
vec2 vs_shipEmitterLight(vec3 worldPos) {
    float maxShip = 0.0;
    float maxWorld = 0.0;
    int n = min(u_VsShipEmitterCount, VS_EMITTER_LOOP_CAP);
    for (int i = 0; i < n; i++) {
        vec4 e = texelFetch(u_VsShipEmitters, i * 2);
        vec4 q = texelFetch(u_VsShipEmitters, i * 2 + 1);
        vec3 offset = worldPos - e.xyz;
        vec3 offset_ship = vs_quatRotateInv(q, offset);
        float distShip = abs(offset_ship.x) + abs(offset_ship.y) + abs(offset_ship.z);
        float distWorld = abs(offset.x) + abs(offset.y) + abs(offset.z);
        maxShip = max(maxShip, max(0.0, e.w - distShip));
        maxWorld = max(maxWorld, max(0.0, e.w - distWorld));
    }
    return vec2(maxShip, maxWorld);
}
#endif

#ifdef VS_FLOOD_GRID
// ===== Flooded ship light: the occlusion gate ============================
//
// The emitter loop above is a pure distance falloff — it has no idea a hull or a hillside is in the
// way, so ship light shines straight through walls. The compute passes under
// assets/valkyrienskies/shaders/compute flood the same emitters through the world's block grid,
// stopping at ship voxels and terrain, and write the result into the section buffer sampled here.
//
// The flood is used only to GATE the emitter field, not to replace it. In open air the two agree
// (both are the light level minus a Manhattan-ish distance) so min() barely changes anything and the
// emitter list keeps its sub-block-precise, continuously-tracking falloff — the property that made
// the grid unusable as the primary source in the first place. Behind a wall the flood is 0 and the
// emitter's contribution is cut, which is exactly the case the emitter list gets wrong.
uniform usamplerBuffer u_VsWorldFromShipSections;
uniform usamplerBuffer u_VsWorldFromShipLut;
// 0 when the flood could not be produced this frame; the gate then stands down entirely rather
// than reading a grid nothing refreshed.
uniform int u_VsFloodGridValid;

// (The old fixed visibility threshold is gone -- the gate is now a ratio against the unoccluded
// reference, which is what lets a rotated ship's pool survive past the flood's Manhattan reach.)

// Must match VsWorldFromShipLightStorage's packed layout.
const uint VSF_SECTION_SIZE_INTS = 1641u;
const uint VSF_LIGHT_START_INTS = 183u;

bool vsf_nextLut(uint base, int coord, out uint next) {
    int start = int(texelFetch(u_VsWorldFromShipLut, int(base)).r);
    uint size = texelFetch(u_VsWorldFromShipLut, int(base) + 1).r;
    int idx = coord - start;
    if (idx < 0 || idx >= int(size)) return true;
    next = texelFetch(u_VsWorldFromShipLut, int(base + 2u + uint(idx))).r;
    return false;
}

bool vsf_section(ivec3 sectionPos, out uint sectionOffset) {
    uint first;
    if (vsf_nextLut(0u, sectionPos.y, first) || first == 0u) return false;
    uint second;
    if (vsf_nextLut(first, sectionPos.x, second) || second == 0u) return false;
    uint index;
    if (vsf_nextLut(second, sectionPos.z, index) || index == 0u) return false;
    sectionOffset = (index - 1u) * VSF_SECTION_SIZE_INTS;
    return true;
}

/** True when the packed blocker bitmap marks this voxel opaque (a ship voxel or world terrain). */
bool vsf_solidAt(uint sectionOffset, ivec3 blockInSection) {
    uint bitOffset = uint(blockInSection.x)
        + uint(blockInSection.z) * 18u
        + uint(blockInSection.y) * 324u;
    uint word = texelFetch(u_VsWorldFromShipSections, int(sectionOffset + (bitOffset >> 5u))).r;
    return (word & (1u << (bitOffset & 31u))) != 0u;
}

float vsf_lightAt(uint sectionOffset, ivec3 blockInSection) {
    uint byteOffset = uint(blockInSection.x)
        + uint(blockInSection.z) * 18u
        + uint(blockInSection.y) * 324u;
    uint raw = texelFetch(u_VsWorldFromShipSections,
        int(sectionOffset + VSF_LIGHT_START_INTS + (byteOffset >> 2u))).r;
    // The whole byte, in 16ths of a level. A 4-bit read here was the grid lock: the field could only
    // take 16 values, so its contours snapped to cell boundaries and the lit pattern jumped a block at
    // a time as a ship drifted.
    return float((raw >> ((byteOffset & 3u) << 3u)) & 0xFFu) * (1.0 / 16.0);
}

// Trilinearly sampled flooded light, or -1 when this fragment sits outside every tracked section.
// The caller must leave the emitter field alone in that case rather than gate it to zero: "no data"
// means the flood simply never covered here, not that the spot is dark.
float vsf_floodTrilinear(vec3 worldPos) {
    vec3 p = worldPos - 0.5;              // grid values live at cell centres
    ivec3 base = ivec3(floor(p));
    vec3 f = p - vec3(base);

    ivec3 baseSection = base >> 4;
    uint baseOffset;
    if (!vsf_section(baseSection, baseOffset)) return -1.0;

    // SOLID taps are dropped and the rest renormalised. A fragment sits ON a block face, so half its
    // taps land inside the block behind it; averaging those in would halve the value at every lit
    // surface and the gate would then eat light that belongs there. Excluding them by the blocker
    // bitmap — rather than by "the tap read zero", which was the earlier rule — keeps genuinely dark
    // air taps in the average, so light still falls off and still stops at walls. Dropping zero taps
    // instead let a single lit tap carry a shadowed fragment, which leaked light around corners.
    float acc = 0.0;
    float weight = 0.0;
    for (int i = 0; i < 8; i++) {
        ivec3 step = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        vec3 w3 = mix(vec3(1.0) - f, f, vec3(step));
        float w = w3.x * w3.y * w3.z;
        if (w <= 0.0) continue;
        ivec3 c = base + step;
        uint offset = baseOffset;
        // All eight taps usually land in one section; only re-walk the LUT when one doesn't.
        if ((c >> 4) != baseSection && !vsf_section(c >> 4, offset)) continue;
        ivec3 inSection = (c & 15) + 1;
        if (vsf_solidAt(offset, inSection)) continue;
        acc += w * vsf_lightAt(offset, inSection);
        weight += w;
    }
    // Every tap opaque: the fragment is buried, so there is no light here.
    return weight > 0.0 ? acc / weight : 0.0;
}
#endif // VS_FLOOD_GRID

void main() {
    vec4 diffuseColor = u_UseRGSS
            ? ws_sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize)
            : ws_sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < _material_alpha_cutoff(v_Material)) {
        discard;
    }
#endif

    vec2 lightCoord = v_LightCoord;
    vec3 worldPos = v_CameraRelWorldPos + vec3(u_VsRenderOrigin);

    // Ship emitters: max-merge their distance-attenuated contribution into the block-light UV.
    // Sub-block-precise because the emitter coords are floats.
    // Ship light on world terrain, straight out of the flood.
    //
    // This used to be a hybrid: a per-fragment distance falloff evaluated from a list of emitters,
    // clamped or gated by the flood. That existed only because the flood was quantised to whole light
    // levels and so moved in block steps, and every attempt to reconcile the two metrics traded one
    // artefact for another -- the emitter falloff is Manhattan in the SHIP's frame while the flood
    // counts in world axes, so a gate either cut a rotated ship's pool along a straight line or leaked
    // unoccluded light past the radius where the two disagreed.
    //
    // With the field carrying 16ths of a level there is nothing left to reconcile. The flood is the
    // light: propagation exactly as vanilla does it -- a 6-neighbour BFS, one level per block, hard
    // occlusion -- sampled with sub-block precision so it slides with the ship instead of snapping to
    // the world grid. Occlusion and falloff now come from one field that agrees with itself.
#ifdef VS_FLOOD_GRID
    float shipFlood = vsf_floodTrilinear(worldPos);
    // u_VsFloodGridValid still guards the one case the grid cannot speak for: a frame where the flood
    // did not run at all. With the flood now the sole source of ship light, "no data" has to mean no
    // light rather than falling back to something else.
    float shipLight = u_VsFloodGridValid != 0 ? max(shipFlood, 0.0) : 0.0;
#else
    // No compute support: the CPU path still supplies only the emitter list, so fall back to the
    // distance falloff. Unoccluded, as it always was on this path.
    float shipLight = vs_shipEmitterLight(worldPos).x;
#endif
#ifdef VS_DEBUG_FLOOD
    float vsf_debugApplied = shipLight;
    float vsf_debugFlood = shipFlood;
#endif
    if (shipLight > 0.0) {
        // MC packs block-light at U = (lightLevel + 0.5) / 16.
        float shipLightUv = (shipLight + 0.5) / 16.0;
        lightCoord.x = max(lightCoord.x, shipLightUv);
    }

    vec4 lightSample = texture(u_LightTex, clamp(lightCoord, vec2(WS_UV_MIN), vec2(WS_UV_MAX)));

    // Tint x lightmap. AO and shade are applied below as a single combined multiplier so vanilla world
    // AO and ship-to-world AO stack the way vanilla's per-vertex averaging would, instead of multiplying
    // independently.
    diffuseColor.rgb *= v_Color.rgb * lightSample.rgb;

    // Combined AO + shade. v_Color.a is PURE vanilla AO (no shade); ship AO comes from per-fragment
    // ws_shipAo(). Combine the two AO sources additively — vanilla's per-vertex averaging compounds
    // overlap that way (two op cells at a corner -> loss 0.2 + 0.2 = 0.4, not 0.8 x 0.8 = 0.64). Floor
    // 0.2 matches sodium's deepest AO value for opaque blocks. Face shade (UP=1, DOWN=0.5, N/S=0.8,
    // E/W=0.6) is applied after, mirroring sodium's applySidedBrightness step. Slot 6/7
    // (unshaded/fullbright) skip both AO and shade via v_IsShaded.
    float ao = v_Color.a;
    if (v_IsShaded == 1) {
        float shipAo = ws_shipAo(worldPos, v_WorldNormal);
        float combined = max(0.2, ao - (1.0 - shipAo));
        float shade = 1.0;
        if (v_WorldNormal.y < -0.5)          shade = 0.5; // DOWN
        else if (abs(v_WorldNormal.y) > 0.5) shade = 1.0; // UP
        else if (abs(v_WorldNormal.x) > 0.5) shade = 0.6; // EAST / WEST
        else                                 shade = 0.8; // NORTH / SOUTH
        diffuseColor.rgb *= combined * shade;
    } else {
        diffuseColor.rgb *= ao;
    }


    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, v_FadeFactor);

#ifdef VS_DEBUG_FLOOD
    // ===== Debug paint =====================================================================
    // Written OVER the finished fragColor rather than returning early: an early return lets the compiler
    // dead-code-eliminate the earlier texture reads, and sodium's bindUniform then throws
    // "No uniform exists with name: u_BlockTex" at link time. The 1e-4 term keeps them all live.
    {
        float dbgFlood = max(vsf_debugFlood, 0.0);
#if VS_DEBUG_FLOOD == 2
        // Mode 2 — accuracy against vanilla. RED = the vanilla block-light level already baked into this
        // fragment's lightmap UV, GREEN = the flooded ship light. Put a world glowstone beside a ship one at the
        // same height and the two fields should have the same shape and magnitude: agreement reads yellow,
        // red-only is light the flood failed to reproduce, green-only is light it invented.
        float dbgVanilla = max(0.0, v_LightCoord.x * 16.0 - 0.5);
        fragColor = vec4(clamp(dbgVanilla / 15.0, 0.0, 1.0),
                         clamp(dbgFlood / 15.0, 0.0, 1.0),
                         0.0, 1.0) + fragColor * 1.0e-4;
#else
        // Mode 1 — leak hunt. GREEN = light the flood justifies, RED = light applied on top of that
        // (the leak), BLUE = fragment outside every tracked section so the gate cannot act at all.
        float dbgLeak = max(0.0, vsf_debugApplied - dbgFlood);
        fragColor = vec4(clamp(dbgLeak / 8.0, 0.0, 1.0),
                         clamp(dbgFlood / 15.0, 0.0, 1.0),
                         (vsf_debugFlood < 0.0 && vsf_debugApplied > 0.0) ? 1.0 : 0.0,
                         1.0) + fragColor * 1.0e-4;
#endif
    }
#endif
}
