package org.valkyrienskies.mod.compat.sodium;

import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.gl.shader.GlShader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderType;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkStatus;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderBindingPoints;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import me.jellysquid.mods.sodium.client.util.iterator.ByteIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.config.ShipRendererKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.render.batched.ShipBatchRenderer;
import org.valkyrienskies.mod.common.render.light.VsDynamicLight;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.hooks.VSGameEvents;
import org.valkyrienskies.mod.common.hooks.VSGameEvents.ShipRenderEventSodium;
import org.valkyrienskies.mod.compat.LoadedMods;
import org.valkyrienskies.mod.compat.LoadedMods.FlywheelVersion;
import org.valkyrienskies.mod.compat.VSRenderer;
import org.valkyrienskies.mod.compat.flywheel.FlywheelDynLightCompat;
import org.valkyrienskies.mod.compat.iris.IrisCompat;
import org.valkyrienskies.mod.compat.sodium.shader.VsShipBiomeColorStorage;
import org.valkyrienskies.mod.compat.sodium.shader.VsShipEmitterList;
import org.valkyrienskies.mod.compat.sodium.shader.VsShipOccluderList;
import org.valkyrienskies.mod.compat.sodium.shader.VsShipLightStorage;
import org.valkyrienskies.mod.compat.sodium.shader.VsWorldFromShipLightStorage;
import org.valkyrienskies.mod.mixin.ValkyrienCommonMixinConfigPlugin;
import org.valkyrienskies.mod.mixin.accessors.client.render.LevelRendererAccessor;
import org.valkyrienskies.mod.mixin.mod_compat.sodium.RenderSectionManagerAccessor;
import org.valkyrienskies.mod.mixinducks.mod_compat.sodium.RenderSectionManagerDuck;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.joml.primitives.AABBdc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

public class SodiumCompat {
    /**
     * Composite cache key for ship shader programs. Sodium's
     * {@link ChunkShaderOptions} alone isn't enough — our shaders branch on
     * compile-time defines derived from VS config, so we need a separate
     * compiled program per combination.
     */
    private record ShaderCacheKey(ChunkShaderOptions options, int vsFeatureBits) {}

    /** Bit flags for VS-specific shader feature defines. Package-visible because
     * {@link ShipThing} needs them at construction to decide which uniforms to bind. */
    static final int FEATURE_BIOME = 1;
    static final int FEATURE_LIGHT = 2;
    static final int FEATURE_SHADE = 4;
    /** Ship FSH also queries the world-from-ship storage (populated for the
     *  world chunk shader) so ship-A voxels can shadow / illuminate ship-B. */
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
    // debugFloodPaint = 5: paint the seam-AO loss instead of the shaded color, in both the
    // world and ship shaders. Red = loss applied; the coloured dots mark sampled corners.
    static final int FEATURE_DEBUG_SEAM_AO = 1024;
    /**
     * Cross-ship AO merging compiled OUT. Zeroing the claims alone already produces the unmerged
     * FIELD, but the shader would still walk every host's partner list, build the claim matrix and
     * test every run against every host to reach that result. This bit lets it skip all of it -- most
     * importantly the per-run early-out in pass 2, where a run not owned by the host being processed
     * is rejected before the weight lookup and the per-voxel loop.
     */
    static final int FEATURE_SEAM_NO_MERGE = 2048;
    /**
     * Read the seam AO's occupancy from the precomputed per-ship field instead of stamping voxels
     * per fragment. World shader only. A compile-time variant rather than a runtime branch on
     * purpose: the point is to remove the voxel loop's registers from the shader, and a branch would
     * keep both paths allocated.
     */
    static final int FEATURE_SEAM_PRECOMP = 4096;
    /**
     * -Pvs.aoprof=N: make the per-fragment seam AO return early at stage N, so its cost can be
     * bisected in a running client. 0 (or unset) is the normal shader. Constant for the process, so
     * it needs no place in the shader cache key.
     */
    private static final int AO_PROF = Integer.getInteger("vs.aoprof", 0);
    /**
     * -Pvs.aocut=N: COMPILE OUT parts of the seam AO, the inverse of {@link #AO_PROF}. AO_PROF keeps
     * every stage's register allocation identical so the stages compare as work; that is exactly why
     * it cannot attribute the FLOOR, which is occupancy rather than work. This removes code from the
     * compiler's view instead, so the difference between levels is register pressure. Pair it with
     * -Pvs_aoprof=4 so nothing executes at any level. Diagnostic only: levels above 0 do not render
     * correct AO. Constant for the process, so it needs no place in the shader cache key.
     */
    private static final int AO_CUT = Integer.getInteger("vs.aocut", 0);
    /**
     * -Pvs.aocutship=N: the same as {@link #AO_CUT} but for the SHIP shader's seam body. Separate
     * from AO_CUT on purpose: the fleet measurements showed the AO's floor is entirely the ship
     * program, so the two have to be cut independently to locate it rather than together.
     */
    private static final int AO_CUT_SHIP = Integer.getInteger("vs.aocutship", 0);
    /**
     * -Pvs_precompship=false: leave the SHIP program on the per-fragment path while the world one
     * uses the precomputed field, so the two halves can be A/B'd back to back in one sitting. They
     * have to be: the world half is a clear win and the ship half is not obviously one, and this rig
     * drifts enough between sittings to swallow the difference.
     */
    private static final boolean PRECOMP_SHIP =
        !"false".equalsIgnoreCase(System.getProperty("vs.precompship", "true"));
    /** -Pvs_seamsub=0: diagnostic, show the precomputed aggregate without the self-subtraction. */
    private static final String SEAM_SUB = System.getProperty("vs.seamsub", "");
    /**
     * -Pvs_seamkernel=N: which B-spline the seam AO splats its occluder voxels with. 1 (or unset) is
     * the tent, which is what every verified result was produced with and the only value that keeps
     * the field unchanged; 2 and 3 are the quadratic and cubic, which SHADE DIFFERENTLY and exist to
     * measure what a smoother kernel costs. See VS_SEAM_KERNEL in the chunk fragment shaders and
     * claude-scratchpad/seam6.py for the model. Constant for the process, so it needs no place in
     * the shader cache key.
     */
    private static final int SEAM_KERNEL = Integer.getInteger("vs.seamkernel", 1);
    /**
     * -Pvs_aogate=off disables the "no occluders in frame, so compile the AO out" gate below. Exists
     * to measure what that gate is worth: with it on, a frame with no ship in view uses the world
     * program that does not carry the seam code at all.
     */
    private static final boolean AO_GATE = !"off".equals(System.getProperty("vs.aogate", "on"));

    static Map<ShaderCacheKey, GlProgram<ShipThing>> cachedPrograms = new HashMap<>();
    private static final ThreadLocal<Matrix4f> CURRENT_TRANSFORM = new ThreadLocal<>();
    private static final ThreadLocal<Matrix4f> CURRENT_LOCAL_TO_WORLD = new ThreadLocal<>();
    private static final ThreadLocal<int[]> CURRENT_RENDER_ORIGIN = new ThreadLocal<>();
    /** Per-frame occluder-list index of the ship currently being drawn; -1 when drawing anything else. */
    private static final ThreadLocal<Integer> CURRENT_SELF_SHIP_INDEX = ThreadLocal.withInitial(() -> -1);
    private static final ThreadLocal<Boolean> IS_RENDERING_SHIP = ThreadLocal.withInitial(() -> false);
    /** Ship being rendered on this thread. Used by setupShipShaderState to
     *  feed the ship FSH the per-frame ship index so vs_sosShipAo can skip
     *  same-ship voxels (their AO is already baked into v_Color.a). */

    // Texture units used for the ship light buffer textures.
    // Sodium uses unit 0 for the block atlas and 1 for the lightmap.
    //
    // Units 2..11 are claimed by ShipWaterPocketExternalWaterCull's
    // ValkyrienAir_Mask{0..8} (sampler2D) + ValkyrienAir_FluidMask uniforms
    // (BASE_MASK_TEX_UNIT=2, MAX_SHIPS=9 + 1 fluid mask). Those uniforms
    // get assigned absolute units on the chunk program once the air-pocket
    // setup runs on a translucent pass, and the assignment persists across
    // all subsequent passes of that program. If we placed our usamplerBuffer
    // / samplerBuffer uniforms in 2..11 they would alias the same texture
    // image unit as a sampler2D from a different sampler type, and NVIDIA
    // throws GL_INVALID_OPERATION ("program texture usage") on every draw.
    // So our units start at 14, comfortably past the air-pocket range.
    public static final int LIGHT_SECTIONS_TEXTURE_UNIT = 14;
    public static final int LIGHT_LUT_TEXTURE_UNIT = 15;
    public static final int BIOME_SECTIONS_TEXTURE_UNIT = 16;
    public static final int BIOME_LUT_TEXTURE_UNIT = 17;
    /** Ship voxels projected into world coords for sky-occlusion + emitter
     *  contribution on the world's chunk shader (and ship-on-ship in the ship
     *  shader). Populated by {@link VsWorldFromShipLightStorage}. */
    public static final int WORLD_FROM_SHIP_SECTIONS_TEXTURE_UNIT = 18;
    public static final int WORLD_FROM_SHIP_LUT_TEXTURE_UNIT = 19;
    /** Buffer texture (RGBA32F) holding the per-frame ship-emitter list as
     *  vec4(worldX, worldY, worldZ, lightLevel) entries. Used by both the
     *  world chunk shader (ship lights world) and ship chunk shader (ship
     *  lights other ships) for sub-block-precise glow that tracks ship
     *  motion smoothly. */
    public static final int SHIP_EMITTER_LIST_TEXTURE_UNIT = 20;
    /** Buffer texture (RGBA32F) holding the per-frame ship-occluder list as
     *  vec4(worldX, worldY, worldZ, 0) entries — every solid voxel of every
     *  loaded ship. Used by the world chunk shader's per-fragment ship AO so
     *  the shadow shape follows ship rotation/translation continuously
     *  (cell-storage-based AO can only morph between cell-aligned configs). */
    public static final int SHIP_OCCLUDER_LIST_TEXTURE_UNIT = 21;
    /** Seam-AO acceleration structures (see VsShipOccluderList.buildSeamData):
     *  sub-run headers with bounding spheres, and the per-ship directory
     *  (pose, responsibility r, top-4 cross-ship merge claims). */
    public static final int SEAM_RUN_HEADERS_TEXTURE_UNIT = 22;
    public static final int SEAM_SHIP_DIR_TEXTURE_UNIT = 23;
    /** Coarse spatial grid binning ships to cells, so a fragment scans only
     *  its own cell's ships in pass 1 (see VsShipOccluderList spatial grid). */
    public static final int SEAM_GRID_TEXTURE_UNIT = 24;
    /** Precomputed per-ship occupancy field (R32F, one scalar per lattice cell) and its per-ship
     *  descriptors (RGBA32I: base/dims, origin/valid). World shader only -- see
     *  VsShipOccluderList.buildOccField for why the ship shader cannot use one field per host. */
    public static final int SEAM_OCC_TEXTURE_UNIT = 25;
    public static final int SEAM_OCC_DESC_TEXTURE_UNIT = 26;
    /** The SHIP path's field set gets its own units rather than sharing the world one's. The two
     *  hold different data -- different weights, and the ship set adds the world lattice as a host
     *  plus the per-pair boxes -- and the binds are elided to once per FRAME by needsListRebind(),
     *  so sharing a unit would leave whichever program drew second reading the other's field. */
    public static final int SEAM_SHIP_OCC_TEXTURE_UNIT = 27;
    public static final int SEAM_SHIP_OCC_DESC_TEXTURE_UNIT = 28;

    private static final double WORLD_FROM_SHIP_VISIBILITY_PADDING = 32.0;

    /** Cached VS world chunk programs, keyed by sodium's render-pass options. */
    private static final Map<ShaderCacheKey, GlProgram<WorldThing>> cachedWorldPrograms = new HashMap<>();

    // The storages themselves know nothing about Sodium, so they live in VsDynamicLight and are shared
    // with the 0.9 compat layer; only the frustum test below is generation-specific.

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

    private static boolean isShipRelevantToWorldFromShipFrame(ClientShip ship, Viewport viewport) {
        if (viewport == null) return true;
        final AABBdc aabb = ship.getRenderAABB();
        if (aabb == null) return false;
        return isExpandedAabbVisible(viewport, aabb, WORLD_FROM_SHIP_VISIBILITY_PADDING);
    }

    private static boolean isExpandedAabbVisible(Viewport viewport, AABBdc aabb, double padding) {
        final double minX = aabb.minX() - padding;
        final double minY = aabb.minY() - padding;
        final double minZ = aabb.minZ() - padding;
        final double maxX = aabb.maxX() + padding;
        final double maxY = aabb.maxY() + padding;
        final double maxZ = aabb.maxZ() + padding;
        final double centerX = (minX + maxX) * 0.5;
        final double centerY = (minY + maxY) * 0.5;
        final double centerZ = (minZ + maxZ) * 0.5;
        final int x = Mth.floor(centerX);
        final int y = Mth.floor(centerY);
        final int z = Mth.floor(centerZ);
        final float extentX = (float) ((maxX - minX) * 0.5 + Math.abs(centerX - x) + 1.0);
        final float extentY = (float) ((maxY - minY) * 0.5 + Math.abs(centerY - y) + 1.0);
        final float extentZ = (float) ((maxZ - minZ) * 0.5 + Math.abs(centerZ - z) + 1.0);
        return viewport.isBoxVisible(x, y, z, extentX, extentY, extentZ);
    }

    public static GlProgram<ChunkShaderInterface> getOrCreateShipProgram(ChunkShaderOptions options) {
        int features = computeFeatureBits();
        if (VSGameConfig.CLIENT.getDebugFloodPaint() == 3 || VSGameConfig.CLIENT.getDebugFloodPaint() == 4) {
            features |= FEATURE_DEBUG_SHIP_LIGHT;
        }
        if (VSGameConfig.CLIENT.getDebugFloodPaint() == 5) {
            features |= FEATURE_DEBUG_SEAM_AO;
        }
        ShaderCacheKey key = new ShaderCacheKey(options, features);
        GlProgram<ShipThing> program = cachedPrograms.get(key);
        if (program == null) {
            program = createShader("blocks/block_layer_opaque", options, features);
            cachedPrograms.put(key, program);
        }
        return (GlProgram<ChunkShaderInterface>) (Object) program;
    }

    /** Snapshot the VS shader-feature config bits at program-build time. */
    private static int computeFeatureBits() {
        int bits = 0;
        if (VSGameConfig.CLIENT.getDynamicShipBiomeTinting()) bits |= FEATURE_BIOME;
        if (VSGameConfig.CLIENT.getDynamicShipLighting()) bits |= FEATURE_LIGHT;
        if (VSGameConfig.CLIENT.getBetterVanillaShipShading()) bits |= FEATURE_SHADE;
        if (VSGameConfig.CLIENT.getShipAmbientOcclusion()) {
            bits |= FEATURE_SHIP_AO;
            if (!VSGameConfig.CLIENT.getShipAmbientOcclusionMerging()) bits |= FEATURE_SEAM_NO_MERGE;
            if (VSGameConfig.CLIENT.getShipAmbientOcclusionPrecompute() && PRECOMP_SHIP) {
                bits |= FEATURE_SEAM_PRECOMP;
            }
        }
        // The seam-AO pass needs u_TransformMatrix and the world-relative varyings, which ride on
        // this bit, so it is set for any feature that draws through the ship shader.
        if (VSGameConfig.CLIENT.getDynamicShipLighting()
            || VSGameConfig.CLIENT.getBetterVanillaShipShading()
            || VSGameConfig.CLIENT.getDynamicShipToWorldLighting()) {
            bits |= FEATURE_SHIP_ON_SHIP;
        }
        // The flood is the ship-on-ship light, so it rides with ship->world lighting.
        if (VSGameConfig.CLIENT.getDynamicShipToWorldLighting() && VsDynamicLight.isGpuFloodActive()) {
            bits |= FEATURE_FLOOD_GRID;
        }
        return bits;
    }

    /**
     * True iff at least one of the three ship-shader features is enabled. When
     * false there's no reason to swap in the VS ship shader at all — the
     * mesher mixin's gates fall through to sodium's native byte format and
     * sodium's stock chunk shader can render ship blocks correctly.
     */
    public static boolean anyShipShaderFeatureEnabled() {
        return computeFeatureBits() != 0;
    }

    public static void setupShipShaderState(GlProgram<ChunkShaderInterface> program, ChunkRenderMatrices matrices, Matrix4fc transformMatrix) {
        ShipThing shipInterface = (ShipThing) program.getInterface();
        shipInterface.setupState();
        // Set projection and model-view matrices
        shipInterface.setProjectionMatrix(matrices.projection());
        shipInterface.setModelViewMatrix(matrices.modelView());
        // Set transform matrix (identity if not provided)
        shipInterface.setTransformMatrix(transformMatrix != null ? transformMatrix : new Matrix4f().identity());
        // Local-to-world maps the ship-local vertex space (after sodium's chunk
        // translation) into absolute world block coordinates so the shader can
        // look up world-space block/sky lighting.
        Matrix4f localToWorld = CURRENT_LOCAL_TO_WORLD.get();
        shipInterface.setLocalToWorldMatrix(localToWorld != null ? localToWorld : new Matrix4f().identity());
        int[] origin = CURRENT_RENDER_ORIGIN.get();
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
        shipInterface.setWorldFromShipSamplers(
            WORLD_FROM_SHIP_SECTIONS_TEXTURE_UNIT, WORLD_FROM_SHIP_LUT_TEXTURE_UNIT);
        shipInterface.setFloodGridValid(VsDynamicLight.isFloodGridValid());
        shipInterface.setSeamData(SEAM_RUN_HEADERS_TEXTURE_UNIT, getShipOccluderList().headerCount(),
                SEAM_SHIP_DIR_TEXTURE_UNIT,
                getShipOccluderList().boundsCenterX(), getShipOccluderList().boundsCenterY(),
                getShipOccluderList().boundsCenterZ(), getShipOccluderList().boundsRadius());
        shipInterface.setSeamOccField(SEAM_SHIP_OCC_TEXTURE_UNIT, SEAM_SHIP_OCC_DESC_TEXTURE_UNIT);
        shipInterface.setSeamGrid(SEAM_GRID_TEXTURE_UNIT,
                getShipOccluderList().gridOriginX(), getShipOccluderList().gridOriginY(),
                getShipOccluderList().gridOriginZ(), getShipOccluderList().gridInvCellX(),
                getShipOccluderList().gridInvCellY(), getShipOccluderList().gridInvCellZ());
        // Tell the ship FSH which ship is being drawn, so the seam pass skips that ship's own voxels
        // -- their occlusion is already baked into v_Color.a by the mesher, and counting it again
        // double-darkens every concave corner on the hull.
        //
        // This reads the index the render loop pushed (vsRenderLayer, pushSelfShipIndex). It used to
        // read a CURRENT_SHIP_ID ThreadLocal that the merge left orphaned -- upstream set it in the
        // loop this branch replaced, so it kept its initial 0 forever, getShipIndex(0) returned 0,
        // and the shader's `owner == selfShipIndex` never matched a real voxel (they start at 1).
        // Self-occlusion was therefore never removed on this generation.
        //
        // -1 when no ship is being drawn, which likewise matches no voxel.
        shipInterface.setCurrentShipIndex(CURRENT_SELF_SHIP_INDEX.get());
    }

    /** Stores transform for the next render() call on the current thread. */
    public static void pushTransform(Matrix4f transform) {
        CURRENT_TRANSFORM.set(transform);
    }

    /** Retrieves and clears the stored transform for this thread. */
    public static Matrix4f popTransform() {
        Matrix4f transform = CURRENT_TRANSFORM.get();
        CURRENT_TRANSFORM.remove();
        return transform;
    }

    public static void pushLocalToWorld(Matrix4f m) {
        CURRENT_LOCAL_TO_WORLD.set(m);
    }

    public static void pushSelfShipIndex(final int index) {
        CURRENT_SELF_SHIP_INDEX.set(index);
    }

    public static void pushRenderOrigin(int x, int y, int z) {
        CURRENT_RENDER_ORIGIN.set(new int[] { x, y, z });
    }

    public static boolean isRenderingShip() {
        return IS_RENDERING_SHIP.get();
    }

    public static void onChunkAdded(final ClientLevel level, final int x, final int z) {
        if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() == VSRenderer.SODIUM) {
            ChunkTrackerHolder.get(level).onChunkStatusAdded(x, z, ChunkStatus.FLAG_HAS_BLOCK_DATA);
            markShipSectionCacheDirty(level, x, z);
            if (VSGameUtilsKt.getShipManagingPos(level, x, z) instanceof final ClientShip ship
                    && ShipRendererKt.getUsesBatchedRenderer(ship)) {
                for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) {
                    ShipBatchRenderer.INSTANCE.markSectionDirty(ship.getId(), x, sy, z);
                }
            }
        }
    }

    public static void onChunkRemoved(final ClientLevel level, final int x, final int z) {
        if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() == VSRenderer.SODIUM) {
            ChunkTrackerHolder.get(level).onChunkStatusRemoved(x, z, ChunkStatus.FLAG_HAS_BLOCK_DATA);
            markShipSectionCacheDirty(level, x, z);
        }
    }

    public static void markShipRenderListsDirty() {
//        final SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
//        if (renderer instanceof SodiumWorldRendererDuck duck) {
//            duck.vs$markShipRenderListsDirty();
//        }
        return;
    }

    public static void markShipSectionCacheDirty(final ClientShip ship) {
//        final SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
//        if (renderer instanceof SodiumWorldRendererDuck duck) {
//            duck.vs$invalidateShipSectionCache(ship);
//        }
        return;
    }

    public static void markShipSectionCacheDirty(final ClientLevel level, final int x, final int z) {
        return;
    }

    // --- Redundant-bind elision -------------------------------------------
// Sodium calls DefaultChunkRenderer.render() once per ship (and once for
// the world pass), each of which triggers begin() via the mixin. Within a
// single frame+pass, consecutive ships hitting the same shader path don't
// need glUseProgram / renderPass.startDrawing() / texture-buffer rebinds
// repeated — only the per-ship uniforms (transform, localToWorld, origin)
// actually change ship-to-ship. This state tracks what's already bound so
// MixinDefaultChunkRenderer and vsRenderLayer can skip redundant GL calls.
    private enum BoundPath { UNSET, SHIP, WORLD, VANILLA }
    private static BoundPath lastBoundPath = BoundPath.UNSET;
    private static TerrainRenderPass lastBoundPass = null;
    // The program object itself, not just which path it belongs to. A config change (ship AO, debug
    // paint, the flood) produces a DIFFERENT program for the same path+pass, and comparing only
    // path+pass left the old program bound -- the setting then appears to do nothing at all until
    // the next world reload.
    private static Object lastBoundProgram = null;
    private static long lastBoundListsFrame = -1;
    private static long frameToken = 0;

    public static boolean needsShipProgramBind(TerrainRenderPass pass, Object program) {
        return lastBoundPath != BoundPath.SHIP || lastBoundPass != pass || lastBoundProgram != program;
    }

    public static void recordShipProgramBound(TerrainRenderPass pass, Object program) {
        lastBoundPath = BoundPath.SHIP;
        lastBoundPass = pass;
        lastBoundProgram = program;
    }

    public static boolean needsWorldProgramBind(TerrainRenderPass pass, Object program) {
        return lastBoundPath != BoundPath.WORLD || lastBoundPass != pass || lastBoundProgram != program;
    }

    public static void recordWorldProgramBound(TerrainRenderPass pass, Object program) {
        lastBoundPath = BoundPath.WORLD;
        lastBoundPass = pass;
        lastBoundProgram = program;
    }

    public static void recordVanillaBound(TerrainRenderPass pass) {
        lastBoundPath = BoundPath.VANILLA;
        lastBoundPass = pass;
        lastBoundProgram = null;
    }

    /** True once per frame — the light/biome/emitter/occluder buffer textures
     *  are only re-populated once per frame (see populateWorldFromShipsForFrame,
     *  populateLightSectionStorage, populateBiomeSectionStorage), so rebinding
     *  them per-ship or per-pass is pure waste. */
    /**
     * Bind BOTH precomputed field sets, from either draw branch.
     *
     * <p>The binds around this are elided to once per frame by {@link #needsListRebind()}, so
     * whichever of the ship and world branches draws first is the only one that binds anything. That
     * is fine for the buffers both branches bind identically -- occluders, runs, directory, grid --
     * and it is exactly wrong for a buffer only one branch binds: the ship field went to units
     * nothing had bound, the sampler read zero, and the ship AO silently disappeared while the
     * world's kept working. Binding both sets from both branches makes the elision safe again.
     */
    public static void bindSeamOccFields() {
        getShipOccluderList().bindOccField(SEAM_OCC_TEXTURE_UNIT, SEAM_OCC_DESC_TEXTURE_UNIT);
        getShipOccluderList().bindShipOccField(SEAM_SHIP_OCC_TEXTURE_UNIT,
            SEAM_SHIP_OCC_DESC_TEXTURE_UNIT);
    }

    public static boolean needsListRebind() {
        return lastBoundListsFrame != frameToken;
    }

    public static void recordListsBound() {
        lastBoundListsFrame = frameToken;
    }

    /** Bumps the frame token and resets the "what's bound" tracking. Must run
     *  once per real frame — placed at the top of populateWorldFromShipsForFrame
     *  (called every tick from MixinLevelRenderer.updateDynamicLight) rather
     *  than inside vsRenderLayer, since vsRenderLayer runs 3x per frame (once
     *  per terrain pass) and would otherwise reset elision state mid-frame. */
    private static void advanceFrameToken() {
        frameToken++;
        lastBoundPath = BoundPath.UNSET;
        lastBoundPass = null;
        lastBoundProgram = null;
    }

    private static final ThreadLocal<Boolean> IS_LAST_SHIP_IN_BATCH = ThreadLocal.withInitial(() -> true);

    /** Set false for every ship except the last one in vsRenderLayer's loop, so
     *  the end() redirect can defer teardown until the pass is actually done
     *  rendering ships — instead of tearing down and rebuilding shader state
     *  between every consecutive ship. Defaults to true so the world pass's
     *  single, non-batched render() call always tears down normally. */
    public static void setLastShipInBatch(boolean isLast) {
        IS_LAST_SHIP_IN_BATCH.set(isLast);
    }

    public static boolean isLastShipInBatch() {
        return IS_LAST_SHIP_IN_BATCH.get();
    }

    public static void vsRenderLayer(RenderSectionManager renderSectionManager, ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z,
            CommandList commandList) {

        VSGameEvents.INSTANCE.getShipsStartRenderingSodium().emit(new VSGameEvents.ShipStartRenderEventSodium(
            pass, matrices, x, y, z
        ));

        // Refresh the world-light/solid + biome-color buffers for any sections
        // occupied by the ships we are about to render. Ship-on-ship AO needs
        // the solid bitmap too, so it requests this storage even when the full
        // dynamic ship-light feature is disabled.
        final ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        final boolean dynamicLight = VSGameConfig.CLIENT.getDynamicShipLighting();
        final boolean dynamicBiome = VSGameConfig.CLIENT.getDynamicShipBiomeTinting();
        final boolean dynamicShipToWorld = VSGameConfig.CLIENT.getDynamicShipToWorldLighting();
        final boolean needsWorldSolids = dynamicLight || dynamicShipToWorld;
        final VsShipLightStorage storage = needsWorldSolids ? getLightStorage() : null;
        final VsShipBiomeColorStorage biomeStorageLocal = dynamicBiome ? getBiomeStorage() : null;
        final ArrayList<ClientShip> renderableShips = new ArrayList<>();
        final ArrayList<SortedRenderLists> renderableRenderLists = new ArrayList<>();
        ((RenderSectionManagerDuck) renderSectionManager).vs_getShipRenderLists().forEach((ship, renderList) -> {
            if (hasRenderableGeometryForPass(renderList, pass)) {
                renderableShips.add(ship);
                renderableRenderLists.add(renderList);
            }
        });
        if (renderableShips.isEmpty()) {
            return;
        }

        Vector3d cameraWorldScratch = new Vector3d();
        Vector3d cameraShipSpaceScratch = new Vector3d();

        Matrix4d newModelViewScratch = new Matrix4d();
        Matrix4d localToCameraRelScratch = new Matrix4d();

        Matrix4f modelViewScratch = new Matrix4f();
        Matrix4f transformScratch = new Matrix4f();
        Matrix4f localToWorldScratch = new Matrix4f();

        for (int i = 0; i < renderableShips.size(); i++) {
            final ClientShip ship = renderableShips.get(i);
            final SortedRenderLists renderList = renderableRenderLists.get(i);
            VSGameEvents.INSTANCE.getRenderShipSodium().emit(new ShipRenderEventSodium(pass, matrices, x, y, z, ship, renderList));
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
            final Matrix4dc s = ship.getRenderTransform().getShipToWorld();
            newModelViewScratch
                .set(matrices.modelView())
                .translate(-x, -y, -z)
                .mul(s)
                .translate(cameraShipSpaceScratch);
            modelViewScratch.set(newModelViewScratch);

            // Build a precision-friendly matrix that maps a sodium-chunk-local vertex
            // pos to (worldPos - renderOrigin), where renderOrigin is the integer
            // camera world block position. Combined with the ivec3 renderOrigin
            // uniform, the shader can reconstruct an exact world block pos for the
            // flywheel-style light fetch.
            //
            // We want `M * p = worldPos - origin = S*(p + cameraShipSpace) - origin`.
            // To stay precision-friendly when origin can be ~30M, we build:
            //   T(camera-origin) * T(-camera) * S * T(cameraShipSpace)
            // = T(-origin) * S * T(cameraShipSpace)
            // where the FINAL translation column equals (cameraWorld - origin) ~= frac
            // and is therefore safe to truncate to float.
            final int originX = (int) Math.floor(x);
            final int originY = (int) Math.floor(y);
            final int originZ = (int) Math.floor(z);
            localToCameraRelScratch
                .identity()
                .translate(-originX, -originY, -originZ)
                .mul(s)
                .translate(cameraShipSpaceScratch);

            final ChunkRenderMatrices newMatrices = new ChunkRenderMatrices(matrices.projection(), modelViewScratch.set(newModelViewScratch));
            DefaultChunkRenderer chunkRenderer = (DefaultChunkRenderer) ((RenderSectionManagerAccessor) renderSectionManager).getChunkRenderer();

            // Stash uniforms for the mixin's redirected begin() to consume
            transformScratch.set(s);
            pushTransform(transformScratch);
            localToWorldScratch.set(localToCameraRelScratch);
            pushLocalToWorld(localToWorldScratch);
            pushRenderOrigin(originX, originY, originZ);
            pushSelfShipIndex(getShipOccluderList().getShipIndex(ship.getId()));
            IS_RENDERING_SHIP.set(true);

            // Bind the world-light + biome-color buffer textures so the ship
            // shader can sample them. Bound only when the corresponding feature
            // is enabled, and only once per frame — these are the same GL
            // buffer textures for every ship this frame, so N ships shouldn't
            // pay for N redundant rebinds. (Shares recordListsBound() state
            // with MixinDefaultChunkRenderer's ship/world branches, which
            // bind the same emitter/occluder units — whichever runs first
            // in a frame satisfies both.)
            if (needsListRebind()) {
                if (storage != null) storage.bind(LIGHT_SECTIONS_TEXTURE_UNIT, LIGHT_LUT_TEXTURE_UNIT);
                if (biomeStorageLocal != null) biomeStorageLocal.bind(BIOME_SECTIONS_TEXTURE_UNIT, BIOME_LUT_TEXTURE_UNIT);
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

            SodiumCompat.setLastShipInBatch(i == renderableShips.size() - 1);

            chunkRenderer.render(newMatrices, commandList, renderList, pass,
                new CameraTransform(cameraShipSpaceScratch.x(), cameraShipSpaceScratch.y(), cameraShipSpaceScratch.z()));
            IS_RENDERING_SHIP.set(false);
            pushSelfShipIndex(-1);

             if (distanceScaling != 1f) {
                RenderSystem.setShaderFogStart(initialFogStart);
                RenderSystem.setShaderFogEnd(initialFogEnd);
            }

            VSGameEvents.INSTANCE.getPostRenderShipSodium().emit(new ShipRenderEventSodium(pass, matrices, x, y, z, ship, renderList));
        }
    }

    private static boolean hasRenderableGeometryForPass(final SortedRenderLists renderList, final TerrainRenderPass pass) {
        final Iterator<ChunkRenderList> iterator = renderList.iterator(pass.isReverseOrder());
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

            final ByteIterator sections = chunkRenderList.sectionsWithGeometryIterator(pass.isReverseOrder());
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


    public static void renderShips(RenderSectionManager renderSectionManager, RenderType renderLayer, ChunkRenderMatrices matrices, double x, double y, double z) {
        SodiumCompat.advanceFrameToken();
        Minecraft.getInstance().getProfiler().push("vs_dynamic_lighting");
        ClientLevel level = Minecraft.getInstance().level;
        // The CPU BFS is expensive enough that it only runs every 15th frame — visible as ship
        // lights lagging the hull. The compute flood is cheap enough to run every frame, so it
        // isn't throttled.
        // (be aware this is frames, not ticks)
        try {
            if (VsDynamicLight.isGpuFloodActive() || frameToken % 15 == 0) {
                SodiumCompat.populateWorldFromShipsForFrame(level);
                SodiumCompat.populateLightSectionStorage(level);
                SodiumCompat.populateBiomeSectionStorage(level);
                SodiumCompat.dispatchGpuLightFlood();
                if (LoadedMods.getFlywheel() != FlywheelVersion.NONE) {
                    FlywheelDynLightCompat.updateDynamicLightingForFlywheel(level);
                }
            }
        } finally {
            Minecraft.getInstance().getProfiler().pop();
        }
        if (renderLayer == RenderType.solid()) {
            renderShipsForPass(renderSectionManager, matrices, DefaultTerrainRenderPasses.SOLID, x, y, z);
            renderShipsForPass(renderSectionManager, matrices, DefaultTerrainRenderPasses.CUTOUT, x, y, z);
        } else if (renderLayer == RenderType.translucent()) {
            renderShipsForPass(renderSectionManager, matrices, DefaultTerrainRenderPasses.TRANSLUCENT, x, y, z);
        }
        renderBatchedShips(renderLayer, matrices, x, y, z);
    }

    public static void renderBatchedShips(RenderType renderLayer, ChunkRenderMatrices matrices,
            double x, double y, double z) {
        if (LoadedMods.getIris() && IrisCompat.isIrisShaderActive()) {
            return;
        }
        final PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(new Matrix4f(matrices.modelView()));
        final Matrix4f projection = new Matrix4f(matrices.projection());

        Frustum frustum = ((LevelRendererAccessor) Minecraft.getInstance().levelRenderer).getCullingFrustum();
        ShipBatchRenderer.INSTANCE.drawLayer(renderLayer, poseStack, x, y, z, projection, frustum);
    }

    private static void renderShipsForPass(RenderSectionManager renderSectionManager, ChunkRenderMatrices matrices,
            TerrainRenderPass pass, double x, double y, double z) {
        CommandList commandList = RenderDevice.INSTANCE.createCommandList();
        try {
            vsRenderLayer(renderSectionManager, matrices, pass, x, y, z, commandList);
        } finally {
            commandList.close();
            IS_RENDERING_SHIP.set(false);
            pushSelfShipIndex(-1);
        }
    }

    public static GlProgram<ChunkShaderInterface> getOrCreateWorldProgram(ChunkShaderOptions options) {
        // The world shader has one VS feature of its own — the flood-grid gate — so it needs the same
        // (options, features) cache key the ship shader uses, not options alone.
        int features = VsDynamicLight.isGpuFloodActive() ? FEATURE_FLOOD_GRID : 0;
        // The world program builds its own feature bits rather than calling computeFeatureBits(), so
        // every world-shader feature has to be added HERE as well. Ship-cast AO lives in the world
        // shader (it shades world terrain under a ship), and omitting it here compiled the AO out
        // unconditionally -- the config appeared to do nothing at all.
        //
        // The occluder-count test is a performance one, and it is worth more than it looks. Profiling
        // (autotest/perf_aoprof.sh) found that merely HAVING the seam code in the world shader costs
        // ~1.27ms/frame at 1080p in a stage that executes none of it -- occupancy lost to register
        // pressure, paid by every terrain fragment. With no occluders in the frame the AO provably
        // contributes nothing (ws_seamAoFrag returns 0 on its first test), so those frames can use the
        // program that does not carry the code at all. Both variants stay in cachedWorldPrograms, so
        // ships entering and leaving view is a bind, not a recompile.
        if (VSGameConfig.CLIENT.getShipAmbientOcclusion()
            && (!AO_GATE || VsDynamicLight.getShipOccluderList().size() > 0)) {
            features |= FEATURE_SHIP_AO;
            if (!VSGameConfig.CLIENT.getShipAmbientOcclusionMerging()) features |= FEATURE_SEAM_NO_MERGE;
            if (VSGameConfig.CLIENT.getShipAmbientOcclusionPrecompute()) features |= FEATURE_SEAM_PRECOMP;
        }
        if (features != 0) {
            int paint = VSGameConfig.CLIENT.getDebugFloodPaint();
            if (paint == 1) features |= FEATURE_DEBUG_FLOOD_1;
            else if (paint == 5) features |= FEATURE_DEBUG_SEAM_AO;
            else if (paint >= 2) features |= FEATURE_DEBUG_FLOOD_2;
        }
        ShaderCacheKey key = new ShaderCacheKey(options, features);
        GlProgram<WorldThing> program = cachedWorldPrograms.get(key);
        if (program == null) {
            program = createWorldShader("blocks/world_layer_opaque", options, features);
            cachedWorldPrograms.put(key, program);
        }
        return (GlProgram<ChunkShaderInterface>) (Object) program;
    }

    public static void setupWorldShaderState(GlProgram<ChunkShaderInterface> program, ChunkRenderMatrices matrices) {
        WorldThing wt = (WorldThing) program.getInterface();
        wt.setupState();
        wt.setProjectionMatrix(matrices.projection());
        wt.setModelViewMatrix(matrices.modelView());

        // Sodium hands the VSH `position = vertex - cameraExact`. We want
        // `vertex - floor(camera)` so that floor() at the fragment is stable
        // as the camera's fractional drifts through an integer boundary. The
        // VSH adds u_VsCameraFrac to convert; the FSH then does
        //   block = floor(v_CameraRelWorldPos) + u_VsRenderOrigin
        // = floor(vertex - floor(camera)) + floor(camera) = floor(vertex).
        net.minecraft.world.phys.Vec3 cameraPos =
                net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        int ox = (int) Math.floor(cameraPos.x);
        int oy = (int) Math.floor(cameraPos.y);
        int oz = (int) Math.floor(cameraPos.z);
        wt.setRenderOrigin(ox, oy, oz);
        wt.setCameraFrac(
                (float) (cameraPos.x - ox),
                (float) (cameraPos.y - oy),
                (float) (cameraPos.z - oz));
        wt.setShipEmitters(SHIP_EMITTER_LIST_TEXTURE_UNIT, getShipEmitterList().size());
        wt.setShipOccluders(SHIP_OCCLUDER_LIST_TEXTURE_UNIT, getShipOccluderList().size());
        wt.setWorldFromShipSamplers(
            WORLD_FROM_SHIP_SECTIONS_TEXTURE_UNIT, WORLD_FROM_SHIP_LUT_TEXTURE_UNIT);
        wt.setFloodGridValid(VsDynamicLight.isFloodGridValid());
        // Section storage so ws_shipAo can fold world blocks into the same SDF as ship voxels.
        wt.setLightSectionsSampler(LIGHT_SECTIONS_TEXTURE_UNIT);
        wt.setLightLutSampler(LIGHT_LUT_TEXTURE_UNIT);
        wt.setSeamData(SEAM_RUN_HEADERS_TEXTURE_UNIT, getShipOccluderList().headerCount(),
                SEAM_SHIP_DIR_TEXTURE_UNIT,
                getShipOccluderList().boundsCenterX(), getShipOccluderList().boundsCenterY(),
                getShipOccluderList().boundsCenterZ(), getShipOccluderList().boundsRadius());
        wt.setSeamGrid(SEAM_GRID_TEXTURE_UNIT,
                getShipOccluderList().gridOriginX(), getShipOccluderList().gridOriginY(),
                getShipOccluderList().gridOriginZ(), getShipOccluderList().gridInvCellX(),
                getShipOccluderList().gridInvCellY(), getShipOccluderList().gridInvCellZ());
        wt.setSeamOccField(SEAM_OCC_TEXTURE_UNIT, SEAM_OCC_DESC_TEXTURE_UNIT);
    }

    private static GlProgram<WorldThing> createWorldShader(String path, ChunkShaderOptions options,
            int features) {
        ShaderConstants constants = createWorldShaderConstants(options, features);

        GlShader vertShader = ShaderLoader.loadShader(ShaderType.VERTEX,
                new ResourceLocation("valkyrienskies", path + ".vsh"), constants);
        GlShader fragShader = ShaderLoader.loadShader(ShaderType.FRAGMENT,
                new ResourceLocation("valkyrienskies", path + ".fsh"), constants);

        try {
            return GlProgram.builder(new ResourceLocation("valkyrienskies", "world_chunk_shader"))
                    .attachShader(vertShader)
                    .attachShader(fragShader)
                    .bindAttribute("a_Position", ChunkShaderBindingPoints.ATTRIBUTE_POSITION)
                    .bindAttribute("a_Color", ChunkShaderBindingPoints.ATTRIBUTE_COLOR)
                    .bindAttribute("a_TexCoord", ChunkShaderBindingPoints.ATTRIBUTE_TEXTURE)
                    .bindAttribute("a_LightAndData", ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_MATERIAL_INDEX)
                    .bindFragmentData("fragColor", ChunkShaderBindingPoints.FRAG_COLOR)
                    .link((shader) -> new WorldThing(shader, options, features));
        } finally {
            vertShader.delete();
            fragShader.delete();
        }
    }

    private static ShaderConstants createWorldShaderConstants(ChunkShaderOptions options,
            int features) {
        // Sodium's stock chunk shader uses USE_FRAGMENT_DISCARD / USE_FOG /
        // USE_VANILLA_COLOR_FORMAT defines from the pass options. We want the
        // same set so the world shader handles cutout / translucent passes
        // correctly. USE_VANILLA_COLOR_FORMAT is dropped — our shader doesn't
        // implement that compatibility branch.
        ShaderConstants.Builder builder = ShaderConstants.builder();
        for (String define : options.constants().getDefineStrings()) {
            String[] parts = define.split("\\s+", 3);
            if (parts.length < 2 || !"#define".equals(parts[0])) continue;
            String name = parts[1];
            if ("USE_VANILLA_COLOR_FORMAT".equals(name)) continue;
            if (parts.length == 2) builder.add(name);
            else builder.add(name, parts[2]);
        }
        if ((features & FEATURE_FLOOD_GRID) != 0) builder.add("VS_FLOOD_GRID");
        if ((features & FEATURE_DEBUG_FLOOD_1) != 0) builder.add("VS_DEBUG_FLOOD", "1");
        if ((features & FEATURE_DEBUG_FLOOD_2) != 0) builder.add("VS_DEBUG_FLOOD", "2");
        if ((features & FEATURE_SHIP_AO) != 0) builder.add("VS_SHIP_AO");
        if ((features & FEATURE_SEAM_NO_MERGE) != 0) builder.add("VS_SEAM_NO_MERGE");
        if ((features & FEATURE_SEAM_PRECOMP) != 0) builder.add("VS_SEAM_PRECOMP");
        if ((features & FEATURE_DEBUG_SEAM_AO) != 0) builder.add("VS_DEBUG_SEAM_AO");
        if (AO_PROF != 0) builder.add("VS_AOPROF", Integer.toString(AO_PROF));
        if (AO_CUT != 0) builder.add("VS_AOCUT", Integer.toString(AO_CUT));
        if (AO_CUT_SHIP != 0) builder.add("VS_AOCUTSHIP", Integer.toString(AO_CUT_SHIP));
        if (!SEAM_SUB.isEmpty()) builder.add("VS_SEAM_SUBTRACT", SEAM_SUB);
        if (SEAM_KERNEL != 1) builder.add("VS_SEAM_KERNEL", Integer.toString(SEAM_KERNEL));
        if ((features & FEATURE_DEBUG_SHIP_LIGHT) != 0) builder.add("VS_DEBUG_SHIP_LIGHT", VSGameConfig.CLIENT.getDebugFloodPaint() == 4 ? "4" : "3");
        return builder.build();
    }

    /**
     * True when ship-to-world dynamic lighting should override sodium's stock
     * chunk shader. Gated only on the config because the mesher mixin packs
     * face-slot bits into the alpha byte for world chunks whenever the config
     * is on; sodium's stock shader would misinterpret those bits as plain AO,
     * so the swap MUST stay aligned with the packing — no "skip when no ships
     * are nearby" optimization. The world shader runs fine with empty storage
     * (LUT lookups return 0, emitter loop runs 0 times, no visible effect).
     */
    public static boolean shouldUseWorldFromShipShader() {
        return VSGameConfig.CLIENT.getDynamicShipToWorldLighting();
    }

    private static GlProgram<ShipThing> createShader(String path, ChunkShaderOptions options, int features) {
        ShaderConstants constants = createShipShaderConstants(options, features);

        GlShader vertShader = ShaderLoader.loadShader(ShaderType.VERTEX,
                new ResourceLocation("valkyrienskies", path + ".vsh"), constants);
        
        GlShader fragShader = ShaderLoader.loadShader(ShaderType.FRAGMENT,
                new ResourceLocation("valkyrienskies", path + ".fsh"), constants);

        try {
            return GlProgram.builder(new ResourceLocation("valkyrienskies", "chunk_shader"))
                    .attachShader(vertShader)
                    .attachShader(fragShader)
                    .bindAttribute("a_Position", ChunkShaderBindingPoints.ATTRIBUTE_POSITION)
                    .bindAttribute("a_Color", ChunkShaderBindingPoints.ATTRIBUTE_COLOR)
                    .bindAttribute("a_TexCoord", ChunkShaderBindingPoints.ATTRIBUTE_TEXTURE)
                    .bindAttribute("a_LightAndData", ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_MATERIAL_INDEX)
                    .bindFragmentData("fragColor", ChunkShaderBindingPoints.FRAG_COLOR)
                    .link((shader) -> new ShipThing(shader, options, features));
        } finally {
            vertShader.delete();
            fragShader.delete();
        }
    }

    private static ShaderConstants createShipShaderConstants(ChunkShaderOptions options, int features) {
        ShaderConstants.Builder builder = ShaderConstants.builder();

        for (String define : options.constants().getDefineStrings()) {
            String[] parts = define.split("\\s+", 3);
            if (parts.length < 2 || !"#define".equals(parts[0])) {
                throw new IllegalArgumentException("Unexpected shader define format: " + define);
            }

            String name = parts[1];
            if ("USE_VANILLA_COLOR_FORMAT".equals(name)) {
                continue;
            }

            if (parts.length == 2) {
                builder.add(name);
            } else {
                builder.add(name, parts[2]);
            }
        }

        // VS-specific feature defines, gated by config. Compile-time `#ifdef`
        // in the VSH/FSH means disabled features cost nothing on the GPU.
        if ((features & FEATURE_BIOME) != 0) builder.add("VS_DYNAMIC_BIOME");
        if ((features & FEATURE_LIGHT) != 0) builder.add("VS_DYNAMIC_LIGHT");
        if ((features & FEATURE_SHADE) != 0) builder.add("VS_DYNAMIC_SHADE");
        if ((features & FEATURE_SHIP_ON_SHIP) != 0) builder.add("VS_SHIP_ON_SHIP");
        if ((features & FEATURE_FLOOD_GRID) != 0) builder.add("VS_FLOOD_GRID");
        // Every feature bit ShipThing gates a uniform binding on has to be emitted as a define here,
        // or the FSH compiles without the reader, GLSL drops the uniform, and bindUniform NPEs.
        if ((features & FEATURE_SHIP_AO) != 0) builder.add("VS_SHIP_AO");
        if ((features & FEATURE_SEAM_NO_MERGE) != 0) builder.add("VS_SEAM_NO_MERGE");
        if ((features & FEATURE_SEAM_PRECOMP) != 0) builder.add("VS_SEAM_PRECOMP");
        if ((features & FEATURE_DEBUG_SEAM_AO) != 0) builder.add("VS_DEBUG_SEAM_AO");
        if (AO_PROF != 0) builder.add("VS_AOPROF", Integer.toString(AO_PROF));
        if (AO_CUT != 0) builder.add("VS_AOCUT", Integer.toString(AO_CUT));
        if (AO_CUT_SHIP != 0) builder.add("VS_AOCUTSHIP", Integer.toString(AO_CUT_SHIP));
        if (!SEAM_SUB.isEmpty()) builder.add("VS_SEAM_SUBTRACT", SEAM_SUB);
        if (SEAM_KERNEL != 1) builder.add("VS_SEAM_KERNEL", Integer.toString(SEAM_KERNEL));
        // getOrCreateShipProgram sets this bit from debugFloodPaint, so it has to be emitted here too
        // -- without it the debug paint is silently a no-op on ship chunks.
        if ((features & FEATURE_DEBUG_SHIP_LIGHT) != 0) {
            builder.add("VS_DEBUG_SHIP_LIGHT", VSGameConfig.CLIENT.getDebugFloodPaint() == 4 ? "4" : "3");
        }

        return builder.build();
    }
}
