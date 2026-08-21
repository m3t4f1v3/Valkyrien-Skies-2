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
    }

    public void delete() {
        if (arenaPtr != 0L) MemoryUtil.nmemFree(arenaPtr);
        if (headerPtr != 0L) MemoryUtil.nmemFree(headerPtr);
        if (shipDirPtr != 0L) MemoryUtil.nmemFree(shipDirPtr);
        if (gridPtr != 0L) MemoryUtil.nmemFree(gridPtr);
        if (buffer != 0) { GL15.glDeleteBuffers(buffer); buffer = 0; }
        if (texture != 0) { GL11.glDeleteTextures(texture); texture = 0; }
        if (headerBuffer != 0) { GL15.glDeleteBuffers(headerBuffer); headerBuffer = 0; }
        if (headerTexture != 0) { GL11.glDeleteTextures(headerTexture); headerTexture = 0; }
        if (shipDirBuffer != 0) { GL15.glDeleteBuffers(shipDirBuffer); shipDirBuffer = 0; }
        if (shipDirTexture != 0) { GL11.glDeleteTextures(shipDirTexture); shipDirTexture = 0; }
        if (gridBuffer != 0) { GL15.glDeleteBuffers(gridBuffer); gridBuffer = 0; }
        if (gridTexture != 0) { GL11.glDeleteTextures(gridTexture); gridTexture = 0; }
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
        for (int s = 1; s < shipCap; s++) {
            if (!seen[s]) continue;
            java.util.Arrays.fill(bestClaim, 0f);
            java.util.Arrays.fill(bestShip, 0);
            for (int t = 1; t < shipCap; t++) {
                if (t == s || !seen[t]) continue;
                float c = pairClaim(s, t, aabb, anchor, quat);
                if (c <= 0f) continue;
                // insert into the top-MAX_PARTNERS list
                for (int k = 0; k < MAX_PARTNERS; k++) {
                    if (c > bestClaim[k]) {
                        for (int m = MAX_PARTNERS - 1; m > k; m--) {
                            bestClaim[m] = bestClaim[m - 1];
                            bestShip[m] = bestShip[m - 1];
                        }
                        bestClaim[k] = c;
                        bestShip[k] = t;
                        break;
                    }
                }
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
