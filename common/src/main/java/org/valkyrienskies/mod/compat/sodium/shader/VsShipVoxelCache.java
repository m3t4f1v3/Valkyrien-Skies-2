package org.valkyrienskies.mod.compat.sodium.shader;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.joml.primitives.AABBic;
import org.lwjgl.system.MemoryUtil;

import org.valkyrienskies.core.api.ships.ClientShip;

/**
 * Per-ship list of the shipyard-space voxels that matter to dynamic lighting: every solid (full-cube
 * opaque) block and every light emitter. This is the static half of what
 * {@link VsWorldFromShipLightStorage#markShipVoxels} used to recompute from scratch every population
 * pass — the block scan only has to happen again when the ship's blocks change, so per frame the GPU
 * path just re-transforms a cached array.
 *
 * <p>Entries are 8 bytes, two uints, so the stamp compute shader can read them as a {@code uvec2}:
 * <pre>
 *   word0: dx | (dy &lt;&lt; 16)                          offsets from the ship AABB min, uint16 each
 *   word1: dz | (light &lt;&lt; 16) | (solidFlag &lt;&lt; 24)
 * </pre>
 *
 * <p>Invalidation mirrors {@code ShipRenderObject}'s {@code emittersDirty}: a client block update in a
 * ship's chunk marks that ship dirty and the next request rebuilds it.
 */
public final class VsShipVoxelCache {
    /** 8 bytes per voxel — see the class doc for the packing. */
    public static final int BYTES_PER_VOXEL = 8;
    /** Safety cap per ship, matching the old {@code MAX_BLOCKS_PER_SHIP} AABB guard. */
    private static final int MAX_VOXELS_PER_SHIP = 1 << 18;
    /**
     * How many consecutive scans must agree before a ship's voxel list is trusted and scanning stops.
     *
     * <p>A client receives a ship's shipyard chunks over several ticks after joining, and a scan run
     * mid-stream returns a partial ship — often missing its emitters entirely. Nothing would ever
     * mark that stale (the blocks never "changed"), so a single scan would freeze the ship out of
     * dynamic lighting for the rest of the session. Re-scanning until the result stops changing lets
     * it converge on its own.
     */
    private static final int SCAN_CONFIRMATIONS = 3;
    /** Spacing between confirmation scans. */
    private static final long RESCAN_INTERVAL_MS = 400L;

    /** One ship's cached voxels. {@code ptr} and {@code solidPtr} are native memory owned by this cache. */
    public static final class ShipVoxels {
        long ptr;
        int capacity;
        int count;
        int minX;
        int minY;
        int minZ;
        /**
         * Dense one-bit-per-cell solid mask over the ship's whole shipyard AABB, indexed
         * {@code ((dy * sizeZ) + dz) * sizeX + dx} to match the scan order below.
         *
         * <p>The voxel LIST above cannot answer "is shipyard cell (x,y,z) solid?" without a search,
         * and the stamp needs exactly that: mapping a rotated ship onto world cells has to be done as
         * a gather (ask each world cell which ship voxel covers it) rather than a scatter (send each
         * ship voxel to a world cell). A scatter of a rotated lattice is not one-to-one — voxels
         * collide onto the same cell and leave others empty — which both seals real openings and
         * punches holes in solid hull.
         */
        long solidPtr;
        int solidInts;
        /**
         * Cells where this ship's light reaches open air, in the same 8-byte packing as the voxel
         * list. These, not the emitters, are what seeds the world flood: by the time light is here it
         * has already been through the hull in the grid the hull is exact in, so the world side never
         * has to know the ship's shape. Transform-independent, so it is cached with the scan.
         */
        long exitPtr;
        int exitCapacity;
        int exitCount;
        int maxExitLevel;
        int sizeX;
        int sizeY;
        int sizeZ;
        int maxLightLevel;
        boolean dirty = true;
        boolean seenThisFrame;
        int confirmations;
        long lastScanMs;

        public int count() {
            return count;
        }

        public long pointer() {
            return ptr;
        }

        /** Ship-space coordinate that voxel offsets are relative to. */
        public int minX() {
            return minX;
        }

        public int minY() {
            return minY;
        }

        public int minZ() {
            return minZ;
        }

        /** Cells where ship light reaches open air; see the field doc. */
        public long exitPointer() {
            return exitPtr;
        }

        public int exitCount() {
            return exitCount;
        }

        /** Brightest exit cell, which bounds how many world flood iterations can be needed. */
        public int maxExitLevel() {
            return maxExitLevel;
        }

        /** Dense shipyard-space solid mask; see the field doc. */
        public long solidPointer() {
            return solidPtr;
        }

        public int solidInts() {
            return solidInts;
        }

        public int sizeX() {
            return sizeX;
        }

        public int sizeY() {
            return sizeY;
        }

        public int sizeZ() {
            return sizeZ;
        }

        /** Brightest emitter in the ship, which bounds how many flood iterations it can need. */
        public int maxLightLevel() {
            return maxLightLevel;
        }

        private void ensureCapacity(final int needed) {
            if (capacity >= needed) {
                return;
            }
            int newCapacity = Math.max(capacity == 0 ? 1024 : capacity * 2, needed);
            ptr = MemoryUtil.nmemRealloc(ptr, (long) newCapacity * BYTES_PER_VOXEL);
            capacity = newCapacity;
        }

        private void ensureSolidCapacity(final int neededInts) {
            if (solidInts >= neededInts) {
                return;
            }
            solidPtr = MemoryUtil.nmemRealloc(solidPtr, (long) neededInts * Integer.BYTES);
            solidInts = neededInts;
        }

        private void ensureExitCapacity(final int needed) {
            if (exitCapacity >= needed) {
                return;
            }
            final int newCapacity = Math.max(exitCapacity == 0 ? 256 : exitCapacity * 2, needed);
            exitPtr = MemoryUtil.nmemRealloc(exitPtr, (long) newCapacity * BYTES_PER_VOXEL);
            exitCapacity = newCapacity;
        }

        private void free() {
            if (exitPtr != 0L) {
                MemoryUtil.nmemFree(exitPtr);
                exitPtr = 0L;
            }
            exitCapacity = 0;
            exitCount = 0;
            if (ptr != 0L) {
                MemoryUtil.nmemFree(ptr);
                ptr = 0L;
            }
            if (solidPtr != 0L) {
                MemoryUtil.nmemFree(solidPtr);
                solidPtr = 0L;
            }
            solidInts = 0;
            capacity = 0;
            count = 0;
        }
    }

    private final Long2ObjectMap<ShipVoxels> byShip = new Long2ObjectOpenHashMap<>();
    private long revision = 0L;

    /**
     * Bumped whenever any ship's voxel list actually changes. Consumers that mirror this cache into a
     * GPU buffer compare it to know whether their copy is still good.
     */
    public long revision() {
        return revision;
    }

    public void delete() {
        for (final ShipVoxels voxels : byShip.values()) {
            voxels.free();
        }
        byShip.clear();
    }

    /** Mark a ship's voxel list stale; the next {@link #get} rescans its blocks. */
    public void invalidate(final long shipId) {
        final ShipVoxels voxels = byShip.get(shipId);
        if (voxels != null) {
            voxels.dirty = true;
            voxels.confirmations = 0;
        }
    }

    public void invalidateAll() {
        for (final ShipVoxels voxels : byShip.values()) {
            voxels.dirty = true;
            voxels.confirmations = 0;
        }
    }

    /** Call once per frame before the {@link #get} calls, so {@link #pruneUnused} can see the set. */
    public void beginFrame() {
        for (final ShipVoxels voxels : byShip.values()) {
            voxels.seenThisFrame = false;
        }
    }

    /** Drop ships that weren't requested this frame (unloaded, or out of the contributing set). */
    public void pruneUnused() {
        final ObjectIterator<Long2ObjectMap.Entry<ShipVoxels>> it =
            byShip.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            final Long2ObjectMap.Entry<ShipVoxels> entry = it.next();
            if (!entry.getValue().seenThisFrame) {
                entry.getValue().free();
                it.remove();
                revision++;
            }
        }
    }

    /**
     * The ship's cached voxels, rescanning its blocks if the cache is stale or its AABB moved.
     * Returns null when the ship has no AABB or is too large to scan.
     */
    public ShipVoxels get(final LevelAccessor level, final ClientShip ship) {
        final AABBic aabb = ship.getShipAABB();
        if (aabb == null) {
            return null;
        }
        ShipVoxels voxels = byShip.get(ship.getId());
        if (voxels == null) {
            voxels = new ShipVoxels();
            byShip.put(ship.getId(), voxels);
        }
        voxels.seenThisFrame = true;

        final long now = System.currentTimeMillis();
        final boolean aabbMoved = voxels.minX != aabb.minX()
            || voxels.minY != aabb.minY() || voxels.minZ != aabb.minZ();
        if (!voxels.dirty && !aabbMoved) {
            if (voxels.confirmations >= SCAN_CONFIRMATIONS
                || now - voxels.lastScanMs < RESCAN_INTERVAL_MS) {
                return voxels;
            }
        }

        final int previousCount = voxels.count;
        final boolean wasSettled = !voxels.dirty && !aabbMoved;
        if (!rescan(level, aabb, voxels)) {
            // Shipyard data wasn't readable at all; stay dirty and retry next frame.
            return null;
        }
        voxels.dirty = false;
        voxels.lastScanMs = now;
        if (wasSettled && voxels.count == previousCount) {
            voxels.confirmations++;
        } else {
            // Content changed (or this is the first scan) — the GPU copy is stale, and the count has
            // to hold still again before we stop scanning.
            voxels.confirmations = 1;
            revision++;
        }
        return voxels;
    }

    /**
     * Walks the ship's shipyard-space AABB and records every solid or light-emitting voxel.
     *
     * <p>Reads through {@link LevelAccessor#getBlockState}, the same accessor
     * {@link VsShipEmitterList#scanShipEmitters} uses, rather than resolving chunks and sections by
     * hand. On the client, {@code ClientLevel.getChunk} does not reliably hand back a ship's shipyard
     * chunks — they sit millions of blocks from the player and miss the client chunk cache's
     * position check — so a section-walk silently reports an empty ship. The per-block accessor goes
     * through VS's own lookup and sees them. This scan is cached per ship and only re-runs when the
     * ship's blocks change, so the extra per-block cost is paid rarely.
     *
     * <p>The AABB is max-EXCLUSIVE, matching {@code scanShipEmitters} and the bounds checks in the
     * fluid-containment mixins.
     */
    private boolean rescan(final LevelAccessor level, final AABBic aabb, final ShipVoxels voxels) {
        final int xMin = aabb.minX();
        final int yMin = aabb.minY();
        final int zMin = aabb.minZ();
        final int xMax = aabb.maxX();
        final int yMax = aabb.maxY();
        final int zMax = aabb.maxZ();

        final long volume = (long) (xMax - xMin) * (yMax - yMin) * (zMax - zMin);
        if (volume <= 0L || volume > MAX_VOXELS_PER_SHIP) {
            voxels.count = 0;
            return false;
        }

        voxels.minX = xMin;
        voxels.minY = yMin;
        voxels.minZ = zMin;
        voxels.sizeX = xMax - xMin;
        voxels.sizeY = yMax - yMin;
        voxels.sizeZ = zMax - zMin;
        voxels.count = 0;
        voxels.maxLightLevel = 0;

        final int solidInts = (int) ((volume + 31L) >> 5);
        voxels.ensureSolidCapacity(solidInts);
        MemoryUtil.memSet(voxels.solidPtr, 0, (long) solidInts * Integer.BYTES);

        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // A ship always has blocks, so seeing none at all means the shipyard data wasn't readable
        // this frame. Reporting failure keeps the entry dirty so the next frame retries; caching the
        // empty result would drop the ship from lighting for the rest of the session, because
        // nothing short of a block change would ever mark it dirty again.
        int nonAirSeen = 0;

        for (int sy = yMin; sy < yMax; sy++) {
            for (int sz = zMin; sz < zMax; sz++) {
                for (int sx = xMin; sx < xMax; sx++) {
                    final BlockState state = level.getBlockState(pos.set(sx, sy, sz));
                    if (state.isAir()) {
                        continue;
                    }
                    nonAirSeen++;
                    final boolean isSolid = state.canOcclude()
                        && Block.isShapeFullBlock(state.getOcclusionShape(level, pos));
                    final int light = state.getLightEmission() & 0xF;
                    if (!isSolid && light == 0) {
                        continue;
                    }
                    final int dx = sx - xMin;
                    final int dy = sy - yMin;
                    final int dz = sz - zMin;
                    if (isSolid) {
                        final int bit = ((dy * voxels.sizeZ) + dz) * voxels.sizeX + dx;
                        final long word = voxels.solidPtr + (long) (bit >>> 5) * Integer.BYTES;
                        MemoryUtil.memPutInt(word, MemoryUtil.memGetInt(word) | (1 << (bit & 31)));
                    }
                    append(voxels, dx, dy, dz, light, isSolid);
                }
            }
        }
        if (nonAirSeen > 0) {
            buildExitSurface(voxels);
        }
        return nonAirSeen > 0;
    }

    /**
     * Flood this ship's light through its OWN grid, then record where that light reaches open air.
     *
     * <p>This is the change that takes ship geometry out of the world grid entirely. Light propagation
     * inside a ship happens here, in shipyard space, where the hull is exactly one block per cell and
     * an opening is exactly one cell wide. Nothing about it depends on the ship's transform, so it is
     * computed once per block change, is identical at every rotation and offset, and cannot flicker.
     *
     * <p>What crosses to the world side is only the {@code exit} list: cells of open air, outside the
     * hull, that the ship's light actually reached. A sealed hull produces an empty list and therefore
     * cannot light the world at any angle -- by construction, rather than by a resampling accident.
     *
     * <p>Both passes run over the AABB expanded by one cell, so the ship's outside surface is inside
     * the working volume and the exterior fill has a boundary to start from.
     */
    private void buildExitSurface(final ShipVoxels voxels) {
        final int sx = voxels.sizeX + 2;
        final int sy = voxels.sizeY + 2;
        final int sz = voxels.sizeZ + 2;
        final int volume = sx * sy * sz;

        final byte[] light = new byte[volume];
        final int[] queue = new int[volume];

        // --- ship-space light flood, 6-neighbour, blocked by the ship's own solid cells ---
        int head = 0;
        int tail = 0;
        final long base = voxels.ptr;
        for (int i = 0; i < voxels.count; i++) {
            final long entry = base + (long) i * BYTES_PER_VOXEL;
            final int word0 = MemoryUtil.memGetInt(entry);
            final int word1 = MemoryUtil.memGetInt(entry + 4);
            final int level = (word1 >>> 16) & 0xFF;
            if (level == 0) {
                continue;
            }
            final int idx = index(sx, sy, sz,
                (word0 & 0xFFFF) + 1, ((word0 >>> 16) & 0xFFFF) + 1, (word1 & 0xFFFF) + 1);
            if (level > light[idx]) {
                light[idx] = (byte) level;
                queue[tail++] = idx;
            }
        }
        while (head < tail) {
            final int idx = queue[head++];
            final int level = light[idx] - 1;
            if (level <= 0) {
                continue;
            }
            for (int dir = 0; dir < 6; dir++) {
                final int n = neighbour(idx, dir, sx, sy, sz);
                // An emitter is usually a solid block itself, so light leaves a solid cell but never
                // enters one -- the same rule the GPU flood uses, and what lets a glowstone light its
                // neighbours at 14 while a wall stays dark.
                if (n < 0 || isShipSolid(voxels, sx, sy, sz, n) || light[n] >= level) {
                    continue;
                }
                light[n] = (byte) level;
                if (tail == queue.length) {
                    // Cannot happen: every cell is enqueued at most once per strictly-increasing level
                    // and the queue is sized to the volume. Bail rather than corrupt memory.
                    break;
                }
                queue[tail++] = n;
            }
        }

        // --- exit cells: the lit cells of the SHELL, one layer outside the ship's AABB ---
        //
        // The shell is a closed surface around the whole ship, so all light leaving the ship crosses
        // it, and nothing still inside the ship is on it.
        //
        // The alternative -- any lit air cell REACHABLE from outside -- accepts the full length of a
        // shaft bored through a hull, because such a shaft is reachable end to end. Measured on a
        // 14-block shaft it produced 15 exit cells at light 15, seeded at world positions INSIDE the
        // hull, and drove 14 flood sweeps across 50-odd sections for a ship whose light does not reach
        // open air at all; this rule produces zero exits and runs no sweeps. Neither leaked visibly in
        // that test, because a seed inside a hull is smothered by the world-side coverage cost within a
        // cell or two -- but that is the coverage heuristic covering for the exit rule, and it only
        // holds while hulls are thick enough for coverage to saturate.
        //
        // Cost of the stricter rule: light escaping into a deep concavity is not seeded until it
        // reaches the bounding box, so an open bay is lit slightly late. Under-lighting, never leaking.
        voxels.exitCount = 0;
        voxels.maxExitLevel = 0;
        for (int idx = 0; idx < volume; idx++) {
            final int level = light[idx];
            if (level <= 0) {
                continue;
            }
            final int x = idx % sx;
            final int y = (idx / sx) % sy;
            final int z = idx / (sx * sy);
            if (x != 0 && y != 0 && z != 0 && x != sx - 1 && y != sy - 1 && z != sz - 1) {
                continue;
            }
            appendExit(voxels, x, y, z, level);
            if (level > voxels.maxExitLevel) {
                voxels.maxExitLevel = level;
            }
        }
    }

    private static int index(final int sx, final int sy, final int sz, final int x, final int y,
        final int z) {
        return x + sx * (y + sy * z);
    }

    private static int neighbour(final int idx, final int dir, final int sx, final int sy,
        final int sz) {
        final int x = idx % sx;
        final int y = (idx / sx) % sy;
        final int z = idx / (sx * sy);
        final int nx = x + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
        final int ny = y + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
        final int nz = z + (dir == 4 ? 1 : dir == 5 ? -1 : 0);
        if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) {
            return -1;
        }
        return index(sx, sy, sz, nx, ny, nz);
    }

    /** Solidity of a cell of the EXPANDED box, reading the dense mask built over the unexpanded one. */
    private static boolean isShipSolid(final ShipVoxels voxels, final int sx, final int sy,
        final int sz, final int idx) {
        final int x = idx % sx - 1;
        final int y = (idx / sx) % sy - 1;
        final int z = idx / (sx * sy) - 1;
        if (x < 0 || y < 0 || z < 0 || x >= voxels.sizeX || y >= voxels.sizeY || z >= voxels.sizeZ) {
            return false;
        }
        final int bit = ((y * voxels.sizeZ) + z) * voxels.sizeX + x;
        final long word = voxels.solidPtr + (long) (bit >>> 5) * Integer.BYTES;
        return (MemoryUtil.memGetInt(word) & (1 << (bit & 31))) != 0;
    }

    /**
     * Offsets are stored in EXPANDED-box coordinates, i.e. shipyard offset + 1, so they stay
     * non-negative -- an exit cell legitimately sits at -1 on the shell, and the 16-bit unsigned
     * packing shared with the voxel list has no room for a sign. The seed shader subtracts the 1 back
     * out before transforming.
     */
    private void appendExit(final ShipVoxels voxels, final int dx, final int dy, final int dz,
        final int level) {
        voxels.ensureExitCapacity(voxels.exitCount + 1);
        final long entry = voxels.exitPtr + (long) voxels.exitCount * BYTES_PER_VOXEL;
        MemoryUtil.memPutInt(entry, (dx & 0xFFFF) | ((dy & 0xFFFF) << 16));
        MemoryUtil.memPutInt(entry + 4, (dz & 0xFFFF) | (level << 16));
        voxels.exitCount++;
    }

    private void append(final ShipVoxels voxels, final int dx, final int dy, final int dz,
        final int light, final boolean solid) {
        voxels.ensureCapacity(voxels.count + 1);
        final long entry = voxels.ptr + (long) voxels.count * BYTES_PER_VOXEL;
        MemoryUtil.memPutInt(entry, (dx & 0xFFFF) | ((dy & 0xFFFF) << 16));
        MemoryUtil.memPutInt(entry + 4, (dz & 0xFFFF) | (light << 16) | ((solid ? 1 : 0) << 24));
        voxels.count++;
        if (light > voxels.maxLightLevel) {
            voxels.maxLightLevel = light;
        }
    }
}
