package org.valkyrienskies.mod.common

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import java.util.function.Supplier

class VSRenderTypes(
    name: String,
    format: VertexFormat,
    mode: VertexFormat.Mode,
    bufferSize: Int,
    affectsCrumbling: Boolean,
    sortOnUpload: Boolean,
    setupState: Runnable,
    clearState: Runnable
) : RenderType(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState) {
    companion object {
        var shipSolidShader: ShaderInstance? = null
        var shipCutoutMippedShader: ShaderInstance? = null
        var shipCutoutShader: ShaderInstance? = null

        private val RENDERTYPE_SHIP_SOLID_SHADER: ShaderStateShard = ShaderStateShard(Supplier { shipSolidShader })
        private val RENDERTYPE_SHIP_CUTOUT_MIPPED_SHADER: ShaderStateShard = ShaderStateShard(Supplier { shipCutoutMippedShader })
        private val RENDERTYPE_SHIP_CUTOUT_SHADER: ShaderStateShard = ShaderStateShard(Supplier { shipCutoutShader })

        val SHIP_SOLID = create(
            ValkyrienSkiesMod.MOD_ID + "ship_solid",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            2097152,
            true,
            false,
            CompositeState.builder()
                .setLightmapState(LIGHTMAP)
                .setShaderState(RENDERTYPE_SHIP_SOLID_SHADER)
                .setTextureState(BLOCK_SHEET_MIPPED)
                .createCompositeState(true)
        );
        val SHIP_CUTOUT_MIPPED: RenderType = create(
            ValkyrienSkiesMod.MOD_ID + "ship_cutout_mipped",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            131072,
            true,
            false,
            CompositeState.builder()
                .setLightmapState(LIGHTMAP)
                .setShaderState(RENDERTYPE_SHIP_CUTOUT_MIPPED_SHADER)
                .setTextureState(BLOCK_SHEET_MIPPED)
                .createCompositeState(true)
        )
        val SHIP_CUTOUT: RenderType = create(
            ValkyrienSkiesMod.MOD_ID + "ship_cutout",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            131072,
            true,
            false,
            CompositeState.builder()
                .setLightmapState(LIGHTMAP)
                .setShaderState(RENDERTYPE_SHIP_CUTOUT_SHADER)
                .setTextureState(BLOCK_SHEET)
                .createCompositeState(true)
        )

        fun shipShaderFor(renderType: RenderType): ShaderInstance? {
            return when (renderType) {
                RenderType.solid() -> shipSolidShader
                RenderType.cutoutMipped() -> shipCutoutMippedShader
                RenderType.cutout() -> shipCutoutShader
                else -> null
            }
        }
    }
}
