package org.valkyrienskies.mod.compat.sodium;

import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.gl.shader.GlShader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderType;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkStatus;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkFogMode;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderBindingPoints;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.hooks.VSGameEvents;
import org.valkyrienskies.mod.common.hooks.VSGameEvents.ShipRenderEventSodium;
import org.valkyrienskies.mod.compat.VSRenderer;
import org.valkyrienskies.mod.mixin.ValkyrienCommonMixinConfigPlugin;
import org.valkyrienskies.mod.mixin.mod_compat.sodium.RenderSectionManagerAccessor;
import org.valkyrienskies.mod.mixin.mod_compat.sodium.ShaderChunkRendererAccessor;
import org.valkyrienskies.mod.mixinducks.mod_compat.sodium.RenderSectionManagerDuck;

import com.mojang.blaze3d.systems.RenderSystem;

public class SodiumCompat {
    static Map<ChunkShaderOptions, GlProgram<ShipThing>> cachedPrograms = new HashMap<>();
    private static final ThreadLocal<Matrix4f> CURRENT_ROTATION = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IS_RENDERING_SHIP = ThreadLocal.withInitial(() -> false);

    public static GlProgram<ChunkShaderInterface> getOrCreateShipProgram(ChunkShaderOptions options) {
        GlProgram<ShipThing> program = cachedPrograms.get(options);
        if (program == null) {
            program = createShader("blocks/block_layer_opaque", options);
            cachedPrograms.put(options, program);
        }
        return (GlProgram<ChunkShaderInterface>) (Object) program;
    }

    public static void setupShipShaderState(GlProgram<ChunkShaderInterface> program, ChunkRenderMatrices matrices, Matrix4fc rotationMatrix) {
        ShipThing shipInterface = (ShipThing) program.getInterface();
        shipInterface.setupState();
        // Set projection and model-view matrices
        shipInterface.setProjectionMatrix(matrices.projection());
        shipInterface.setModelViewMatrix(matrices.modelView());
        // Set rotation matrix (identity if not provided)
        shipInterface.setRotationMatrix(rotationMatrix != null ? rotationMatrix : new Matrix4f().identity());
        // shipInterface.setNormalMatrix(matrices.modelView().invert(new Matrix4f()).transpose());
    }

    /** Stores rotation for the next render() call on the current thread. */
    public static void pushRotation(Matrix4f rotation) {
        CURRENT_ROTATION.set(rotation);
    }

    /** Retrieves and clears the stored rotation for this thread. */
    public static Matrix4f popRotation() {
        Matrix4f rot = CURRENT_ROTATION.get();
        CURRENT_ROTATION.remove();
        return rot;
    }

    public static boolean isRenderingShip() {
        return IS_RENDERING_SHIP.get();
    }

    public static void onChunkAdded(final ClientLevel level, final int x, final int z) {
        if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() == VSRenderer.SODIUM) {
            ChunkTrackerHolder.get(level).onChunkStatusAdded(x, z, ChunkStatus.FLAG_HAS_BLOCK_DATA);
        }
    }

    public static void onChunkRemoved(final ClientLevel level, final int x, final int z) {
        if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() == VSRenderer.SODIUM) {
            ChunkTrackerHolder.get(level).onChunkStatusRemoved(x, z, ChunkStatus.FLAG_HAS_BLOCK_DATA);
        }
    }

    public static void vsRenderLayer(RenderSectionManager renderSectionManager, ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z,
            CommandList commandList) {

        VSGameEvents.INSTANCE.getShipsStartRenderingSodium().emit(new VSGameEvents.ShipStartRenderEventSodium(
            pass, matrices, x, y, z
        ));

        ((RenderSectionManagerDuck) renderSectionManager).vs_getShipRenderLists().forEach((ship, renderList) -> {
            VSGameEvents.INSTANCE.getRenderShipSodium().emit(new ShipRenderEventSodium(pass, matrices, x, y, z, ship, renderList));
            final ShipTransform shipTransform = ship.getRenderTransform();

            final float distanceScaling = 1 / (float) shipTransform.getShipToWorldScaling().x();
            final float initialFogStart = RenderSystem.getShaderFogStart();
            final float initialFogEnd = RenderSystem.getShaderFogEnd();

            if (distanceScaling != 1f) {
                RenderSystem.setShaderFogStart(initialFogStart * distanceScaling);
                RenderSystem.setShaderFogEnd(initialFogEnd * distanceScaling);
            }

            final Vector3dc cameraShipSpace = shipTransform.getWorldToShip().transformPosition(new Vector3d(x, y, z));
            final Matrix4dc s = ship.getRenderTransform().getShipToWorld();
            final Matrix4d newModelView = new Matrix4d(matrices.modelView())
                .translate(-x, -y, -z)
                .mul(s)
                .translate(cameraShipSpace);

            final ChunkRenderMatrices newMatrices =
                new ChunkRenderMatrices(matrices.projection(), new Matrix4f(newModelView));
            DefaultChunkRenderer chunkRenderer = (DefaultChunkRenderer) ((RenderSectionManagerAccessor) renderSectionManager).getChunkRenderer();
            
            // Extract rotation matrix from ship-to-world transform (3x3 upper-left)
            Matrix4f shipRotation = new Matrix4f(
                (float) s.m00(), (float) s.m01(), (float) s.m02(), 0,
                (float) s.m10(), (float) s.m11(), (float) s.m12(), 0,
                (float) s.m20(), (float) s.m21(), (float) s.m22(), 0,
                0, 0, 0, 1
            );

            // Stash rotation for the mixin's redirected begin() to consume
            pushRotation(shipRotation);
            IS_RENDERING_SHIP.set(true);

            chunkRenderer.render(newMatrices, commandList, renderList, pass,
                new CameraTransform(cameraShipSpace.x(), cameraShipSpace.y(), cameraShipSpace.z()));
            commandList.close();
            IS_RENDERING_SHIP.set(false);

             if (distanceScaling != 1f) {
                RenderSystem.setShaderFogStart(initialFogStart);
                RenderSystem.setShaderFogEnd(initialFogEnd);
            }

            VSGameEvents.INSTANCE.getPostRenderShipSodium().emit(new ShipRenderEventSodium(pass, matrices, x, y, z, ship, renderList));
        });
    }


    public static void renderShips(RenderSectionManager renderSectionManager, RenderType renderLayer, ChunkRenderMatrices matrices, double x, double y, double z) {
        RenderDevice device = RenderDevice.INSTANCE;
        CommandList commandList = device.createCommandList();

        if (renderLayer == RenderType.solid()) {
            vsRenderLayer(renderSectionManager, matrices, DefaultTerrainRenderPasses.SOLID, x, y, z, commandList);
            vsRenderLayer(renderSectionManager, matrices, DefaultTerrainRenderPasses.CUTOUT, x, y, z, commandList);
        } else if (renderLayer == RenderType.translucent()) {
            vsRenderLayer(renderSectionManager, matrices, DefaultTerrainRenderPasses.TRANSLUCENT, x, y, z, commandList);
        }

        commandList.close();
    }

    private static GlProgram<ShipThing> createShader(String path, ChunkShaderOptions options) {
        ShaderConstants constants = createShipShaderConstants(options);

        GlShader vertShader = ShaderLoader.loadShader(ShaderType.VERTEX,
                new ResourceLocation("valkyrienskies", path + ".vsh"), constants);
        
        GlShader fragShader = ShaderLoader.loadShader(ShaderType.FRAGMENT,
                new ResourceLocation("valkyrienskies", path + ".fsh"), constants);

        try {
            return GlProgram.builder(new ResourceLocation("valkyrienskies", "chunk_shader"))
                    .attachShader(vertShader)
                    .attachShader(fragShader)
                    .bindAttribute("a_Position", ChunkShaderBindingPoints.ATTRIBUTE_POSITION)
                    .bindAttribute("a_Color", ChunkShaderBindingPoints.ATTRIBUTE_COLOR)
                    .bindAttribute("a_TexCoord", ChunkShaderBindingPoints.ATTRIBUTE_TEXTURE)
                    .bindAttribute("a_LightAndData", ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_MATERIAL_INDEX)
                    .bindFragmentData("fragColor", ChunkShaderBindingPoints.FRAG_COLOR)
                    .link((shader) -> new ShipThing(shader, options));
        } finally {
            vertShader.delete();
            fragShader.delete();
        }
    }

    private static ShaderConstants createShipShaderConstants(ChunkShaderOptions options) {
        ShaderConstants.Builder builder = ShaderConstants.builder();

        for (String define : options.constants().getDefineStrings()) {
            String[] parts = define.split("\\s+", 3);
            if (parts.length < 2 || !"#define".equals(parts[0])) {
                throw new IllegalArgumentException("Unexpected shader define format: " + define);
            }

            String name = parts[1];
            if ("USE_VANILLA_COLOR_FORMAT".equals(name)) {
                continue;
            }

            if (parts.length == 2) {
                builder.add(name);
            } else {
                builder.add(name, parts[2]);
            }
        }

        return builder.build();
    }
}
