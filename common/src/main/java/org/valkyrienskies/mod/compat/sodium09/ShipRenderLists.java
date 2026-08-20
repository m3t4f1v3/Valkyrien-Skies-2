package org.valkyrienskies.mod.compat.sodium09;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Iterator;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.iterator.ReversibleObjectArrayIterator;

/**
 * A render list for one ship's visible sections.
 *
 * <p>Sodium 0.9 gives every {@link RenderRegion} exactly one {@link ChunkRenderList}, reset once per
 * frame by whichever collector sees it first. That is fine when there is a single traversal per frame,
 * but VS produces one list per ship <em>plus</em> the world's, all live at the same time and often over
 * the same regions — so reusing the region-owned lists would have each ship stomp on the last one and on
 * the world. This builds independent {@code ChunkRenderList} instances instead, which is why it
 * implements {@link ChunkRenderListIterable} directly rather than producing a {@code SortedRenderLists}
 * (whose constructor is package-private anyway).
 *
 * <p>Ordering: regions are appended in the order sections are visited. Sodium sorts regions by distance
 * to the camera for correct translucency ordering; ship geometry is drawn per-ship in its own space, so
 * the cross-region ordering that matters is within one ship, and the section walk is already in
 * shipyard-chunk order.
 */
public final class ShipRenderLists implements ChunkRenderListIterable {
    private final ObjectArrayList<ChunkRenderList> lists = new ObjectArrayList<>();
    private final ObjectArrayList<RenderRegion> regions = new ObjectArrayList<>();

    private RenderRegion lastRegion;
    private ChunkRenderList lastList;

    public void add(final RenderSection section, final int frame) {
        final RenderRegion region = section.getRegion();

        if (region == null) {
            return;
        }

        ChunkRenderList list = this.lastRegion == region ? this.lastList : null;

        if (list == null) {
            for (int i = 0; i < this.regions.size(); i++) {
                if (this.regions.get(i) == region) {
                    list = this.lists.get(i);
                    break;
                }
            }
        }

        if (list == null) {
            list = new ChunkRenderList(region);
            list.reset(frame);
            this.regions.add(region);
            this.lists.add(list);
        }

        this.lastRegion = region;
        this.lastList = list;

        list.add(LocalSectionIndex.pack(
            section.getChunkX() & RenderRegion.REGION_WIDTH_M,
            section.getChunkY() & RenderRegion.REGION_HEIGHT_M,
            section.getChunkZ() & RenderRegion.REGION_LENGTH_M));
    }

    public boolean isEmpty() {
        return this.lists.isEmpty();
    }

    /**
     * Drops the draw-command batches Sodium caches on each region this list touches.
     *
     * <p>The cache is keyed by region and pass only, and is filled from whichever render list drew that
     * region last. Since the world pass and every ship take turns drawing the same regions with
     * different lists and different camera transforms within one frame, the cache has to be dropped
     * before each of our draws — and once more afterwards, so the world's next pass refills it from its
     * own list rather than inheriting a ship's.
     */
    public void invalidateCachedBatches(final TerrainRenderPass pass) {
        for (int i = 0; i < this.regions.size(); i++) {
            this.regions.get(i).clearCachedBatchFor(pass);
        }
    }

    @Override
    public Iterator<ChunkRenderList> iterator(final boolean reverse) {
        return new ReversibleObjectArrayIterator<>(this.lists, reverse);
    }
}
