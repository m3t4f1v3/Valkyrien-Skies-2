package net.caffeinemc.mods.sodium.api.vertex.serializer;

import net.caffeinemc.mods.sodium.api.internal.DependencyInjection;
import net.minecraft.class_293;

public interface VertexSerializerRegistry {
    VertexSerializerRegistry INSTANCE = DependencyInjection.load(VertexSerializerRegistry.class,
            "net.caffeinemc.mods.sodium.client.render.vertex.serializers.VertexSerializerRegistryImpl");

    static VertexSerializerRegistry instance() {
        return INSTANCE;
    }

    VertexSerializer get(class_293 srcFormat, class_293 dstFormat);

    void registerSerializer(class_293 srcFormat, class_293 dstFormat, VertexSerializer serializer);
}
