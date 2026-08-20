package org.valkyrienskies.mod.mixin.mod_compat.sodium09;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.Matrix4dc;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.ShipRendererKt;
import org.valkyrienskies.mod.compat.LoadedMods;
import org.valkyrienskies.mod.compat.iris.IrisCompat;
import org.valkyrienskies.mod.compat.sodium09.ShipRenderLists;
import org.valkyrienskies.mod.compat.sodium09.ShipSectionCache;
import org.valkyrienskies.mod.compat.sodium09.ShipSectionCandidate;
import org.valkyrienskies.mod.mixinducks.mod_compat.sodium09.RenderSectionManagerDuck;

/**
 * Builds a render list per visible ship, and keeps the ships' chunk sections meshed.
 *
 * <p>The 0.5 version of this mixin collected ship sections with sodium's own {@code VisibleChunkCollector}
 * and merged the collector's rebuild lists back into the manager's. Neither works in 0.9:
 *
 * <ul>
 *   <li>The collector now writes into the render list each {@code RenderRegion} owns, one per region and
 *       reset once per frame. A second traversal in the same frame would overwrite the world's list, so
 *       ship lists are built into standalone {@link ShipRenderLists} instead.</li>
 *   <li>Build scheduling moved to a deferred task queue fed by the section tree traversal. Shipyard
 *       sections sit far outside the traversal's search distance and are never reached, so they are
 *       submitted directly, at the tail of the manager's own task submission, out of the same collector
 *       and budget it was about to use.</li>
 * </ul>
 *
 * @author Rubydesic (original 0.5 implementation)
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class MixinRenderSectionManager implements RenderSectionManagerDuck {

    @Unique
    private final WeakHashMap<ClientShip, ShipRenderLists> vs$shipRenderLists = new WeakHashMap<>();

    @Unique
    private final WeakHashMap<ClientShip, ShipSectionCache> vs$shipSectionCaches = new WeakHashMap<>();

    @Unique
    private boolean vs$shipRenderListsDirty = true;

    @Unique
    private int vs$shipSectionCacheGeneration = 1;

    @Unique
    private int vs$lastShipRenderListFrame = Integer.MIN_VALUE;

    @Shadow
    @Final
    private ClientLevel level;

    @Shadow
    private int frame;

    @Shadow
    protected abstract RenderSection getRenderSection(int x, int y, int z);

    @Shadow
    protected abstract void submitSectionTask(ChunkJobCollector collector, RenderSection section,
        UploadResourceBudget uploadBudget);

    @Override
    public Map<ClientShip, ShipRenderLists> vs$getShipRenderLists() {
        return this.vs$shipRenderLists;
    }

    @Override
    public void vs$markShipRenderListsDirty() {
        this.vs$shipRenderListsDirty = true;
        this.vs$shipSectionCacheGeneration++;
    }

    @Override
    public void vs$invalidateShipSectionCache(final ClientShip ship) {
        this.vs$shipSectionCaches.remove(ship);
    }

    @Inject(method = "finalizeRenderLists", at = @At("TAIL"))
    private void vs$afterFinalizeRenderLists(final Camera camera, final Viewport viewport,
        final FogParameters fogParameters, final boolean updateChunksImmediately, final CallbackInfo ci) {
        this.vs$updateShipRenderLists(camera, viewport, this.frame, false);
    }

    @Override
    public void vs$updateShipRenderLists(final Camera camera, final Viewport viewport, final int frame,
        final boolean spectator) {
        if (this.vs$lastShipRenderListFrame == frame) {
            return;
        }
        this.vs$lastShipRenderListFrame = frame;

        final Minecraft minecraft = Minecraft.getInstance();
        final ProfilerFiller profiler = minecraft.getProfiler();
        profiler.push("vs_ship_render_lists");
        try {
            final Iterable<ClientShip> loadedShips = VSGameUtilsKt.getShipObjectWorld(minecraft).getLoadedShips();
            this.vs$shipRenderLists.clear();

            final boolean shadersActive = LoadedMods.getIris() && IrisCompat.isIrisShaderActive();
            for (final ClientShip ship : loadedShips) {
                final boolean useTerrainPath = ShipRendererKt.getUsesTerrainChunkRenderer(ship)
                    || (shadersActive && ShipRendererKt.getUsesBatchedRenderer(ship));
                if (!useTerrainPath) {
                    continue;
                }
                final AABBdc shipAabb = ship.getRenderAABB();
                if (shipAabb == null || !vs$isAabbVisible(viewport, shipAabb)) {
                    continue;
                }

                final ShipRenderLists renderLists = new ShipRenderLists();
                final Matrix4dc shipToWorld = ship.getRenderTransform().getShipToWorld();
                final AABBd tempAabb = new AABBd();

                for (final ShipSectionCandidate candidate : this.vs$getShipSectionCache(ship).sections) {
                    if (!vs$isShipSectionVisible(viewport, shipToWorld, tempAabb,
                        candidate.x, candidate.y, candidate.z)) {
                        continue;
                    }
                    if (candidate.section == null || candidate.section.isDisposed()) {
                        continue;
                    }
                    renderLists.add(candidate.section, frame);
                }

                if (renderLists.isEmpty()) {
                    continue;
                }

                this.vs$shipRenderLists.put(ship, renderLists);
            }

            this.vs$shipRenderListsDirty = false;
        } finally {
            profiler.pop();
        }
    }

    /**
     * Meshes ship sections the tree traversal never reaches.
     *
     * <p>Runs at the tail of the manager's own submission so it can hand tasks to the same deferred
     * collector and upload budget, which keeps ship builds inside the frame's build budget instead of
     * competing with it.
     */
    @Inject(method = "submitSectionTasks", at = @At("TAIL"))
    private void vs$submitShipSectionTasks(final ChunkJobCollector importantCollector,
        final ChunkJobCollector semiImportantCollector, final ChunkJobCollector deferredCollector,
        final UploadResourceBudget uploadBudget, final Viewport viewport, final CallbackInfo ci) {
        if (this.vs$shipSectionCaches.isEmpty()) {
            return;
        }

        for (final ShipSectionCache cache : this.vs$shipSectionCaches.values()) {
            for (final ShipSectionCandidate candidate : cache.sections) {
                if (!deferredCollector.hasBudgetRemaining() || !uploadBudget.isAvailable()) {
                    return;
                }
                final RenderSection section = candidate.section;
                if (section == null || section.isDisposed() || section.getPendingUpdate() == 0) {
                    continue;
                }
                this.submitSectionTask(deferredCollector, section, uploadBudget);
            }
        }
    }

    /** Sodium only ticks animated sprites for the world's render lists; do the same for each ship's. */
    @Inject(method = "tickVisibleRenders", at = @At("TAIL"))
    private void vs$tickVisibleShipRenders(final CallbackInfo ci) {
        for (final ShipRenderLists lists : this.vs$shipRenderLists.values()) {
            final var it = lists.iterator(false);

            while (it.hasNext()) {
                final ChunkRenderList renderList = it.next();
                final var region = renderList.getRegion();
                final var sections = renderList.sectionsWithSpritesIterator();

                if (sections == null) {
                    continue;
                }

                while (sections.hasNext()) {
                    final TextureAtlasSprite[] sprites = region.getAnimatedSprites(sections.nextByteAsInt());

                    if (sprites == null) {
                        continue;
                    }

                    for (final TextureAtlasSprite sprite : sprites) {
                        SpriteUtil.INSTANCE.markSpriteActive(sprite);
                    }
                }
            }
        }
    }

    @Inject(method = "onSectionAdded", at = @At("TAIL"))
    private void vs$onSectionAdded(final int x, final int y, final int z, final CallbackInfo ci) {
        this.vs$markShipRenderListsDirty();
    }

    @Inject(method = "onSectionRemoved", at = @At("TAIL"))
    private void vs$onSectionRemoved(final int x, final int y, final int z, final CallbackInfo ci) {
        this.vs$markShipRenderListsDirty();
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void vs$onDestroy(final CallbackInfo ci) {
        this.vs$shipRenderLists.clear();
        this.vs$shipSectionCaches.clear();
        this.vs$shipRenderListsDirty = true;
    }

    @Unique
    private ShipSectionCache vs$getShipSectionCache(final ClientShip ship) {
        final ShipSectionCache cached = this.vs$shipSectionCaches.get(ship);
        final int activeChunkCount = ship.getActiveChunksSet().getSize();
        if (cached != null
            && !this.vs$shipRenderListsDirty
            && cached.activeChunkCount == activeChunkCount
            && cached.dirtyGeneration == this.vs$shipSectionCacheGeneration) {
            return cached;
        }

        final ArrayList<ShipSectionCandidate> sections = new ArrayList<>();
        ship.getActiveChunksSet().forEach((x, z) -> {
            final LevelChunk levelChunk = this.level.getChunk(x, z);
            for (int y = this.level.getMinSection(); y < this.level.getMaxSection(); y++) {
                final LevelChunkSection levelChunkSection = levelChunk.getSection(y - this.level.getMinSection());
                if (levelChunkSection.hasOnlyAir()) {
                    continue;
                }

                final RenderSection renderSection = this.getRenderSection(x, y, z);
                if (renderSection == null) {
                    continue;
                }

                sections.add(new ShipSectionCandidate(x, y, z, renderSection));
            }
        });

        final ShipSectionCache rebuilt =
            new ShipSectionCache(sections, activeChunkCount, this.vs$shipSectionCacheGeneration);
        this.vs$shipSectionCaches.put(ship, rebuilt);
        return rebuilt;
    }

    @Unique
    private static boolean vs$isShipSectionVisible(final Viewport viewport, final Matrix4dc shipToWorld,
        final AABBd tempAabb, final int x, final int y, final int z) {
        tempAabb.setMin((x << 4) - 0.6, (y << 4) - 0.6, (z << 4) - 0.6);
        tempAabb.setMax((x << 4) + 15.6, (y << 4) + 15.6, (z << 4) + 15.6);
        tempAabb.transform(shipToWorld);
        return vs$isAabbVisible(viewport, tempAabb);
    }

    /**
     * 0.9's {@code Viewport#isBoxVisible} tests a chunk-section-sized box at a section origin, which a
     * ship-space AABB rotated into world space is not. {@code isBoxVisibleDirect} takes camera-relative
     * floats and a single half-extent, so we pass the largest of the three — conservative, never culling
     * something that is actually visible.
     */
    @Unique
    private static boolean vs$isAabbVisible(final Viewport viewport, final AABBdc aabb) {
        final CameraTransform camera = viewport.getTransform();

        final double centerX = (aabb.minX() + aabb.maxX()) * 0.5;
        final double centerY = (aabb.minY() + aabb.maxY()) * 0.5;
        final double centerZ = (aabb.minZ() + aabb.maxZ()) * 0.5;

        final double halfX = (aabb.maxX() - aabb.minX()) * 0.5;
        final double halfY = (aabb.maxY() - aabb.minY()) * 0.5;
        final double halfZ = (aabb.maxZ() - aabb.minZ()) * 0.5;

        return viewport.isBoxVisibleDirect(
            (float) (centerX - camera.x),
            (float) (centerY - camera.y),
            (float) (centerZ - camera.z),
            (float) (Math.max(halfX, Math.max(halfY, halfZ)) + 1.0));
    }
}
