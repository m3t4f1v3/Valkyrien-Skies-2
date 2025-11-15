package org.valkyrienskies.mod.mixin.mod_compat.sodium;

import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.util.BitwiseMath;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

@Mixin(DefaultChunkRenderer.class)
public abstract class MixinDefaultChunkRenderer {
    @Inject(method = "getVisibleFaces", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cancelBlockFaceCulling(
            int originX, int originY, int originZ,
            int chunkX, int chunkY, int chunkZ,
            CallbackInfoReturnable<Integer> cir) {
        ClientShip ship = (ClientShip) VSGameUtilsKt.getShipManagingPos(
                Minecraft.getInstance().level, chunkX, chunkZ);

        if (ship == null)
            return;

        Vector3d blockCenter = (Vector3d) ship.getRenderTransform().getPositionInWorld();
        Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 camPos = cam.getPosition();

        Vector3d deltaWorld = new Vector3d(blockCenter.x - camPos.x,
                blockCenter.y - camPos.y,
                blockCenter.z - camPos.z);

        Vector3d deltaShip = new Vector3d();
        ship.getRenderTransform().transformDirectionNoScalingFromWorldToShip(deltaWorld, deltaShip);
        deltaShip.normalize();

        int facesVisible = 0;

        for (Direction dir : Direction.values()) {
            Vec3i normMc = dir.getNormal();
            Vector3d normal = new Vector3d(normMc.getX(), normMc.getY(), normMc.getZ());

            if (deltaShip.dot(normal) < 0.05) {
                facesVisible |= 1 << ModelQuadFacing.fromDirection(dir).ordinal();
            }
        }

        // System.out.println(facesVisible);

        cir.setReturnValue(facesVisible);
    }
}
