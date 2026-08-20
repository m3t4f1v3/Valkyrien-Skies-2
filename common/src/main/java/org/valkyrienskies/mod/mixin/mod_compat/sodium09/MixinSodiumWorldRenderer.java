package org.valkyrienskies.mod.mixin.mod_compat.sodium09;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.SortedSet;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSClientGameUtils;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.assembly.SeamlessChunksManager;
import org.valkyrienskies.mod.common.render.batched.ShipBatchRenderer;
import org.valkyrienskies.mod.compat.LoadedMods;
import org.valkyrienskies.mod.compat.LoadedMods.FlywheelVersion;
import org.valkyrienskies.mod.compat.sodium09.ShipRenderLists;
import org.valkyrienskies.mod.compat.sodium09.SodiumCompat;
import org.valkyrienskies.mod.mixinducks.mod_compat.sodium09.RenderSectionManagerDuck;

/**
 * Sodium 0.9 counterpart of the world-renderer hooks.
 *
 * <p>Two of these used to be per-loader: {@code drawChunkLayer} took a {@code PoseStack} on Embeddium and
 * {@code ChunkRenderMatrices} on Sodium. 0.9 is one shared codebase for both loaders, so these live in
 * common. Block entity rendering also collapsed into a single method here, which means the ship pass adds
 * a second traversal at its tail rather than redirecting the inner overload the 0.5 version had.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false, priority = 1100)
public abstract class MixinSodiumWorldRenderer {

    @Shadow
    private ClientLevel level;

    @Shadow
    private RenderSectionManager renderSectionManager;

    @Shadow
    private static void renderBlockEntity(final PoseStack poseStack, final RenderBuffers bufferBuilders,
        final Long2ObjectMap<SortedSet<BlockDestructionProgress>> progression, final float tickDelta,
        final MultiBufferSource.BufferSource immediate, final double x, final double y, final double z,
        final BlockEntityRenderDispatcher dispatcher, final BlockEntity entity) {
        throw new AssertionError();
    }

    @Inject(method = "drawChunkLayer", at = @At("TAIL"))
    private void vs$afterChunkLayer(final RenderType renderLayer, final ChunkRenderMatrices matrices, final double x,
        final double y, final double z, final CallbackInfo ci) {
        SodiumCompat.renderShips((SodiumWorldRenderer) (Object) this, this.renderSectionManager, renderLayer, matrices,
            x, y, z);
    }

    /**
     * Ship block entities are stored at their shipyard positions, so the plain camera-relative translate
     * would draw them out in the shipyard. Substitute the ship's render transform instead.
     */
    @Redirect(
        method = "renderBlockEntity",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V"))
    private static void vs$renderShipBlockEntityInShipyard(
        final PoseStack instance,
        final double x, final double y, final double z,
        final PoseStack ignoredPoseStack,
        final RenderBuffers bufferBuilders,
        final Long2ObjectMap<SortedSet<BlockDestructionProgress>> progression,
        final float tickDelta,
        final MultiBufferSource.BufferSource immediate,
        final double camX, final double camY, final double camZ,
        final BlockEntityRenderDispatcher dispatcher,
        final BlockEntity entity) {

        final BlockPos pos = entity.getBlockPos();

        // fix for https://github.com/ValkyrienSkies/Valkyrien-Skies-2/issues/818
        if (!(dispatcher.level instanceof final ClientLevel clientLevel)) {
            // fix for https://github.com/ValkyrienSkies/Valkyrien-Skies-2/issues/1162
            instance.translate(x, y, z);
            return;
        }

        final ClientShip ship = VSGameUtilsKt.getLoadedShipManagingPos(clientLevel, pos);

        if (ship == null) {
            instance.translate(x, y, z);
        } else {
            VSClientGameUtils.transformRenderWithShip(ship.getRenderTransform(), instance, pos, camX, camY, camZ);
        }
    }

    /**
     * Draws each ship's block entities after the world's.
     *
     * <p>0.9 collapsed the two {@code renderBlockEntities} overloads into one, so there is no inner call
     * to redirect a substituted render list into; the ship traversal is spelled out here instead, over
     * the same region/section iteration the original performs.
     */
    @Inject(method = "renderBlockEntities", at = @At("TAIL"))
    private void vs$renderShipBlockEntities(final PoseStack poseStack, final RenderBuffers bufferBuilders,
        final Long2ObjectMap<SortedSet<BlockDestructionProgress>> progression, final Camera camera,
        final float tickDelta, final CallbackInfo ci) {

        final MultiBufferSource.BufferSource immediate = bufferBuilders.bufferSource();
        final BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();

        final Vec3 cameraPos = camera.getPosition();
        final double x = cameraPos.x();
        final double y = cameraPos.y();
        final double z = cameraPos.z();

        for (final ShipRenderLists renderLists
            : ((RenderSectionManagerDuck) this.renderSectionManager).vs$getShipRenderLists().values()) {
            final var listIterator = renderLists.iterator(false);

            while (listIterator.hasNext()) {
                final ChunkRenderList renderList = listIterator.next();
                final RenderRegion region = renderList.getRegion();
                final var sections = renderList.sectionsWithEntitiesIterator();

                if (sections == null) {
                    continue;
                }

                while (sections.hasNext()) {
                    final BlockEntity[] blockEntities = region.getCulledBlockEntities(sections.nextByteAsInt());

                    if (blockEntities == null) {
                        continue;
                    }

                    for (final BlockEntity blockEntity : blockEntities) {
                        renderBlockEntity(poseStack, bufferBuilders, progression, tickDelta, immediate,
                            x, y, z, dispatcher, blockEntity);
                    }
                }
            }
        }

        ShipBatchRenderer.INSTANCE.renderBlockEntities(this.level, poseStack, immediate, camera, tickDelta);
    }

    @Inject(method = "setupTerrain", at = @At("TAIL"))
    private void vs$updateShipRenderLists(final Camera camera, final Viewport viewport,
        final FogParameters fogParameters, final boolean spectator, final boolean updateChunksImmediately,
        final Matrix4f cullMatrix, final CallbackInfo ci) {
        ((RenderSectionManagerDuck) this.renderSectionManager)
            .vs$updateShipRenderLists(camera, viewport, this.renderSectionManager.getFrame(), spectator);
    }

    /**
     * Process deferred ship chunk packets BEFORE vanilla's light updates so that ship chunks are loaded
     * and their light is computed before render chunks compile.
     */
    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void vs$drainShipChunksBeforeLightUpdate(final Camera camera, final Viewport viewport,
        final FogParameters fogParameters, final boolean spectator, final boolean updateChunksImmediately,
        final Matrix4f cullMatrix, final CallbackInfo ci) {
        final SeamlessChunksManager manager = SeamlessChunksManager.get();
        if (manager != null) {
            if (LoadedMods.getFlywheel() == FlywheelVersion.V1) {
                return;
            }
            manager.drainDeferredBatch();
            // Drain all queued light updates so the light engine has the latest data
            if (!this.level.isLightUpdateQueueEmpty()) {
                this.level.pollLightUpdates();
            }
        }
    }

    /** Fix entities in ships not rendering when Sodium is installed. */
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private <T extends Entity> void vs$isEntityVisible(final EntityRenderer<T> renderer, final T entity,
        final CallbackInfoReturnable<Boolean> cir) {
        if (VSGameUtilsKt.isBlockInShipyard(this.level, entity.position())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "reload", at = @At("HEAD"))
    private void vs$deleteStoragesOnReload(final CallbackInfo ci) {
        ShipBatchRenderer.INSTANCE.freeAll();
        SodiumCompat.deleteStorages();
    }

    @Inject(method = "unloadLevel", at = @At("HEAD"))
    private void vs$deleteStoragesOnUnload(final CommandList commandList, final CallbackInfo ci) {
        ShipBatchRenderer.INSTANCE.freeAll();
        SodiumCompat.deleteStorages();
    }

    @Inject(method = "initRenderer", at = @At("TAIL"))
    private void vs$populateStorage(final CommandList commandList, final CallbackInfo ci) {
        SodiumCompat.populateWorldFromShipsForFrame(this.level);
        SodiumCompat.populateLightSectionStorage(this.level);
        SodiumCompat.populateBiomeSectionStorage(this.level);
        SodiumCompat.dispatchGpuLightFlood();
    }
}
