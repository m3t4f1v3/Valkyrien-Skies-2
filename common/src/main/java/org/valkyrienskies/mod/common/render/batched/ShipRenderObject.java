package org.valkyrienskies.mod.common.render.batched;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.valkyrienskies.core.api.ships.ClientShip;

/**
public final class ShipRenderObject implements AutoCloseable {

    public final ClientShip ship;
    private final Long2ObjectMap<ShipSectionMesh> sections = new Long2ObjectOpenHashMap<>();

    private final LongOpenHashSet dirtySections = new LongOpenHashSet();
    private final LongOpenHashSet activeChunks = new LongOpenHashSet();

    private long lastActiveChunkSignature = Long.MIN_VALUE;
    private int lastActiveChunkCount = -1;
    private boolean built = false;

    private final List<BlockEntity> blockEntities = new ArrayList<>();
    private boolean blockEntitiesDirty = true;

    public ShipRenderObject(final ClientShip ship) {
        this.ship = ship;
    }

    public Long2ObjectMap<ShipSectionMesh> getSections() {
        return sections;
    }

    public List<BlockEntity> getBlockEntities(final ClientLevel level) {
        if (blockEntitiesDirty) {
            blockEntities.clear();
            ship.getActiveChunksSet().forEach((x, z) -> {
                final LevelChunk chunk = level.getChunk(x, z);
                blockEntities.addAll(chunk.getBlockEntities().values());
            });
            blockEntitiesDirty = false;
        }
        return blockEntities;
    }

    public void markSectionDirty(final int sx, final int sy, final int sz) {
        synchronized (dirtySections) {
            dirtySections.add(SectionPos.asLong(sx, sy, sz));
            dirtySections.add(SectionPos.asLong(sx - 1, sy, sz));
            dirtySections.add(SectionPos.asLong(sx + 1, sy, sz));
            dirtySections.add(SectionPos.asLong(sx, sy - 1, sz));
            dirtySections.add(SectionPos.asLong(sx, sy + 1, sz));
            dirtySections.add(SectionPos.asLong(sx, sy, sz - 1));
            dirtySections.add(SectionPos.asLong(sx, sy, sz + 1));
        }
    }

    public void ensureCompiled(final ClientLevel level, final BlockRenderDispatcher dispatcher,
        final ShipSectionCompiler compiler) {

        final long[] signature = {0L};
        final int[] count = {0};
        activeChunks.clear();
        ship.getActiveChunksSet().forEach((x, z) -> {
            final long key = ChunkPos.asLong(x, z);
            count[0]++;
            signature[0] += key * 0x9E3779B97F4A7C15L;
            signature[0] ^= Long.rotateLeft(key, 32);
            activeChunks.add(key);
        });

        final boolean structuralChange =
            !built || count[0] != lastActiveChunkCount || signature[0] != lastActiveChunkSignature;

        if (structuralChange) {
            closeSections();
            ship.getActiveChunksSet().forEach((x, z) -> {
                final LevelChunk chunk = level.getChunk(x, z);
                for (int y = level.getMinSection(); y < level.getMaxSection(); y++) {
                    final LevelChunkSection sec = chunk.getSection(level.getSectionIndexFromSectionY(y));
                    if (!sec.hasOnlyAir()) {
                        final ShipSectionMesh mesh = compiler.compile(level, dispatcher, x, y, z);
                        if (mesh != null) {
                            sections.put(SectionPos.asLong(x, y, z), mesh);
                        }
                    }
                }
            });
            synchronized (dirtySections) {
                dirtySections.clear();
            }
            lastActiveChunkSignature = signature[0];
            lastActiveChunkCount = count[0];
            built = true;
            blockEntitiesDirty = true;
            return;
        }

        final long[] dirty;
        synchronized (dirtySections) {
            if (dirtySections.isEmpty()) {
                return;
            }
            dirty = dirtySections.toLongArray();
            dirtySections.clear();
        }
        blockEntitiesDirty = true;
        for (final long secPos : dirty) {
            final int sx = SectionPos.x(secPos);
            final int sy = SectionPos.y(secPos);
            final int sz = SectionPos.z(secPos);
            final ShipSectionMesh old = sections.remove(secPos);
            if (old != null) {
                old.close();
            }
            if (activeChunks.contains(ChunkPos.asLong(sx, sz))) {
                final ShipSectionMesh mesh = compiler.compile(level, dispatcher, sx, sy, sz);
                if (mesh != null) {
                    sections.put(secPos, mesh);
                }
            }
        }
    }

    private void closeSections() {
        for (final ShipSectionMesh mesh : sections.values()) {
            mesh.close();
        }
        sections.clear();
    }

    @Override
    public void close() {
        closeSections();
    }
}
