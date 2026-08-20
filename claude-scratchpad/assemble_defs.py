import sys
FOG_STUB = """
vec4 _linearFog(vec4 color, float dist, vec4 fogColor, float fogStart, float fogEnd) {
    float f = clamp((fogEnd - dist) / max(fogEnd - fogStart, 1e-5), 0.0, 1.0);
    return vec4(mix(fogColor.rgb, color.rgb, f), color.a);
}
"""
src = open(sys.argv[1]).read().replace("\\\n", " ")
defines = sys.argv[3:]
out = []
for line in src.split("\n"):
    if line.startswith("#import"):
        out.append(FOG_STUB)
    elif line.startswith("#version"):
        out.append(line)
        for d in defines:
            out.append("#define %s" % d)
    else:
        out.append(line)
open(sys.argv[2], "w").write("\n".join(out))
