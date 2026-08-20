package org.valkyrienskies.mod.fabric.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.valkyrienskies.mod.compat.LoadedMods;
import org.valkyrienskies.mod.compat.SodiumGeneration;
import org.valkyrienskies.mod.mixin.ValkyrienCommonMixinConfigPlugin;

public class ValkyrienSkiesFabricMixinPlugin implements IMixinConfigPlugin {

    private static boolean classExists(final String className) {
        try {
            Class.forName(className, false, ValkyrienSkiesFabricMixinPlugin.class.getClassLoader());
            return true;
        } catch (final ClassNotFoundException ex) {
            return false;
        }
    }

    @Override
    public void onLoad(final String s) {

    }

    @Override
    public String getRefMapperConfig() {
        return "";
    }

    @Override
    public boolean shouldApplyMixin(final String s, final String mixinClassName) {
        final boolean isMixinBoosterLoaded = classExists("io.github.steelwoolmc.mixintransmog.MixinModlauncherRemapper");

        if (mixinClassName.contains("org.valkyrienskies.mod.fabric.mixin.compat.old_create")) {
            return LoadedMods.getOldCreate();
        }

        // These target me.jellysquid classes; Sodium 0.9's equivalents are handled by the common
        // sodium09 mixins instead.
        if (mixinClassName.contains("org.valkyrienskies.mod.fabric.mixin.compat.sodium")) {
            return ValkyrienCommonMixinConfigPlugin.getSodiumGeneration() == SodiumGeneration.LEGACY;
        }

        return true;
    }

    @Override
    public void acceptTargets(final Set<String> set, final Set<String> set1) {

    }

    @Override
    public List<String> getMixins() {
        return List.of();
    }

    @Override
    public void preApply(final String s, final ClassNode classNode, final String s1, final IMixinInfo iMixinInfo) {

    }

    @Override
    public void postApply(final String s, final ClassNode classNode, final String s1, final IMixinInfo iMixinInfo) {

    }
}
