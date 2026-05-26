package org.valkyrienskies.mod.air_pockets.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

public final class AirPocketRenderHooks {


    // copy of the translucent render type
    public static final RenderType AIR_CULL_RENDER_TYPE =
        RenderType.create("vs_air_pocket", DefaultVertexFormat.BLOCK, Mode.QUADS, 2097152, true, true,
            RenderType.translucentState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER));

    private AirPocketRenderHooks() {
    }
}
