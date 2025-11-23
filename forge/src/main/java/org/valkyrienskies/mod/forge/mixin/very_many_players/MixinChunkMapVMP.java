package org.valkyrienskies.mod.forge.mixin.very_many_players;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.valkyrienskies.core.internal.world.VsiPlayer;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.MinecraftPlayer;

@Mixin(ChunkMap.class)
public class MixinChunkMapVMP {

    @Shadow
    @Final
    private ServerLevel level;

    @TargetHandler(
        mixin = "com.ishland.vmp.mixins.playerwatching.MixinThreadedAnvilChunkStorage",
        name = "m_183262_"
    )
    @WrapMethod(
        method = "@MixinSquared:handler"
    )
    private List<ServerPlayer> addPlayerToVS_0(ChunkPos chunkPos, boolean onlyOnWatchDistanceEdge, Operation<List<ServerPlayer>> original){
        List<ServerPlayer> result = new ArrayList<>(original.call(chunkPos, onlyOnWatchDistanceEdge));
        Iterator<VsiPlayer> vsPlayersWatching = VSGameUtilsKt.getShipObjectWorld(level).getIPlayersWatchingShipChunk(chunkPos.x, chunkPos.z, VSGameUtilsKt.getDimensionId(level));
        vsPlayersWatching.forEachRemaining(
            iPlayer -> {
                result.add((ServerPlayer) ((MinecraftPlayer) iPlayer).getPlayer());
            }
        );
        return result;
    }

    @TargetHandler(
        mixin = "com.ishland.vmp.mixins.playerwatching.MixinThreadedAnvilChunkStorage",
        name = "m_183888_"
    )
    @WrapMethod(
        method = "@MixinSquared:handler"
    )
    private List<ServerPlayer> addPlayerToVS_1(ChunkPos chunkPos, Operation<List<ServerPlayer>> original){
        List<ServerPlayer> result = new ArrayList<>(original.call(chunkPos));
        Iterator<VsiPlayer> vsPlayersWatching = VSGameUtilsKt.getShipObjectWorld(level).getIPlayersWatchingShipChunk(chunkPos.x, chunkPos.z, VSGameUtilsKt.getDimensionId(level));
        vsPlayersWatching.forEachRemaining(
            iPlayer -> {
                result.add((ServerPlayer) ((MinecraftPlayer) iPlayer).getPlayer());
            }
        );
        return result;
    }


    @TargetHandler(
        mixin = "com.ishland.vmp.mixins.playerwatching.MixinThreadedAnvilChunkStorage",
        name = "m_183879_"
    )
    @WrapMethod(
        method = "@MixinSquared:handler"
    )
    private boolean isPlayerNearVS(ChunkPos chunkPos, Operation<Boolean> original){
        boolean result = original.call(chunkPos);
        if (result) return true;
        Iterator<VsiPlayer> vsPlayersWatching = VSGameUtilsKt.getShipObjectWorld(level).getIPlayersWatchingShipChunk(chunkPos.x, chunkPos.z, VSGameUtilsKt.getDimensionId(level));
        return vsPlayersWatching.hasNext();
    }
}
