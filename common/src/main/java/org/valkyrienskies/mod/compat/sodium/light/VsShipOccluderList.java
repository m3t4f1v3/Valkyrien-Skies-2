package org.valkyrienskies.mod.compat.sodium.light;

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
 * <p>Used by the world FSH for ship-to-world AO. Iterating voxel centers
 * directly (rather than reading from the world-grid-aligned cell strengths
 * in {@link VsWorldFromShipLightStorage}) lets the AO shadow follow the
 * ship's transform continuously — including rotation — because the world
 * coords are continuous floats, not grid-quantized. With the cell-storage
 * approach, the AO pattern was anchored to world-aligned cells and
 * couldn't truly rotate; voxel-list AO is rotation-aware by construction.
 */
public class VsShipOccluderList {
    /** Cap on occluders tracked per frame. The shader's loop is bounded
     *  too; keep these in sync. 1024 entries × 16 bytes = 16 KB GPU buffer. */
    public static final int MAX_OCCLUDERS = 1024;
    private static final int BYTES_PER_OCCLUDER = 16; // 4 floats

    private final long arenaPtr;
    private int count = 0;

    private int buffer = 0;
    private int texture = 0;
    private int currentByteSize = 0;

    private final Vector3d scratch = new Vector3d();
    private final BlockPos.MutableBlockPos scratchBlockPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos scratchNeighborPos = new BlockPos.MutableBlockPos();

    // Bit positions in the per-voxel ship-frame neighbor mask packed
    // into the 4th float of each occluder vec4. The shader maps these
    // ship-frame axes to face-plane U/V via the face normal — works for
    // axis-aligned ships (which is the common case); rotated ships fall
    // through to plain Manhattan because the bits no longer line up with
    // world axes.
    private static final int NMASK_MINUS_X = 1 << 0;
    private static final int NMASK_PLUS_X  = 1 << 1;
    private static final int NMASK_MINUS_Y = 1 << 2;
    private static final int NMASK_PLUS_Y  = 1 << 3;
    private static final int NMASK_MINUS_Z = 1 << 4;
    private static final int NMASK_PLUS_Z  = 1 << 5;

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
    }

    public int size() {
        return count;
    }

    /** Walk a ship's voxels, find solid blocks, transform centers to world. */
    public void populateFromShip(LevelAccessor level, ClientShip ship) {
        AABBic shipyardAabb = ship.getShipAABB();
        if (shipyardAabb == null) return;

        ShipTransform xform = ship.getRenderTransform();
        Matrix4dc shipToWorld = xform.getShipToWorld();

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

                    // Ship-frame neighbor mask. Tells the shader, for each
                    // voxel, which of its ±X/±Y/±Z ship-frame neighbors are
                    // also solid occluders. The shader uses this to gate a
                    // bilinear corner-correction term on top of the Manhattan
                    // tent — without it, a row of voxels casts a string of
                    // octagons with bright triangular slices between them
                    // (each Manhattan tent's diagonal corner falls off to 0
                    // at distance 1, leaving the corner cell of every interior
                    // face only at 1/6 occlusion instead of vanilla's 1/3).
                    // With the mask, the shader knows the corner blocks of a
                    // row are part of an L with the axial neighbor, fires the
                    // bilinear extra in their corner cells, and the strip
                    // matches vanilla's uniform AO along the row.
                    int neighborMask = 0;
                    if (isSolidShipBlock(level, sx - 1, sy, sz)) neighborMask |= NMASK_MINUS_X;
                    if (isSolidShipBlock(level, sx + 1, sy, sz)) neighborMask |= NMASK_PLUS_X;
                    if (isSolidShipBlock(level, sx, sy - 1, sz)) neighborMask |= NMASK_MINUS_Y;
                    if (isSolidShipBlock(level, sx, sy + 1, sz)) neighborMask |= NMASK_PLUS_Y;
                    if (isSolidShipBlock(level, sx, sy, sz - 1)) neighborMask |= NMASK_MINUS_Z;
                    if (isSolidShipBlock(level, sx, sy, sz + 1)) neighborMask |= NMASK_PLUS_Z;

                    long offset = arenaPtr + (long) count * BYTES_PER_OCCLUDER;
                    MemoryUtil.memPutFloat(offset,        (float) scratch.x);
                    MemoryUtil.memPutFloat(offset + 4,    (float) scratch.y);
                    MemoryUtil.memPutFloat(offset + 8,    (float) scratch.z);
                    // Mask 0..63 stores cleanly as a small float — well within
                    // the integer-exact range of float32 and free of denormal
                    // flush issues. Read in shader via uint(voxel.w + 0.5).
                    MemoryUtil.memPutFloat(offset + 12,   (float) neighborMask);
                    count++;
                }
            }
        }
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

    /** Same isSolid check the main loop uses, but on a separate scratch
     *  position so it can be called for neighbor probes without clobbering
     *  the outer loop's scratchBlockPos. */
    private boolean isSolidShipBlock(LevelAccessor level, int x, int y, int z) {
        scratchNeighborPos.set(x, y, z);
        BlockState state = level.getBlockState(scratchNeighborPos);
        if (state.isAir()) return false;
        return state.canOcclude() && state.isCollisionShapeFullBlock(level, scratchNeighborPos);
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
