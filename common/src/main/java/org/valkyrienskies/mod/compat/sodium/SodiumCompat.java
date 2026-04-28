package org.valkyrienskies.mod.compat.sodium;

import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.gl.shader.GlShader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderType;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkStatus;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderBindingPoints;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
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
import org.valkyrienskies.mod.compat.sodium.light.VsShipLightStorage;
import org.valkyrienskies.mod.mixin.ValkyrienCommonMixinConfigPlugin;
import org.valkyrienskies.mod.mixin.mod_compat.sodium.RenderSectionManagerAccessor;
import org.valkyrienskies.mod.mixinducks.mod_compat.sodium.RenderSectionManagerDuck;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.joml.primitives.AABBdc;

import com.mojang.blaze3d.systems.RenderSystem;

public class SodiumCompat {
    static Map<ChunkShaderOptions, GlProgram<ShipThing>> cachedPrograms = new HashMap<>();
    private static final ThreadLocal<Matrix4f> CURRENT_TRANSFORM = new ThreadLocal<>();
    private static final ThreadLocal<Matrix4f> CURRENT_LOCAL_TO_WORLD = new ThreadLocal<>();
    private static final ThreadLocal<int[]> CURRENT_RENDER_ORIGIN = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IS_RENDERING_SHIP = ThreadLocal.withInitial(() -> false);

    // Texture units used for the ship light buffer textures.
    // Sodium uses unit 0 for the block atlas and 2 for the lightmap (LIGHT_TEXTURE_TARGET).
    // Pick free units past those.
    public static final int LIGHT_SECTIONS_TEXTURE_UNIT = 6;
    public static final int LIGHT_LUT_TEXTURE_UNIT = 7;

    private static VsShipLightStorage lightStorage;

    public static VsShipLightStorage getLightStorage() {
        if (lightStorage == null) {
            lightStorage = new VsShipLightStorage();
        }
        return lightStorage;
    }

    public static GlProgram<ChunkShaderInterface> getOrCreateShipProgram(ChunkShaderOptions options) {
        GlProgram<ShipThing> program = cachedPrograms.get(options);
        if (program == null) {
            program = createShader("blocks/block_layer_opaque", options);
            cachedPrograms.put(options, program);
        }
        return (GlProgram<ChunkShaderInterface>) (Object) program;
    }

    public static void setupShipShaderState(GlProgram<ChunkShaderInterface> program, ChunkRenderMatrices matrices, Matrix4fc transformMatrix) {
        ShipThing shipInterface = (ShipThing) program.getInterface();
        shipInterface.setupState();
        // Set projection and model-view matrices
        shipInterface.setProjectionMatrix(matrices.projection());
        shipInterface.setModelViewMatrix(matrices.modelView());
        // Set transform matrix (identity if not provided)
        shipInterface.setTransformMatrix(transformMatrix != null ? transformMatrix : new Matrix4f().identity());
        // Local-to-world maps the ship-local vertex space (after sodium's chunk
        // translation) into absolute world block coordinates so the shader can
        // look up world-space block/sky lighting.
        Matrix4f localToWorld = CURRENT_LOCAL_TO_WORLD.get();
        shipInterface.setLocalToWorldMatrix(localToWorld != null ? localToWorld : new Matrix4f().identity());
        int[] origin = CURRENT_RENDER_ORIGIN.get();
        if (origin != null) {
            shipInterface.setRenderOrigin(origin[0], origin[1], origin[2]);
        } else {
            shipInterface.setRenderOrigin(0, 0, 0);
        }
        shipInterface.setLightSectionsSampler(LIGHT_SECTIONS_TEXTURE_UNIT);
        shipInterface.setLightLutSampler(LIGHT_LUT_TEXTURE_UNIT);
    }

    /** Stores transform for the next render() call on the current thread. */
    public static void pushTransform(Matrix4f transform) {
        CURRENT_TRANSFORM.set(transform);
    }

    /** Retrieves and clears the stored transform for this thread. */
    public static Matrix4f popTransform() {
        Matrix4f transform = CURRENT_TRANSFORM.get();
        CURRENT_TRANSFORM.remove();
        return transform;
    }

    public static void pushLocalToWorld(Matrix4f m) {
        CURRENT_LOCAL_TO_WORLD.set(m);
    }

    public static void pushRenderOrigin(int x, int y, int z) {
        CURRENT_RENDER_ORIGIN.set(new int[] { x, y, z });
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

        // Refresh the world-light buffer for any sections occupied by the ships we are about to render.
        final ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        final VsShipLightStorage storage = getLightStorage();
        if (level != null) {
            storage.beginFrame();
            ((RenderSectionManagerDuck) renderSectionManager).vs_getShipRenderLists().forEach((ship, renderList) -> {
                final AABBdc aabb = ((ClientShip) ship).getRenderAABB();
                if (aabb != null) {
                    storage.requestSectionsInAabb(level,
                            aabb.minX(), aabb.minY(), aabb.minZ(),
                            aabb.maxX(), aabb.maxY(), aabb.maxZ());
                }
            });
            storage.pruneUnused();
            storage.upload();
        }

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

            // Build a precision-friendly matrix that maps a sodium-chunk-local vertex
            // pos to (worldPos - renderOrigin), where renderOrigin is the integer
            // camera world block position. Combined with the ivec3 renderOrigin
            // uniform, the shader can reconstruct an exact world block pos for the
            // flywheel-style light fetch.
            //
            // We want `M * p = worldPos - origin = S*(p + cameraShipSpace) - origin`.
            // To stay precision-friendly when origin can be ~30M, we build:
            //   T(camera-origin) * T(-camera) * S * T(cameraShipSpace)
            // = T(-origin) * S * T(cameraShipSpace)
            // where the FINAL translation column equals (cameraWorld - origin) ~= frac
            // and is therefore safe to truncate to float.
            final int originX = (int) Math.floor(x);
            final int originY = (int) Math.floor(y);
            final int originZ = (int) Math.floor(z);
            final Matrix4d localToCameraRel = new Matrix4d()
                .translate(x - originX, y - originY, z - originZ)
                .translate(-x, -y, -z)
                .mul(s)
                .translate(cameraShipSpace);

            final ChunkRenderMatrices newMatrices =
                new ChunkRenderMatrices(matrices.projection(), new Matrix4f(newModelView));
            DefaultChunkRenderer chunkRenderer = (DefaultChunkRenderer) ((RenderSectionManagerAccessor) renderSectionManager).getChunkRenderer();

            // Stash uniforms for the mixin's redirected begin() to consume
            pushTransform(new Matrix4f(s));
            pushLocalToWorld(new Matrix4f(localToCameraRel));
            pushRenderOrigin(originX, originY, originZ);
            IS_RENDERING_SHIP.set(true);

            // Bind the world-light buffer textures so the ship shader can sample them.
            storage.bind(LIGHT_SECTIONS_TEXTURE_UNIT, LIGHT_LUT_TEXTURE_UNIT);

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
