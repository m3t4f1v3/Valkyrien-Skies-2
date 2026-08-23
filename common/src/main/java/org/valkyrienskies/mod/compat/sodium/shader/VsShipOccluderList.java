package org.valkyrienskies.mod.compat.sodium.shader;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

import org.joml.Matrix4dc;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.primitives.AABBic;

import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;

/**
 * Per-frame list of every solid ship voxel, packed as
 * {@code vec4(worldX, worldY, worldZ, padding)} entries into a buffer
 * texture. Mirrors {@link VsShipEmitterList} structurally but populates
 * from solid (full-cube opaque) voxels instead of light-emitting voxels.
 *
 * <p>Consumed per-vertex by the ship and world VSHs (vs_seamAoCorrection) for
 * ship-to-world and ship-on-ship AO seam matching. Iterating voxel centers
 * directly (rather than reading from the world-grid-aligned cell strengths
 * in {@link VsWorldFromShipLightStorage}) lets the AO shadow follow the
 * ship's transform continuously — including rotation — because the world
 * coords are continuous floats, not grid-quantized. With the cell-storage
 * approach, the AO pattern was anchored to world-aligned cells and
 * couldn't truly rotate; voxel-list AO is rotation-aware by construction.
 */
public class VsShipOccluderList {
    /** Cap on occluders tracked per frame. 1024 entries × 32 bytes = 32 KB GPU
     *  buffer (2 RGBA32F texels per voxel: position, quaternion). */
    public static final int MAX_OCCLUDERS = 1024;
    /** 8 floats per voxel, two vec4 texels:
     *    [i*2 + 0] = (worldX, worldY, worldZ, packedShipIndex)
     *    [i*2 + 1] = (qx, qy, qz, qw) — ship rotation, used so each
     *                voxel's octagon stays oriented with the ship
     *                instead of becoming a world-axis box.
     *  packedShipIndex is raw int bits (write/read via
     *  Float.intBitsToFloat / floatToRawIntBits, NEVER as a literal float
     *  value): bits 0-15 = dense per-frame ship index. */
    private static final int BYTES_PER_OCCLUDER = 32;
    private static final int SHIP_INDEX_MASK = 0xFFFF;

    // ==== Seam-AO acceleration data (built once per frame by buildSeamData,
    // consumed per-fragment by the chunk FSHs) =============================
    /** Sub-run split thresholds: a new header starts on ship change, every
     *  SUBRUN_MAX_VOXELS voxels, or when the sub-run's bounding sphere
     *  radius would exceed SUBRUN_MAX_RADIUS. Small tight spheres let the
     *  shader reject almost every sub-run with one texel fetch. */
    private static final int SUBRUN_MAX_VOXELS = 32;
    private static final float SUBRUN_MAX_RADIUS = 8.0f;
    /** 2 RGBA32F texels per sub-run header:
     *    [h*2 + 0] = (sphereCenterX, Y, Z, sphereRadius)
     *    [h*2 + 1] = (intBits(startVoxel), intBits(voxelCount),
     *                 intBits(shipIndex), 0) */
    private static final int BYTES_PER_HEADER = 32;
    /** Ship directory: 6 RGBA32F texels per dense ship index. Row 0 holds
     *  only the ship count in [0].x (intBits); rows 1..count are ships:
     *    [s*6 + 0] = (qx, qy, qz, qw)
     *    [s*6 + 1] = (anchorX, anchorY, anchorZ, r)   r = 1/(1 + Σ claims)
     *    [s*6 + 2] = (claim0, claim1, claim2, claim3)
     *    [s*6 + 3] = (intBits(p0), intBits(p1), intBits(p2), intBits(p3))
     *    [s*6 + 4] = (sphereCx, sphereCy, sphereCz, sphereRadius)
     *    [s*6 + 5] = (intBits(firstRun), intBits(runCount), 0, 0)
     *  p0..p3 = dense ship indices of the top-MAX_PARTNERS claim targets
     *  (0 = none). r counts ONLY the retained claims so every share the
     *  shader can look up actually materializes in some host lattice. The
     *  per-ship sphere + run range let the shader coarse-reject a whole far
     *  ship with one texel and scan only near ships' sub-runs. */
    private static final int MAX_SHIPS = MAX_OCCLUDERS + 1;
    private static final int BYTES_PER_SHIP = 96;
    private static final int MAX_PARTNERS = 4;
    /** Merge gate on the ship-pair AABB gap: 1 below G_FULL, 0 above G_ZERO.
     *  Must stay in sync with the Python reference (seam5.py) and the
     *  shader's field-support constant (4.5). */
    private static final float SEAM_G_FULL = 2.2f;
    private static final float SEAM_G_ZERO = 3.0f;
    private static final float OROT_LO = (float) (1.0 / Math.sqrt(2.0));
    /** Field support radius; must match the shader's *_SEAM_SUPPORT (3.7) and
     *  seam5.SUPPORT. Used to fatten sub-run spheres when binning them into the
     *  spatial grid, so a fragment within support of a sub-run always finds it
     *  in its own cell. The tent field is exactly zero past this distance, so
     *  3.7 (trimmed from a conservative 4.5) is drift-free while binning into
     *  fewer cells -> fewer sub-runs per fragment cell. */
    private static final float SEAM_SUPPORT = 3.7f;
    /** Distance LOD on the merge: the near edge of the fade is this fraction of
     *  the configured far edge, so one config number sets the whole ramp. */
    private static final float MERGE_LOD_NEAR_FRAC = 1f / 3f;

    // ==== Coarse spatial grid over the occluder bounds, binning SUB-RUNS.
    // A fragment reads only the sub-runs in its own cell, so the per-fragment
    // cost near a big ship is bounded by nearby runs, not the ship's total
    // run count. One RGBA32F buffer: [GRID_CELLS header texels (offset,count)]
    // then a flat sub-run-index list packed 4/texel.
    private static final int GRID_DIM = 8;
    private static final int GRID_CELLS = GRID_DIM * GRID_DIM * GRID_DIM;
    /** Max (sub-run, cell) insertions; overflow drops the last entries. */
    private static final int GRID_LIST_CAP = MAX_OCCLUDERS * 64;
    private static final int GRID_LIST_TEXELS = (GRID_LIST_CAP + 3) / 4;
    private static final int GRID_TEXEL_COUNT = GRID_CELLS + GRID_LIST_TEXELS;

    private final long arenaPtr;
    private final long headerPtr;
    private final long shipDirPtr;
    private final long gridPtr;
    private int count = 0;
    private int headerCount = 0;
    /** Camera position and merge-fade band, refreshed per frame by
     *  {@link #updateMergeLod()}. mergeLodFar == 0 means the LOD is off. */
    private float camX, camY, camZ;
    private float mergeLodNear, mergeLodFar;

    private int buffer = 0;
    private int texture = 0;
    private int currentByteSize = 0;
    private int headerBuffer = 0;
    private int headerTexture = 0;
    private int headerByteSize = 0;
    private int shipDirBuffer = 0;
    private int shipDirTexture = 0;
    private int shipDirByteSize = 0;
    private int gridBuffer = 0;
    private int gridTexture = 0;

    // ==== Precomputed occupancy field ======================================
    // O(cell) = sum over contributing voxels of w * K(cell centre - c), one scalar per cell of each
    // host ship's own lattice. Every tap the fragment's pass 2 evaluates sits at a lattice cell
    // CENTRE, so the whole per-voxel stamp is a function of the cell alone and not of the fragment
    // -- the fragment only picks WHICH cells (b0, cu, cv) and the interpolation weights. That is the
    // identity claude-scratchpad/seam_precompute.py verifies exactly (0.0 max error over 600 random
    // fragments for the tent, which is the shipped kernel).
    //
    // Used by the WORLD shader only. The ship shader also skips its own ship's material
    // (`owner == selfShipIndex`), which is a property of the DRAW and not of the host lattice, so one
    // field per host cannot serve it. The world pass has no self ship, and it is also the larger
    // half: removing world pass 2 saves 11.32 ms of a 26.55 ms frame on 200 ships at 4K against
    // 6.94 ms for the ship pass.
    //
    // The splat runs on the CPU. It is voxels x targets x 8 cells -- at most 1024 x 5 x 8 -- which is
    // small next to buildSeamData's O(ships^2) claim pass, and it keeps this first cut free of
    // compute dispatches, SSBO aliasing and barriers. Moving it to a compute shader is a later
    // optimisation, not a correctness question.
    /** Per-ship dense grid over exactly the cells its contributing voxels can touch; cells outside
     *  read 0, which is what they are, so no padding is needed. */
    private static final int OCC_MAX_CELLS = 4 * 1024 * 1024;   // 16 MB
    /** 2 RGBA32I texels per ship: (base, dimX, dimY, dimZ) and (originX, originY, originZ, valid). */
    private static final int OCC_DESC_BYTES = 32;
    private final long occPtr;
    private final long occDescPtr;
    private int occBuffer = 0;
    private int occTexture = 0;
    private int occDescBuffer = 0;
    private int occDescTexture = 0;
    private int occByteSize = 0;
    private int occDescByteSize = 0;
    private int occCellCount = 0;
    /** Source -> target slots per ship: itself, its MAX_PARTNERS claim targets, the world lattice. */
    private static final int OCC_PAIR_SLOTS = MAX_PARTNERS + 2;
    /** Descriptor texel where the (source, target) pair boxes start; hosts occupy 2 texels each
     *  below it. MUST match VS_SEAM_PAIR_DESC_BASE in the ship shader. */
    private static final int PAIR_DESC_BASE_TEXEL = MAX_SHIPS * 2;
    private static final int SHIP_DESC_TEXELS = PAIR_DESC_BASE_TEXEL + MAX_SHIPS * OCC_PAIR_SLOTS * 2;
    private final long shipOccPtr;
    private final long shipOccDescPtr;
    private int shipOccBuffer = 0;
    private int shipOccTexture = 0;
    private int shipOccDescBuffer = 0;
    private int shipOccDescTexture = 0;
    private int shipOccByteSize = 0;
    private int shipOccDescByteSize = 0;
    private int shipOccCellCount = 0;
    private int gridByteSize = 0;

    // global bounds over all occluder voxels this frame (world space)
    private float boundsCx, boundsCy, boundsCz, boundsRadius;
    // spatial-grid transform: cellIndex = floor((worldPos - gridOrigin) * gridInvCell)
    private float gridOx, gridOy, gridOz, gridInvCx, gridInvCy, gridInvCz;

    private final Vector3d scratch = new Vector3d();
    private final Quaterniond scratchQuat = new Quaterniond();
    private final BlockPos.MutableBlockPos scratchBlockPos = new BlockPos.MutableBlockPos();

    /** Per-frame map from ship.getId() → small dense index used as the
     *  shader's per-voxel ship tag. Reset every frame in beginFrame; index
     *  0 is reserved for "no ship" so the ship FSH can compare against
     *  -1 / unset values without false matches. */
    private final Long2IntMap shipIdToIndex = new Long2IntOpenHashMap();
    private int nextShipIndex = 1;

    public VsShipOccluderList() {
        arenaPtr = MemoryUtil.nmemAlloc((long) MAX_OCCLUDERS * BYTES_PER_OCCLUDER);
        // worst case one header per voxel (pathological ship interleave)
        headerPtr = MemoryUtil.nmemAlloc((long) MAX_OCCLUDERS * BYTES_PER_HEADER);
        shipDirPtr = MemoryUtil.nmemAlloc((long) MAX_SHIPS * BYTES_PER_SHIP);
        gridPtr = MemoryUtil.nmemAlloc((long) GRID_TEXEL_COUNT * 16L);
        occPtr = MemoryUtil.nmemAlloc((long) OCC_MAX_CELLS * 4L);
        occDescPtr = MemoryUtil.nmemAlloc((long) MAX_SHIPS * OCC_DESC_BYTES);
        shipOccPtr = MemoryUtil.nmemAlloc((long) OCC_MAX_CELLS * 4L);
        shipOccDescPtr = MemoryUtil.nmemAlloc((long) SHIP_DESC_TEXELS * 16L);
    }

    public void delete() {
        if (arenaPtr != 0L) MemoryUtil.nmemFree(arenaPtr);
        if (headerPtr != 0L) MemoryUtil.nmemFree(headerPtr);
        if (shipDirPtr != 0L) MemoryUtil.nmemFree(shipDirPtr);
        if (gridPtr != 0L) MemoryUtil.nmemFree(gridPtr);
        if (occPtr != 0L) MemoryUtil.nmemFree(occPtr);
        if (occDescPtr != 0L) MemoryUtil.nmemFree(occDescPtr);
        if (shipOccPtr != 0L) MemoryUtil.nmemFree(shipOccPtr);
        if (shipOccDescPtr != 0L) MemoryUtil.nmemFree(shipOccDescPtr);
        if (buffer != 0) { GL15.glDeleteBuffers(buffer); buffer = 0; }
        if (texture != 0) { GL11.glDeleteTextures(texture); texture = 0; }
        if (headerBuffer != 0) { GL15.glDeleteBuffers(headerBuffer); headerBuffer = 0; }
        if (headerTexture != 0) { GL11.glDeleteTextures(headerTexture); headerTexture = 0; }
        if (shipDirBuffer != 0) { GL15.glDeleteBuffers(shipDirBuffer); shipDirBuffer = 0; }
        if (shipDirTexture != 0) { GL11.glDeleteTextures(shipDirTexture); shipDirTexture = 0; }
        if (gridBuffer != 0) { GL15.glDeleteBuffers(gridBuffer); gridBuffer = 0; }
        if (gridTexture != 0) { GL11.glDeleteTextures(gridTexture); gridTexture = 0; }
        if (occBuffer != 0) { GL15.glDeleteBuffers(occBuffer); occBuffer = 0; }
        if (occTexture != 0) { GL11.glDeleteTextures(occTexture); occTexture = 0; }
        if (occDescBuffer != 0) { GL15.glDeleteBuffers(occDescBuffer); occDescBuffer = 0; }
        if (occDescTexture != 0) { GL11.glDeleteTextures(occDescTexture); occDescTexture = 0; }
        if (shipOccBuffer != 0) { GL15.glDeleteBuffers(shipOccBuffer); shipOccBuffer = 0; }
        if (shipOccTexture != 0) { GL11.glDeleteTextures(shipOccTexture); shipOccTexture = 0; }
        if (shipOccDescBuffer != 0) { GL15.glDeleteBuffers(shipOccDescBuffer); shipOccDescBuffer = 0; }
        if (shipOccDescTexture != 0) {
            GL11.glDeleteTextures(shipOccDescTexture); shipOccDescTexture = 0;
        }
    }

    public void beginFrame() {
        count = 0;
        headerCount = 0;
        shipIdToIndex.clear();
        nextShipIndex = 1;
    }

    public int size() {
        return count;
    }

    public int headerCount() {
        return headerCount;
    }

    public float boundsCenterX() { return boundsCx; }
    public float boundsCenterY() { return boundsCy; }
    public float boundsCenterZ() { return boundsCz; }
    public float boundsRadius() { return boundsRadius; }

    public float gridOriginX() { return gridOx; }
    public float gridOriginY() { return gridOy; }
    public float gridOriginZ() { return gridOz; }
    public float gridInvCellX() { return gridInvCx; }
    public float gridInvCellY() { return gridInvCy; }
    public float gridInvCellZ() { return gridInvCz; }

    /** Returns the per-frame dense index assigned to {@code shipId} during
     *  populateFromShip, or 0 if the ship wasn't populated this frame.
     *  Used by setupShipShaderState to tell the ship FSH which voxels in
     *  u_VsShipOccluders belong to the currently rendering ship so they
     *  can be skipped (their AO is already baked into v_Color.a). */
    public int getShipIndex(long shipId) {
        return shipIdToIndex.getOrDefault(shipId, 0);
    }

    /** Assigns a fresh per-frame dense index to {@code shipId} if it doesn't
     *  already have one, and returns it. Callers populating occluders from
     *  outside {@link #populateFromShip} (e.g. {@link VsWorldFromShipLightStorage})
     *  use this to tag voxels with the ship they belong to. */
    public int assignShipIndex(long shipId) {
        return shipIdToIndex.computeIfAbsent(shipId, id -> nextShipIndex++);
    }


    public void appendOccluder(final double worldX, final double worldY, final double worldZ, final float shipIndex,
        final float qx, final float qy, final float qz, final float qw) {
        if (count >= MAX_OCCLUDERS) return;

        long offset = arenaPtr + (long) count * BYTES_PER_OCCLUDER;
        // Texel 0: position + ship index
        MemoryUtil.memPutFloat(offset,        (float) worldX);
        MemoryUtil.memPutFloat(offset + 4,    (float) worldY);
        MemoryUtil.memPutFloat(offset + 8,    (float) worldZ);
        // Packed as raw bits, NOT a literal float value — see the field
        // comment on BYTES_PER_OCCLUDER.
        int shipIndexInt = Math.round(shipIndex) & SHIP_INDEX_MASK;
        MemoryUtil.memPutFloat(offset + 12,   Float.intBitsToFloat(shipIndexInt));
        // Texel 1: quaternion
        MemoryUtil.memPutFloat(offset + 16,   qx);
        MemoryUtil.memPutFloat(offset + 20,   qy);
        MemoryUtil.memPutFloat(offset + 24,   qz);
        MemoryUtil.memPutFloat(offset + 28,   qw);
        count++;
    }

    /** Build the per-frame seam acceleration data from the voxel arena:
     *  sub-run headers (tight bounding spheres so the shader rejects whole
     *  runs with one texel fetch), the per-ship directory (pose,
     *  responsibility r, top-{@link #MAX_PARTNERS} claim partners for the
     *  cross-ship merge) and the global bounds (one-uniform early-out for
     *  fragments nowhere near a ship). One O(N) scan plus an O(S²) pass
     *  over the few distinct ships — replaces the old O(N²)
     *  computeSeamCandidateFlags scan entirely. Call after every populate /
     *  appendOccluder for the frame and before {@link #upload()}. */
    public void buildSeamData() {
        headerCount = 0;
        occCellCount = 0;
        shipOccCellCount = 0;
        // Everything this builds -- sub-run headers, the ship directory, the pair claims, the
        // spatial grid, the occupancy fields -- is read by the seam AO shaders and by nothing else,
        // so with the AO off it is all thrown away. It was being built anyway: on 240 ships that is
        // 1.8 ms per frame of an 18 ms frame, spent entirely on a feature the user has disabled, and
        // the pair-claim pass inside it is O(ships^2) so it gets worse the more ships there are.
        if (!org.valkyrienskies.mod.common.config.VSGameConfig.CLIENT.getShipAmbientOcclusion()
                && !Boolean.getBoolean("vs.seamgateoff")) {
            return;
        }
        int shipCap = Math.min(nextShipIndex, MAX_SHIPS);

        // per-ship aggregates
        boolean[] seen = new boolean[shipCap];
        float[] aabb = new float[shipCap * 6];
        float[] anchor = new float[shipCap * 3];
        float[] quat = new float[shipCap * 4];

        float bnx = Float.MAX_VALUE, bny = Float.MAX_VALUE, bnz = Float.MAX_VALUE;
        float bxx = -Float.MAX_VALUE, bxy = -Float.MAX_VALUE, bxz = -Float.MAX_VALUE;

        // ---- pass 1: scan voxels; per-ship aggregates + sub-run headers ---
        int runStart = 0, runShip = -1;
        float rnx = 0, rny = 0, rnz = 0, rxx = 0, rxy = 0, rxz = 0;
        for (int i = 0; i < count; i++) {
            long off = arenaPtr + (long) i * BYTES_PER_OCCLUDER;
            float x = MemoryUtil.memGetFloat(off);
            float y = MemoryUtil.memGetFloat(off + 4);
            float z = MemoryUtil.memGetFloat(off + 8);
            int ship = Float.floatToRawIntBits(MemoryUtil.memGetFloat(off + 12)) & SHIP_INDEX_MASK;

            bnx = Math.min(bnx, x); bny = Math.min(bny, y); bnz = Math.min(bnz, z);
            bxx = Math.max(bxx, x); bxy = Math.max(bxy, y); bxz = Math.max(bxz, z);

            if (ship < shipCap) {
                int a6 = ship * 6;
                if (!seen[ship]) {
                    seen[ship] = true;
                    aabb[a6] = aabb[a6 + 1] = aabb[a6 + 2] = Float.MAX_VALUE;
                    aabb[a6 + 3] = aabb[a6 + 4] = aabb[a6 + 5] = -Float.MAX_VALUE;
                    anchor[ship * 3] = x; anchor[ship * 3 + 1] = y; anchor[ship * 3 + 2] = z;
                    quat[ship * 4]     = MemoryUtil.memGetFloat(off + 16);
                    quat[ship * 4 + 1] = MemoryUtil.memGetFloat(off + 20);
                    quat[ship * 4 + 2] = MemoryUtil.memGetFloat(off + 24);
                    quat[ship * 4 + 3] = MemoryUtil.memGetFloat(off + 28);
                }
                aabb[a6] = Math.min(aabb[a6], x);
                aabb[a6 + 1] = Math.min(aabb[a6 + 1], y);
                aabb[a6 + 2] = Math.min(aabb[a6 + 2], z);
                aabb[a6 + 3] = Math.max(aabb[a6 + 3], x);
                aabb[a6 + 4] = Math.max(aabb[a6 + 4], y);
                aabb[a6 + 5] = Math.max(aabb[a6 + 5], z);
            }

            // sub-run bookkeeping: flush on ship change / count / extent
            boolean startNew = i == runStart ? false : ship != runShip;
            if (!startNew && i > runStart) {
                if (i - runStart >= SUBRUN_MAX_VOXELS) {
                    startNew = true;
                } else {
                    float hx = Math.max(rxx, x) - Math.min(rnx, x);
                    float hy = Math.max(rxy, y) - Math.min(rny, y);
                    float hz = Math.max(rxz, z) - Math.min(rnz, z);
                    if (0.25f * (hx * hx + hy * hy + hz * hz) > SUBRUN_MAX_RADIUS * SUBRUN_MAX_RADIUS) {
                        startNew = true;
                    }
                }
            }
            if (startNew) {
                flushHeader(runStart, i, runShip, rnx, rny, rnz, rxx, rxy, rxz);
                runStart = i;
            }
            if (i == runStart) {
                runShip = ship;
                rnx = rxx = x; rny = rxy = y; rnz = rxz = z;
            } else {
                rnx = Math.min(rnx, x); rny = Math.min(rny, y); rnz = Math.min(rnz, z);
                rxx = Math.max(rxx, x); rxy = Math.max(rxy, y); rxz = Math.max(rxz, z);
            }
        }
        if (count > 0) {
            flushHeader(runStart, count, runShip, rnx, rny, rnz, rxx, rxy, rxz);
            boundsCx = 0.5f * (bnx + bxx);
            boundsCy = 0.5f * (bny + bxy);
            boundsCz = 0.5f * (bnz + bxz);
            float dx = bxx - boundsCx, dy = bxy - boundsCy, dz = bxz - boundsCz;
            boundsRadius = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        } else {
            boundsCx = boundsCy = boundsCz = 0;
            boundsRadius = 0;
        }

        // per-ship sub-run range (headers are contiguous per ship in buffer
        // order, so one linear pass suffices)
        int[] shipFirstRun = new int[shipCap];
        int[] shipRunCount = new int[shipCap];
        for (int h = 0; h < headerCount; h++) {
            long hp = headerPtr + (long) h * BYTES_PER_HEADER;
            int ship = Float.floatToRawIntBits(MemoryUtil.memGetFloat(hp + 24)) & SHIP_INDEX_MASK;
            if (ship > 0 && ship < shipCap) {
                if (shipRunCount[ship] == 0) shipFirstRun[ship] = h;
                shipRunCount[ship]++;
            }
        }

        // ---- pass 2: per-ship-pair claims → directory rows ----------------
        MemoryUtil.memSet(shipDirPtr, 0, (long) shipCap * BYTES_PER_SHIP);
        // Row 0 [.x] = ship count (max dense index), for the shader's ship loop.
        MemoryUtil.memPutFloat(shipDirPtr, Float.intBitsToFloat(shipCap - 1));
        float[] bestClaim = new float[MAX_PARTNERS];
        int[] bestShip = new int[MAX_PARTNERS];
        // Carried alongside the claim so the distance LOD can be applied AFTER
        // ranking: the rank decides which partner is cheapest to lose, the
        // budget decides how many survive. Ranking on the faded value instead
        // would be circular, since the fade needs the rank.
        float[] bestBudget = new float[MAX_PARTNERS];
        updateMergeLod();
        // Cross-ship merging, switchable. Leaving every claim at zero is exactly "no merging" and
        // needs no shader change: responsibility r = 1/(1 + sum claims) becomes 1, no partner is added
        // as a host in pass 1 (that is gated on claims > 0), and hostClaim degenerates to the
        // identity, so each ship stamps only into its own lattice and the fields are summed. That is
        // the pre-merge behaviour, and it leaves the merging maths below untouched rather than
        // maintaining a second version of it.
        final boolean merge = org.valkyrienskies.mod.common.config.VSGameConfig.CLIENT
            .getShipAmbientOcclusionMerging();
        for (int s = 1; s < shipCap; s++) {
            if (!seen[s]) continue;
            java.util.Arrays.fill(bestClaim, 0f);
            java.util.Arrays.fill(bestShip, 0);
            java.util.Arrays.fill(bestBudget, 0f);
            for (int t = 1; merge && t < shipCap; t++) {
                if (t == s || !seen[t]) continue;
                float c = pairClaim(s, t, aabb, anchor, quat);
                if (c <= 0f) continue;
                float b = mergeBudget(s, t, aabb);
                // insert into the top-MAX_PARTNERS list
                for (int k = 0; k < MAX_PARTNERS; k++) {
                    if (c > bestClaim[k]) {
                        for (int m = MAX_PARTNERS - 1; m > k; m--) {
                            bestClaim[m] = bestClaim[m - 1];
                            bestShip[m] = bestShip[m - 1];
                            bestBudget[m] = bestBudget[m - 1];
                        }
                        bestClaim[k] = c;
                        bestShip[k] = t;
                        bestBudget[k] = b;
                        break;
                    }
                }
            }
            // ---- distance LOD ---------------------------------------------
            // Rank k survives while the pair's budget is above k, so the WEAKEST
            // claim is dropped first and each one leaves over a linear ramp
            // rather than a step. Zeroing a claim here is not an approximation
            // of switching merging off, it IS switching it off for that pair:
            // r is recomputed from the faded claims just below, and because ship
            // s deposits r into its own lattice and r*claim into each partner's,
            // it still deposits r*(1 + sum claims) = 1 in total for any claim
            // values. Fading moves mass between lattices, it never destroys it,
            // which is why the transition holds together instead of the seam
            // brightening as it crosses. Measured in claude-scratchpad/seam_lod.py:
            // the whole merged->unmerged swing is 0.06-0.07 of AO loss and the
            // fade contributes at most 0.11 AO/second at elytra speed, against
            // the 2.5 AO/second that simply walking over a seam already shows.
            //
            // A slot faded to zero is still occupied, so a ship with more than
            // MAX_PARTNERS partners could hold a dead slot while a live partner
            // goes unrecorded. That needs 5+ ships all in contact with one hull
            // AND spread over the fade band, and costs a little merge quality
            // rather than correctness, so it is left alone.
            for (int k = 0; k < MAX_PARTNERS; k++) {
                bestClaim[k] *= clamp01(bestBudget[k] - k);
                if (bestClaim[k] <= 0f) bestShip[k] = 0;
            }
            float sum = 0f;
            for (int k = 0; k < MAX_PARTNERS; k++) sum += bestClaim[k];
            float r = 1f / (1f + sum);

            long d = shipDirPtr + (long) s * BYTES_PER_SHIP;
            MemoryUtil.memPutFloat(d,      quat[s * 4]);
            MemoryUtil.memPutFloat(d + 4,  quat[s * 4 + 1]);
            MemoryUtil.memPutFloat(d + 8,  quat[s * 4 + 2]);
            MemoryUtil.memPutFloat(d + 12, quat[s * 4 + 3]);
            MemoryUtil.memPutFloat(d + 16, anchor[s * 3]);
            MemoryUtil.memPutFloat(d + 20, anchor[s * 3 + 1]);
            MemoryUtil.memPutFloat(d + 24, anchor[s * 3 + 2]);
            MemoryUtil.memPutFloat(d + 28, r);
            for (int k = 0; k < MAX_PARTNERS; k++) {
                MemoryUtil.memPutFloat(d + 32 + k * 4, bestClaim[k]);
                MemoryUtil.memPutFloat(d + 48 + k * 4, Float.intBitsToFloat(bestShip[k]));
            }
            // bounding sphere (AABB center + half-diagonal) + sub-run range,
            // for the shader's two-level ship-then-run cull.
            int a6 = s * 6;
            float scx = 0.5f * (aabb[a6] + aabb[a6 + 3]);
            float scy = 0.5f * (aabb[a6 + 1] + aabb[a6 + 4]);
            float scz = 0.5f * (aabb[a6 + 2] + aabb[a6 + 5]);
            float sdx = aabb[a6 + 3] - scx, sdy = aabb[a6 + 4] - scy, sdz = aabb[a6 + 5] - scz;
            MemoryUtil.memPutFloat(d + 64, scx);
            MemoryUtil.memPutFloat(d + 68, scy);
            MemoryUtil.memPutFloat(d + 72, scz);
            MemoryUtil.memPutFloat(d + 76, (float) Math.sqrt(sdx * sdx + sdy * sdy + sdz * sdz));
            MemoryUtil.memPutFloat(d + 80, Float.intBitsToFloat(shipFirstRun[s]));
            MemoryUtil.memPutFloat(d + 84, Float.intBitsToFloat(shipRunCount[s]));
        }

        // ---- precomputed occupancy field, from the directory just written ----
        // Only when something will read it. Both field sets were being built unconditionally, so a
        // client with the precompute switched off still paid for them every frame -- and the
        // per-fragment side of every A/B was charged for work only the other side uses.
        if (org.valkyrienskies.mod.common.config.VSGameConfig.CLIENT
                .getShipAmbientOcclusionPrecompute()) {
            buildOccField(shipCap, seen, quat, anchor);
            buildShipOccField(shipCap, seen, quat, anchor);
        } else {
            occCellCount = 0;
            shipOccCellCount = 0;
        }

        // ---- spatial grid: bin each SUB-RUN (support-fattened sphere) into
        // the cells it overlaps, so a fragment reads only nearby sub-runs.
        MemoryUtil.memSet(gridPtr, 0, (long) GRID_TEXEL_COUNT * 16L);
        if (count > 0 && headerCount > 0) {
            gridOx = bnx - SEAM_SUPPORT;
            gridOy = bny - SEAM_SUPPORT;
            gridOz = bnz - SEAM_SUPPORT;
            float sizeX = Math.max((bxx - bnx) + 2f * SEAM_SUPPORT, 1e-3f);
            float sizeY = Math.max((bxy - bny) + 2f * SEAM_SUPPORT, 1e-3f);
            float sizeZ = Math.max((bxz - bnz) + 2f * SEAM_SUPPORT, 1e-3f);
            gridInvCx = GRID_DIM / sizeX;
            gridInvCy = GRID_DIM / sizeY;
            gridInvCz = GRID_DIM / sizeZ;
            int[] cellCount = new int[GRID_CELLS];
            // pass A: count sub-runs per cell
            for (int h = 0; h < headerCount; h++) {
                long hp = headerPtr + (long) h * BYTES_PER_HEADER;
                float cx = MemoryUtil.memGetFloat(hp), cy = MemoryUtil.memGetFloat(hp + 4);
                float cz = MemoryUtil.memGetFloat(hp + 8);
                float er = MemoryUtil.memGetFloat(hp + 12) + SEAM_SUPPORT;
                int x0 = clampCell((cx - er - gridOx) * gridInvCx), x1 = clampCell((cx + er - gridOx) * gridInvCx);
                int y0 = clampCell((cy - er - gridOy) * gridInvCy), y1 = clampCell((cy + er - gridOy) * gridInvCy);
                int z0 = clampCell((cz - er - gridOz) * gridInvCz), z1 = clampCell((cz + er - gridOz) * gridInvCz);
                for (int gz = z0; gz <= z1; gz++)
                    for (int gy = y0; gy <= y1; gy++)
                        for (int gx = x0; gx <= x1; gx++)
                            cellCount[(gz * GRID_DIM + gy) * GRID_DIM + gx]++;
            }
            // pass B: prefix-sum into per-cell (offset,count) headers
            int total = 0;
            for (int c = 0; c < GRID_CELLS; c++) {
                long cp = gridPtr + (long) c * 16L;
                MemoryUtil.memPutFloat(cp, Float.intBitsToFloat(total));
                MemoryUtil.memPutFloat(cp + 4, Float.intBitsToFloat(cellCount[c]));
                int start = total;
                total += cellCount[c];
                cellCount[c] = start;   // reuse as write cursor
            }
            // pass C: scatter sub-run indices into the flat list
            for (int h = 0; h < headerCount; h++) {
                long hp = headerPtr + (long) h * BYTES_PER_HEADER;
                float cx = MemoryUtil.memGetFloat(hp), cy = MemoryUtil.memGetFloat(hp + 4);
                float cz = MemoryUtil.memGetFloat(hp + 8);
                float er = MemoryUtil.memGetFloat(hp + 12) + SEAM_SUPPORT;
                int x0 = clampCell((cx - er - gridOx) * gridInvCx), x1 = clampCell((cx + er - gridOx) * gridInvCx);
                int y0 = clampCell((cy - er - gridOy) * gridInvCy), y1 = clampCell((cy + er - gridOy) * gridInvCy);
                int z0 = clampCell((cz - er - gridOz) * gridInvCz), z1 = clampCell((cz + er - gridOz) * gridInvCz);
                for (int gz = z0; gz <= z1; gz++)
                    for (int gy = y0; gy <= y1; gy++)
                        for (int gx = x0; gx <= x1; gx++) {
                            int cell = (gz * GRID_DIM + gy) * GRID_DIM + gx;
                            int w = cellCount[cell]++;
                            if (w >= GRID_LIST_CAP) continue;
                            long lp = gridPtr + (long) (GRID_CELLS + (w >> 2)) * 16L + (long) (w & 3) * 4L;
                            MemoryUtil.memPutFloat(lp, Float.intBitsToFloat(h));
                        }
            }
        } else {
            gridOx = gridOy = gridOz = 0f;
            gridInvCx = gridInvCy = gridInvCz = 0f;
        }
    }

    private static int clampCell(float f) {
        int i = (int) Math.floor(f);
        return i < 0 ? 0 : (i >= GRID_DIM ? GRID_DIM - 1 : i);
    }

    private void flushHeader(int start, int end, int ship,
            float nx, float ny, float nz, float xx, float xy, float xz) {
        if (end <= start || headerCount >= MAX_OCCLUDERS) return;
        float cx = 0.5f * (nx + xx), cy = 0.5f * (ny + xy), cz = 0.5f * (nz + xz);
        float dx = xx - cx, dy = xy - cy, dz = xz - cz;
        float radius = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        long h = headerPtr + (long) headerCount * BYTES_PER_HEADER;
        MemoryUtil.memPutFloat(h,      cx);
        MemoryUtil.memPutFloat(h + 4,  cy);
        MemoryUtil.memPutFloat(h + 8,  cz);
        MemoryUtil.memPutFloat(h + 12, radius);
        MemoryUtil.memPutFloat(h + 16, Float.intBitsToFloat(start));
        MemoryUtil.memPutFloat(h + 20, Float.intBitsToFloat(end - start));
        MemoryUtil.memPutFloat(h + 24, Float.intBitsToFloat(ship));
        MemoryUtil.memPutFloat(h + 28, 0f);
        headerCount++;
    }

    /**
     * Build the per-ship occupancy field the world shader reads instead of walking voxels.
     *
     * <p>Two passes over (voxel x its <= MAX_PARTNERS+1 target hosts): the first sizes each host's
     * grid from the cells its contributing voxels actually touch, the second splats. Iterating
     * SOURCES and pushing to their targets keeps this O(voxels x 5); iterating hosts and pulling
     * would be O(hosts x voxels), which is a million operations on a full occluder list.
     *
     * <p>The tent has support 1, so a voxel at lattice coordinate c touches cells
     * {floor(c - 0.5), floor(c - 0.5) + 1} on each axis -- eight cells, not the 27 a conservative
     * 3x3x3 window would visit.
     *
     * <p>Must run after the ship directory is written: the weights come from it, so that the field
     * and anything still reading the directory cannot disagree.
     */
    private void buildOccField(final int shipCap, final boolean[] seen, final float[] quat,
        final float[] anchor) {
        occCellCount = 0;
        MemoryUtil.memSet(occDescPtr, 0, (long) shipCap * OCC_DESC_BYTES);
        if (count <= 0) {
            return;
        }

        // Per-host cell bounds, in that host's own lattice. lo > hi marks "nothing landed here".
        final int[] lo = new int[shipCap * 3];
        final int[] hi = new int[shipCap * 3];
        java.util.Arrays.fill(lo, Integer.MAX_VALUE);
        java.util.Arrays.fill(hi, Integer.MIN_VALUE);

        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                // Sizes are known: lay the grids out back to back and clear them.
                int base = 0;
                for (int t = 1; t < shipCap; t++) {
                    if (!seen[t] || lo[t * 3] > hi[t * 3]) continue;
                    final int dx = hi[t * 3] - lo[t * 3] + 1;
                    final int dy = hi[t * 3 + 1] - lo[t * 3 + 1] + 1;
                    final int dz = hi[t * 3 + 2] - lo[t * 3 + 2] + 1;
                    final long cells = (long) dx * dy * dz;
                    if (base + cells > OCC_MAX_CELLS) {
                        // Loud, not silent: the world shader has no fallback path compiled in when
                        // the precomputed field is enabled, so a ship dropped here would simply stop
                        // casting AO with nothing to say why.
                        org.slf4j.LoggerFactory.getLogger("VS2-seamfield").error(
                            "seam occupancy field out of space at ship {} ({} cells needed, {} cap)"
                                + " -- its AO will be missing; raise OCC_MAX_CELLS", t, cells,
                            OCC_MAX_CELLS);
                        break;
                    }
                    final long d = occDescPtr + (long) t * OCC_DESC_BYTES;
                    MemoryUtil.memPutInt(d, base);
                    MemoryUtil.memPutInt(d + 4, dx);
                    MemoryUtil.memPutInt(d + 8, dy);
                    MemoryUtil.memPutInt(d + 12, dz);
                    MemoryUtil.memPutInt(d + 16, lo[t * 3]);
                    MemoryUtil.memPutInt(d + 20, lo[t * 3 + 1]);
                    MemoryUtil.memPutInt(d + 24, lo[t * 3 + 2]);
                    MemoryUtil.memPutInt(d + 28, 1);
                    base += (int) cells;
                }
                occCellCount = base;
                MemoryUtil.memSet(occPtr, 0, (long) occCellCount * 4L);
            }

            for (int i = 0; i < count; i++) {
                final long off = arenaPtr + (long) i * BYTES_PER_OCCLUDER;
                final float vx = MemoryUtil.memGetFloat(off);
                final float vy = MemoryUtil.memGetFloat(off + 4);
                final float vz = MemoryUtil.memGetFloat(off + 8);
                final int s = Float.floatToRawIntBits(MemoryUtil.memGetFloat(off + 12)) & SHIP_INDEX_MASK;
                if (s <= 0 || s >= shipCap || !seen[s]) continue;

                final long sd = shipDirPtr + (long) s * BYTES_PER_SHIP;
                final float rs = MemoryUtil.memGetFloat(sd + 28);
                for (int k = -1; k < MAX_PARTNERS; k++) {
                    final int t;
                    final float w;
                    if (k < 0) {
                        t = s;                       // a ship always hosts its own material at r
                        w = rs;
                    } else {
                        t = Float.floatToRawIntBits(MemoryUtil.memGetFloat(sd + 48 + k * 4));
                        w = rs * MemoryUtil.memGetFloat(sd + 32 + k * 4);
                    }
                    if (t <= 0 || t >= shipCap || !seen[t] || w <= 0f) continue;

                    // Voxel centre in host t's lattice, shifted so cell m spans [m, m+1).
                    final float cx = latX(t, vx, vy, vz, quat, anchor);
                    final float cy = latY(t, vx, vy, vz, quat, anchor);
                    final float cz = latZ(t, vx, vy, vz, quat, anchor);
                    final int mx = (int) Math.floor(cx - 0.5f);
                    final int my = (int) Math.floor(cy - 0.5f);
                    final int mz = (int) Math.floor(cz - 0.5f);

                    if (pass == 0) {
                        final int b = t * 3;
                        lo[b] = Math.min(lo[b], mx);         hi[b] = Math.max(hi[b], mx + 1);
                        lo[b + 1] = Math.min(lo[b + 1], my); hi[b + 1] = Math.max(hi[b + 1], my + 1);
                        lo[b + 2] = Math.min(lo[b + 2], mz); hi[b + 2] = Math.max(hi[b + 2], mz + 1);
                        continue;
                    }

                    final long d = occDescPtr + (long) t * OCC_DESC_BYTES;
                    if (MemoryUtil.memGetInt(d + 28) == 0) continue;   // ship dropped for space
                    final int tbase = MemoryUtil.memGetInt(d);
                    final int dx = MemoryUtil.memGetInt(d + 4);
                    final int dy = MemoryUtil.memGetInt(d + 8);
                    final int ox = MemoryUtil.memGetInt(d + 16);
                    final int oy = MemoryUtil.memGetInt(d + 20);
                    final int oz = MemoryUtil.memGetInt(d + 24);
                    for (int az = 0; az < 2; az++) {
                        final float tz = tent(cz - (mz + az + 0.5f));
                        if (tz == 0f) continue;
                        for (int ay = 0; ay < 2; ay++) {
                            final float ty = tent(cy - (my + ay + 0.5f));
                            if (ty == 0f) continue;
                            final float tyz = w * ty * tz;
                            for (int ax = 0; ax < 2; ax++) {
                                final float tx = tent(cx - (mx + ax + 0.5f));
                                if (tx == 0f) continue;
                                final int idx = tbase
                                    + ((mz + az - oz) * dy + (my + ay - oy)) * dx + (mx + ax - ox);
                                final long p = occPtr + (long) idx * 4L;
                                MemoryUtil.memPutFloat(p, MemoryUtil.memGetFloat(p) + tyz * tx);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The ship path's field set: one aggregate per host, plus one per (source, target) pair.
     *
     * <p>The ship shader cannot read the world path's field, for two independent reasons.
     *
     * <p>First, its WEIGHTS differ. A ship's material is shared with the world lattice as well as
     * with its ship partners, so the shader renormalises r to
     * {@code r_block = r / (1 + r * claimWorld)} and treats the world lattice as a host in its own
     * right. Same voxels, different numbers.
     *
     * <p>Second, and the reason for the pair fields: a ship skips its OWN material
     * ({@code owner == selfShipIndex}), because the mesher already baked that occlusion into the
     * vertex AO. That is a property of the draw, not of the cell, so no single field per host can
     * serve every draw. Occupancy is linear in its sources though -- the clamp only happens later,
     * in the fragment, after this -- so the fragment can read the aggregate and subtract the one
     * source it must not see. Hence G(host) and F(source -> host), and
     * {@code occ = G(t) - F(self -> t)}.
     *
     * <p>Only pairs that can be non-zero are stored: a source reaches its own lattice, its
     * MAX_PARTNERS claim targets and the world lattice, so six slots per ship.
     */
    private void buildShipOccField(final int shipCap, final boolean[] seen, final float[] quat,
        final float[] anchor) {
        shipOccCellCount = 0;
        MemoryUtil.memSet(shipOccDescPtr, 0, (long) SHIP_DESC_TEXELS * 16L);
        if (count <= 0) {
            return;
        }

        // Per-ship world alignment and the block normaliser, mirroring vs_seamWorldAlign and the
        // r_block line in the ship shader. If these drift apart the AO is subtly wrong everywhere
        // rather than obviously wrong somewhere, so they are computed once here and nowhere else.
        final float[] cw = new float[shipCap];
        final float[] rBlock = new float[shipCap];
        for (int s = 1; s < shipCap; s++) {
            if (!seen[s]) continue;
            final float r = MemoryUtil.memGetFloat(shipDirPtr + (long) s * BYTES_PER_SHIP + 28);
            cw[s] = worldAlign(quat, anchor, s);
            rBlock[s] = r / (1f + r * cw[s]);
        }

        final int hosts = shipCap;                 // host 0 is the WORLD lattice
        final int[] hlo = new int[hosts * 3];
        final int[] hhi = new int[hosts * 3];
        final int[] plo = new int[shipCap * OCC_PAIR_SLOTS * 3];
        final int[] phi = new int[shipCap * OCC_PAIR_SLOTS * 3];
        java.util.Arrays.fill(hlo, Integer.MAX_VALUE);
        java.util.Arrays.fill(hhi, Integer.MIN_VALUE);
        java.util.Arrays.fill(plo, Integer.MAX_VALUE);
        java.util.Arrays.fill(phi, Integer.MIN_VALUE);

        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                int base = 0;
                for (int h = 0; h < hosts; h++) {
                    base = layoutBox(shipOccDescPtr, h, hlo, hhi, h * 3, base);
                }
                for (int s = 1; s < shipCap; s++) {
                    for (int k = 0; k < OCC_PAIR_SLOTS; k++) {
                        final int pi = s * OCC_PAIR_SLOTS + k;
                        base = layoutBox(shipOccDescPtr, PAIR_DESC_BASE_TEXEL / 2 + pi, plo, phi,
                            pi * 3, base);
                    }
                }
                shipOccCellCount = base;
                MemoryUtil.memSet(shipOccPtr, 0, (long) shipOccCellCount * 4L);
                if (Boolean.getBoolean("vs.seamfieldtrace")) {
                    org.slf4j.LoggerFactory.getLogger("VS2-seamfield").info(
                        "ship field: shipCap={} voxels={} cells={} host0valid={} host1valid={}",
                        shipCap, count, shipOccCellCount,
                        MemoryUtil.memGetInt(shipOccDescPtr + 28),
                        shipCap > 1 ? MemoryUtil.memGetInt(shipOccDescPtr + OCC_DESC_BYTES + 28) : -1);
                }
            }

            for (int i = 0; i < count; i++) {
                final long off = arenaPtr + (long) i * BYTES_PER_OCCLUDER;
                final float vx = MemoryUtil.memGetFloat(off);
                final float vy = MemoryUtil.memGetFloat(off + 4);
                final float vz = MemoryUtil.memGetFloat(off + 8);
                final int s = Float.floatToRawIntBits(MemoryUtil.memGetFloat(off + 12)) & SHIP_INDEX_MASK;
                if (s <= 0 || s >= shipCap || !seen[s]) continue;
                final long sd = shipDirPtr + (long) s * BYTES_PER_SHIP;

                for (int k = 0; k < OCC_PAIR_SLOTS; k++) {
                    final int t;
                    final float claim;
                    if (k == 0) {
                        t = s;
                        claim = 1f;
                    } else if (k <= MAX_PARTNERS) {
                        t = Float.floatToRawIntBits(MemoryUtil.memGetFloat(sd + 48 + (k - 1) * 4));
                        claim = MemoryUtil.memGetFloat(sd + 32 + (k - 1) * 4);
                    } else {
                        t = 0;                    // the world lattice
                        claim = cw[s];
                    }
                    final float w = rBlock[s] * claim;
                    if (t < 0 || t >= hosts || w <= 0f) continue;
                    if (t > 0 && !seen[t]) continue;

                    final float cx = latH(t, vx, vy, vz, quat, anchor, 0);
                    final float cy = latH(t, vx, vy, vz, quat, anchor, 1);
                    final float cz = latH(t, vx, vy, vz, quat, anchor, 2);
                    final int mx = (int) Math.floor(cx - 0.5f);
                    final int my = (int) Math.floor(cy - 0.5f);
                    final int mz = (int) Math.floor(cz - 0.5f);
                    final int pi = s * OCC_PAIR_SLOTS + k;

                    if (pass == 0) {
                        growBox(hlo, hhi, t * 3, mx, my, mz);
                        growBox(plo, phi, pi * 3, mx, my, mz);
                        continue;
                    }
                    // The same eight taps land in the host's aggregate and in this pair's own box;
                    // the fragment subtracts the second from the first.
                    splatBox(shipOccDescPtr, t, cx, cy, cz, mx, my, mz, w);
                    splatBox(shipOccDescPtr, PAIR_DESC_BASE_TEXEL / 2 + pi, cx, cy, cz, mx, my, mz, w);
                }
            }
        }
    }

    private static void growBox(final int[] lo, final int[] hi, final int b,
        final int mx, final int my, final int mz) {
        lo[b] = Math.min(lo[b], mx);         hi[b] = Math.max(hi[b], mx + 1);
        lo[b + 1] = Math.min(lo[b + 1], my); hi[b + 1] = Math.max(hi[b + 1], my + 1);
        lo[b + 2] = Math.min(lo[b + 2], mz); hi[b + 2] = Math.max(hi[b + 2], mz + 1);
    }

    /** Write one box's descriptor at {@code descIndex} and return the next free cell. */
    private int layoutBox(final long descPtr, final int descIndex, final int[] lo, final int[] hi,
        final int b, final int base) {
        if (lo[b] > hi[b]) return base;
        final int dx = hi[b] - lo[b] + 1;
        final int dy = hi[b + 1] - lo[b + 1] + 1;
        final int dz = hi[b + 2] - lo[b + 2] + 1;
        final long cells = (long) dx * dy * dz;
        if (base + cells > OCC_MAX_CELLS) {
            org.slf4j.LoggerFactory.getLogger("VS2-seamfield").error(
                "ship seam occupancy field out of space ({} cells needed, {} cap) -- AO will be"
                    + " missing; raise OCC_MAX_CELLS", cells, OCC_MAX_CELLS);
            return base;
        }
        final long d = descPtr + (long) descIndex * OCC_DESC_BYTES;
        MemoryUtil.memPutInt(d, base);
        MemoryUtil.memPutInt(d + 4, dx);
        MemoryUtil.memPutInt(d + 8, dy);
        MemoryUtil.memPutInt(d + 12, dz);
        MemoryUtil.memPutInt(d + 16, lo[b]);
        MemoryUtil.memPutInt(d + 20, lo[b + 1]);
        MemoryUtil.memPutInt(d + 24, lo[b + 2]);
        MemoryUtil.memPutInt(d + 28, 1);
        return base + (int) cells;
    }

    private void splatBox(final long descPtr, final int descIndex, final float cx, final float cy,
        final float cz, final int mx, final int my, final int mz, final float w) {
        final long d = descPtr + (long) descIndex * OCC_DESC_BYTES;
        if (MemoryUtil.memGetInt(d + 28) == 0) return;
        final int tbase = MemoryUtil.memGetInt(d);
        final int dx = MemoryUtil.memGetInt(d + 4);
        final int dy = MemoryUtil.memGetInt(d + 8);
        final int ox = MemoryUtil.memGetInt(d + 16);
        final int oy = MemoryUtil.memGetInt(d + 20);
        final int oz = MemoryUtil.memGetInt(d + 24);
        for (int az = 0; az < 2; az++) {
            final float tz = tent(cz - (mz + az + 0.5f));
            if (tz == 0f) continue;
            for (int ay = 0; ay < 2; ay++) {
                final float ty = tent(cy - (my + ay + 0.5f));
                if (ty == 0f) continue;
                final float tyz = w * ty * tz;
                for (int ax = 0; ax < 2; ax++) {
                    final float tx = tent(cx - (mx + ax + 0.5f));
                    if (tx == 0f) continue;
                    final int idx = tbase
                        + ((mz + az - oz) * dy + (my + ay - oy)) * dx + (mx + ax - ox);
                    final long p = shipOccPtr + (long) idx * 4L;
                    MemoryUtil.memPutFloat(p, MemoryUtil.memGetFloat(p) + tyz * tx);
                }
            }
        }
    }

    /** Lattice coordinate for a ship host, or for host 0 -- the WORLD lattice, whose identity
     *  rotation and (0.5, 0.5, 0.5) anchor make c the world coordinate itself, so world block
     *  centres land exactly on cell centres. */
    private static float latH(final int t, final float vx, final float vy, final float vz,
        final float[] quat, final float[] anchor, final int comp) {
        if (t == 0) {
            return comp == 0 ? vx : (comp == 1 ? vy : vz);
        }
        return latComp(t, vx, vy, vz, quat, anchor, comp);
    }

    /** Mirrors vs_seamWorldAlign in the ship shader: rotation alignment times lattice alignment. */
    private static float worldAlign(final float[] quat, final float[] anchor, final int s) {
        final float x = quat[s * 4], y = quat[s * 4 + 1], z = quat[s * 4 + 2], w = quat[s * 4 + 3];
        final float m = (max3(1f - 2f * (y * y + z * z), 2f * (x * y + z * w), 2f * (x * z - y * w))
            + max3(2f * (x * y - z * w), 1f - 2f * (x * x + z * z), 2f * (y * z + x * w))
            + max3(2f * (x * z + y * w), 2f * (y * z - x * w), 1f - 2f * (x * x + y * y))) / 3f;
        final float oRot = clamp01((m - OROT_LO) / (1f - OROT_LO));
        return oRot * (1f - fractDist(anchor[s * 3])) * (1f - fractDist(anchor[s * 3 + 1]))
            * (1f - fractDist(anchor[s * 3 + 2]));
    }

    private static float max3(final float a, final float b, final float c) {
        return Math.max(Math.max(Math.abs(a), Math.abs(b)), Math.abs(c));
    }

    private static float tent(final float d) {
        final float a = Math.abs(d);
        return a >= 1f ? 0f : 1f - a;
    }

    /** Component of {@code Rinv_t * (v - anchor_t) + 0.5}; mirrors the shader's fragL/c exactly. */
    private static float latComp(final int t, final float vx, final float vy, final float vz,
        final float[] quat, final float[] anchor, final int comp) {
        final float dx = vx - anchor[t * 3];
        final float dy = vy - anchor[t * 3 + 1];
        final float dz = vz - anchor[t * 3 + 2];
        final float qx = -quat[t * 4], qy = -quat[t * 4 + 1], qz = -quat[t * 4 + 2];
        final float qw = quat[t * 4 + 3];
        final float c1x = qy * dz - qz * dy + qw * dx;
        final float c1y = qz * dx - qx * dz + qw * dy;
        final float c1z = qx * dy - qy * dx + qw * dz;
        switch (comp) {
            case 0: return dx + 2f * (qy * c1z - qz * c1y) + 0.5f;
            case 1: return dy + 2f * (qz * c1x - qx * c1z) + 0.5f;
            default: return dz + 2f * (qx * c1y - qy * c1x) + 0.5f;
        }
    }

    private static float latX(final int t, final float vx, final float vy, final float vz,
        final float[] q, final float[] a) {
        return latComp(t, vx, vy, vz, q, a, 0);
    }

    private static float latY(final int t, final float vx, final float vy, final float vz,
        final float[] q, final float[] a) {
        return latComp(t, vx, vy, vz, q, a, 1);
    }

    private static float latZ(final int t, final float vx, final float vy, final float vz,
        final float[] q, final float[] a) {
        return latComp(t, vx, vy, vz, q, a, 2);
    }

    /** Refresh the camera position and the fade band once per frame. A far edge
     *  of 0 (or no camera yet) disables the LOD by parking the band beyond any
     *  reachable distance, so {@link #mergeBudget} returns a full budget and the
     *  merge behaves exactly as it did before this existed. */
    private void updateMergeLod() {
        mergeLodFar = (float) org.valkyrienskies.mod.common.config.VSGameConfig.CLIENT
            .getShipAmbientOcclusionMergeDistance();
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.client.Camera cam = mc == null ? null : mc.gameRenderer.getMainCamera();
        if (cam == null || mergeLodFar <= 0f) {
            mergeLodFar = 0f;
            return;
        }
        final net.minecraft.world.phys.Vec3 p = cam.getPosition();
        camX = (float) p.x;
        camY = (float) p.y;
        camZ = (float) p.z;
        mergeLodNear = mergeLodFar * MERGE_LOD_NEAR_FRAC;
    }

    /** How many of ship s's partners are still worth merging, as a CONTINUOUS
     *  count, from how far the camera is from THIS pair's seam.
     *
     *  <p>The seam is not either ship's centre — for a long hull that can be
     *  hundreds of blocks from where the two actually touch — so the anchor is
     *  the overlap of the two AABBs fattened by the merge gate, which is exactly
     *  the region where one ship's material can land in the other's lattice, and
     *  the distance is to that BOX rather than to a point in it. A long seam
     *  therefore stays at full fidelity as long as any part of it is close. */
    private float mergeBudget(int s, int t, float[] aabb) {
        if (mergeLodFar <= 0f) return MAX_PARTNERS;
        float d2 = 0f;
        for (int i = 0; i < 3; i++) {
            float lo = Math.max(aabb[s * 6 + i], aabb[t * 6 + i]) - SEAM_G_ZERO;
            float hi = Math.min(aabb[s * 6 + 3 + i], aabb[t * 6 + 3 + i]) + SEAM_G_ZERO;
            if (hi < lo) {           // no overlap on this axis even fattened
                float mid = 0.5f * (lo + hi);
                lo = hi = mid;
            }
            float c = i == 0 ? camX : (i == 1 ? camY : camZ);
            float e = Math.max(Math.max(lo - c, c - hi), 0f);
            d2 += e * e;
        }
        float dist = (float) Math.sqrt(d2);
        float span = Math.max(mergeLodFar - mergeLodNear, 1e-3f);
        return MAX_PARTNERS * (1f - clamp01((dist - mergeLodNear) / span));
    }

    /** claim(s → t): gate(AABB gap) × o_rot(relative rotation) ×
     *  o_trans(s's anchor alignment in t's lattice). Mirrors
     *  seam5.pair_claim in the Python reference. */
    private float pairClaim(int s, int t, float[] aabb, float[] anchor, float[] quat) {
        // gate: gap between voxel-center AABBs
        float gx = Math.max(0f, Math.max(aabb[t * 6] - aabb[s * 6 + 3], aabb[s * 6] - aabb[t * 6 + 3]));
        float gy = Math.max(0f, Math.max(aabb[t * 6 + 1] - aabb[s * 6 + 4], aabb[s * 6 + 1] - aabb[t * 6 + 4]));
        float gz = Math.max(0f, Math.max(aabb[t * 6 + 2] - aabb[s * 6 + 5], aabb[s * 6 + 2] - aabb[t * 6 + 5]));
        float gap = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
        float g = clamp01((SEAM_G_ZERO - gap) / (SEAM_G_ZERO - SEAM_G_FULL));
        if (g <= 0f) return 0f;

        // o_rot: mean per-column max |component| of R_t^T · R_s, remapped
        // from [1/sqrt2, 1] to [0, 1]
        float[] rs = quatToMat(quat, s);
        float[] rt = quatToMat(quat, t);
        float m = 0f;
        for (int col = 0; col < 3; col++) {
            float cmax = 0f;
            for (int row = 0; row < 3; row++) {
                // (R_t^T R_s)[row][col] = dot(R_t column row?, ...) —
                // R_t^T[row][k] = R_t[k][row]
                float v = rt[col(0, row)] * rs[col(0, col)]
                        + rt[col(1, row)] * rs[col(1, col)]
                        + rt[col(2, row)] * rs[col(2, col)];
                cmax = Math.max(cmax, Math.abs(v));
            }
            m += cmax;
        }
        m /= 3f;
        float oRot = clamp01((m - OROT_LO) / (1f - OROT_LO));
        if (oRot <= 0f) return 0f;

        // o_trans: fract alignment of s's anchor in t's lattice
        float dx = anchor[s * 3] - anchor[t * 3];
        float dy = anchor[s * 3 + 1] - anchor[t * 3 + 1];
        float dz = anchor[s * 3 + 2] - anchor[t * 3 + 2];
        float qx = -quat[t * 4], qy = -quat[t * 4 + 1], qz = -quat[t * 4 + 2], qw = quat[t * 4 + 3];
        // v + 2 * cross(q.xyz, cross(q.xyz, v) + q.w * v), q.xyz negated
        float c1x = qy * dz - qz * dy + qw * dx;
        float c1y = qz * dx - qx * dz + qw * dy;
        float c1z = qx * dy - qy * dx + qw * dz;
        float lx = dx + 2f * (qy * c1z - qz * c1y);
        float ly = dy + 2f * (qz * c1x - qx * c1z);
        float lz = dz + 2f * (qx * c1y - qy * c1x);
        float oTrans = (1f - fractDist(lx + 0.5f)) * (1f - fractDist(ly + 0.5f))
                * (1f - fractDist(lz + 0.5f));
        return g * oRot * oTrans;
    }

    private static int col(int row, int c) {
        return c * 3 + row;
    }

    private static float fractDist(float v) {
        float f = v - (float) Math.floor(v);
        return Math.abs(f - 0.5f);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** column-major 3x3 rotation matrix of quat[s*4..] (m[col*3+row]). */
    private static float[] quatToMat(float[] quat, int s) {
        float x = quat[s * 4], y = quat[s * 4 + 1], z = quat[s * 4 + 2], w = quat[s * 4 + 3];
        return new float[] {
            1 - 2 * (y * y + z * z), 2 * (x * y + z * w),     2 * (x * z - y * w),
            2 * (x * y - z * w),     1 - 2 * (x * x + z * z), 2 * (y * z + x * w),
            2 * (x * z + y * w),     2 * (y * z - x * w),     1 - 2 * (x * x + y * y),
        };
    }

    public void upload() {
        ensureGlObjects();
        currentByteSize = uploadOne(buffer, texture, arenaPtr,
                Math.max(BYTES_PER_OCCLUDER, count * BYTES_PER_OCCLUDER),
                currentByteSize, count > 0);
        headerByteSize = uploadOne(headerBuffer, headerTexture, headerPtr,
                Math.max(BYTES_PER_HEADER, headerCount * BYTES_PER_HEADER),
                headerByteSize, headerCount > 0);
        int ships = Math.min(nextShipIndex, MAX_SHIPS);
        shipDirByteSize = uploadOne(shipDirBuffer, shipDirTexture, shipDirPtr,
                Math.max(BYTES_PER_SHIP, ships * BYTES_PER_SHIP),
                shipDirByteSize, ships > 1);
        gridByteSize = uploadOne(gridBuffer, gridTexture, gridPtr,
                GRID_TEXEL_COUNT * 16, gridByteSize, true);
        // R32F, one float per cell -- not RGBA like the others, so it gets its own format.
        occByteSize = uploadTyped(occBuffer, occTexture, occPtr,
                Math.max(4, occCellCount * 4), occByteSize, occCellCount > 0, GL30.GL_R32F);
        occDescByteSize = uploadTyped(occDescBuffer, occDescTexture, occDescPtr,
                Math.max(OCC_DESC_BYTES, ships * OCC_DESC_BYTES), occDescByteSize, ships > 1,
                GL30.GL_RGBA32I);
        shipOccByteSize = uploadTyped(shipOccBuffer, shipOccTexture, shipOccPtr,
                Math.max(4, shipOccCellCount * 4), shipOccByteSize, shipOccCellCount > 0,
                GL30.GL_R32F);
        // Fixed size, not sized to the live ship count: the shader indexes pair descriptors at
        // PAIR_DESC_BASE_TEXEL + ..., which is a constant, so the buffer has to reach that far.
        shipOccDescByteSize = uploadTyped(shipOccDescBuffer, shipOccDescTexture, shipOccDescPtr,
                SHIP_DESC_TEXELS * 16, shipOccDescByteSize, true, GL30.GL_RGBA32I);
    }

    /** As {@link #uploadOne} but for a buffer texture whose internal format is not RGBA32F. */
    private static int uploadTyped(int buf, int tex, long ptr, int needed,
            int currentSize, boolean hasData, int internalFormat) {
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buf);
        boolean orphaned = currentSize != needed;
        if (orphaned || hasData) {
            GL15.nglBufferData(GL31.GL_TEXTURE_BUFFER, needed, ptr, GL15.GL_DYNAMIC_DRAW);
        }
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        if (orphaned) {
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tex);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, internalFormat, buf);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        }
        return needed;
    }

    private static int uploadOne(int buf, int tex, long ptr, int needed,
            int currentSize, boolean hasData) {
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buf);
        boolean orphaned = currentSize != needed;
        if (orphaned || hasData) {
            GL15.nglBufferData(GL31.GL_TEXTURE_BUFFER, needed, ptr, GL15.GL_DYNAMIC_DRAW);
        }
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        if (orphaned) {
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tex);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buf);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        }
        return needed;
    }

    public void bind(int textureUnit) {
        ensureGlObjects();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    /** Bind the sub-run header buffer (u_VsSeamRuns). */
    public void bindHeaders(int textureUnit) {
        ensureGlObjects();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, headerTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    /** Bind the per-ship directory buffer (u_VsSeamShipDir). */
    public void bindShipDir(int textureUnit) {
        ensureGlObjects();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, shipDirTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    /** Bind the spatial-grid cell buffer (u_VsSeamGrid). */
    public void bindGrid(int textureUnit) {
        ensureGlObjects();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, gridTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    private void ensureGlObjects() {
        if (buffer == 0) buffer = newBackedBuffer();
        if (texture == 0) texture = makeBufferTexture(buffer);
        if (headerBuffer == 0) headerBuffer = newBackedBuffer();
        if (headerTexture == 0) headerTexture = makeBufferTexture(headerBuffer);
        if (shipDirBuffer == 0) shipDirBuffer = newBackedBuffer();
        if (shipDirTexture == 0) shipDirTexture = makeBufferTexture(shipDirBuffer);
        if (gridBuffer == 0) gridBuffer = newBackedBuffer();
        if (gridTexture == 0) gridTexture = makeBufferTexture(gridBuffer);
        if (occBuffer == 0) occBuffer = newBackedBuffer();
        if (occTexture == 0) occTexture = makeTypedBufferTexture(occBuffer, GL30.GL_R32F);
        if (occDescBuffer == 0) occDescBuffer = newBackedBuffer();
        if (occDescTexture == 0) occDescTexture = makeTypedBufferTexture(occDescBuffer, GL30.GL_RGBA32I);
        if (shipOccBuffer == 0) shipOccBuffer = newBackedBuffer();
        if (shipOccTexture == 0) shipOccTexture = makeTypedBufferTexture(shipOccBuffer, GL30.GL_R32F);
        if (shipOccDescBuffer == 0) shipOccDescBuffer = newBackedBuffer();
        if (shipOccDescTexture == 0) {
            shipOccDescTexture = makeTypedBufferTexture(shipOccDescBuffer, GL30.GL_RGBA32I);
        }
    }

    private static int makeTypedBufferTexture(int buf, int internalFormat) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tex);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, internalFormat, buf);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        return tex;
    }

    /** Bind the precomputed occupancy field (u_VsSeamOcc) and its per-ship descriptors
     *  (u_VsSeamOccDesc). World shader only -- see the note on buildOccField. */
    public void bindOccField(int fieldUnit, int descUnit) {
        ensureGlObjects();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + fieldUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, occTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + descUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, occDescTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    /** Bind the SHIP path's field set: aggregates per host plus the (source, target) pair boxes the
     *  fragment subtracts its own ship's material with. */
    public void bindShipOccField(int fieldUnit, int descUnit) {
        ensureGlObjects();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + fieldUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, shipOccTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + descUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, shipOccDescTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    /**
     * A buffer name with a real (if empty) data store. glTexBuffer against a name that has never
     * seen glBufferData leaves the texture incomplete, and sampling it is GL_INVALID_OPERATION --
     * "Not a valid buffer object", four per frame while the client sits in a world with no ships
     * yet, on the same driver path that has taken this client down before. One zeroed RGBA32F texel
     * is enough to make it legal; the shader early-outs on the count uniforms long before it reads
     * anything here.
     */
    private static int newBackedBuffer() {
        final int buf = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buf);
        GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, new float[] {0f, 0f, 0f, 0f}, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        return buf;
    }

    private static int makeBufferTexture(int buf) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tex);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buf);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        return tex;
    }
}
