### Normal-Aware Ship Rendering (Custom Core Shader)

This feature makes ships use world space normals for shading instead of pre-baked
brightness value baked into vertex colors along with lights and ambient occlusion.
This is achieved by disabling vanilla fake shading and swapping shaders for block
render types with their modified versions that replicate vanilla shading, similar
to how Indigo calculates shading for rotated quads based on their normals.
It can be enabled or disabled via config.

Quads that vanilla exempts from shading (`"shade": false` -- torches, grass, redstone
dust, chains, fire) still have to be exempt after the swap, so the chunk builder has
to carry one bit of side information from `getShade` all the way to the vertex buffer.
It does that by offsetting the brightness value and decoding it further down the
pipeline, where the marker becomes `Color.a = 0` for the shader.

#### Mixins

* `MixinRenderChunkRegion#maxShadeForShips`
    * If config is enabled, return the max brightness value. This is used both
  by vanilla `ModelBlockRenderer` and Fabric `AbstractBlockRenderContext`.
    * If the block is rendered without shading (tall grass, torches, etc),
  return an even larger value that is later processed by another mixin.

* `MixinModelBlockRenderer#vs$decodeNoShadeMarker`
    * Wraps the `putBulkData` call inside `ModelBlockRenderer#putQuadData`, where the
  `BakedQuad` is still in hand. Undoes the offset on the brightness array and, for
  a no-shade quad, routes the vertices through `NoShadeVertexConsumer` so they reach
  the shader with alpha zero.

##### Two traps this decode exists to avoid

Both were live bugs; the visible symptom was powered redstone dust rendering **yellow**
on ships, on Forge, with no Sodium/Embeddium.

1. **Do not decode by redefining `VertexConsumer#putBulkData`.** That is what this used
   to do, from an interface mixin, and it only ever worked in a Mojang-mapped dev
   client. An un-annotated method merged into an interface gets no refmap entry and is
   not reobfuscated, so on a production Forge runtime -- where the target is
   `m_85995_` -- Mixin found nothing named `putBulkData` to replace and silently added
   an unused default method. The offset then survived into the vertex buffer,
   brightness came out about 5x, and `BufferBuilder#vertex` writes colour as
   `(byte)(int)(c * 255)`, which wraps instead of clamping. Power-15 dust is tinted
   (255, 51, 0), so it landed on (251, 255, 0). Low-power dust has no green to
   overflow, which is why only *activated* redstone looked wrong.
   Wrapping the call site also keeps the platform's real `putBulkData` in the chain;
   the old copy was a copy of *vanilla's* body and dropped Forge's
   `applyBakedLighting`, `applyBakedNormals` and baked vertex alpha for every block in
   the game, since it was global and had no config check.

2. **Do not decode from the magnitude of the brightness value.** A `brightness > 4f`
   test cannot survive ambient occlusion: `AmbientOcclusionFace#calculate` finishes
   with `brightness[j] *= shade`, so a no-shade quad in an AO-enabled model arrives as
   `ao * (base + 4)` -- under the threshold entirely once `ao` drops below 0.8, and
   decoding to the wrong number above it. Read `BakedQuad#isShade()` instead.
   Redstone dust only escaped this one because its model also sets
   `"ambientocclusion": false`; chain, sea pickle, big dripleaf, spore blossom,
   seagrass and the calibrated sculk sensor do not.

`MixinAbstractBlockRenderContext` (Fabric) still decodes Indigo's path by magnitude and
carries trap 2. It is left alone here because it is untested on that loader.
