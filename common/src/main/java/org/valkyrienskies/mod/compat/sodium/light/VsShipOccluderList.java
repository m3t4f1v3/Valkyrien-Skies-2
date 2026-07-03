package org.valkyrienskies.mod.compat.sodium.light;

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
    /** Cap on occluders tracked per frame. The shader's loop is bounded
     *  too; keep these in sync. 1024 entries × 32 bytes = 32 KB GPU buffer
     *  (2 RGBA32F texels per voxel: position, quaternion). */
    public static final int MAX_OCCLUDERS = 1024;
    /** 8 floats per voxel, two vec4 texels:
     *    [i*2 + 0] = (worldX, worldY, worldZ, packedShipIndexAndFlags)
     *    [i*2 + 1] = (qx, qy, qz, qw) — ship rotation, used so each
     *                voxel's octagon stays oriented with the ship
     *                instead of becoming a world-axis box.
     *  packedShipIndexAndFlags is raw int bits (write/read via
     *  Float.intBitsToFloat / floatToRawIntBits, NEVER as a literal float
     *  value): bits 0-15 = dense per-frame ship index, bit 16 = "has a
     *  nearby seam candidate" hint set by computeSeamCandidateFlags. */
    private static final int BYTES_PER_OCCLUDER = 32;
    /** Coarse radius for the seam-candidate prefilter — matches the
     *  shader's VS_SEAM_CANDIDATE_RADIUS. Only used to prioritize which
     *  occluders survive the per-vertex loop's cap in dense scenes; the
     *  shader does its own exact geometric pair search regardless. */
    private static final float SEAM_CANDIDATE_RADIUS = 2.5f;
    private static final int SEAM_CANDIDATE_FLAG_BIT = 0x10000;
    private static final int SHIP_INDEX_MASK = 0xFFFF;

    private final long arenaPtr;
    private int count = 0;

    private int buffer = 0;
    private int texture = 0;
    private int currentByteSize = 0;

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
    }

    public void delete() {
        if (arenaPtr != 0L) MemoryUtil.nmemFree(arenaPtr);
        if (buffer != 0) { GL15.glDeleteBuffers(buffer); buffer = 0; }
        if (texture != 0) { GL11.glDeleteTextures(texture); texture = 0; }
    }

    public void beginFrame() {
        count = 0;
        shipIdToIndex.clear();
        nextShipIndex = 1;
    }

    public int size() {
        return count;
    }

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

    /** Walk a ship's voxels, find solid blocks, transform centers to world. */
    public void populateFromShip(LevelAccessor level, ClientShip ship) {
        AABBic shipyardAabb = ship.getShipAABB();
        if (shipyardAabb == null) return;

        ShipTransform xform = ship.getRenderTransform();
        Matrix4dc shipToWorld = xform.getShipToWorld();
        // Pull the rotation out of the ship's transform once per ship —
        // every voxel of this ship shares the same quaternion. The shader
        // applies its inverse to the fragment-to-voxel offset to express
        // the SDF in the ship's local frame, so the Manhattan tent
        // (octagonal shadow) rotates with the ship.
        shipToWorld.getNormalizedRotation(scratchQuat);
        float qx = (float) scratchQuat.x;
        float qy = (float) scratchQuat.y;
        float qz = (float) scratchQuat.z;
        float qw = (float) scratchQuat.w;

        // Assign or look up this ship's per-frame dense index. Stored in
        // voxel.w so the ship FSH can compare against u_VsCurrentShipIndex
        // and skip same-ship voxels (their AO is already baked into
        // v_Color.a — counting them again double-darkens self-shadows).
        long shipId = ship.getId();
        int shipIdx = shipIdToIndex.computeIfAbsent(shipId, id -> nextShipIndex++);
        float shipIndexFloat = (float) shipIdx;

        int xMin = shipyardAabb.minX();
        int yMin = shipyardAabb.minY();
        int zMin = shipyardAabb.minZ();
        int xMax = shipyardAabb.maxX();
        int yMax = shipyardAabb.maxY();
        int zMax = shipyardAabb.maxZ();

        for (int sy = yMin; sy <= yMax; sy++) {
            for (int sz = zMin; sz <= zMax; sz++) {
                for (int sx = xMin; sx <= xMax; sx++) {
                    if (count >= MAX_OCCLUDERS) return;
                    scratchBlockPos.set(sx, sy, sz);
                    BlockState state = level.getBlockState(scratchBlockPos);
                    if (state.isAir()) continue;
                    boolean isSolid = state.canOcclude()
                            && state.isCollisionShapeFullBlock(level, scratchBlockPos);
                    if (!isSolid) continue;

                    // Voxel center → world coords. Float precision preserved
                    // through the matrix multiply, so a rotating ship's voxel
                    // centers move along smooth arcs rather than snapping
                    // cell-to-cell.
                    scratch.set(sx + 0.5, sy + 0.5, sz + 0.5);
                    shipToWorld.transformPosition(scratch);

                    appendOccluder(scratch.x, scratch.y, scratch.z, shipIndexFloat, qx, qy, qz, qw);
                }
            }
        }
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
        // comment on BYTES_PER_OCCLUDER. The seam-candidate flag bit gets
        // OR'd in later by computeSeamCandidateFlags.
        int shipIndexInt = Math.round(shipIndex) & SHIP_INDEX_MASK;
        MemoryUtil.memPutFloat(offset + 12,   Float.intBitsToFloat(shipIndexInt));
        // Texel 1: quaternion
        MemoryUtil.memPutFloat(offset + 16,   qx);
        MemoryUtil.memPutFloat(offset + 20,   qy);
        MemoryUtil.memPutFloat(offset + 24,   qz);
        MemoryUtil.memPutFloat(offset + 28,   qw);
        count++;
    }

    /** O(N²) scan: for each occluder, check whether it has ANY nearby
     *  seam candidate — another ship voxel (any ship) OR a solid world
     *  block within {@link #SEAM_CANDIDATE_RADIUS}. This is only a coarse
     *  prefilter (see {@link #prioritizeSeamCandidateOccluders()}) to keep
     *  likely-relevant occluders at the front of the array ahead of the
     *  per-vertex shader loop's cap in dense scenes — the shader itself
     *  does an exact geometric vertex-pair match per candidate and doesn't
     *  otherwise consume this flag. Sets bit 16 (0x10000) of the packed
     *  shipIndex/flags slot. Must be called after every populate /
     *  appendOccluder for the frame and before {@link #upload()}. */
    public void computeSeamCandidateFlags(LevelAccessor level) {
        float radiusSq = SEAM_CANDIDATE_RADIUS * SEAM_CANDIDATE_RADIUS;
        for (int i = 0; i < count; i++) {
            long iOff = arenaPtr + (long) i * BYTES_PER_OCCLUDER;
            float ix = MemoryUtil.memGetFloat(iOff);
            float iy = MemoryUtil.memGetFloat(iOff + 4);
            float iz = MemoryUtil.memGetFloat(iOff + 8);

            boolean hasCandidate = false;

            // Pass 1: any other ship voxel (any ship) within radius.
            for (int j = 0; j < count && !hasCandidate; j++) {
                if (j == i) continue;
                long jOff = arenaPtr + (long) j * BYTES_PER_OCCLUDER;
                float dx = MemoryUtil.memGetFloat(jOff)     - ix;
                float dy = MemoryUtil.memGetFloat(jOff + 4) - iy;
                float dz = MemoryUtil.memGetFloat(jOff + 8) - iz;
                if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                    hasCandidate = true;
                }
            }

            // Pass 2: any solid world block within radius.
            if (!hasCandidate && level != null) {
                int bx = Math.round(ix - 0.5f);
                int by = Math.round(iy - 0.5f);
                int bz = Math.round(iz - 0.5f);
                int r = Math.round(SEAM_CANDIDATE_RADIUS);
                outer:
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -r; dy <= r; dy++) {
                        for (int dx = -r; dx <= r; dx++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            if (isSolidWorldBlock(level, bx + dx, by + dy, bz + dz)) {
                                hasCandidate = true;
                                break outer;
                            }
                        }
                    }
                }
            }

            int rawBits = Float.floatToRawIntBits(MemoryUtil.memGetFloat(iOff + 12));
            int shipIndexBits = rawBits & SHIP_INDEX_MASK;
            int packed = shipIndexBits | (hasCandidate ? SEAM_CANDIDATE_FLAG_BIT : 0);
            MemoryUtil.memPutFloat(iOff + 12, Float.intBitsToFloat(packed));
        }
        prioritizeSeamCandidateOccluders();
    }

    private void prioritizeSeamCandidateOccluders() {
        int write = 0;
        for (int read = 0; read < count; read++) {
            long readOff = arenaPtr + (long) read * BYTES_PER_OCCLUDER;
            int flags = Float.floatToRawIntBits(MemoryUtil.memGetFloat(readOff + 12));
            if ((flags & SEAM_CANDIDATE_FLAG_BIT) == 0) continue;
            if (read != write) {
                swapOccluders(read, write);
            }
            write++;
        }
    }

    private void swapOccluders(int a, int b) {
        long aOff = arenaPtr + (long) a * BYTES_PER_OCCLUDER;
        long bOff = arenaPtr + (long) b * BYTES_PER_OCCLUDER;
        for (int byteOffset = 0; byteOffset < BYTES_PER_OCCLUDER; byteOffset += Float.BYTES) {
            float tmp = MemoryUtil.memGetFloat(aOff + byteOffset);
            MemoryUtil.memPutFloat(aOff + byteOffset, MemoryUtil.memGetFloat(bOff + byteOffset));
            MemoryUtil.memPutFloat(bOff + byteOffset, tmp);
        }
    }

    private boolean isSolidWorldBlock(LevelAccessor level, int x, int y, int z) {
        scratchBlockPos.set(x, y, z);
        BlockState state = level.getBlockState(scratchBlockPos);
        if (state.isAir()) return false;
        return state.canOcclude() && state.isCollisionShapeFullBlock(level, scratchBlockPos);
    }

    public void upload() {
        ensureGlObjects();
        int needed = Math.max(BYTES_PER_OCCLUDER, count * BYTES_PER_OCCLUDER);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer);
        boolean orphaned = currentByteSize != needed;
        if (orphaned || count > 0) {
            GL15.nglBufferData(GL31.GL_TEXTURE_BUFFER, needed, arenaPtr, GL15.GL_DYNAMIC_DRAW);
            currentByteSize = needed;
        }
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        if (orphaned) {
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffer);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        }
    }

    public void bind(int textureUnit) {
        ensureGlObjects();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    private void ensureGlObjects() {
        if (buffer == 0) buffer = GL15.glGenBuffers();
        if (texture == 0) {
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffer);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        }
    }
}
