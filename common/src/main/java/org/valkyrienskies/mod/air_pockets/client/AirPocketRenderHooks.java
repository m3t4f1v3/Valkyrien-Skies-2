package org.valkyrienskies.mod.air_pockets.client;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.RenderType;

public final class AirPocketRenderHooks {

    public static final TerrainRenderPass AIR_POCKET_PASS =
            new TerrainRenderPass(RenderType.tripwire(), true, false);

    private AirPocketRenderHooks() {}
}