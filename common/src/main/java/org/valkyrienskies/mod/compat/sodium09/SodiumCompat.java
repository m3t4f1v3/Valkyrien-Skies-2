package org.valkyrienskies.mod.compat.sodium09;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.gl.shader.GlShader;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderType;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderBindingPoints;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.ShipRendererKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.render.batched.ShipBatchRenderer;
import org.valkyrienskies.mod.common.hooks.VSGameEvents;
import org.valkyrienskies.mod.common.hooks.VSGameEvents.ShipRenderEventSodium;
import org.valkyrienskies.mod.common.render.light.VsDynamicLight;
import org.valkyrienskies.mod.compat.LoadedMods;
import org.valkyrienskies.mod.compat.LoadedMods.FlywheelVersion;
import org.valkyrienskies.mod.compat.VSRenderer;
import org.valkyrienskies.mod.compat.flywheel.FlywheelDynLightCompat;
import org.valkyrienskies.mod.compat.iris.IrisCompat;
import org.valkyrienskies.mod.compat.sodium.shader.VsShipBiomeColorStorage;
import org.valkyrienskies.mod.compat.sodium.shader.VsShipEmitterList;
import org.valkyrienskies.mod.compat.sodium.shader.VsShipLightStorage;
import org.valkyrienskies.mod.compat.sodium.shader.VsShipOccluderList;
import org.valkyrienskies.mod.compat.sodium.shader.VsWorldFromShipLightStorage;
import org.valkyrienskies.mod.mixin.ValkyrienCommonMixinConfigPlugin;
import org.valkyrienskies.mod.mixin.accessors.client.render.LevelRendererAccessor;
import org.valkyrienskies.mod.mixin.mod_compat.sodium09.SodiumWorldRendererAccessor;
import org.valkyrienskies.mod.mixin.mod_compat.sodium09.UniformBufferManagerAccessor;
import org.valkyrienskies.mod.mixinducks.mod_compat.sodium09.RenderSectionManagerDuck;

/**
 * Sodium 0.9 flavour of the ship terrain renderer.
 *
 * <p>The shape is the same as the 0.5 compat layer — build a render list per ship, then draw each ship
 * with sodium's own chunk renderer under a ship-space model-view — but three things changed underneath
 * and drive most of the differences here:
 *
 * <ul>
 *   <li>The projection and model-view matrices no longer live in plain uniforms. They sit in the
 *       {@code u_Globals} uniform block, which {@link UniformBufferManager} writes once per frame. Each
 *       ship needs its own model-view, so we clear that once-per-frame latch and rewrite the block per
 *       ship, then restore the world's matrices before handing control back.</li>
 *   <li>Draw-command batches are cached per region and pass. The cache is oblivious to which render list
 *       filled it, so it has to be dropped around every ship draw — see
 *       {@link ShipRenderLists#invalidateCachedBatches}.</li>
 *   <li>{@code ChunkShaderInterface} became an interface and gained {@code setRegionData}; the VS
 *       programs extend sodium's {@code DefaultShaderInterface} rather than reimplementing it.</li>
 * </ul>
 *
 * <p>Everything that does not touch sodium — the light, biome, emitter and occluder storages — is shared
 * with the 0.5 layer through {@link VsDynamicLight}.
 */
public class SodiumCompat {
    /**
     * Composite cache key for ship shader programs. Sodium's {@link ChunkShaderOptions} alone isn't
     * enough — our shaders branch on compile-time defines derived from VS config, so we need a separate
     * compiled program per combination.
     */
    private record ShaderCacheKey(ChunkShaderOptions options, int vsFeatureBits) {
    }

    /**
     * Bit flags for VS-specific shader feature defines. Package-visible because {@link ShipThing} needs
     * them at construction to decide which uniforms to bind.
     */
    static final int FEATURE_BIOME = 1;
    static final int FEATURE_LIGHT = 2;
    static final int FEATURE_SHADE = 4;
    /**
     * Ship FSH also queries the world-from-ship storage (populated for the world chunk shader) so ship-A
     * voxels can shadow / illuminate ship-B.
     */
    static final int FEATURE_SHIP_ON_SHIP = 8;
    /** Gate the emitter falloff by the compute-flooded light grid, so hulls and terrain block it. */
    static final int FEATURE_FLOOD_GRID = 16;
    /** Diagnostic paint that visualises the flood gate instead of shading terrain (mode in these two bits). */
    static final int FEATURE_DEBUG_FLOOD_1 = 32;
    static final int FEATURE_DEBUG_FLOOD_2 = 64;
    /** debugFloodPaint 3: paint SHIP chunks with where their own block light comes from. */
    static final int FEATURE_DEBUG_SHIP_LIGHT = 128;
    /** Ship-cast ambient occlusion. Off by default -- the per-fragment occluder scan dominates. */
    static final int FEATURE_SHIP_AO = 512;
    // debugFloodPaint = 5: paint the seam-AO loss instead of the shaded colour, as on the 0.5 path.
    static final int FEATURE_DEBUG_SEAM_AO = 1024;
    /**
     * Cross-ship AO merging compiled OUT. Zeroing the claims alone already produces the unmerged
     * FIELD, but the shader would still walk every host's partner list, build the claim matrix and
     * test every run against every host to reach that result. This bit lets it skip all of it -- most
     * importantly the per-run early-out in pass 2, where a run not owned by the host being processed
     * is rejected before the weight lookup and the per-voxel loop.
     */
    static final int FEATURE_SEAM_NO_MERGE = 2048;

    private static final Map<ShaderCacheKey, GlProgram<ShipThing>> cachedPrograms = new HashMap<>();
    private static final Map<ShaderCacheKey, GlProgram<WorldThing>> cachedWorldPrograms = new HashMap<>();

    private static final ThreadLocal<Matrix4f> CURRENT_TRANSFORM = new ThreadLocal<>();
    private static final ThreadLocal<Matrix4f> CURRENT_LOCAL_TO_WORLD = new ThreadLocal<>();
    private static final ThreadLocal<int[]> CURRENT_RENDER_ORIGIN = new ThreadLocal<>();
    /** Per-frame occluder-list index of the ship currently being drawn; -1 when drawing anything else. */
    private static final ThreadLocal<Integer> CURRENT_SELF_SHIP_INDEX = ThreadLocal.withInitial(() -> -1);
    private static final ThreadLocal<Boolean> IS_RENDERING_SHIP = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> IS_LAST_SHIP_IN_BATCH = ThreadLocal.withInitial(() -> true);

    // Texture units for the VS buffer textures. Sodium 0.9 uses units 0, 1 and 2 (block atlas, lightmap
    // and the per-section time buffer, see ChunkShaderTextureSlot); everything below starts past those
    // and matches the units the 0.5 layer picked, so the shaders' sampler bindings are identical.
    public static final int LIGHT_SECTIONS_TEXTURE_UNIT = VsDynamicLight.LIGHT_SECTIONS_TEXTURE_UNIT;
    public static final int LIGHT_LUT_TEXTURE_UNIT = VsDynamicLight.LIGHT_LUT_TEXTURE_UNIT;
    public static final int BIOME_SECTIONS_TEXTURE_UNIT = VsDynamicLight.BIOME_SECTIONS_TEXTURE_UNIT;
    public static final int BIOME_LUT_TEXTURE_UNIT = VsDynamicLight.BIOME_LUT_TEXTURE_UNIT;
    public static final int WORLD_FROM_SHIP_SECTIONS_TEXTURE_UNIT =
        VsDynamicLight.WORLD_FROM_SHIP_SECTIONS_TEXTURE_UNIT;
    public static final int WORLD_FROM_SHIP_LUT_TEXTURE_UNIT = VsDynamicLight.WORLD_FROM_SHIP_LUT_TEXTURE_UNIT;
    public static final int SHIP_EMITTER_LIST_TEXTURE_UNIT = VsDynamicLight.SHIP_EMITTER_LIST_TEXTURE_UNIT;
    public static final int SHIP_OCCLUDER_LIST_TEXTURE_UNIT = VsDynamicLight.SHIP_OCCLUDER_LIST_TEXTURE_UNIT;
    public static final int SEAM_RUN_HEADERS_TEXTURE_UNIT = VsDynamicLight.SEAM_RUN_HEADERS_TEXTURE_UNIT;
    public static final int SEAM_SHIP_DIR_TEXTURE_UNIT = VsDynamicLight.SEAM_SHIP_DIR_TEXTURE_UNIT;
    public static final int SEAM_GRID_TEXTURE_UNIT = VsDynamicLight.SEAM_GRID_TEXTURE_UNIT;

    private static final double WORLD_FROM_SHIP_VISIBILITY_PADDING = 32.0;

    // --- Storages (shared with the 0.5 layer) ------------------------------

    public static VsShipLightStorage getLightStorage() {
        return VsDynamicLight.getLightStorage();
    }

    public static VsShipBiomeColorStorage getBiomeStorage() {
        return VsDynamicLight.getBiomeStorage();
    }

    public static VsWorldFromShipLightStorage getWorldFromShipStorage() {
        return VsDynamicLight.getWorldFromShipStorage();
    }

    public static VsShipEmitterList getShipEmitterList() {
        return VsDynamicLight.getShipEmitterList();
    }

    public static VsShipOccluderList getShipOccluderList() {
        return VsDynamicLight.getShipOccluderList();
    }

    public static void deleteStorages() {
        VsDynamicLight.deleteStorages();
    }

    public static void populateWorldFromShipsForFrame(final ClientLevel level) {
        populateWorldFromShipsForFrame(level, null);
    }

    public static void populateWorldFromShipsForFrame(final ClientLevel level, final Viewport viewport) {
        VsDynamicLight.populateWorldFromShipsForFrame(level,
            ship -> isShipRelevantToWorldFromShipFrame(ship, viewport));
    }

    public static void populateLightSectionStorage(final ClientLevel level) {
        VsDynamicLight.populateLightSectionStorage(level);
    }

    public static void populateBiomeSectionStorage(final ClientLevel level) {
        VsDynamicLight.populateBiomeSectionStorage(level);
    }

    public static void dispatchGpuLightFlood() {
        VsDynamicLight.dispatchGpuLightFlood();
    }

    private static boolean isShipRelevantToWorldFromShipFrame(final ClientShip ship, final Viewport viewport) {
        if (viewport == null) {
            return true;
        }
        final AABBdc aabb = ship.getRenderAABB();
        if (aabb == null) {
            return false;
        }
        return isExpandedAabbVisible(viewport, aabb, WORLD_FROM_SHIP_VISIBILITY_PADDING);
    }

    /**
     * Frustum test for an arbitrary world-space box.
     *
     * <p>0.9's {@code Viewport#isBoxVisible} only takes a section origin — the padded radius is fixed at
     * one chunk section — so an arbitrary AABB has to go through {@code isBoxVisibleDirect}, which works
     * in camera-relative floats and takes a single half-extent. We pass the largest half-extent, making
     * the test conservative (never culls something visible).
     */
    private static boolean isExpandedAabbVisible(final Viewport viewport, final AABBdc aabb, final double padding) {
        final CameraTransform camera = viewport.getTransform();

        final double centerX = (aabb.minX() + aabb.maxX()) * 0.5;
        final double centerY = (aabb.minY() + aabb.maxY()) * 0.5;
        final double centerZ = (aabb.minZ() + aabb.maxZ()) * 0.5;

        final double halfX = (aabb.maxX() - aabb.minX()) * 0.5 + padding;
        final double halfY = (aabb.maxY() - aabb.minY()) * 0.5 + padding;
        final double halfZ = (aabb.maxZ() - aabb.minZ()) * 0.5 + padding;

        return viewport.isBoxVisibleDirect(
            (float) (centerX - camera.x),
            (float) (centerY - camera.y),
            (float) (centerZ - camera.z),
            (float) Math.max(halfX, Math.max(halfY, halfZ)));
    }

    // --- Shader programs ---------------------------------------------------

    /** Snapshot the VS shader-feature config bits at program-build time. */
    private static int computeFeatureBits() {
        int bits = 0;
        if (VSGameConfig.CLIENT.getDynamicShipBiomeTinting()) {
            bits |= FEATURE_BIOME;
        }
        if (VSGameConfig.CLIENT.getDynamicShipLighting()) {
            bits |= FEATURE_LIGHT;
        }
        if (VSGameConfig.CLIENT.getBetterVanillaShipShading()) {
            bits |= FEATURE_SHADE;
        }
        if (VSGameConfig.CLIENT.getShipAmbientOcclusion()) {
            bits |= FEATURE_SHIP_AO;
            if (!VSGameConfig.CLIENT.getShipAmbientOcclusionMerging()) bits |= FEATURE_SEAM_NO_MERGE;
        }
        if (VSGameConfig.CLIENT.getDynamicShipToWorldLighting()) {
            bits |= FEATURE_SHIP_ON_SHIP;
            // The flood grid only ever gates ship-on-ship light, so it rides along with that bit.
            if (VsDynamicLight.isGpuFloodActive()) {
                bits |= FEATURE_FLOOD_GRID;
            }
        }
        return bits;
    }

    /**
     * True iff at least one of the ship-shader features is enabled. When false there's no reason to swap
     * in the VS ship shader at all — the mesher mixin's gates fall through to sodium's native vertex
     * encoding and sodium's stock chunk shader renders ship blocks correctly.
     */
    public static boolean anyShipShaderFeatureEnabled() {
        return computeFeatureBits() != 0;
    }

    @SuppressWarnings("unchecked")
    public static GlProgram<ChunkShaderInterface> getOrCreateShipProgram(final ChunkShaderOptions options) {
        int features = computeFeatureBits();
        final int paint = VSGameConfig.CLIENT.getDebugFloodPaint();
        if (paint == 3 || paint == 4) {
            features |= FEATURE_DEBUG_SHIP_LIGHT;
        }
        if (paint == 5) {
            features |= FEATURE_DEBUG_SEAM_AO;
        }
        final ShaderCacheKey key = new ShaderCacheKey(options, features);
        GlProgram<ShipThing> program = cachedPrograms.get(key);
        if (program == null) {
            program = createShipShader("blocks09/block_layer_opaque", options, features);
            cachedPrograms.put(key, program);
        }
        return (GlProgram<ChunkShaderInterface>) (Object) program;
    }

    @SuppressWarnings("unchecked")
    public static GlProgram<ChunkShaderInterface> getOrCreateWorldProgram(final ChunkShaderOptions options) {
        // The world shader has one VS feature of its own — the flood-grid gate — so it needs the same
        // (options, features) cache key the ship shader uses, not options alone.
        int features = VsDynamicLight.isGpuFloodActive() ? FEATURE_FLOOD_GRID : 0;
        // The world program builds its own feature bits rather than calling computeFeatureBits(), so
        // every world-shader feature has to be added HERE as well. Ship-cast AO lives in the world
        // shader (ws_shipAo shades world terrain under a ship), and omitting it here compiled the AO
        // out unconditionally -- the config appeared to do nothing at all.
        // See the 0.5 copy: with no occluders in frame the seam AO provably contributes nothing, and
        // simply having its code in the world shader costs ~1.27ms/frame at 1080p in occupancy.
        if (VSGameConfig.CLIENT.getShipAmbientOcclusion()
            && VsDynamicLight.getShipOccluderList().size() > 0) {
            features |= FEATURE_SHIP_AO;
            if (!VSGameConfig.CLIENT.getShipAmbientOcclusionMerging()) features |= FEATURE_SEAM_NO_MERGE;
        }
        if (features != 0) {
            final int paint = VSGameConfig.CLIENT.getDebugFloodPaint();
            if (paint == 1) {
                features |= FEATURE_DEBUG_FLOOD_1;
            } else if (paint == 5) {
                features |= FEATURE_DEBUG_SEAM_AO;
            } else if (paint >= 2) {
                features |= FEATURE_DEBUG_FLOOD_2;
            }
        }
        final ShaderCacheKey key = new ShaderCacheKey(options, features);
        GlProgram<WorldThing> program = cachedWorldPrograms.get(key);
        if (program == null) {
            program = createWorldShader("blocks09/world_layer_opaque", options, features);
            cachedWorldPrograms.put(key, program);
        }
        return (GlProgram<ChunkShaderInterface>) (Object) program;
    }

    /**
     * True when ship-to-world dynamic lighting should override sodium's stock chunk shader. Gated only
     * on the config because the mesher mixin repacks the vertex colour for world chunks whenever the
     * config is on; sodium's stock shader would misinterpret those bits, so the swap MUST stay aligned
     * with the packing — no "skip when no ships are nearby" optimisation. The world shader runs fine
     * with empty storage (LUT lookups return 0, emitter loop runs 0 times, no visible effect).
     */
    public static boolean shouldUseWorldFromShipShader() {
        return VSGameConfig.CLIENT.getDynamicShipToWorldLighting();
    }

    private static GlProgram<ShipThing> createShipShader(final String path, final ChunkShaderOptions options,
        final int features) {
        final ShaderConstants constants = createShaderConstants(options, features);

        final GlShader vertShader = loadVsShader(ShaderType.VERTEX, path + ".vsh", constants);
        final GlShader fragShader = loadVsShader(ShaderType.FRAGMENT, path + ".fsh", constants);

        try {
            return GlProgram.builder(new ResourceLocation("valkyrienskies", "chunk_shader"))
                .attachShader(vertShader)
                .attachShader(fragShader)
                .bindAttribute("a_Position", ChunkShaderBindingPoints.ATTRIBUTE_POSITION)
                .bindAttribute("a_Color", ChunkShaderBindingPoints.ATTRIBUTE_COLOR)
                .bindAttribute("a_TexCoord", ChunkShaderBindingPoints.ATTRIBUTE_TEXTURE)
                .bindAttribute("a_LightAndData", ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_MATERIAL_INDEX)
                .bindFragmentData("fragColor", ChunkShaderBindingPoints.FRAG_COLOR)
                .link(shader -> new ShipThing(shader, options, features));
        } finally {
            vertShader.delete();
            fragShader.delete();
        }
    }

    private static GlProgram<WorldThing> createWorldShader(final String path,
        final ChunkShaderOptions options, final int features) {
        final ShaderConstants constants = createShaderConstants(options, features);

        final GlShader vertShader = loadVsShader(ShaderType.VERTEX, path + ".vsh", constants);
        final GlShader fragShader = loadVsShader(ShaderType.FRAGMENT, path + ".fsh", constants);

        try {
            return GlProgram.builder(new ResourceLocation("valkyrienskies", "world_chunk_shader"))
                .attachShader(vertShader)
                .attachShader(fragShader)
                .bindAttribute("a_Position", ChunkShaderBindingPoints.ATTRIBUTE_POSITION)
                .bindAttribute("a_Color", ChunkShaderBindingPoints.ATTRIBUTE_COLOR)
                .bindAttribute("a_TexCoord", ChunkShaderBindingPoints.ATTRIBUTE_TEXTURE)
                .bindAttribute("a_LightAndData", ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_MATERIAL_INDEX)
                .bindFragmentData("fragColor", ChunkShaderBindingPoints.FRAG_COLOR)
                .link(shader -> new WorldThing(shader, options, features));
        } finally {
            vertShader.delete();
            fragShader.delete();
        }
    }

    /**
     * Loads one of VS's own shader sources.
     *
     * <p>Sodium's {@code ShaderLoader} resolves through {@code ShaderLoader.class.getResourceAsStream},
     * which finds resources in Sodium's own jar. On Fabric every mod shares a class loader, so VS's
     * assets happen to be visible that way too — but on Forge each mod is its own ModLauncher module and
     * the lookup misses, failing with "Shader not found". Reading the source through VS's own class
     * loader and handing it to the parser directly works identically on both.
     *
     * <p>The {@code #import <sodium:...>} directives inside these files still go through Sodium's loader,
     * which is correct: those really are Sodium's resources.
     */
    private static GlShader loadVsShader(final ShaderType type, final String path,
        final ShaderConstants constants) {
        final String resource = "/assets/valkyrienskies/shaders/" + path;
        final String src;

        try (java.io.InputStream in = SodiumCompat.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new RuntimeException("Shader not found: " + resource);
            }
            src = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (final java.io.IOException e) {
            throw new RuntimeException("Failed to read shader source for " + resource, e);
        }

        return new GlShader(type, new ResourceLocation("valkyrienskies", path),
            ShaderParser.parseShader(src, constants));
    }

    /**
     * Reproduces sodium's own {@code ShaderChunkRenderer#createShaderConstants} and adds the VS feature
     * defines. Kept as an explicit copy rather than parsing {@code options.constants()} back apart: 0.9
     * builds the constant set from the fog mode and the pass, and both are reachable from here.
     */
    private static ShaderConstants createShaderConstants(final ChunkShaderOptions options, final int features) {
        final ShaderConstants.Builder builder = ShaderConstants.builder();
        builder.addAll(options.fog().getDefines());

        if (options.pass().supportsFragmentDiscard()) {
            builder.add("USE_FRAGMENT_DISCARD");
        }

        builder.add("USE_VERTEX_COMPRESSION");

        // VS-specific feature defines, gated by config. Compile-time `#ifdef` in the VSH/FSH means
        // disabled features cost nothing on the GPU.
        if ((features & FEATURE_BIOME) != 0) {
            builder.add("VS_DYNAMIC_BIOME");
        }
        if ((features & FEATURE_LIGHT) != 0) {
            builder.add("VS_DYNAMIC_LIGHT");
        }
        if ((features & FEATURE_SHADE) != 0) {
            builder.add("VS_DYNAMIC_SHADE");
        }
        if ((features & FEATURE_SHIP_ON_SHIP) != 0) {
            builder.add("VS_SHIP_ON_SHIP");
        }
        if ((features & FEATURE_FLOOD_GRID) != 0) {
            builder.add("VS_FLOOD_GRID");
        }
        if ((features & FEATURE_DEBUG_FLOOD_1) != 0) {
            builder.add("VS_DEBUG_FLOOD", "1");
        }
        if ((features & FEATURE_DEBUG_FLOOD_2) != 0) {
            builder.add("VS_DEBUG_FLOOD", "2");
        }
        if ((features & FEATURE_DEBUG_SEAM_AO) != 0) {
            builder.add("VS_DEBUG_SEAM_AO");
        }
        if ((features & FEATURE_SHIP_AO) != 0) {
            builder.add("VS_SHIP_AO");
        }
        if ((features & FEATURE_SEAM_NO_MERGE) != 0) {
            builder.add("VS_SEAM_NO_MERGE");
        }
        if ((features & FEATURE_DEBUG_SHIP_LIGHT) != 0) {
            builder.add("VS_DEBUG_SHIP_LIGHT", VSGameConfig.CLIENT.getDebugFloodPaint() == 4 ? "4" : "3");
        }

        return builder.build();
    }

    public static void setupShipShaderState(final GlProgram<ChunkShaderInterface> program,
        final Matrix4fc transformMatrix) {
        final ShipThing shipInterface = (ShipThing) program.getInterface();

        shipInterface.setTransformMatrix(transformMatrix != null ? transformMatrix : new Matrix4f().identity());

        // Local-to-world maps the ship-local vertex space (after sodium's chunk translation) into
        // absolute world block coordinates so the shader can look up world-space block/sky lighting.
        final Matrix4f localToWorld = CURRENT_LOCAL_TO_WORLD.get();
        shipInterface.setLocalToWorldMatrix(localToWorld != null ? localToWorld : new Matrix4f().identity());

        final int[] origin = CURRENT_RENDER_ORIGIN.get();
        if (origin != null) {
            shipInterface.setRenderOrigin(origin[0], origin[1], origin[2]);
        } else {
            shipInterface.setRenderOrigin(0, 0, 0);
        }

        shipInterface.setLightSectionsSampler(LIGHT_SECTIONS_TEXTURE_UNIT);
        shipInterface.setLightLutSampler(LIGHT_LUT_TEXTURE_UNIT);
        shipInterface.setBiomeSectionsSampler(BIOME_SECTIONS_TEXTURE_UNIT);
        shipInterface.setBiomeLutSampler(BIOME_LUT_TEXTURE_UNIT);
        shipInterface.setShipEmitters(SHIP_EMITTER_LIST_TEXTURE_UNIT, getShipEmitterList().size());
        shipInterface.setShipOccluders(SHIP_OCCLUDER_LIST_TEXTURE_UNIT, getShipOccluderList().size());
        shipInterface.setSeamData(SEAM_RUN_HEADERS_TEXTURE_UNIT, getShipOccluderList().headerCount(),
            SEAM_SHIP_DIR_TEXTURE_UNIT,
            getShipOccluderList().boundsCenterX(), getShipOccluderList().boundsCenterY(),
            getShipOccluderList().boundsCenterZ(), getShipOccluderList().boundsRadius());
        shipInterface.setSeamGrid(SEAM_GRID_TEXTURE_UNIT,
            getShipOccluderList().gridOriginX(), getShipOccluderList().gridOriginY(),
            getShipOccluderList().gridOriginZ(), getShipOccluderList().gridInvCellX(),
            getShipOccluderList().gridInvCellY(), getShipOccluderList().gridInvCellZ());

        shipInterface.setWorldFromShipSamplers(
            WORLD_FROM_SHIP_SECTIONS_TEXTURE_UNIT, WORLD_FROM_SHIP_LUT_TEXTURE_UNIT);
        shipInterface.setFloodGridValid(VsDynamicLight.isFloodGridValid());
        shipInterface.setSelfShipIndex(CURRENT_SELF_SHIP_INDEX.get());
    }

    public static void setupWorldShaderState(final GlProgram<ChunkShaderInterface> program) {
        final WorldThing wt = (WorldThing) program.getInterface();

        // Sodium hands the VSH `position = vertex - cameraExact`. We want `vertex - floor(camera)` so
        // that floor() at the fragment is stable as the camera's fractional part drifts through an
        // integer boundary. The VSH adds u_VsCameraFrac to convert; the FSH then does
        //   block = floor(v_CameraRelWorldPos) + u_VsRenderOrigin
        //         = floor(vertex - floor(camera)) + floor(camera) = floor(vertex).
        final net.minecraft.world.phys.Vec3 cameraPos =
            Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        final int ox = (int) Math.floor(cameraPos.x);
        final int oy = (int) Math.floor(cameraPos.y);
        final int oz = (int) Math.floor(cameraPos.z);
        wt.setRenderOrigin(ox, oy, oz);
        wt.setCameraFrac(
            (float) (cameraPos.x - ox),
            (float) (cameraPos.y - oy),
            (float) (cameraPos.z - oz));
        wt.setShipEmitters(SHIP_EMITTER_LIST_TEXTURE_UNIT, getShipEmitterList().size());
        wt.setShipOccluders(SHIP_OCCLUDER_LIST_TEXTURE_UNIT, getShipOccluderList().size());
        wt.setSeamData(SEAM_RUN_HEADERS_TEXTURE_UNIT, getShipOccluderList().headerCount(),
            SEAM_SHIP_DIR_TEXTURE_UNIT,
            getShipOccluderList().boundsCenterX(), getShipOccluderList().boundsCenterY(),
            getShipOccluderList().boundsCenterZ(), getShipOccluderList().boundsRadius());
        wt.setSeamGrid(SEAM_GRID_TEXTURE_UNIT,
            getShipOccluderList().gridOriginX(), getShipOccluderList().gridOriginY(),
            getShipOccluderList().gridOriginZ(), getShipOccluderList().gridInvCellX(),
            getShipOccluderList().gridInvCellY(), getShipOccluderList().gridInvCellZ());

        wt.setWorldFromShipSamplers(
            WORLD_FROM_SHIP_SECTIONS_TEXTURE_UNIT, WORLD_FROM_SHIP_LUT_TEXTURE_UNIT);
        wt.setFloodGridValid(VsDynamicLight.isFloodGridValid());
    }

    // --- Per-ship state handed to the begin() redirect ---------------------

    /** Stores transform for the next render() call on the current thread. */
    public static void pushTransform(final Matrix4f transform) {
        CURRENT_TRANSFORM.set(transform);
    }

    /** Retrieves and clears the stored transform for this thread. */
    public static Matrix4f popTransform() {
        final Matrix4f transform = CURRENT_TRANSFORM.get();
        CURRENT_TRANSFORM.remove();
        return transform;
    }

    public static void pushLocalToWorld(final Matrix4f m) {
        CURRENT_LOCAL_TO_WORLD.set(m);
    }

    public static void pushSelfShipIndex(final int index) {
        CURRENT_SELF_SHIP_INDEX.set(index);
    }

    public static void pushRenderOrigin(final int x, final int y, final int z) {
        CURRENT_RENDER_ORIGIN.set(new int[] {x, y, z});
    }

    public static boolean isRenderingShip() {
        return IS_RENDERING_SHIP.get();
    }

    /**
     * Set false for every ship except the last one in the per-pass loop, so the end() redirect can defer
     * teardown until the pass is actually done rendering ships — instead of tearing down and rebuilding
     * shader state between every consecutive ship. Defaults to true so the world pass's single,
     * non-batched render() call always tears down normally.
     */
    public static void setLastShipInBatch(final boolean isLast) {
        IS_LAST_SHIP_IN_BATCH.set(isLast);
    }

    public static boolean isLastShipInBatch() {
        return IS_LAST_SHIP_IN_BATCH.get();
    }

    // --- Chunk tracking ----------------------------------------------------

    public static void onChunkAdded(final ClientLevel level, final int x, final int z) {
        if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() != VSRenderer.SODIUM) {
            return;
        }
        ChunkTrackerHolder.get(level).onChunkStatusAdded(x, z, ChunkStatus.FLAG_HAS_BLOCK_DATA);
        if (VSGameUtilsKt.getShipManagingPos(level, x, z) instanceof final ClientShip ship
            && ShipRendererKt.getUsesBatchedRenderer(ship)) {
            for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) {
                ShipBatchRenderer.INSTANCE.markSectionDirty(ship.getId(), x, sy, z);
            }
        }
    }

    public static void onChunkRemoved(final ClientLevel level, final int x, final int z) {
        if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() != VSRenderer.SODIUM) {
            return;
        }
        ChunkTrackerHolder.get(level).onChunkStatusRemoved(x, z, ChunkStatus.FLAG_HAS_BLOCK_DATA);
    }

    public static void markShipSectionCacheDirty(final ClientShip ship) {
        final SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        if (renderer == null) {
            return;
        }
        final RenderSectionManager manager = ((SodiumWorldRendererAccessor) renderer).vs$getRenderSectionManager();
        if (manager instanceof final RenderSectionManagerDuck duck) {
            duck.vs$invalidateShipSectionCache(ship);
        }
    }

    // --- Redundant-bind elision -------------------------------------------
    // Sodium calls DefaultChunkRenderer.render() once per ship (and once for the world pass), each of
    // which triggers begin() via the mixin. Within a single frame+pass, consecutive ships hitting the
    // same shader path don't need glUseProgram / renderPass.startDrawing() / texture-buffer rebinds
    // repeated — only the per-ship uniforms actually change ship-to-ship. This state tracks what's
    // already bound so the mixin and vsRenderLayer can skip redundant GL calls.

    private enum BoundPath { UNSET, SHIP, WORLD, VANILLA }

    private static BoundPath lastBoundPath = BoundPath.UNSET;
    private static TerrainRenderPass lastBoundPass = null;
    // The program object itself: a config change (ship AO, debug paint, the flood) yields a DIFFERENT
    // program for the same path+pass, and comparing only path+pass leaves the old one bound, so the
    // setting appears to do nothing until the next world reload.
    private static Object lastBoundProgram = null;
    private static long lastBoundListsFrame = -1;
    private static long frameToken = 0;

    public static boolean needsShipProgramBind(final TerrainRenderPass pass, final Object program) {
        return lastBoundPath != BoundPath.SHIP || lastBoundPass != pass || lastBoundProgram != program;
    }

    public static void recordShipProgramBound(final TerrainRenderPass pass, final Object program) {
        lastBoundPath = BoundPath.SHIP;
        lastBoundPass = pass;
        lastBoundProgram = program;
    }

    public static boolean needsWorldProgramBind(final TerrainRenderPass pass, final Object program) {
        return lastBoundPath != BoundPath.WORLD || lastBoundPass != pass || lastBoundProgram != program;
    }

    public static void recordWorldProgramBound(final TerrainRenderPass pass, final Object program) {
        lastBoundPath = BoundPath.WORLD;
        lastBoundPass = pass;
        lastBoundProgram = program;
    }

    public static void recordVanillaBound(final TerrainRenderPass pass) {
        lastBoundPath = BoundPath.VANILLA;
        lastBoundPass = pass;
        lastBoundProgram = null;
    }

    /**
     * True once per frame — the light/biome/emitter/occluder buffer textures are only re-populated once
     * per frame, so rebinding them per-ship or per-pass is pure waste.
     */
    public static boolean needsListRebind() {
        return lastBoundListsFrame != frameToken;
    }

    public static void recordListsBound() {
        lastBoundListsFrame = frameToken;
    }

    private static void advanceFrameToken() {
        frameToken++;
        lastBoundPath = BoundPath.UNSET;
        lastBoundPass = null;
        lastBoundProgram = null;
    }

    // --- Rendering ---------------------------------------------------------

    /**
     * Entry point from the {@code drawChunkLayer} tail. {@code renderer} is the sodium world renderer
     * whose private per-frame GPU state (uniform buffer, fog, translucency-sorting flag) the ship draws
     * have to reuse — none of it is reachable any other way in 0.9.
     */
    public static void renderShips(final SodiumWorldRenderer renderer, final RenderSectionManager manager,
        final RenderType renderLayer, final ChunkRenderMatrices matrices, final double x, final double y,
        final double z) {
        advanceFrameToken();

        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;

        minecraft.getProfiler().push("vs_dynamic_lighting");
        // The CPU BFS is expensive enough that it only runs every 15th frame — visible as ship
        // lights lagging the hull. The compute flood is cheap enough to run every frame, so it
        // isn't throttled.
        // (be aware this is frames, not ticks)
        try {
            if (VsDynamicLight.isGpuFloodActive() || frameToken % 15 == 0) {
                populateWorldFromShipsForFrame(level);
                populateLightSectionStorage(level);
                populateBiomeSectionStorage(level);
                dispatchGpuLightFlood();
                if (LoadedMods.getFlywheel() != FlywheelVersion.NONE) {
                    FlywheelDynLightCompat.updateDynamicLightingForFlywheel(level);
                }
            }
        } finally {
            minecraft.getProfiler().pop();
        }

        if (renderLayer == RenderType.solid()) {
            renderShipsForPass(renderer, manager, matrices, DefaultTerrainRenderPasses.SOLID, x, y, z);
            renderShipsForPass(renderer, manager, matrices, DefaultTerrainRenderPasses.CUTOUT, x, y, z);
        } else if (renderLayer == RenderType.translucent()) {
            renderShipsForPass(renderer, manager, matrices, DefaultTerrainRenderPasses.TRANSLUCENT, x, y, z);
        }

        renderBatchedShips(renderLayer, matrices, x, y, z);
    }

    public static void renderBatchedShips(final RenderType renderLayer, final ChunkRenderMatrices matrices,
        final double x, final double y, final double z) {
        if (LoadedMods.getIris() && IrisCompat.isIrisShaderActive()) {
            return;
        }
        final PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(new Matrix4f(matrices.modelView()));
        final Matrix4f projection = new Matrix4f(matrices.projection());
        Frustum frustum = ((LevelRendererAccessor) Minecraft.getInstance().levelRenderer).getCullingFrustum();
        ShipBatchRenderer.INSTANCE.drawLayer(renderLayer, poseStack, x, y, z, projection, frustum);
    }

    private static void renderShipsForPass(final SodiumWorldRenderer renderer, final RenderSectionManager manager,
        final ChunkRenderMatrices matrices, final TerrainRenderPass pass, final double x, final double y,
        final double z) {
        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            vsRenderLayer(renderer, manager, matrices, pass, x, y, z, commandList);
        } finally {
            IS_RENDERING_SHIP.set(false);
            pushSelfShipIndex(-1);
        }
    }

    private static void vsRenderLayer(final SodiumWorldRenderer renderer, final RenderSectionManager manager,
        final ChunkRenderMatrices matrices, final TerrainRenderPass pass, final double x, final double y,
        final double z, final CommandList commandList) {

        VSGameEvents.INSTANCE.getShipsStartRenderingSodium().emit(
            new VSGameEvents.ShipStartRenderEventSodium(pass, matrices, x, y, z));

        final SodiumWorldRendererAccessor rendererAccess = (SodiumWorldRendererAccessor) renderer;
        final UniformBufferManager uniforms = rendererAccess.vs$getUniformBufferManager();
        if (uniforms == null) {
            return;
        }
        final FogParameters fog = rendererAccess.vs$getLastFogParameters();
        final boolean indexedRendering = rendererAccess.vs$getUseTranslucencySorting();

        final boolean dynamicLight = VSGameConfig.CLIENT.getDynamicShipLighting();
        final boolean dynamicBiome = VSGameConfig.CLIENT.getDynamicShipBiomeTinting();
        final VsShipLightStorage storage = dynamicLight ? getLightStorage() : null;
        final VsShipBiomeColorStorage biomeStorage = dynamicBiome ? getBiomeStorage() : null;

        final ArrayList<ClientShip> renderableShips = new ArrayList<>();
        final ArrayList<ShipRenderLists> renderableRenderLists = new ArrayList<>();
        ((RenderSectionManagerDuck) manager).vs$getShipRenderLists().forEach((ship, renderList) -> {
            if (hasRenderableGeometryForPass(renderList, pass)) {
                renderableShips.add(ship);
                renderableRenderLists.add(renderList);
            }
        });
        if (renderableShips.isEmpty()) {
            return;
        }

        final DefaultChunkRenderer chunkRenderer = (DefaultChunkRenderer) manager.getChunkRenderer();

        final Vector3d cameraWorldScratch = new Vector3d();
        final Vector3d cameraShipSpaceScratch = new Vector3d();
        final Matrix4d newModelViewScratch = new Matrix4d();
        final Matrix4d localToCameraRelScratch = new Matrix4d();
        final Matrix4f modelViewScratch = new Matrix4f();
        final Matrix4f transformScratch = new Matrix4f();
        final Matrix4f localToWorldScratch = new Matrix4f();

        for (int i = 0; i < renderableShips.size(); i++) {
            final ClientShip ship = renderableShips.get(i);
            final ShipRenderLists renderList = renderableRenderLists.get(i);
            VSGameEvents.INSTANCE.getRenderShipSodium()
                .emit(new ShipRenderEventSodium(pass, matrices, x, y, z, ship, renderList));
            final ShipTransform shipTransform = ship.getRenderTransform();

            final float distanceScaling = 1 / (float) shipTransform.getShipToWorldScaling().x();
            final float initialFogStart = RenderSystem.getShaderFogStart();
            final float initialFogEnd = RenderSystem.getShaderFogEnd();

            if (distanceScaling != 1f) {
                RenderSystem.setShaderFogStart(initialFogStart * distanceScaling);
                RenderSystem.setShaderFogEnd(initialFogEnd * distanceScaling);
            }

            cameraWorldScratch.set(x, y, z);
            shipTransform.getWorldToShip().transformPosition(cameraWorldScratch, cameraShipSpaceScratch);
            final Matrix4dc shipToWorld = shipTransform.getShipToWorld();
            newModelViewScratch
                .set(matrices.modelView())
                .translate(-x, -y, -z)
                .mul(shipToWorld)
                .translate(cameraShipSpaceScratch);
            modelViewScratch.set(newModelViewScratch);

            // Build a precision-friendly matrix that maps a sodium-chunk-local vertex pos to
            // (worldPos - renderOrigin), where renderOrigin is the integer camera world block position.
            // Combined with the ivec3 renderOrigin uniform, the shader can reconstruct an exact world
            // block pos for the flywheel-style light fetch.
            //
            // We want `M * p = worldPos - origin = S*(p + cameraShipSpace) - origin`. To stay
            // precision-friendly when origin can be ~30M, we build:
            //   T(camera-origin) * T(-camera) * S * T(cameraShipSpace) = T(-origin) * S * T(cameraShipSpace)
            // where the FINAL translation column equals (cameraWorld - origin) ~= frac and is therefore
            // safe to truncate to float.
            final int originX = (int) Math.floor(x);
            final int originY = (int) Math.floor(y);
            final int originZ = (int) Math.floor(z);
            localToCameraRelScratch
                .identity()
                .translate(-originX, -originY, -originZ)
                .mul(shipToWorld)
                .translate(cameraShipSpaceScratch);

            final ChunkRenderMatrices shipMatrices =
                new ChunkRenderMatrices(matrices.projection(), new Matrix4f(modelViewScratch));

            // Stash uniforms for the mixin's redirected begin() to consume.
            transformScratch.set(shipToWorld);
            pushTransform(transformScratch);
            localToWorldScratch.set(localToCameraRelScratch);
            pushLocalToWorld(localToWorldScratch);
            pushRenderOrigin(originX, originY, originZ);
            pushSelfShipIndex(getShipOccluderList().getShipIndex(ship.getId()));
            IS_RENDERING_SHIP.set(true);

            // Bind the world-light + biome-color buffer textures so the ship shader can sample them.
            // Bound only when the corresponding feature is enabled, and only once per frame — these are
            // the same GL buffer textures for every ship this frame.
            if (needsListRebind()) {
                if (storage != null) {
                    storage.bind(LIGHT_SECTIONS_TEXTURE_UNIT, LIGHT_LUT_TEXTURE_UNIT);
                }
                if (biomeStorage != null) {
                    biomeStorage.bind(BIOME_SECTIONS_TEXTURE_UNIT, BIOME_LUT_TEXTURE_UNIT);
                }
                if (VSGameConfig.CLIENT.getDynamicShipToWorldLighting()) {
                    // Must bind the same set as MixinDefaultChunkRenderer's branches: they share
                    // recordListsBound() state, so whichever runs first in a frame is the only one
                    // that binds, and anything missing here would be left unbound for both.
                    getShipEmitterList().bind(SHIP_EMITTER_LIST_TEXTURE_UNIT);
                    getShipOccluderList().bind(SHIP_OCCLUDER_LIST_TEXTURE_UNIT);
                    getShipOccluderList().bindHeaders(SEAM_RUN_HEADERS_TEXTURE_UNIT);
                    getShipOccluderList().bindShipDir(SEAM_SHIP_DIR_TEXTURE_UNIT);
                    getShipOccluderList().bindGrid(SEAM_GRID_TEXTURE_UNIT);
                    getWorldFromShipStorage().bind(
                        WORLD_FROM_SHIP_SECTIONS_TEXTURE_UNIT, WORLD_FROM_SHIP_LUT_TEXTURE_UNIT);
                }
                recordListsBound();
            }

            setLastShipInBatch(i == renderableShips.size() - 1);

            // The globals block is written once per frame for the world's matrices; each ship needs its
            // own model-view in there, so clear the latch and rewrite it.
            writeGlobalUniforms(uniforms, commandList, shipMatrices, fog);

            // Drop any draw-command batches the world pass (or the previous ship) left cached on these
            // regions — the cache is keyed by region and pass only.
            renderList.invalidateCachedBatches(pass);

            chunkRenderer.render(shipMatrices, commandList, renderList, pass,
                new CameraTransform(cameraShipSpaceScratch.x(), cameraShipSpaceScratch.y(),
                    cameraShipSpaceScratch.z()),
                fog, indexedRendering,
                uniforms.getUniformBuffer(), uniforms.getSectionTimeInfoTexture(commandList));

            renderList.invalidateCachedBatches(pass);
            IS_RENDERING_SHIP.set(false);
            pushSelfShipIndex(-1);

            if (distanceScaling != 1f) {
                RenderSystem.setShaderFogStart(initialFogStart);
                RenderSystem.setShaderFogEnd(initialFogEnd);
            }

            VSGameEvents.INSTANCE.getPostRenderShipSodium()
                .emit(new ShipRenderEventSodium(pass, matrices, x, y, z, ship, renderList));
        }

        // Put the world's matrices back so the remaining passes of this frame — which call
        // UniformBufferManager#update but are latched out of it — don't render with the last ship's
        // model-view.
        writeGlobalUniforms(uniforms, commandList, matrices, fog);
    }

    private static void writeGlobalUniforms(final UniformBufferManager uniforms, final CommandList commandList,
        final ChunkRenderMatrices matrices, final FogParameters fog) {
        ((UniformBufferManagerAccessor) uniforms).vs$setHasUpdatedThisFrame(false);
        uniforms.update(commandList, matrices, fog);
    }

    private static boolean hasRenderableGeometryForPass(final ShipRenderLists renderList,
        final TerrainRenderPass pass) {
        final Iterator<ChunkRenderList> iterator = renderList.iterator(pass.isTranslucent());
        while (iterator.hasNext()) {
            final ChunkRenderList chunkRenderList = iterator.next();
            if (pass == DefaultTerrainRenderPasses.SOLID && chunkRenderList.getSectionsWithEntitiesCount() > 0) {
                return true;
            }
            if (chunkRenderList.getSectionsWithGeometryCount() <= 0) {
                continue;
            }

            final SectionRenderDataStorage storage = chunkRenderList.getRegion().getStorage(pass);
            if (storage == null) {
                continue;
            }

            final ByteIterator sections = chunkRenderList.sectionsWithGeometryIterator(pass.isTranslucent());
            if (sections == null) {
                continue;
            }

            while (sections.hasNext()) {
                final int sectionIndex = sections.nextByteAsInt();
                if (SectionRenderDataUnsafe.getSliceMask(storage.getDataPointer(sectionIndex)) != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
