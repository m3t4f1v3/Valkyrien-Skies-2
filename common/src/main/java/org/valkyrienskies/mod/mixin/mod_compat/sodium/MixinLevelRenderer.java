package org.valkyrienskies.mod.mixin.mod_compat.sodium;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.compat.sodium.SodiumCompat;
import org.valkyrienskies.mod.mixin.accessors.client.render.GameRendererAccessor;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
    @Inject(
        method = "renderLevel",
        at = @At("HEAD")
    )
    private void setUniforms(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer,
        LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci){

        Vector3f camerPos = camera.getPosition().toVector3f();
        Matrix4f localToCamera = new Matrix4f(poseStack.last().pose()).invert();
        Map<String, ShaderInstance> shaders = ((GameRendererAccessor) gameRenderer).vs$getShaders();
        for(ShaderInstance entityShader : shaders.values()) {
            if (entityShader.getUniform("u_isInWorld") == null) continue;
            entityShader.getUniform("u_VsShipEmitterCount").set(SodiumCompat.getShipEmitterList().size());
            entityShader.getUniform("u_VsShipEmitters").set(SodiumCompat.SHIP_EMITTER_LIST_TEXTURE_UNIT);
            entityShader.getUniform("u_VsLightSections").set(SodiumCompat.LIGHT_SECTIONS_TEXTURE_UNIT);
            entityShader.getUniform("u_VsLightLut").set(SodiumCompat.LIGHT_LUT_TEXTURE_UNIT);
            entityShader.getUniform("u_LocalToCameraRel").set(localToCamera);
            entityShader.getUniform("u_VsRenderOrigin").set(camerPos.x, camerPos.y, camerPos.z);
            entityShader.getUniform("u_isInWorld").set(1);
        }
    }

    @Inject(
        method = "renderLevel",
        at = @At("RETURN")
    )
    private void unsetUniform(PoseStack poseStack, float f, long l, boolean bl, Camera camera,
        GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci){
        Map<String, ShaderInstance> shaders = ((GameRendererAccessor) gameRenderer).vs$getShaders();
        for(ShaderInstance entityShader : shaders.values()) {
            if(entityShader.getUniform("u_isInWorld") == null) continue;
            entityShader.getUniform("u_isInWorld").set(0);
        }
    }
}
