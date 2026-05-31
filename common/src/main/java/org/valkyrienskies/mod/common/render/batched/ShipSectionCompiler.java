package org.valkyrienskies.mod.common.render.batched;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ChunkBufferBuilderPack;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

public final class ShipSectionCompiler {

    private final ChunkBufferBuilderPack buffers = new ChunkBufferBuilderPack();
    private final RandomSource random = RandomSource.create();
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
    private final ShipShadeRemovingGetter shadeRemovingView = new ShipShadeRemovingGetter();

    public ShipSectionMesh compile(final ClientLevel level, final BlockRenderDispatcher dispatcher,
        final int sectionX, final int sectionY, final int sectionZ) {

        final LevelChunk chunk = level.getChunk(sectionX, sectionZ);
        final int sectionIndex = level.getSectionIndexFromSectionY(sectionY);
        final LevelChunkSection[] allSections = chunk.getSections();
        if (sectionIndex < 0 || sectionIndex >= allSections.length) {
            return null;
        }
        final LevelChunkSection section = allSections[sectionIndex];
        if (section.hasOnlyAir()) {
            return null;
        }

        final int originX = sectionX << 4;
        final int originY = sectionY << 4;
        final int originZ = sectionZ << 4;

        final BlockAndTintGetter renderView = shadeRemovingView.forLevel(level);
        final PoseStack poseStack = new PoseStack();
        final Set<RenderType> startedLayers = new HashSet<>();

        ModelBlockRenderer.enableCaching();
        try {
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        final BlockState state = section.getBlockState(lx, ly, lz);
                        final FluidState fluid = state.getFluidState();
                        final boolean hasFluid = !fluid.isEmpty();
                        final boolean hasModel = state.getRenderShape() == RenderShape.MODEL;
                        if (!hasFluid && !hasModel) {
                            continue;
                        }
                        scratchPos.set(originX + lx, originY + ly, originZ + lz);

                        if (hasFluid) {
                            final RenderType fluidLayer = ItemBlockRenderTypes.getRenderLayer(fluid);
                            final BufferBuilder builder = beginLayer(fluidLayer, startedLayers);
                            dispatcher.renderLiquid(scratchPos, renderView, builder, state, fluid);
                        }

                        if (hasModel) {
                            final RenderType blockLayer = ItemBlockRenderTypes.getChunkRenderType(state);
                            final BufferBuilder builder = beginLayer(blockLayer, startedLayers);
                            poseStack.pushPose();
                            poseStack.translate(lx, ly, lz);
                            dispatcher.renderBatched(state, scratchPos, renderView, poseStack, builder, true, random);
                            poseStack.popPose();
                        }
                    }
                }
            }
        } finally {
            ModelBlockRenderer.clearCache();
        }

        if (startedLayers.isEmpty()) {
            return null;
        }

        ShipSectionMesh mesh = null;
        for (final RenderType layer : startedLayers) {
            final int layerIndex = ShipSectionMesh.layerIndex(layer);
            final BufferBuilder builder = buffers.builder(layer);
            final BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
            if (rendered == null) {
                continue;
            }
            if (layerIndex < 0) {
                rendered.release();
                continue;
            }
            final VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vb.bind();
            vb.upload(rendered); // consumes (releases) the RenderedBuffer
            if (mesh == null) {
                mesh = new ShipSectionMesh(originX, originY, originZ);
            }
            mesh.setBuffer(layerIndex, vb);
        }
        VertexBuffer.unbind();

        return mesh;
    }

    private BufferBuilder beginLayer(final RenderType layer, final Set<RenderType> startedLayers) {
        final BufferBuilder builder = buffers.builder(layer);
        if (startedLayers.add(layer)) {
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        }
        return builder;
    }
}
