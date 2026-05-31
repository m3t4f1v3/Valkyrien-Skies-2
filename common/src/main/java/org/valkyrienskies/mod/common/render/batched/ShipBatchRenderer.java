package org.valkyrienskies.mod.common.render.batched;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSClientGameUtils;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.VSRenderTypes;
import org.valkyrienskies.mod.common.config.ShipRendererKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

public final class ShipBatchRenderer {

    public static final ShipBatchRenderer INSTANCE = new ShipBatchRenderer();

    private final Long2ObjectMap<ShipRenderObject> ships = new Long2ObjectOpenHashMap<>();
    private final ShipSectionCompiler compiler = new ShipSectionCompiler();
    private final LongOpenHashSet presentScratch = new LongOpenHashSet();
    private final ArrayList<ShipRenderObject> drawOrder = new ArrayList<>();
    private final ArrayList<ShipSectionMesh> translucentOrderScratch = new ArrayList<>();

    private ShipBatchRenderer() {
    }

    public void markSectionDirty(final long shipId, final int sx, final int sy, final int sz) {
        final ShipRenderObject renderObject;
        synchronized (ships) {
            renderObject = ships.get(shipId);
        }
        if (renderObject != null) {
            renderObject.markSectionDirty(sx, sy, sz);
        }
    }

    public void onShipUnload(final long shipId) {
        final ShipRenderObject removed;
        synchronized (ships) {
            removed = ships.remove(shipId);
        }
        if (removed != null) {
            drawOrder.remove(removed);
            removed.close();
        }
    }

    public void beginFrame(final ClientLevel level) {
        RenderSystem.assertOnRenderThread();
        if (level == null) {
            freeAll();
            return;
        }

        final BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        presentScratch.clear();

        for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (!ShipRendererKt.getUsesBatchedRenderer(ship)) {
                continue;
            }
            presentScratch.add(ship.getId());
            ShipRenderObject renderObject;
            synchronized (ships) {
                renderObject = ships.get(ship.getId());
                if (renderObject == null) {
                    renderObject = new ShipRenderObject(ship);
                    ships.put(ship.getId(), renderObject);
                }
            }
            renderObject.ensureCompiled(level, dispatcher, compiler);
        }

        synchronized (ships) {
            if (ships.size() != presentScratch.size()) {
                final var it = ships.long2ObjectEntrySet().iterator();
                while (it.hasNext()) {
                    final var entry = it.next();
                    if (!presentScratch.contains(entry.getLongKey())) {
                        entry.getValue().close();
                        it.remove();
                    }
                }
            }
            drawOrder.clear();
            drawOrder.addAll(ships.values());
        }
    }

    public void drawLayer(final RenderType renderType, final PoseStack poseStack,
        final double camX, final double camY, final double camZ, final Matrix4f projectionMatrix,
        final Frustum frustum) {

        final int layerIndex = ShipSectionMesh.layerIndex(renderType);
        if (layerIndex < 0 || ships.isEmpty()) {
            return;
        }
        RenderSystem.assertOnRenderThread();

        renderType.setupRenderState();

        final ShaderInstance shipShader = VSRenderTypes.Companion.shipShaderFor(renderType);
        final ShaderInstance shader = shipShader != null ? shipShader : RenderSystem.getShader();
        if (shader == null) {
            renderType.clearRenderState();
            return;
        }

        for (int k = 0; k < 12; ++k) {
            shader.setSampler("Sampler" + k, RenderSystem.getShaderTexture(k));
        }
        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(projectionMatrix);
        }
        if (shader.INVERSE_VIEW_ROTATION_MATRIX != null) {
            shader.INVERSE_VIEW_ROTATION_MATRIX.set(RenderSystem.getInverseViewRotationMatrix());
        }
        if (shader.COLOR_MODULATOR != null) {
            shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        }
        if (shader.FOG_START != null) {
            shader.FOG_START.set(RenderSystem.getShaderFogStart());
        }
        if (shader.FOG_END != null) {
            shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        }
        if (shader.FOG_COLOR != null) {
            shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        }
        if (shader.FOG_SHAPE != null) {
            shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        }
        if (shader.TEXTURE_MATRIX != null) {
            shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        }
        if (shader.GAME_TIME != null) {
            shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        }
        RenderSystem.setupShaderLights(shader);
        shader.apply();

        final Uniform modelViewUniform = shader.MODEL_VIEW_MATRIX;
        final Uniform chunkOffsetUniform = shader.CHUNK_OFFSET;
        final Vector3d camScratch = new Vector3d();
        final boolean translucent = renderType == RenderType.translucent();

        for (int shipIdx = 0; shipIdx < drawOrder.size(); shipIdx++) {
            final ShipRenderObject renderObject = drawOrder.get(shipIdx);
            final ClientShip ship = renderObject.ship;
            if (renderObject.getSections().isEmpty()) {
                continue;
            }
            if (frustum != null
                && !frustum.isVisible(VectorConversionsMCKt.toMinecraft(ship.getRenderAABB()))) {
                continue;
            }

            final ShipTransform transform = ship.getRenderTransform();
            camScratch.set(camX, camY, camZ);
            final Vector3dc camShip = transform.getWorldToShip().transformPosition(camScratch);

            poseStack.pushPose();
            VSClientGameUtils.transformRenderWithShip(transform, poseStack,
                camShip.x(), camShip.y(), camShip.z(), camX, camY, camZ);
            if (modelViewUniform != null) {
                modelViewUniform.set(poseStack.last().pose());
                modelViewUniform.upload();
            }
            poseStack.popPose();

            final Iterable<ShipSectionMesh> sectionsToDraw;
            if (translucent) {
                translucentOrderScratch.clear();
                translucentOrderScratch.addAll(renderObject.getSections().values());
                final double cx = camShip.x();
                final double cy = camShip.y();
                final double cz = camShip.z();
                translucentOrderScratch.sort((a, b) ->
                    Double.compare(sectionCenterDistSq(b, cx, cy, cz), sectionCenterDistSq(a, cx, cy, cz)));
                sectionsToDraw = translucentOrderScratch;
            } else {
                sectionsToDraw = renderObject.getSections().values();
            }

            for (final ShipSectionMesh mesh : sectionsToDraw) {
                final VertexBuffer buffer = mesh.getBuffer(layerIndex);
                if (buffer == null) {
                    continue;
                }
                if (chunkOffsetUniform != null) {
                    chunkOffsetUniform.set(
                        (float) (mesh.originX - camShip.x()),
                        (float) (mesh.originY - camShip.y()),
                        (float) (mesh.originZ - camShip.z()));
                    chunkOffsetUniform.upload();
                }
                buffer.bind();
                buffer.draw();
            }
        }

        if (chunkOffsetUniform != null) {
            chunkOffsetUniform.set(new Vector3f());
        }
        shader.clear();
        VertexBuffer.unbind();
        renderType.clearRenderState();
    }

    private static double sectionCenterDistSq(final ShipSectionMesh mesh,
        final double cx, final double cy, final double cz) {
        final double dx = mesh.originX + 8.0 - cx;
        final double dy = mesh.originY + 8.0 - cy;
        final double dz = mesh.originZ + 8.0 - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    public void renderBlockEntities(final ClientLevel level, final PoseStack poseStack,
        final MultiBufferSource bufferSource, final Camera camera, final float partialTick) {
        if (drawOrder.isEmpty()) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        final BlockEntityRenderDispatcher dispatcher =
            Minecraft.getInstance().getBlockEntityRenderDispatcher();
        final Vec3 cam = camera.getPosition();

        for (int shipIdx = 0; shipIdx < drawOrder.size(); shipIdx++) {
            final ShipRenderObject renderObject = drawOrder.get(shipIdx);
            final List<BlockEntity> shipBlockEntities = renderObject.getBlockEntities(level);
            if (shipBlockEntities.isEmpty()) {
                continue;
            }
            final ShipTransform transform = renderObject.ship.getRenderTransform();
            for (int i = 0; i < shipBlockEntities.size(); i++) {
                final BlockEntity blockEntity = shipBlockEntities.get(i);
                final BlockPos pos = blockEntity.getBlockPos();
                poseStack.pushPose();
                VSClientGameUtils.transformRenderWithShip(transform, poseStack, pos,
                    cam.x(), cam.y(), cam.z());
                dispatcher.render(blockEntity, partialTick, poseStack, bufferSource);
                poseStack.popPose();
            }
        }
    }

    public void freeAll() {
        synchronized (ships) {
            for (final ShipRenderObject renderObject : ships.values()) {
                renderObject.close();
            }
            ships.clear();
        }
        drawOrder.clear();
    }
}
