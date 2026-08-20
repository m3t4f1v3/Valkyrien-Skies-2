#!/usr/bin/env python3
"""Splice the verified seam2 algorithm into both chunk FSHs.

Replaces everything from the face-selection comment through the
`totalLoss += contrib;` accumulator with the spec construction:
occluder-local frame, multi-face max, parallel-face footprint branch,
B-side subtend + single midpoint M, A-side lerp t = dist/REACH, and the
multiplicative AABB falloff. Same template for both shaders, differing
only in symbol names.
"""
import sys

TEMPLATE = """        // This face's square, normal and the fragment lifted into the occluder
        // ship's LOCAL frame: square B's corners are analytic there, the
        // subtend cardinals are the plain <0.5,0>/<0,0.5> ship axes, and the
        // falloff box is axis-aligned. (Distances are rotation-invariant, so
        // matching in local space picks the same pairs as world space.)
        vec3 aLoc[4] = vec3[](
            %INV%(q, Acorners[0] - voxel.xyz),
            %INV%(q, Acorners[1] - voxel.xyz),
            %INV%(q, Acorners[2] - voxel.xyz),
            %INV%(q, Acorners[3] - voxel.xyz));
        vec3 fragLocal = %INV%(q, fragWorldPos - voxel.xyz);
        vec3 towardSelfLocal = %INV%(q, %FACEC% - voxel.xyz);
        vec3 normalALocal = %INV%(q, %NORMA%);
        float frontW = clamp(frontness / 0.25, 0.0, 1.0);

        // Evaluate EVERY voxel face whose outward normal points toward this
        // fragment's cell (up to 3) and keep the MAX contribution. A hard
        // dominant-axis pick flips between adjacent receiving cells (at yaw 45
        // the cardinal cells match the voxel's bottom face, the diagonal cells
        // a side face) and every flip is a visible border step; the max of the
        // per-face fields stays continuous when the argmax switches, and in
        // grid-aligned cases the candidate faces share the seam edge and tie
        // exactly, keeping vanilla parity.
        float contrib = 0.0;
        for (int axisI = 0; axisI < 3; axisI++) {
            if (abs(towardSelfLocal[axisI]) < 1e-6) continue;
            vec3 normalB = vec3(0.0);
            normalB[axisI] = sign(towardSelfLocal[axisI]);

            // Square B: that face of the unit voxel, ship-local, offset half a
            // voxel along normalB onto the actual surface so its corners can
            // COINCIDE with A's at the seam ("the same vertex belongs to both
            // squares").
            vec3 b00, b01, b10, b11;
            %LOCALFACE%(normalB * 0.5, normalB, b00, b01, b10, b11);
            vec3 bLoc[4] = vec3[](b00, b01, b10, b11);

            float faceLoss = 0.0;
            if (dot(normalALocal, normalB) < -0.9) {
                // B faces this face head-on (voxel hovering over/against it):
                // there is no seam -- every corner pair ties and the 2-pair
                // pick below would be a loop-order artifact. The correct merge
                // quad is B's face itself (the footprint shadow), which meets
                // the neighboring cells' seam curtains at the borders.
                vec3 lo = min(min(bLoc[0], bLoc[1]), min(bLoc[2], bLoc[3]));
                vec3 hi = max(max(bLoc[0], bLoc[1]), max(bLoc[2], bLoc[3]));
                vec3 ex = max(max(lo - fragLocal, fragLocal - hi), vec3(0.0));
                vec3 w = clamp(vec3(1.0) - ex / %C%_REACH, vec3(0.0), vec3(1.0));
                faceLoss = %C%_STRENGTH * w.x * w.y * w.z * frontW;
            } else {
                // Scan the 16 corner pairs; take the two nearest that share no
                // vertex on either square (ascending-sort by distance, first
                // two unique pairs) -- the two endpoints of the seam.
                int bi0 = -1, bj0 = -1;
                float best0 = 1e30;
                for (int ai = 0; ai < 4; ai++)
                    for (int bj = 0; bj < 4; bj++) {
                        float dd = distance(aLoc[ai], bLoc[bj]);
                        if (dd < best0) { best0 = dd; bi0 = ai; bj0 = bj; }
                    }
                int bi1 = -1, bj1 = -1;
                float best1 = 1e30;
                for (int ai = 0; ai < 4; ai++) {
                    if (ai == bi0) continue;
                    for (int bj = 0; bj < 4; bj++) {
                        if (bj == bj0) continue;
                        float dd = distance(aLoc[ai], bLoc[bj]);
                        if (dd < best1) { best1 = dd; bi1 = ai; bj1 = bj; }
                    }
                }
                if (bi1 < 0 || best0 > %C%_MATCH_REACH) continue;

                // === SUBTEND (B side) ===
                // Push the two matched B corners half a ship block along the
                // face cardinal (<0.5,0> / <0,0.5> in ship space) that got the
                // SMALLER |dot| with their pair vector (bigger dot -> use the
                // other one), signed into the face interior. The midpoint M of
                // the subtended line is the merge target.
                vec3 bAbsN = abs(normalB);
                vec3 bU = bAbsN.x > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                vec3 bV = bAbsN.z > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
                vec3 sb0 = bLoc[bj0], sb1 = bLoc[bj1];
                vec3 pairVec = sb1 - sb0;
                vec3 axis = (abs(dot(bU, pairVec)) <= abs(dot(bV, pairVec)) ? bU : bV) * 0.5;
                vec3 interior = normalB * 0.5 - 0.5 * (sb0 + sb1);
                vec3 h = dot(axis, interior) >= 0.0 ? axis : -axis;
                vec3 su = sb0 + h;
                vec3 sv = sb1 + h;
                vec3 M = 0.5 * (su + sv);

                // === MERGE: A's matched corners lerp toward M by
                // t = pairDist / REACH. Touching corners stay put, so the quad
                // spans the seam edge -> vanilla-exact gradient; at one block
                // of separation they have fully collapsed onto B's face, so
                // the field dies exactly where the next cell's reconstruction
                // starts from zero (cell-border continuity).
                vec3 m0 = mix(aLoc[bi0], M, clamp(best0 / %C%_REACH, 0.0, 1.0));
                vec3 m1 = mix(aLoc[bi1], M, clamp(best1 / %C%_REACH, 0.0, 1.0));

#ifdef VS_DEBUG_SEAM_AO
                // DEBUG: blue = A corners; orange = merge-quad outline (the
                // falloff source); green = the subtended line; pink = M.
                {
                    vec3 wSu = voxel.xyz + %FWD%(q, su);
                    vec3 wSv = voxel.xyz + %FWD%(q, sv);
                    vec3 wM0 = voxel.xyz + %FWD%(q, m0);
                    vec3 wM1 = voxel.xyz + %FWD%(q, m1);
                    float e = min(min(%SEG%(fragWorldPos, wSu, wSv), %SEG%(fragWorldPos, wSv, wM1)),
                                  min(%SEG%(fragWorldPos, wM1, wM0), %SEG%(fragWorldPos, wM0, wSu)));
                    for (int k = 0; k < 4; k++)
                        if (distance(fragWorldPos, Acorners[k]) < %DBG%_VERTEX_RADIUS) dbgVertex = 1.0;
                    if (e < %DBG%_EDGE_RADIUS) dbgVertex = 2.0;
                    if (%SEG%(fragWorldPos, wSu, wSv) < %DBG%_EDGE_RADIUS) dbgVertex = 3.0;
                    if (distance(fragWorldPos, voxel.xyz + %FWD%(q, M)) < %DBG%_VERTEX_RADIUS) dbgVertex = 4.0;
                }
#endif

                // === PRODUCT FALLOFF (ship-frame box) ===
                // Per-axis exterior offsets from the merge quad's ship-local
                // bounding box, combined MULTIPLICATIVELY. Vanilla's bilinear
                // per-vertex AO decomposes into sums of products of axis
                // ramps, so the product form reproduces it exactly for
                // grid-aligned cases (summing the offsets -- L1 -- decays
                // diagonals twice as fast as vanilla; Euclidean distance
                // rounds the corners radially).
                vec3 lo = min(min(su, sv), min(m0, m1));
                vec3 hi = max(max(su, sv), max(m0, m1));
                vec3 ex = max(max(lo - fragLocal, fragLocal - hi), vec3(0.0));
                vec3 w = clamp(vec3(1.0) - ex / %C%_REACH, vec3(0.0), vec3(1.0));
                faceLoss = %C%_STRENGTH * w.x * w.y * w.z * frontW;
            }
            contrib = max(contrib, faceLoss);
        }
        // Vanilla SUMS per-sample losses (each solid sample subtracts 0.2 in
        // the 4-sample vertex average), so accumulate additively across
        // voxels; the clamp at return matches vanilla's 0.2 multiplier floor.
        totalLoss += contrib;"""

FILES = {
    "common/src/main/resources/assets/valkyrienskies/shaders/blocks/world_layer_opaque.fsh": {
        "%INV%": "vs_quatRotateInv", "%FWD%": "ws_seamQuatRotate",
        "%LOCALFACE%": "ws_seamLocalFace", "%SEG%": "ws_distToSeg",
        "%C%": "WS_SEAM", "%DBG%": "WS_DBG",
        "%FACEC%": "faceCenter", "%NORMA%": "normal",
    },
    "common/src/main/resources/assets/valkyrienskies/shaders/blocks/block_layer_opaque.fsh": {
        "%INV%": "vs_sosQuatRotateInv", "%FWD%": "vs_seamQuatRotate",
        "%LOCALFACE%": "vs_seamLocalFace", "%SEG%": "vs_distToSeg",
        "%C%": "VS_SEAM", "%DBG%": "VS_DBG",
        "%FACEC%": "faceCenterWorld", "%NORMA%": "worldNA",
    },
}

START = "        // Which of the candidate voxel's faces points back at this face?"
END = "        totalLoss += contrib;"

root = sys.argv[1]
for rel, subs in FILES.items():
    path = f"{root}/{rel}"
    src = open(path).read()
    i = src.index(START)
    j = src.index(END, i) + len(END)
    body = TEMPLATE
    for k, v in subs.items():
        body = body.replace(k, v)
    open(path, "w").write(src[:i] + body + src[j:])
    print(f"spliced {rel}: replaced {j - i} chars with {len(body)}")
