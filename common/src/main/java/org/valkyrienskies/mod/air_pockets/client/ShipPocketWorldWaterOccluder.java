package org.valkyrienskies.mod.air_pockets.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSClientGameUtils;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.air_pockets.ShipWaterPocketManager;

/**
 * Writes opaque depth values for every pocket-air voxel of every ship before world translucent
 * rendering, with color writes disabled, so world water (and other translucent chunk geometry)
 * z-fails inside the pocket volume and the pocket pixels keep whatever was already in the
 * framebuffer (sky / hull / scene). No shader injection — works with vanilla, Sodium, Embeddium
 * and Iris/Oculus shaderpacks because the only thing we touch is the shared depth buffer and the
 * color-write mask, both of which all chunk-rendering pipelines respect.
 *
 * <p>Polygon offset biases our depth slightly toward the camera so water faces at the same world
 * position fail {@code GL_LEQUAL}. Backface culling is disabled so the trick works whether the
 * camera is outside the pocket (front faces visible) or inside it (back faces visible).</p>
 *
 * <p><b>Debug:</b> Set the system property {@code vsk.occluder.debug=true} to render the cubes
 * with color writes enabled and a bright magenta tint so you can visually verify the geometry
 * is in the right place.</p>
 */
public final class ShipPocketWorldWaterOccluder {

    private ShipPocketWorldWaterOccluder() {}

    private static final Logger LOGGER = LogManager.getLogger("ValkyrienAir PocketWaterOccluder");
    private static final long DIAG_INTERVAL_MS = 3000L;
    private static long lastDiagAtMs = 0L;
    private static final boolean DEBUG_VISUALIZE =
        Boolean.parseBoolean(System.getProperty("vsk.occluder.debug", "false"));

    private static final class ShipMesh {
        private final long shipId;
        private long geometryRevision = Long.MIN_VALUE;
        private VertexBuffer vertexBuffer;
        private int vertexCount;
        private int interiorBitsSet;
        // Snapshot bounds at build time — the mesh is stored in *local* coords (lx, ly, lz) so
        // that float precision survives ship coordinates ≥ ~16M. The caller must apply this
        // offset through the double-precision transform path at render time.
        private int minX;
        private int minY;
        private int minZ;

        private ShipMesh(final long shipId) {
            this.shipId = shipId;
        }

        private void close() {
            if (vertexBuffer != null) {
                vertexBuffer.close();
                vertexBuffer = null;
            }
            vertexCount = 0;
            interiorBitsSet = 0;
        }
    }

    private static final Map<Long, ShipMesh> MESHES = new HashMap<>();
    private static ClientLevel lastLevel = null;

    public static void clear() {
        for (final ShipMesh m : MESHES.values()) {
            m.close();
        }
        MESHES.clear();
        lastLevel = null;
    }

    public static void render(final double cameraX, final double cameraY, final double cameraZ,
        final Matrix4f projectionMatrix, final PoseStack poseStack) {
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null) return;

        if (lastLevel != level) {
            clear();
            lastLevel = level;
        }

        RenderSystem.assertOnRenderThread();

        // ----- Save state we'll change -----
        final boolean prevCullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        final boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        final boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        final boolean prevPolygonOffset = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
        final int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        final boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final float prevPolygonOffsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
        final float prevPolygonOffsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        final java.nio.ByteBuffer prevColorMask = java.nio.ByteBuffer.allocateDirect(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, prevColorMask);

        // ----- Configure depth-only state -----
        if (!prevDepthTest) RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        if (DEBUG_VISUALIZE) {
            RenderSystem.colorMask(true, true, true, true);
        } else {
            RenderSystem.colorMask(false, false, false, false);
        }
        // Force ColorModulator to (1,1,1,1) so per-vertex magenta is preserved in debug mode.
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        // Aggressive bias: drivers vary on tiny offsets, so use a few units. Sign is negative
        // = depth pulled toward camera so water at the same world position loses GL_LEQUAL.
        GL11.glPolygonOffset(-2.0f, -8.0f);

        int totalShipsRendered = 0;
        int totalShipsWithSnapshot = 0;
        int totalVerts = 0;
        try {
            for (final LoadedShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
                final long shipId = ship.getId();
                final ShipWaterPocketManager.ClientWaterReachableSnapshot snapshot =
                    ShipWaterPocketManager.getClientWaterReachableSnapshot(level, shipId);
                if (snapshot == null) continue;
                totalShipsWithSnapshot++;

                final ShipMesh mesh = MESHES.computeIfAbsent(shipId, ShipMesh::new);
                if (mesh.geometryRevision != snapshot.getGeometryRevision()) {
                    rebuildMesh(mesh, snapshot);
                }
                if (mesh.vertexBuffer == null || mesh.vertexCount <= 0) continue;

                final ShipTransform xform = (ship instanceof final ClientShip cs)
                    ? cs.getRenderTransform()
                    : ship.getShipTransform();

                poseStack.pushPose();
                try {
                    // Pass mesh's (minX, minY, minZ) as the shipspace offset. The double-precision
                    // matrix path inside transformRenderWithShip composes T(-cam) * shipToWorld *
                    // T(minX,minY,minZ); the resulting Matrix4f has small translation values, so
                    // float precision survives even when shipspace coords are at ~28M.
                    VSClientGameUtils.transformRenderWithShip(
                        xform,
                        poseStack,
                        (double) mesh.minX, (double) mesh.minY, (double) mesh.minZ,
                        cameraX, cameraY, cameraZ
                    );
                    final Matrix4f modelView = new Matrix4f(poseStack.last().pose());
                    mesh.vertexBuffer.bind();
                    mesh.vertexBuffer.drawWithShader(modelView, projectionMatrix, GameRenderer.getPositionColorShader());
                } finally {
                    poseStack.popPose();
                }

                totalShipsRendered++;
                totalVerts += mesh.vertexCount;
            }
            VertexBuffer.unbind();
        } finally {
            // ----- Restore state -----
            if (prevPolygonOffset) {
                GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            } else {
                GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            }
            GL11.glPolygonOffset(prevPolygonOffsetFactor, prevPolygonOffsetUnits);
            RenderSystem.colorMask(
                prevColorMask.get(0) != 0,
                prevColorMask.get(1) != 0,
                prevColorMask.get(2) != 0,
                prevColorMask.get(3) != 0
            );
            RenderSystem.depthMask(prevDepthMask);
            RenderSystem.depthFunc(prevDepthFunc);
            if (!prevDepthTest) RenderSystem.disableDepthTest();
            if (prevCullFace) {
                RenderSystem.enableCull();
            } else {
                RenderSystem.disableCull();
            }
            if (prevBlend) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }
        }

        // Periodic diagnostic — visible in client logs to confirm the hook is actually running.
        final long now = System.currentTimeMillis();
        if (now - lastDiagAtMs >= DIAG_INTERVAL_MS) {
            lastDiagAtMs = now;
            LOGGER.info("Occluder pass: {} ships with snapshot, {} drawn, {} total verts (debug={})",
                totalShipsWithSnapshot, totalShipsRendered, totalVerts, DEBUG_VISUALIZE);
        }
    }

    private static void rebuildMesh(final ShipMesh mesh, final ShipWaterPocketManager.ClientWaterReachableSnapshot snapshot) {
        final BitSet interior = snapshot.getInterior();
        final int minX = snapshot.getMinX();
        final int minY = snapshot.getMinY();
        final int minZ = snapshot.getMinZ();
        final int sizeX = snapshot.getSizeX();
        final int sizeY = snapshot.getSizeY();
        final int sizeZ = snapshot.getSizeZ();

        final int interiorCount = interior == null ? 0 : interior.cardinality();
        LOGGER.info("Rebuilding occluder mesh for ship {}: bounds=({},{},{}) size=({},{},{}) interiorBits={}",
            mesh.shipId, minX, minY, minZ, sizeX, sizeY, sizeZ, interiorCount);

        if (interior == null || interior.isEmpty()) {
            mesh.geometryRevision = snapshot.getGeometryRevision();
            mesh.vertexCount = 0;
            mesh.interiorBitsSet = 0;
            mesh.minX = minX;
            mesh.minY = minY;
            mesh.minZ = minZ;
            if (mesh.vertexBuffer != null) {
                mesh.vertexBuffer.close();
                mesh.vertexBuffer = null;
            }
            return;
        }

        final BufferBuilder bb = new BufferBuilder(8192);
        // POSITION_COLOR — color is per-vertex so it's robust against shaderpacks that mess with
        // the ColorModulator uniform. In non-debug mode color writes are masked off so the color
        // bytes are inert.
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // Bright magenta when debug, else fully-opaque (color writes are masked off anyway).
        final int dr = DEBUG_VISUALIZE ? 255 : 0;
        final int dg = DEBUG_VISUALIZE ? 0   : 0;
        final int db = DEBUG_VISUALIZE ? 255 : 0;
        final int da = 255;

        int vertCount = 0;
        for (int z = 0; z < sizeZ; z++) {
            for (int y = 0; y < sizeY; y++) {
                for (int x = 0; x < sizeX; x++) {
                    final int idx = x + sizeX * (y + sizeY * z);
                    if (!interior.get(idx)) continue;

                    // Local coords (0..size). The (minX, minY, minZ) offset is applied at render
                    // time through the double-precision matrix path, so we never form
                    // `minX + lx` as a float and lose precision at huge ship coords.
                    final float wx0 = (float) x;
                    final float wy0 = (float) y;
                    final float wz0 = (float) z;
                    final float wx1 = wx0 + 1.0f;
                    final float wy1 = wy0 + 1.0f;
                    final float wz1 = wz0 + 1.0f;

                    // All 6 cube faces. We don't cull internal faces between adjacent pocket
                    // voxels — depth coverage of the *full* volume matters, so the shared face
                    // must still write depth.
                    // -X face
                    bb.vertex(wx0, wy0, wz0).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx0, wy1, wz0).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx0, wy1, wz1).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx0, wy0, wz1).color(dr, dg, db, da).endVertex();
                    // +X face
                    bb.vertex(wx1, wy0, wz1).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx1, wy1, wz1).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx1, wy1, wz0).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx1, wy0, wz0).color(dr, dg, db, da).endVertex();
                    // -Y face
                    bb.vertex(wx0, wy0, wz0).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx0, wy0, wz1).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx1, wy0, wz1).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx1, wy0, wz0).color(dr, dg, db, da).endVertex();
                    // +Y face
                    bb.vertex(wx0, wy1, wz0).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx1, wy1, wz0).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx1, wy1, wz1).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx0, wy1, wz1).color(dr, dg, db, da).endVertex();
                    // -Z face
                    bb.vertex(wx0, wy0, wz0).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx1, wy0, wz0).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx1, wy1, wz0).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx0, wy1, wz0).color(dr, dg, db, da).endVertex();
                    // +Z face
                    bb.vertex(wx0, wy0, wz1).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx0, wy1, wz1).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx1, wy1, wz1).color(dr, dg, db, da).endVertex();
                    bb.vertex(wx1, wy0, wz1).color(dr, dg, db, da).endVertex();

                    vertCount += 24;
                }
            }
        }

        if (vertCount == 0) {
            bb.discard();
            mesh.geometryRevision = snapshot.getGeometryRevision();
            mesh.vertexCount = 0;
            mesh.interiorBitsSet = interiorCount;
            if (mesh.vertexBuffer != null) {
                mesh.vertexBuffer.close();
                mesh.vertexBuffer = null;
            }
            return;
        }

        final BufferBuilder.RenderedBuffer rendered = bb.end();
        if (mesh.vertexBuffer == null) {
            mesh.vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        mesh.vertexBuffer.bind();
        mesh.vertexBuffer.upload(rendered);
        VertexBuffer.unbind();

        mesh.geometryRevision = snapshot.getGeometryRevision();
        mesh.vertexCount = vertCount;
        mesh.interiorBitsSet = interiorCount;
        mesh.minX = minX;
        mesh.minY = minY;
        mesh.minZ = minZ;
        LOGGER.info("Built occluder mesh for ship {}: {} verts ({} cube faces)",
            mesh.shipId, vertCount, vertCount / 4);
    }
}
