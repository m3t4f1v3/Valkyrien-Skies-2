package org.valkyrienskies.mod.common.render.light;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;

import com.mojang.blaze3d.systems.RenderSystem;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.ShipRendererKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.compat.sodium.shader.VsComputeSupport;
import org.valkyrienskies.mod.compat.sodium.shader.VsGpuLightFlood;
import org.valkyrienskies.mod.compat.sodium.shader.VsShipBiomeColorStorage;
import org.valkyrienskies.mod.compat.sodium.shader.VsShipEmitterList;
import org.valkyrienskies.mod.compat.sodium.shader.VsShipLightStorage;
import org.valkyrienskies.mod.compat.sodium.shader.VsShipOccluderList;
import org.valkyrienskies.mod.compat.sodium.shader.VsShipVoxelCache;
import org.valkyrienskies.mod.compat.sodium.shader.VsWorldFromShipLightStorage;

public final class VsDynamicLight {

    public static final int LIGHT_SECTIONS_TEXTURE_UNIT = 6;
    public static final int LIGHT_LUT_TEXTURE_UNIT = 7;
    public static final int BIOME_SECTIONS_TEXTURE_UNIT = 8;
    public static final int BIOME_LUT_TEXTURE_UNIT = 9;
    public static final int WORLD_FROM_SHIP_SECTIONS_TEXTURE_UNIT = 10;
    public static final int WORLD_FROM_SHIP_LUT_TEXTURE_UNIT = 11;
    public static final int SHIP_EMITTER_LIST_TEXTURE_UNIT = 12;
    public static final int SHIP_OCCLUDER_LIST_TEXTURE_UNIT = 13;

    private static VsShipLightStorage lightStorage;
    private static VsShipBiomeColorStorage biomeStorage;
    private static VsWorldFromShipLightStorage worldFromShipStorage;
    private static VsShipEmitterList shipEmitterList;
    private static VsShipOccluderList shipOccluderList;
    private static VsGpuLightFlood gpuLightFlood;
    /**
     * Per-ship voxel lists, shared by both paths. The block scan is the expensive half of projecting
     * a ship into world lighting, and a ship's blocks only change when someone edits them, so both
     * the compute flood and the CPU BFS read it from here rather than rescanning per frame.
     */
    private static VsShipVoxelCache shipVoxelCache;
    /** Last render-thread answer from {@link #isGpuFloodActive}, readable from any thread. */
    private static volatile boolean gpuFloodActive = false;

    private VsDynamicLight() {
    }

    public static VsShipLightStorage getLightStorage() {
        if (lightStorage == null) {
            lightStorage = new VsShipLightStorage();
        }
        return lightStorage;
    }

    public static VsShipBiomeColorStorage getBiomeStorage() {
        if (biomeStorage == null) {
            biomeStorage = new VsShipBiomeColorStorage();
        }
        return biomeStorage;
    }

    public static VsWorldFromShipLightStorage getWorldFromShipStorage() {
        if (worldFromShipStorage == null) {
            worldFromShipStorage = new VsWorldFromShipLightStorage();
        }
        return worldFromShipStorage;
    }

    public static VsShipEmitterList getShipEmitterList() {
        if (shipEmitterList == null) {
            shipEmitterList = new VsShipEmitterList();
        }
        return shipEmitterList;
    }

    public static VsShipOccluderList getShipOccluderList() {
        if (shipOccluderList == null) {
            shipOccluderList = new VsShipOccluderList();
        }
        return shipOccluderList;
    }

    public static VsShipVoxelCache getShipVoxelCache() {
        if (shipVoxelCache == null) {
            shipVoxelCache = new VsShipVoxelCache();
        }
        return shipVoxelCache;
    }

    public static VsGpuLightFlood getGpuLightFlood() {
        if (gpuLightFlood == null) {
            gpuLightFlood = new VsGpuLightFlood();
        }
        return gpuLightFlood;
    }

    /**
     * Whether the ship-to-world flood runs in compute shaders. False sends every caller down the
     * original CPU BFS in {@link VsWorldFromShipLightStorage}, which stays fully functional.
     *
     * <p>The GL capability probe is context-local, so off the render thread this answers with the
     * last value the render thread computed. Chunk meshing threads ask via
     * {@link #shipToWorldBlockLightAt} and only need to know which storage holds live data.
     */
    public static boolean isGpuFloodActive() {
        if (!RenderSystem.isOnRenderThread()) {
            return gpuFloodActive;
        }
        final boolean active = VSGameConfig.CLIENT.getDynamicShipToWorldLighting()
            && VSGameConfig.CLIENT.getGpuDynamicLightFlood()
            && VsComputeSupport.isAvailable()
            && !getGpuLightFlood().isBroken();
        gpuFloodActive = active;
        return active;
    }

    /**
     * Whether the flood grid is authoritative this frame, i.e. whether the shaders may treat "no section
     * here" as "no ship light here". False on the CPU path and whenever the compute passes were skipped.
     */
    /**
     * Union of the flood's tracked region in world coordinates, or null when nothing is tracked.
     *
     * <p>The fragment shaders use it to skip the flood sample outright. At 1920x1080 the vast majority
     * of pixels are nowhere near a ship, and without this each one still walks the section LUT only to
     * be told there is no section here.
     */
    public static double[] floodBounds() {
        return gpuLightFlood == null ? null : gpuLightFlood.floodBounds();
    }

    public static boolean isFloodGridValid() {
        return isGpuFloodActive() && getGpuLightFlood().isGridValid();
    }

    /**
     * Ship-emitter contribution to a world block's block light, for entity and block-entity
     * brightness.
     *
     * <p>On the CPU path this is the exact flooded value. On the GPU path the flood lives only in GPU
     * memory, so it falls back to the emitter list's distance falloff — the same curve the fragment
     * shaders draw in open air, without occlusion. Reading the real grid back would stall the
     * pipeline every time an entity asked how bright it is.
     */
    public static int shipToWorldBlockLightAt(final BlockPos pos) {
        if (isGpuFloodActive()) {
            return getShipEmitterList()
                .maxLightAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        return getWorldFromShipStorage().getBlockLightAt(pos);
    }

    public static void deleteStorages() {
        if (gpuLightFlood != null) {
            gpuLightFlood.delete();
            gpuLightFlood = null;
        }
        if (shipVoxelCache != null) {
            shipVoxelCache.delete();
            shipVoxelCache = null;
        }
        if (biomeStorage != null) {
            biomeStorage.delete();
            biomeStorage = null;
        }
        if (lightStorage != null) {
            lightStorage.delete();
            lightStorage = null;
        }
        if (worldFromShipStorage != null) {
            worldFromShipStorage.delete();
            worldFromShipStorage = null;
        }
        if (shipEmitterList != null) {
            shipEmitterList.delete();
            shipEmitterList = null;
        }
        if (shipOccluderList != null) {
            shipOccluderList.delete();
            shipOccluderList = null;
        }
    }

    public static void populateWorldLightForBatched(final ClientLevel level) {
        if (level == null) {
            return;
        }
        final VsShipLightStorage light = getLightStorage();
        light.beginFrame();
        for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (!ShipRendererKt.getUsesBatchedRenderer(ship)) {
                continue;
            }
            final AABBdc aabb = ship.getRenderAABB();
            if (aabb != null) {
                light.requestSectionsInAabb(level,
                    aabb.minX(), aabb.minY(), aabb.minZ(),
                    aabb.maxX(), aabb.maxY(), aabb.maxZ());
            }
        }
        light.pruneUnused();
        light.upload();
    }

    /**
     * Rebuilds the ship-to-world storage plus the per-frame emitter and occluder lists.
     *
     * <p>Nothing here touches Sodium, so both the 0.5-era and the 0.9 compat layers call straight into
     * it rather than each keeping their own copy of the traversal.
     *
     * @param filter decides which ships contribute this frame; {@code null} means all of them. The
     *               Sodium-generation-specific callers pass a frustum test built from their own
     *               viewport type, which is the only part of this that differs between them.
     */
    public static void populateWorldFromShipsForFrame(final ClientLevel level,
        final Predicate<ClientShip> filter) {
        if (!VSGameConfig.CLIENT.getDynamicShipToWorldLighting()) {
            return;
        }
        if (level == null) {
            return;
        }
        final VsWorldFromShipLightStorage storage = getWorldFromShipStorage();
        final VsShipEmitterList emitters = getShipEmitterList();
        final VsShipOccluderList occluders = getShipOccluderList();

        if (isGpuFloodActive()) {
            // CPU half only. The compute dispatch waits for the world light storage, which the
            // caller populates next; see dispatchGpuLightFlood.
            getGpuLightFlood().prepare(level,
                VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips(), filter,
                getShipVoxelCache(), storage, emitters, occluders);
            // Seam-AO acceleration data (sub-run headers, per-ship directory, global bounds) has to
            // be built after the last appendOccluder and before upload, on every path that uploads.
            occluders.buildSeamData();
            emitters.upload();
            occluders.upload();
            return;
        }

        final VsShipVoxelCache cache = getShipVoxelCache();
        storage.setGpuMode(false);
        storage.beginFrame();
        cache.beginFrame();
        emitters.beginFrame();
        occluders.beginFrame();
        for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (filter != null && !filter.test(ship)) {
                continue;
            }
            storage.populateFromShip(level, ship, cache, emitters, occluders);
        }
        cache.pruneUnused();
        storage.pruneUnused();
        occluders.buildSeamData();
        storage.upload();
        emitters.upload();
        occluders.upload();
    }

    /**
     * Runs the compute passes, once {@link #populateLightSectionStorage} has uploaded the world
     * terrain opacity the flood reads. A no-op on the CPU path.
     */
    public static void dispatchGpuLightFlood() {
        if (!isGpuFloodActive()) {
            return;
        }
        getGpuLightFlood().dispatch(getWorldFromShipStorage(), getLightStorage());
    }

    public static void populateLightSectionStorage(final ClientLevel level) {
        final boolean gpuFlood = isGpuFloodActive();
        if (!VSGameConfig.CLIENT.getDynamicShipLighting() && !gpuFlood) {
            return;
        }
        final VsShipLightStorage storage = getLightStorage();
        storage.beginFrame();
        if (VSGameConfig.CLIENT.getDynamicShipLighting()) {
            for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
                final AABBdc aabb = ship.getRenderAABB();
                storage.requestSectionsInAabb(level,
                    aabb.minX(), aabb.minY(), aabb.minZ(),
                    aabb.maxX(), aabb.maxY(), aabb.maxZ());
            }
        }
        if (gpuFlood) {
            // The flood needs world terrain opacity everywhere a ship emitter can reach. Requesting
            // it here rather than inside the flood keeps this storage's cross-frame cache intact —
            // requesting after the prune below would have it re-collected from scratch every frame.
            final DoubleArrayList regions = getGpuLightFlood().floodRegions();
            for (int i = 0; i + 5 < regions.size(); i += 6) {
                storage.requestSectionsInAabb(level,
                    regions.getDouble(i), regions.getDouble(i + 1), regions.getDouble(i + 2),
                    regions.getDouble(i + 3), regions.getDouble(i + 4), regions.getDouble(i + 5));
            }
        }
        storage.pruneUnused();
        storage.upload();
    }

    public static void populateBiomeSectionStorage(final ClientLevel level) {
        if (!VSGameConfig.CLIENT.getDynamicShipBiomeTinting()) {
            return;
        }
        final VsShipBiomeColorStorage storage = getBiomeStorage();
        storage.beginFrame();
        for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            final AABBdc aabb = ship.getRenderAABB();
            storage.requestSectionsInAabb(level,
                aabb.minX(), aabb.minY(), aabb.minZ(),
                aabb.maxX(), aabb.maxY(), aabb.maxZ());
        }
        storage.pruneUnused();
        storage.upload();
    }
}
