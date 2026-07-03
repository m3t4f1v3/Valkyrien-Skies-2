#!/usr/bin/env python3
"""Assemble a VS chunk FSH for offline glslangValidator checking:
   - strip the #import of sodium's fog include and inline a stub
   - inject the feature #defines after #version
   - collapse backslash line-continuations (glslang lexer chokes on them)
"""
import sys

FOG_STUB = """
// --- stub for sodium:include/fog.glsl ---
vec4 _linearFog(vec4 color, float dist, vec4 fogColor, float fogStart, float fogEnd) {
    float f = clamp((fogEnd - dist) / max(fogEnd - fogStart, 1e-5), 0.0, 1.0);
    return vec4(mix(fogColor.rgb, color.rgb, f), color.a);
}
// --- end stub ---
"""

DEFINES = [
    "USE_FRAGMENT_DISCARD",
    "VS_DYNAMIC_LIGHT",
    "VS_DYNAMIC_BIOME",
    "VS_DYNAMIC_SHADE",
    "VS_SHIP_ON_SHIP",
]

src = open(sys.argv[1]).read()
# collapse backslash-newline continuations
src = src.replace("\\\n", " ")
out = []
for line in src.split("\n"):
    if line.startswith("#import"):
        out.append(FOG_STUB)
    elif line.startswith("#version"):
        out.append(line)
        for d in DEFINES:
            out.append("#define %s" % d)
    else:
        out.append(line)
sys.stdout.write("\n".join(out))
