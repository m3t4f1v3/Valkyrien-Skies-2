package org.valkyrienskies.mod.mixin.mod_compat.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.Resource;
import org.apache.commons.io.IOUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.common.config.VSGameConfig;

@Mixin(ShaderInstance.class)
public class MixinShaderInstance {
    @WrapOperation(
        method = "getOrCreate",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/resources/Resource;open()Ljava/io/InputStream;")
    )
    private static InputStream streamWithConstants(Resource resource, Operation<InputStream> original, @Local(argsOnly = true) String name)
        throws IOException {
        InputStream inputstream = original.call(resource);
        if(!name.contains("entity")) return inputstream;
        String shader = IOUtils.toString(inputstream, StandardCharsets.UTF_8);
        ArrayList<String> shaderLines = new ArrayList<>(shader.lines().toList());
        if (VSGameConfig.CLIENT.getDynamicShipBiomeTinting()) shaderLines.add(1, "#define VS_DYNAMIC_BIOME");
        if (VSGameConfig.CLIENT.getDynamicShipLighting()) shaderLines.add(1, "#define VS_DYNAMIC_LIGHT");
        if (VSGameConfig.CLIENT.getBetterVanillaShipShading()) shaderLines.add(1, "#define VS_DYNAMIC_SHADE");
        if (VSGameConfig.CLIENT.getDynamicShipToWorldLighting()) shaderLines.add(1, "#define VS_SHIP_ON_SHIP");
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        for(String line : shaderLines) {
            byteArrayOutputStream.writeBytes(line.concat("\r\n").getBytes(StandardCharsets.UTF_8));
        }
        return new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
    }
}
