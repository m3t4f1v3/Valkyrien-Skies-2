package org.valkyrienskies.mod.fabric.mixin.compat.sodium;

import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.mixinducks.mod_compat.sodium.RenderSectionManagerDuck;

import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import org.valkyrienskies.mod.common.VSClientGameUtils;
import org.valkyrienskies.mod.common.hooks.VSGameEvents;
import org.valkyrienskies.mod.common.hooks.VSGameEvents.ShipRenderEventSodium;
import org.valkyrienskies.mod.mixin.mod_compat.sodium.RenderSectionManagerAccessor;

import net.minecraft.client.renderer.RenderType;

@Mixin(value = SodiumWorldRenderer.class, remap = false, priority = 1100)
public class MixinSodiumShipRenderer {

    @Shadow
    private RenderSectionManager renderSectionManager;

    private void vsRenderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z,
            CommandList commandList) {

        VSGameEvents.INSTANCE.getShipsStartRenderingSodium().emit(new VSGameEvents.ShipStartRenderEventSodium(
            pass, matrices, x, y, z
        ));

        ((RenderSectionManagerDuck) this.renderSectionManager).vs_getShipRenderLists().forEach((ship, renderList) -> {
            VSGameEvents.INSTANCE.getRenderShipSodium().emit(new ShipRenderEventSodium(pass, matrices, x, y, z, ship, renderList));
            final Vector3dc center = ship.getRenderTransform().getPositionInShip();
            final Matrix4dc s = ship.getRenderTransform().getShipToWorld();
            final Matrix4d newModelView = new Matrix4d(matrices.modelView())
                .translate(-x, -y, -z)
                .mul(s)
                .translate(center);

            final ChunkRenderMatrices newMatrices =
                new ChunkRenderMatrices(matrices.projection(), new Matrix4f(newModelView));
            ((RenderSectionManagerAccessor) this.renderSectionManager).getChunkRenderer().render(newMatrices, commandList, renderList, pass,
                new CameraTransform(center.x(), center.y(), center.z()));
            commandList.close();
            VSGameEvents.INSTANCE.getPostRenderShipSodium().emit(new ShipRenderEventSodium(pass, matrices, x, y, z, ship, renderList));
        });
    }

    @Inject(method = "drawChunkLayer", at = @At("TAIL"))
    private void afterChunkLayer(RenderType renderLayer, ChunkRenderMatrices matrices, double x, double y, double z,
            CallbackInfo ci) {

        RenderDevice device = RenderDevice.INSTANCE;
        CommandList commandList = device.createCommandList();

        if (renderLayer == RenderType.solid()) {
            vsRenderLayer(matrices, DefaultTerrainRenderPasses.SOLID, x, y, z, commandList);
            vsRenderLayer(matrices, DefaultTerrainRenderPasses.CUTOUT, x, y, z, commandList);
        } else if (renderLayer == RenderType.translucent()) {
            vsRenderLayer(matrices, DefaultTerrainRenderPasses.TRANSLUCENT, x, y, z, commandList);
        }

        commandList.close();
    }

}
