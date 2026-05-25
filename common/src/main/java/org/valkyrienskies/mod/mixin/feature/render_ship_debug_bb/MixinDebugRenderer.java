package org.valkyrienskies.mod.mixin.feature.render_ship_debug_bb;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Intersectiond;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2d;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.joml.primitives.AABBic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.bodies.ClientVsBody;
import org.valkyrienskies.core.api.bodies.VsBody;
import org.valkyrienskies.core.api.bodies.VsBodyCreateData;
import org.valkyrienskies.core.api.bodies.properties.BodyTransform;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.core.internal.world.VsiClientShipWorld;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.DragInfoReporter;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

@Mixin(DebugRenderer.class)
public class MixinDebugRenderer {

    /**
     * This mixin renders ship bounding boxes and center of masses.
     *
     * <p>They get rendered in the same pass as entities.
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void postRender(final PoseStack matrices, final MultiBufferSource.BufferSource vertexConsumersIgnore,
        final double cameraX, final double cameraY, final double cameraZ, final CallbackInfo ci) {

        final MultiBufferSource.BufferSource bufferSource =
            MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        final ClientLevel world = Minecraft.getInstance().level;
        final VsiClientShipWorld shipObjectClientWorld = VSGameUtilsKt.getShipObjectWorld(world);

        if (Minecraft.getInstance().getEntityRenderDispatcher().shouldRenderHitBoxes()) {
            // Further on coordinates will be relative to (0, 0, 0).
            // This raycast is used to determine if player's line of sight to a ship is obstructed.
            // [Entity#pick] produces false results for blocks like tall grass, hence we do a verbose raycast
            // with a different ClipContext.Block.
            Entity camera = Minecraft.getInstance().getCameraEntity();
            Vec3 eyeVec = camera.getEyePosition(0.0F);
            Vec3 viewVec = camera.getViewVector(0.0F).scale(20.0F);
            Vec3 targetVec = eyeVec.add(viewVec);
            HitResult hit = world.clip(
                new ClipContext(
                    eyeVec,
                    targetVec,
                    Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    camera
                )
            );
            // Reduced debug info (gamerule) disables ability to see normal hitboxes, so we disable ship ones too
            if (Minecraft.getInstance().showOnlyReducedInfo()) return;

            List<Long> alreadyRenderedVSBodies = new ArrayList<>();
            for (final ClientShip shipObjectClient : shipObjectClientWorld.getLoadedShips()) {
                alreadyRenderedVSBodies.add(shipObjectClient.getBodyId());
                final ShipTransform shipRenderTransform = shipObjectClient.getRenderTransform();
                final Vector3d shipRenderPosition = shipObjectClient.getRenderAABB().center(new Vector3d());
                final Quaternionf shipRenderRotate = shipRenderTransform.getRotation().get(new Quaternionf());
                // First, move to the location of the ship.
                matrices.pushPose();
                matrices.translate(-cameraX + shipRenderPosition.x(), -cameraY + shipRenderPosition.y(), -cameraZ + shipRenderPosition.z());

                boolean xrayEligible =
                    // Allow for spectators and creative builders.
                    Minecraft.getInstance().player.isSpectator() || Minecraft.getInstance().player.isCreative()
                        ||
                    // Force xray if player is already inside the ship.
                        shipObjectClient.getRenderAABB().containsPoint(
                            VectorConversionsMCKt.toJOML(Minecraft.getInstance().player.position())
                        )
                        ||
                    // Otherwise, only allow if line of sight to ship is not obstructed by a solid block.
                        shipObjectClient.getRenderAABB().intersectsLineSegment(
                            cameraX, cameraY, cameraZ,
                            hit.location.x, hit.location.y, hit.location.z,
                            new Vector2d()
                        ) != Intersectiond.OUTSIDE
                    ;

                final AABBic shipAABB = shipObjectClient.getShipAABB();
                AABB renderAABB = VectorConversionsMCKt.toMinecraft(shipObjectClient.getRenderAABB());
                renderAABB = new AABB(
                    renderAABB.maxX - shipRenderPosition.x(), renderAABB.maxY - shipRenderPosition.y(), renderAABB.maxZ - shipRenderPosition.z(),
                    renderAABB.minX - shipRenderPosition.x(), renderAABB.minY - shipRenderPosition.y(), renderAABB.minZ - shipRenderPosition.z()
                );

                if (shipAABB == null) {
                    // Ship with no blocks, something is wrong. Rendering a small marker in its position.
                    LevelRenderer
                        .renderLineBox(matrices, bufferSource.getBuffer(xrayEligible ? XRAY_LINES : RenderType.LINES), renderAABB, 1.0F, 0.0F, 0.0F, 1.0F);
                    continue;
                }

                // Bounding Box
                LevelRenderer
                    .renderLineBox(matrices, bufferSource.getBuffer(RenderType.LINES), renderAABB, 234.0F / 255.0F, 0.0F, 217.0f / 255.0f, 1.0F);

                // The following all rotate along with the ship.
                matrices.mulPose(shipRenderRotate);
                // Ship Block Bounding Box
                final Vector3dc centerOfShip = shipAABB.center(new Vector3d());
                final AABB shipVoxelAABBAfterOffset =
                    new AABB(
                        shipAABB.minX() - centerOfShip.x(),
                        shipAABB.minY() - centerOfShip.y(),
                        shipAABB.minZ() - centerOfShip.z(),
                        shipAABB.maxX() - centerOfShip.x(),
                        shipAABB.maxY() - centerOfShip.y(),
                        shipAABB.maxZ() - centerOfShip.z()
                    );
                LevelRenderer.renderLineBox(
                    matrices, bufferSource.getBuffer(RenderType.LINES),
                    shipVoxelAABBAfterOffset,
                    0.5F, 0.0F, 0.0F, 1.0F);

                // Render center of mass as a small cube
                Vector3d centerOfMass = shipRenderTransform.getPositionInModel().sub(centerOfShip, new Vector3d());
                final double comBoxSize = .25;
                final AABB comBox = AABB.ofSize(VectorConversionsMCKt.toMinecraft(centerOfMass), comBoxSize, comBoxSize, comBoxSize);
                LevelRenderer.renderLineBox(
                    matrices, bufferSource.getBuffer(xrayEligible ? XRAY_LINES : RenderType.LINES),
                    comBox,
                    250.0F / 255.0F, 194.0F / 255.0F, 19.0F / 255.0F, 1.0F
                );
                // Render gizmos (X, Y, Z axes from center of mass)
                if (xrayEligible) {
                    vs_renderGizmoInsideAABB(
                        matrices, bufferSource.getBuffer(XRAY_LINES),
                        shipVoxelAABBAfterOffset,
                        centerOfMass.x, centerOfMass.y, centerOfMass.z, 1.0F, .125F
                    );
                }
                // Render the ship's drag and lift forces as lines
                final Vector3dc dragForce = DragInfoReporter.INSTANCE.getShipDragValues().get(shipObjectClient.getId());
                final Vector3dc liftForce = DragInfoReporter.INSTANCE.getShipLiftValues().get(shipObjectClient.getId());
                if (dragForce != null) {
                    final Vector3d dragForceInShip = shipRenderTransform.getWorldToShip()
                        .transformDirection(dragForce, new Vector3d());
                    vs_renderForce(matrices, bufferSource.getBuffer(RenderType.LINES), centerOfMass, dragForceInShip,
                        0.01, 10.0, 0.0F, 0.5F, 1.0F, 1.0F);
                }
                if (liftForce != null) {
                    final Vector3d liftForceInShip = shipRenderTransform.getWorldToShip()
                        .transformDirection(liftForce, new Vector3d());
                    vs_renderForce(matrices, bufferSource.getBuffer(RenderType.LINES), centerOfMass, liftForceInShip,
                        0.01, 10.0, 0.0F, 1.0F, 0.5F, 1.0F);
                }

                matrices.popPose();
            }

            // TODO: This render stuff is uh kinda broken.
            //  Pretty much the only part that works is the center of mass box,
            //  the rest renders in wrong places / coordinate spaces / incorrectly.
            //  Ideally we could render the actual voxel shape of the vsbody,
            //  but that doesn't seem to be synced to client?
            for (ClientVsBody clientVsBody : shipObjectClientWorld.getAllBodies()) {
                if (alreadyRenderedVSBodies.contains(clientVsBody.getId())) continue;
                final BodyTransform bodyRenderTransform = clientVsBody.getRenderTransform();

                AABBdc localRenderAABB = clientVsBody.getRenderAABB();
                AABBdc renderAABBd = localRenderAABB.transform(bodyRenderTransform.getToWorld(), new AABBd());

                final Vector3d bodyRenderPosition = renderAABBd.center(new Vector3d());
                final Quaternionf bodyRenderRotate = bodyRenderTransform.getRotation().get(new Quaternionf());
                // First, move to the location of the body
                matrices.pushPose();
                //matrices.translate(-cameraX + bodyRenderPosition.x(), -cameraY + bodyRenderPosition.y(), -cameraZ + bodyRenderPosition.z());

                boolean xrayEligible =
                    // Allow for spectators and creative builders.
                    Minecraft.getInstance().player.isSpectator() || Minecraft.getInstance().player.isCreative()
                        ||
                        // Force xray if player is already inside the ship.
                        clientVsBody.getRenderAABB().containsPoint(
                            VectorConversionsMCKt.toJOML(Minecraft.getInstance().player.position())
                        )
                        ||
                        // Otherwise, only allow if line of sight to ship is not obstructed by a solid block.
                        clientVsBody.getRenderAABB().intersectsLineSegment(
                            cameraX, cameraY, cameraZ,
                            hit.location.x, hit.location.y, hit.location.z,
                            new Vector2d()
                        ) != Intersectiond.OUTSIDE
                    ;

                AABB renderAABB = VectorConversionsMCKt.toMinecraft(renderAABBd);

                // Bounding Box
                LevelRenderer
                    .renderLineBox(matrices, bufferSource.getBuffer(RenderType.LINES), renderAABB, 234.0F / 255.0F, 0.0F, 217.0f / 255.0f, 1.0F);

                // The following all rotate along with the ship.
                // We do a union to make sure the AABB is at least 0.1x0.1x0.1 otherwise /0 happens and shit gets weird
                AABBdc shipAABB = clientVsBody.getRenderAABB();//.union(new AABBd(-0.1, -0.1, -0.1, 0.1, 0.1, 0.1), new AABBd());

                if (!shipAABB.isValid()) {
                    shipAABB = new AABBd(shipAABB.minX(), shipAABB.minY(), shipAABB.minZ(), shipAABB.maxX()+0.1, shipAABB.maxY() + 0.1, shipAABB.maxZ() + 0.1);
                }
                // Ship Block Bounding Box
                final Vector3dc centerOfShip = shipAABB.center(new Vector3d());
                /*final AABB shipVoxelAABBAfterOffset =
                    new AABB(
                        shipAABB.minX() - centerOfShip.x(),
                        shipAABB.minY() - centerOfShip.y(),
                        shipAABB.minZ() - centerOfShip.z(),
                        shipAABB.maxX() - centerOfShip.x(),
                        shipAABB.maxY() - centerOfShip.y(),
                        shipAABB.maxZ() - centerOfShip.z()
                    );*/
                if (shipAABB.isValid()) {
                    LevelRenderer.renderLineBox(
                        matrices, bufferSource.getBuffer(RenderType.LINES),
                        VectorConversionsMCKt.toMinecraft(shipAABB),
                        0.5F, 1.0F, 0.0F, 1.0F);
                }

                //LevelRenderer.renderVoxelShape(matrices, bufferSource.getBuffer(RenderType.LINES), (DataVsBody)  clientVsBody);
                matrices.mulPose(bodyRenderRotate);
                matrices.translate(-cameraX + bodyRenderPosition.x(), -cameraY + bodyRenderPosition.y(), -cameraZ + bodyRenderPosition.z());


                // Render center of mass as a small cube
                Vector3d centerOfMass = bodyRenderTransform.getPositionInModel().sub(centerOfShip, new Vector3d());
                final double comBoxSize = .25;
                final AABB comBox = AABB.ofSize(VectorConversionsMCKt.toMinecraft(centerOfMass), comBoxSize, comBoxSize, comBoxSize);
                LevelRenderer.renderLineBox(
                    matrices, bufferSource.getBuffer(xrayEligible ? XRAY_LINES : RenderType.LINES),
                    comBox,
                    250.0F / 255.0F, 194.0F / 255.0F, 19.0F / 255.0F, 1.0F
                );
                // Render gizmos (X, Y, Z axes from center of mass)
                if (xrayEligible) {
                    vs_renderGizmoInsideAABB(
                        matrices, bufferSource.getBuffer(XRAY_LINES),
                        VectorConversionsMCKt.toMinecraft(shipAABB),
                        centerOfMass.x, centerOfMass.y, centerOfMass.z, 1.0F, .125F
                    );
                }

                matrices.popPose();
            }
        }
        bufferSource.endBatch();
    }

    @Unique
    private static void vs_renderForce(PoseStack poseStack, VertexConsumer vertexConsumer, Vector3dc pos, Vector3dc force, double scale, double cap, float r, float g, float b, float alpha) {
        Matrix4f m4 = poseStack.last().pose();
        Matrix3f m3 = poseStack.last().normal();

        Vector3d diff = new Vector3d(
            Math.min(Math.max(-cap, force.x() * scale), cap),
            Math.min(Math.max(-cap, force.y() * scale), cap),
            Math.min(Math.max(-cap, force.z() * scale), cap)
        );

        vertexConsumer.vertex(m4, (float) pos.x(), (float) pos.y(), (float) pos.z())
            .color(r, g, b, alpha)
            .normal(m3, (float) diff.x, (float) diff.y, (float) diff.z).endVertex();
        vertexConsumer.vertex(m4, (float) (pos.x() + diff.x), (float) (pos.y() + diff.y), (float) (pos.z() + diff.z))
            .color(1.0F, 1.0F, 1.0F, 0.0F) // Fade out effect for force lines to stand out against AABBs and gizmos
            .normal(m3, (float) diff.x, (float) diff.y, (float) diff.z).endVertex();
    }

    @Unique
    private static void vs_renderGizmoInsideAABB(PoseStack poseStack, VertexConsumer vertexConsumer, AABB aABB, double cx, double cy, double cz, float alpha, float gizmoSize) {
        vs_renderGizmoInsideAABB(poseStack, vertexConsumer, aABB.minX, aABB.minY, aABB.minZ, aABB.maxX, aABB.maxY, aABB.maxZ, cx, cy, cz, alpha, gizmoSize);
    }

    @Unique
    private static void vs_renderGizmoInsideAABB(
        PoseStack poseStack,
        VertexConsumer vertexConsumer,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        double cx, double cy, double cz,
        float alpha,
        float gizmoSize
    ) {
        Matrix4f m4 = poseStack.last().pose();
        Matrix3f m3 = poseStack.last().normal();

        float mx = (float) minX, my = (float) minY, mz = (float) minZ;
        float Mx = (float) maxX, My = (float) maxY, Mz = (float) maxZ;
        float fx = (float) cx, fy = (float) cy, fz = (float) cz;

        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f color = new Vector3f();

        java.util.function.BiConsumer<Vector3f, Vector3f> line = (a, b) -> {
            float dx = b.x - a.x;
            float dy = b.y - a.y;
            float dz = b.z - a.z;
            vertexConsumer.vertex(m4, a.x, a.y, a.z)
                .color(color.x, color.y, color.z, alpha)
                .normal(m3, dx, dy, dz).endVertex();
            vertexConsumer.vertex(m4, b.x, b.y, b.z)
                .color(color.x, color.y, color.z, alpha)
                .normal(m3, dx, dy, dz).endVertex();
        };

        color.set(1, 0, 0);
        line.accept(p1.set(mx, fy, fz), p2.set(Mx, fy, fz));
        line.accept(p1.set(mx, fy - gizmoSize, fz - gizmoSize), p2.set(mx, fy + gizmoSize, fz + gizmoSize));
        line.accept(p1.set(mx, fy - gizmoSize, fz + gizmoSize), p2.set(mx, fy + gizmoSize, fz - gizmoSize));
        float backX = Mx - gizmoSize;
        line.accept(p1.set(backX, fy - gizmoSize, fz), p2.set(Mx, fy, fz));
        line.accept(p1.set(backX, fy + gizmoSize, fz), p2.set(Mx, fy, fz));
        line.accept(p1.set(backX, fy, fz - gizmoSize), p2.set(Mx, fy, fz));
        line.accept(p1.set(backX, fy, fz + gizmoSize), p2.set(Mx, fy, fz));

        color.set(0, 1, 0);
        line.accept(p1.set(fx, my, fz), p2.set(fx, My, fz));
        line.accept(p1.set(fx - gizmoSize, my, fz - gizmoSize), p2.set(fx + gizmoSize, my, fz + gizmoSize));
        line.accept(p1.set(fx - gizmoSize, my, fz + gizmoSize), p2.set(fx + gizmoSize, my, fz - gizmoSize));
        float backY = My - gizmoSize;
        line.accept(p1.set(fx - gizmoSize, backY, fz), p2.set(fx, My, fz));
        line.accept(p1.set(fx + gizmoSize, backY, fz), p2.set(fx, My, fz));
        line.accept(p1.set(fx, backY, fz - gizmoSize), p2.set(fx, My, fz));
        line.accept(p1.set(fx, backY, fz + gizmoSize), p2.set(fx, My, fz));

        color.set(0, 0, 1);
        line.accept(p1.set(fx, fy, mz), p2.set(fx, fy, Mz));
        line.accept(p1.set(fx - gizmoSize, fy - gizmoSize, mz), p2.set(fx + gizmoSize, fy + gizmoSize, mz));
        line.accept(p1.set(fx - gizmoSize, fy + gizmoSize, mz), p2.set(fx + gizmoSize, fy - gizmoSize, mz));
        float backZ = Mz - gizmoSize;
        line.accept(p1.set(fx - gizmoSize, fy, backZ), p2.set(fx, fy, Mz));
        line.accept(p1.set(fx + gizmoSize, fy, backZ), p2.set(fx, fy, Mz));
        line.accept(p1.set(fx, fy - gizmoSize, backZ), p2.set(fx, fy, Mz));
        line.accept(p1.set(fx, fy + gizmoSize, backZ), p2.set(fx, fy, Mz));
    }

    @Unique
    private static RenderType XRAY_LINES = new RenderStateShard(null, null, null) {
        // RenderStateShard.RENDERTYPE_LINES_SHADER and others are public in Fabric but protected in Forge.
        // Instead of accessor mixins or access transformers we will use a dummy anonymous class.
        // It is only called once and references static methods and fields. This should be safe.
        public static RenderType createXrayLines() {
            return RenderType.create(
                "xray_lines",
                DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES,
                256, false, false,
                RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(RenderStateShard.DEFAULT_LINE) // Thinner than RenderType.LINES, though we do not really care.
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .createCompositeState(false)
            );
        }
    }.createXrayLines();
}
