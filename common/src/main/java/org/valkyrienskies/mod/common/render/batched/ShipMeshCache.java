package org.valkyrienskies.mod.common.render.batched;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.valkyrienskies.core.api.ships.ClientShip;

/**
 * The compiled geometry of every ship anything is currently drawing.
 *
 * <p>Split out of {@link ShipBatchRenderer}, which used to own it privately. That was fine while the
 * batched renderer was the only thing that ever wanted a ship as vertex buffers, and stopped being
 * fine the moment a second caller did: meshing a ship is expensive, and a ship being drawn both into
 * the world and into {@link ShipPortraitRenderer}'s camera should be meshed once and drawn twice.
 *
 * <p>Meshing is deliberately independent of which world renderer is in use. It reads blocks and
 * writes vertex buffers, and neither of those cares whether the world around it is being drawn by
 * Minecraft's chunk renderer, by Sodium, or by the batched renderer here — which is what lets a ship
 * be drawn from a second camera on all three.
 *
 * <p>Entries are evicted by whoever is driving the frame, through {@link #retainOnly}. A caller that
 * wants a ship kept regardless of whether the world is drawing it — a portrait of a ship the world
 * is rendering some other way — pins it, and eviction leaves pinned entries alone.
 */
public final class ShipMeshCache {

    public static final ShipMeshCache INSTANCE = new ShipMeshCache();

    private final Long2ObjectMap<ShipRenderObject> objects = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet pinned = new LongOpenHashSet();
    private final ShipSectionCompiler compiler = new ShipSectionCompiler();

    private ShipMeshCache() {
    }

    /** The geometry for a ship, compiling it if nothing has yet. */
    public ShipRenderObject obtain(final ClientShip ship) {
        synchronized (objects) {
            ShipRenderObject object = objects.get(ship.getId());
            if (object == null) {
                object = new ShipRenderObject(ship);
                objects.put(ship.getId(), object);
            }
            return object;
        }
    }

    /** The geometry for a ship if something has already compiled it, and null otherwise. */
    public ShipRenderObject peek(final long shipId) {
        synchronized (objects) {
            return objects.get(shipId);
        }
    }

    /**
     * Brings a ship's geometry up to date with its blocks.
     *
     * @param mayRemesh whether the caller has budget left for a full remesh this frame; a false
     *                  here defers a structural change rather than dropping it
     * @return whether a full remesh was actually done, so the caller can spend its budget
     */
    public boolean compile(final ClientLevel level, final ShipRenderObject object,
        final boolean mayRemesh) {
        return object.ensureCompiled(level, Minecraft.getInstance().getBlockRenderer(), compiler,
            mayRemesh);
    }

    /** Keeps a ship's geometry alive through eviction, for a caller the frame does not know about. */
    public void pin(final long shipId) {
        synchronized (objects) {
            pinned.add(shipId);
        }
    }

    /**
     * Lets a pinned ship be evicted again.
     *
     * <p>Does not free it here: if the world is still drawing the ship, its geometry is still
     * wanted. The next {@link #retainOnly} decides.
     */
    public void unpin(final long shipId) {
        synchronized (objects) {
            pinned.remove(shipId);
        }
    }

    /** Drops everything not in {@code present} and not pinned. */
    public void retainOnly(final LongSet present) {
        synchronized (objects) {
            if (objects.size() == present.size() && pinned.isEmpty()) {
                return;
            }
            final var it = objects.long2ObjectEntrySet().iterator();
            while (it.hasNext()) {
                final var entry = it.next();
                final long id = entry.getLongKey();
                if (present.contains(id) || pinned.contains(id)) {
                    continue;
                }
                entry.getValue().close();
                it.remove();
            }
        }
    }

    public void release(final long shipId) {
        final ShipRenderObject removed;
        synchronized (objects) {
            pinned.remove(shipId);
            removed = objects.remove(shipId);
        }
        if (removed != null) {
            removed.close();
        }
    }

    public void releaseAll() {
        synchronized (objects) {
            for (final ShipRenderObject object : objects.values()) {
                object.close();
            }
            objects.clear();
            pinned.clear();
        }
    }

    public boolean isEmpty() {
        synchronized (objects) {
            return objects.isEmpty();
        }
    }
}
