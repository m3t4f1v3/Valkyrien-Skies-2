package org.valkyrienskies.mod.mixin.mod_compat.sodium;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.mixin.accessors.client.render.GameRendererAccessor;

@Mixin(ItemInHandRenderer.class)
public class MixinItemInHandRenderer {
    @Inject(
        method = "renderHandsWithItems",
        at = @At("HEAD")
    )
    private void setInWorld(float f, PoseStack poseStack, BufferSource bufferSource, LocalPlayer localPlayer, int i,
        CallbackInfo ci){
        Map<String, ShaderInstance> shaders = ((GameRendererAccessor) Minecraft.getInstance().gameRenderer).vs$getShaders();
        for(ShaderInstance entityShader : shaders.values()) {
            if(entityShader.getUniform("u_isInWorld") == null) continue;
            entityShader.getUniform("u_isInWorld").set(1);
        }
    }

    @Inject(
        method = "renderHandsWithItems",
        at = @At("RETURN")
    )
    private void unsetInWorld(float f, PoseStack poseStack, BufferSource bufferSource, LocalPlayer localPlayer, int i,
        CallbackInfo ci){
        Map<String, ShaderInstance> shaders = ((GameRendererAccessor) Minecraft.getInstance().gameRenderer).vs$getShaders();
        for(ShaderInstance entityShader : shaders.values()) {
            if(entityShader.getUniform("u_isInWorld") == null) continue;
            entityShader.getUniform("u_isInWorld").set(0);
        }
    }
}
