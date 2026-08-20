package org.valkyrienskies.mod.compat.sodium.shader;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3f;
import org.joml.Matrix4dc;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import org.slf4j.LoggerFactory;

import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;

/**
 * GPU replacement for {@link VsWorldFromShipLightStorage}'s CPU stamp + BFS.
 *
 * <p>The CPU keeps only the work that is genuinely cheap: transforming each ship's cached voxel list
 * (see {@link VsShipVoxelCache}) into the per-frame emitter and occluder lists the fragment shaders
 * already read, allocating the sections the flood will touch, and handing the ship transforms over as
 * uniforms. Everything else — stamping voxels into the world grid, flooding light out of them, and
 * packing the result into the sampled section layout — happens in the five compute passes under
 * {@code assets/valkyrienskies/shaders/compute}.
 *
 * <p>The work splits into {@link #prepare} and {@link #dispatch} because the flood reads world
 * terrain opacity out of {@link VsShipLightStorage}, which is only finalised after the caller has run
 * its own light-section population. {@link #prepare} therefore publishes the volumes the flood needs
 * via {@link #floodRegions()} so that storage can be asked for them, and {@link #dispatch} runs once
 * everything it reads has been uploaded.
 */
public final class VsGpuLightFlood {
    /** Vanilla's maximum light level, and so the furthest a flood front can travel. */
    private static final int MAX_LIGHT_LEVEL = 15;
    /** Blocks of slack beyond the light's reach when sizing the tracked region; see its use below. */
    /**
     * Slack blocks beyond the light's reach when sizing the tracked region. Kept at zero: the region is
     * rounded out to whole sections anyway, and every extra block of slack costs section volume
     * cubically -- at 6 it more than doubled the section count for a 3x3x3 ship.
     */
    private static final int FLOOD_REGION_MARGIN = 0;
    /** Ceiling on live sections, bounding the working buffers at roughly 24 MB. */
    private static final int MAX_ACTIVE_SECTIONS = 512;

    private static final int BIND_LIGHT_SRC = 0;
    private static final int BIND_LIGHT_DST = 1;
    private static final int BIND_OCCL = 2;
    private static final int BIND_SOLID = 3;
    private static final int BIND_LUT = 4;
    private static final int BIND_SECTIONS = 5;
    private static final int BIND_WORLD_LUT = 6;
    private static final int BIND_WORLD_SECTIONS = 7;
    private static final int BIND_SLOT_POS = 8;
    private static final int BIND_VOXELS = 9;
    private static final int BIND_COUNT = 10;

    private static final int CORE_VOXELS = 4096;      // 16^3
    private static final int CORE_SOLID_INTS = 128;   // 4096 bits
    private static final int SECTION_SIZE_INTS = VsWorldFromShipLightStorage.SECTION_SIZE_INTS;

    /** One ship's contribution to the stamp pass. */
    private static final class ShipDispatch {
        final Matrix3f rotation = new Matrix3f();
        float posRelX;
        float posRelY;
        float posRelZ;
        // The same corner in absolute world coordinates, kept in double so the emitter and occluder
        // lists don't inherit the render-origin round trip's float error.
        double originX;
        double originY;
        double originZ;
        int firstVoxel;
        int voxelCount;
        long sourcePtr;
        // Exit cells: where this ship's light reaches open air. Uploaded into the tail of the same
        // buffer as the voxels, since they share the 8-byte packing.
        long exitSourcePtr;
        int firstExit;
        int exitCount;
    }


    private VsComputeProgram clearProgram;
    private VsComputeProgram bakeProgram;
    private VsComputeProgram stampProgram;
    private VsComputeProgram seedProgram;
    private VsComputeProgram floodProgram;
    private VsComputeProgram packProgram;
    private boolean programsFailed = false;

    private int lightABuffer = 0;
    private int lightBBuffer = 0;
    private int occlBuffer = 0;
    private int solidBuffer = 0;
    private int slotPosBuffer = 0;
    private int voxelBuffer = 0;

    private int lightABytes = 0;
    private int lightBBytes = 0;
    private int occlBytes = 0;
    private int solidBytes = 0;
    private int slotPosBytes = 0;
    private int voxelBytes = 0;

    private long stagingPtr = 0L;
    private long stagingBytes = 0L;
    private long uploadedVoxelSignature = Long.MIN_VALUE;
    private long voxelRevision = 0L;

    private final List<ShipDispatch> dispatches = new ArrayList<>();
    private final IntArrayList slotPositions = new IntArrayList();
    private final DoubleArrayList floodRegions = new DoubleArrayList();
    private final Vector3d scratchCorner = new Vector3d();

    private int activeSections = 0;
    private int floodIterations = 0;
    // Per-frame state trace, enabled by -Dvs.floodtrace=true. Logs only on CHANGE, so a steady frame
    // costs nothing and any frame that alters the flood's inputs stands out next to a screenshot burst.
    private static final boolean TRACE = Boolean.getBoolean("vs.floodtrace");
    /**
     * Bench switch, -Dvs.floodbench=N. 1 skips every compute dispatch, 2 keeps the dispatches but runs
     * zero flood sweeps. Comparing the three against a normal run says whether frame time is going to
     * the GPU passes, to the flood loop specifically, or to the CPU work around them -- which guessing
     * from a frame-time delta alone cannot.
     */
    private static final int BENCH = Integer.getInteger("vs.floodbench", 0);
    private int traceSections = -1;
    private int traceVoxels = -1;
    private int traceIters = -1;
    private boolean traceValid;
    private long traceFrame;

    private int renderOriginX;
    private int renderOriginY;
    private int renderOriginZ;
    private boolean readyToDispatch = false;
    /**
     * Whether the packed grid can be trusted as the authority on where ship light reaches.
     *
     * <p>Deliberately STICKY across frames. The grid lives in GPU memory and survives between frames, so a
     * chunk drawn before this frame's dispatch simply samples last frame's grid, which is still real data.
     * Clearing this at the top of every prepare() instead made it a race: terrain batches drawn earlier in
     * the frame than the dispatch saw 0 and switched the occlusion gate off, which is a per-draw-batch
     * flicker that reads as a large permanent light leak — about a quarter of the screen in testing.
     * It is cleared only when the grid genuinely stops being maintained.
     */
    private boolean gridValid = false;

    /**
     * World-space AABBs the flood can reach, as {minX, minY, minZ, maxX, maxY, maxZ} runs. Published
     * so {@link VsShipLightStorage} can be asked for terrain opacity over the same volume before
     * {@link #dispatch} runs.
     */
    public DoubleArrayList floodRegions() {
        return floodRegions;
    }

    /** Whether the packed grid is authoritative this frame; drives {@code u_VsFloodGridValid}. */
    public boolean isGridValid() {
        return gridValid;
    }

    /** True once the compute programs have failed to build, so callers can fall back permanently. */
    public boolean isBroken() {
        return programsFailed;
    }

    /**
     * CPU half of the frame: refresh the ship voxel caches, rebuild the emitter and occluder lists,
     * allocate the sections the flood needs, and stage everything the compute passes will read.
     */
    public void prepare(final ClientLevel level, final Iterable<ClientShip> ships,
        final Predicate<ClientShip> filter, final VsShipVoxelCache voxelCache,
        final VsWorldFromShipLightStorage storage, final VsShipEmitterList emitters,
        final VsShipOccluderList occluders) {
        readyToDispatch = false;
        dispatches.clear();
        floodRegions.clear();

        storage.setGpuMode(true);
        storage.beginFrame();
        voxelCache.beginFrame();
        emitters.beginFrame();
        occluders.beginFrame();

        final Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        renderOriginX = (int) Math.floor(camera.x);
        renderOriginY = (int) Math.floor(camera.y);
        renderOriginZ = (int) Math.floor(camera.z);

        int totalVoxels = 0;
        int totalExits = 0;
        long signature = 1469598103934665603L;
        int maxLight = 0;

        for (final ClientShip ship : ships) {
            if (filter != null && !filter.test(ship)) {
                continue;
            }
            final AABBdc renderAabb = ship.getRenderAABB();
            if (renderAabb == null) {
                continue;
            }
            final VsShipVoxelCache.ShipVoxels voxels = voxelCache.get(level, ship);
            if (voxels == null || voxels.count() == 0) {
                continue;
            }

            final ShipTransform transform = ship.getRenderTransform();
            final Matrix4dc shipToWorld = transform.getShipToWorld();

            // shipToWorld is affine, so transforming the AABB-min voxel centre once here in double
            // precision and adding the rotated integer offset per voxel on the GPU is exact — and it
            // keeps the GPU's floats camera-relative, where they have plenty of precision.
            scratchCorner.set(voxels.minX() + 0.5, voxels.minY() + 0.5, voxels.minZ() + 0.5);
            shipToWorld.transformPosition(scratchCorner);

            final ShipDispatch dispatch = new ShipDispatch();
            fillRotation(shipToWorld, dispatch.rotation);
            dispatch.posRelX = (float) (scratchCorner.x - renderOriginX);
            dispatch.posRelY = (float) (scratchCorner.y - renderOriginY);
            dispatch.posRelZ = (float) (scratchCorner.z - renderOriginZ);
            dispatch.originX = scratchCorner.x;
            dispatch.originY = scratchCorner.y;
            dispatch.originZ = scratchCorner.z;
            dispatch.firstVoxel = totalVoxels;
            dispatch.voxelCount = voxels.count();
            dispatch.sourcePtr = voxels.pointer();

            dispatch.exitSourcePtr = voxels.exitPointer();
            dispatch.exitCount = voxels.exitCount();
            dispatch.firstExit = totalExits;
            dispatches.add(dispatch);

            totalVoxels += voxels.count();
            totalExits += voxels.exitCount();
            signature = (signature ^ ship.getId()) * 1099511628211L;
            signature = (signature ^ voxels.count()) * 1099511628211L;
            maxLight = Math.max(maxLight, voxels.maxExitLevel());

            // Sections holding the ship's own voxels: solid bits, occluder strength, emitter seeds.
            storage.ensureSectionsInAabb(level,
                renderAabb.minX(), renderAabb.minY(), renderAabb.minZ(),
                renderAabb.maxX(), renderAabb.maxY(), renderAabb.maxZ());

            appendListsAndFloodRegion(level, storage, ship, transform, voxels, dispatch, emitters,
                occluders);
        }

        // Exactly the light's own reach: the flood is the light now, not a mask that has to outrun a
        // second field, so there is nothing to overshoot and every extra sweep is wasted frame time.
        floodIterations = Math.max(0, Math.min(maxLight, MAX_LIGHT_LEVEL) - 1);

        voxelRevision = voxelCache.revision();
        voxelCache.pruneUnused();
        storage.pruneUnused();
        activeSections = storage.fillActiveSlotPositions(slotPositions);

        if (activeSections > MAX_ACTIVE_SECTIONS) {
            // Degenerate scene (an enormous ship, or many lit ships at once). Clearing rather than
            // uploading leaves the shaders finding no data, which disables the occlusion gate and
            // falls back to the plain emitter field instead of sampling a grid nothing refreshed.
            LoggerFactory.getLogger("VS2").debug(
                "GPU light flood skipped: {} live sections exceeds the {} cap",
                activeSections, MAX_ACTIVE_SECTIONS);
            storage.clearAll();
            activeSections = 0;
            gridValid = false;
            return;
        }

        storage.uploadForGpu();
        if (activeSections == 0 || totalVoxels == 0) {
            // Nothing to flood: the grid is empty, so it is not an authority on anything.
            gridValid = false;
            return;
        }
        // NOTE: zero exit cells is NOT this case. A sealed ship legitimately reaches open air nowhere,
        // and "no light escapes" is an answer, not a failure -- the passes still run and produce a
        // zeroed grid that correctly gates all of its light away. Treating it as an invalid grid
        // switched the gate off globally instead, so a sealed hull lit the ground at full strength
        // while an open one looked identical.

        if (TRACE) {
            traceFrame++;
            if (activeSections != traceSections || totalVoxels + totalExits != traceVoxels
                || floodIterations != traceIters || gridValid != traceValid) {
                LoggerFactory.getLogger("VS2-floodtrace").info(
                    "frame={} sections={} voxels={} exits={} iters={} gridValid={}",
                    traceFrame, activeSections, totalVoxels, totalExits, floodIterations, gridValid);
                traceSections = activeSections;
                traceVoxels = totalVoxels + totalExits;
                traceIters = floodIterations;
                traceValid = gridValid;
            }
        }
        // Exits live in the tail of the voxel buffer, so their offsets only become absolute once the
        // voxel region's final size is known.
        for (final ShipDispatch dispatch : dispatches) {
            dispatch.firstExit += totalVoxels;
        }
        uploadVoxels(totalVoxels, totalExits, signature);
        uploadSlotPositions();
        ensureWorkingBuffers(storage.capacity());
        readyToDispatch = true;
    }

    /**
     * Appends this ship's voxels to the per-frame emitter and occluder lists (which the fragment
     * shaders read for the continuous, sub-block-precise part of the lighting) and grows the flood
     * region to cover every emitter's reach.
     */
    private void appendListsAndFloodRegion(final ClientLevel level,
        final VsWorldFromShipLightStorage storage, final ClientShip ship,
        final ShipTransform transform, final VsShipVoxelCache.ShipVoxels voxels,
        final ShipDispatch dispatch, final VsShipEmitterList emitters,
        final VsShipOccluderList occluders) {

        final Quaterniondc rotation = transform.getRotation();
        final float qx = (float) rotation.x();
        final float qy = (float) rotation.y();
        final float qz = (float) rotation.z();
        final float qw = (float) rotation.w();

        final int shipIndex = occluders.indexForShip(ship.getId());
        final Matrix3f m = dispatch.rotation;
        final double originX = dispatch.originX;
        final double originY = dispatch.originY;
        final double originZ = dispatch.originZ;

        double emitterMinX = Double.POSITIVE_INFINITY;
        double emitterMinY = Double.POSITIVE_INFINITY;
        double emitterMinZ = Double.POSITIVE_INFINITY;
        double emitterMaxX = Double.NEGATIVE_INFINITY;
        double emitterMaxY = Double.NEGATIVE_INFINITY;
        double emitterMaxZ = Double.NEGATIVE_INFINITY;

        final long base = voxels.pointer();
        for (int i = 0; i < voxels.count(); i++) {
            final long entry = base + (long) i * VsShipVoxelCache.BYTES_PER_VOXEL;
            final int word0 = MemoryUtil.memGetInt(entry);
            final int word1 = MemoryUtil.memGetInt(entry + 4);
            final float dx = word0 & 0xFFFF;
            final float dy = (word0 >>> 16) & 0xFFFF;
            final float dz = word1 & 0xFFFF;
            final int light = (word1 >>> 16) & 0xFF;
            final boolean solid = ((word1 >>> 24) & 1) != 0;

            final double wx = m.m00 * dx + m.m10 * dy + m.m20 * dz + originX;
            final double wy = m.m01 * dx + m.m11 * dy + m.m21 * dz + originY;
            final double wz = m.m02 * dx + m.m12 * dy + m.m22 * dz + originZ;

            if (solid) {
                occluders.appendOccluder(wx, wy, wz, shipIndex, qx, qy, qz, qw);
            }
            if (light > 0) {
                emitters.appendEmitter(wx, wy, wz, light, qx, qy, qz, qw);
                emitterMinX = Math.min(emitterMinX, wx);
                emitterMinY = Math.min(emitterMinY, wy);
                emitterMinZ = Math.min(emitterMinZ, wz);
                emitterMaxX = Math.max(emitterMaxX, wx);
                emitterMaxY = Math.max(emitterMaxY, wy);
                emitterMaxZ = Math.max(emitterMaxZ, wz);
            }
        }

        // The flood now starts at the ship's EXIT CELLS, which sit on its outer surface rather than at
        // its emitters, so the region has to be grown from the hull outward. Expanding the render AABB
        // by the brightest exit level covers every cell any of them can reach.
        final AABBdc renderAabb = ship.getRenderAABB();
        if (renderAabb == null || voxels.exitCount() == 0) {
            return;
        }
        // Reach plus a margin. Sized to the reach exactly, the tracked region ends where the flood
        // still holds a level or two, and the shaders read "no section here" as a hard zero -- so the
        // region's own edge became a visible straight line sweeping across the ground as the ship
        // moved. The margin pushes the boundary out to where the flood is genuinely 0, making the cut
        // invisible instead of merely small.
        final int reach = Math.min(voxels.maxExitLevel(), MAX_LIGHT_LEVEL) + FLOOD_REGION_MARGIN;
        final double floodMinX = renderAabb.minX() - reach;
        final double floodMinY = renderAabb.minY() - reach;
        final double floodMinZ = renderAabb.minZ() - reach;
        final double floodMaxX = renderAabb.maxX() + reach;
        final double floodMaxY = renderAabb.maxY() + reach;
        final double floodMaxZ = renderAabb.maxZ() + reach;

        floodRegions.add(floodMinX);
        floodRegions.add(floodMinY);
        floodRegions.add(floodMinZ);
        floodRegions.add(floodMaxX);
        floodRegions.add(floodMaxY);
        floodRegions.add(floodMaxZ);

        storage.ensureSectionsInAabb(level,
            floodMinX, floodMinY, floodMinZ, floodMaxX, floodMaxY, floodMaxZ);
    }

    private static void fillRotation(final Matrix4dc shipToWorld, final Matrix3f out) {
        out.set(
            (float) shipToWorld.m00(), (float) shipToWorld.m01(), (float) shipToWorld.m02(),
            (float) shipToWorld.m10(), (float) shipToWorld.m11(), (float) shipToWorld.m12(),
            (float) shipToWorld.m20(), (float) shipToWorld.m21(), (float) shipToWorld.m22());
    }

    /** GPU half of the frame. Must run after the world light storage has been uploaded. */
    public void dispatch(final VsWorldFromShipLightStorage storage,
        final VsShipLightStorage worldLight) {
        if (BENCH == 1) {
            readyToDispatch = false;
            return;
        }
        if (!readyToDispatch || !ensurePrograms()) {
            readyToDispatch = false;
            if (programsFailed) {
                gridValid = false;
            }
            return;
        }
        readyToDispatch = false;

        final int capacity = storage.capacity();

        bind(BIND_OCCL, occlBuffer);
        bind(BIND_SOLID, solidBuffer);
        bind(BIND_LUT, storage.lutBufferId());
        bind(BIND_SECTIONS, storage.sectionsBufferId());
        bind(BIND_WORLD_LUT, worldLight.lutBufferId());
        bind(BIND_WORLD_SECTIONS, worldLight.sectionsBufferId());
        bind(BIND_SLOT_POS, slotPosBuffer);
        bind(BIND_VOXELS, voxelBuffer);
        bind(BIND_LIGHT_SRC, lightABuffer);
        bind(BIND_LIGHT_DST, lightBBuffer);

        clearProgram.bind();
        clearProgram.set("u_ActiveCount", activeSections);
        clearProgram.dispatch(activeSections * CORE_VOXELS);
        barrier();

        bakeProgram.bind();
        bakeProgram.set("u_ActiveCount", activeSections);
        bakeProgram.dispatch(activeSections * CORE_VOXELS);
        barrier();

        stampProgram.bind();
        stampProgram.set("u_RenderOrigin", renderOriginX, renderOriginY, renderOriginZ);
        for (final ShipDispatch dispatch : dispatches) {
            stampProgram.setMatrix3("u_Rot", dispatch.rotation);
            stampProgram.set("u_PosRel", dispatch.posRelX, dispatch.posRelY, dispatch.posRelZ);
            stampProgram.set("u_FirstVoxel", dispatch.firstVoxel);
            stampProgram.set("u_VoxelCount", dispatch.voxelCount);
            stampProgram.dispatch(dispatch.voxelCount);
        }
        barrier();

        // Seeds go in only once every solid bit above is visible, so a seed can refuse to land inside
        // a wall. Merging this back into the stamp would reintroduce the leak it exists to prevent:
        // within one dispatch the wall a seed needs to test may not be written yet.
        seedProgram.bind();
        seedProgram.set("u_RenderOrigin", renderOriginX, renderOriginY, renderOriginZ);
        for (final ShipDispatch dispatch : dispatches) {
            seedProgram.setMatrix3("u_Rot", dispatch.rotation);
            seedProgram.set("u_PosRel", dispatch.posRelX, dispatch.posRelY, dispatch.posRelZ);
            seedProgram.set("u_FirstVoxel", dispatch.firstExit);
            seedProgram.set("u_VoxelCount", dispatch.exitCount);
            if (dispatch.exitCount > 0) {
                seedProgram.dispatch(dispatch.exitCount);
            }
        }
        barrier();

        // Ping-pong: each sweep reads whatever is bound at BIND_LIGHT_SRC and writes BIND_LIGHT_DST,
        // so the two swap every iteration and the last write is re-bound as the source for the pack.
        final int sweeps = BENCH == 2 ? 0 : floodIterations;
        boolean resultInA = true;
        floodProgram.bind();
        floodProgram.set("u_ActiveCount", activeSections);
        for (int i = 0; i < sweeps; i++) {
            bind(BIND_LIGHT_SRC, resultInA ? lightABuffer : lightBBuffer);
            bind(BIND_LIGHT_DST, resultInA ? lightBBuffer : lightABuffer);
            floodProgram.dispatch(activeSections * CORE_VOXELS);
            barrier();
            resultInA = !resultInA;
        }
        bind(BIND_LIGHT_SRC, resultInA ? lightABuffer : lightBBuffer);

        packProgram.bind();
        packProgram.set("u_ActiveCount", activeSections);
        packProgram.dispatch(activeSections * SECTION_SIZE_INTS);
        gridValid = true;

        VsComputeProgram.unbind();
        // The packed sections are sampled as a buffer texture by the chunk shaders later this frame.
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

        // GlStateManager doesn't track SSBO binding points, so leave none behind for Sodium to trip
        // over.
        for (int i = 0; i < BIND_COUNT; i++) {
            bind(i, 0);
        }
    }

    private static void barrier() {
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }

    private static void bind(final int index, final int buffer) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, index, buffer);
    }

    private boolean ensurePrograms() {
        if (programsFailed) {
            return false;
        }
        if (clearProgram != null) {
            return true;
        }
        try {
            clearProgram = VsComputeProgram.load("vs_light_clear.comp");
            bakeProgram = VsComputeProgram.load("vs_light_bake_world.comp");
            stampProgram = VsComputeProgram.load("vs_light_stamp.comp");
            seedProgram = VsComputeProgram.load("vs_light_seed.comp");
            floodProgram = VsComputeProgram.load("vs_light_flood.comp");
            packProgram = VsComputeProgram.load("vs_light_pack.comp");
            return true;
        } catch (final RuntimeException e) {
            programsFailed = true;
            deletePrograms();
            LoggerFactory.getLogger("VS2").error(
                "Failed to build the GPU dynamic-light compute programs; falling back to the CPU "
                    + "flood", e);
            return false;
        }
    }

    private void ensureWorkingBuffers(final int capacity) {
        final int lightBytes = capacity * CORE_VOXELS * 4;
        final int bitmapBytes = capacity * CORE_SOLID_INTS * 4;
        lightABuffer = ensureBuffer(lightABuffer, lightABytes, lightBytes);
        lightABytes = lightBytes;
        lightBBuffer = ensureBuffer(lightBBuffer, lightBBytes, lightBytes);
        lightBBytes = lightBytes;
        occlBuffer = ensureBuffer(occlBuffer, occlBytes, lightBytes);
        occlBytes = lightBytes;
        solidBuffer = ensureBuffer(solidBuffer, solidBytes, bitmapBytes);
        solidBytes = bitmapBytes;
    }

    private static int ensureBuffer(int buffer, final int currentBytes, final int neededBytes) {
        if (buffer != 0 && currentBytes == neededBytes) {
            return buffer;
        }
        if (buffer == 0) {
            buffer = GL15.glGenBuffers();
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15.nglBufferData(GL43.GL_SHADER_STORAGE_BUFFER, neededBytes, MemoryUtil.NULL,
            GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        return buffer;
    }

    private void uploadSlotPositions() {
        final int bytes = slotPositions.size() * 4;
        final long staging = ensureStaging(bytes);
        for (int i = 0; i < slotPositions.size(); i++) {
            MemoryUtil.memPutInt(staging + (long) i * 4, slotPositions.getInt(i));
        }
        if (slotPosBuffer == 0) {
            slotPosBuffer = GL15.glGenBuffers();
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, slotPosBuffer);
        if (slotPosBytes != bytes) {
            GL15.nglBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytes, staging, GL15.GL_DYNAMIC_DRAW);
            slotPosBytes = bytes;
        } else {
            GL15.nglBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, bytes, staging);
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * Repacks and re-uploads the concatenated ship voxel arena. Ship voxel lists are static, so this
     * only runs when the contributing ship set or one of their block layouts changed — the signature
     * covers the first, the cache's revision the second.
     */
    private void uploadVoxels(final int totalVoxels, final int totalExits, final long signature) {
        final int bytes = (totalVoxels + totalExits) * VsShipVoxelCache.BYTES_PER_VOXEL;
        final long combined = signature * 31L + voxelRevision;
        if (combined == uploadedVoxelSignature && voxelBytes == bytes) {
            return;
        }
        uploadedVoxelSignature = combined;

        final long staging = ensureStaging(bytes);
        for (final ShipDispatch dispatch : dispatches) {
            MemoryUtil.memCopy(dispatch.sourcePtr,
                staging + (long) dispatch.firstVoxel * VsShipVoxelCache.BYTES_PER_VOXEL,
                (long) dispatch.voxelCount * VsShipVoxelCache.BYTES_PER_VOXEL);
            // Exit cells share the packing, so they ride in the tail of the same buffer.
            MemoryUtil.memCopy(dispatch.exitSourcePtr,
                staging + (long) dispatch.firstExit * VsShipVoxelCache.BYTES_PER_VOXEL,
                (long) dispatch.exitCount * VsShipVoxelCache.BYTES_PER_VOXEL);
        }

        if (voxelBuffer == 0) {
            voxelBuffer = GL15.glGenBuffers();
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, voxelBuffer);
        GL15.nglBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytes, staging, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        voxelBytes = bytes;
    }

    private long ensureStaging(final long bytes) {
        if (stagingBytes < bytes) {
            stagingPtr = MemoryUtil.nmemRealloc(stagingPtr, bytes);
            stagingBytes = bytes;
        }
        return stagingPtr;
    }

    private void deletePrograms() {
        if (clearProgram != null) clearProgram.delete();
        if (bakeProgram != null) bakeProgram.delete();
        if (stampProgram != null) stampProgram.delete();
        if (seedProgram != null) seedProgram.delete();
        if (floodProgram != null) floodProgram.delete();
        if (packProgram != null) packProgram.delete();
        clearProgram = null;
        bakeProgram = null;
        stampProgram = null;
        seedProgram = null;
        floodProgram = null;
        packProgram = null;
    }

    public void delete() {
        gridValid = false;
        deletePrograms();
        for (final int buffer : new int[] {lightABuffer, lightBBuffer, occlBuffer, solidBuffer,
            slotPosBuffer, voxelBuffer}) {
            if (buffer != 0) {
                GL15.glDeleteBuffers(buffer);
            }
        }
        lightABuffer = 0;
        lightBBuffer = 0;
        occlBuffer = 0;
        solidBuffer = 0;
        slotPosBuffer = 0;
        voxelBuffer = 0;
        lightABytes = 0;
        lightBBytes = 0;
        occlBytes = 0;
        solidBytes = 0;
        slotPosBytes = 0;
        voxelBytes = 0;
        if (stagingPtr != 0L) {
            MemoryUtil.nmemFree(stagingPtr);
            stagingPtr = 0L;
            stagingBytes = 0L;
        }
        uploadedVoxelSignature = Long.MIN_VALUE;
        readyToDispatch = false;
        programsFailed = false;
    }
}
