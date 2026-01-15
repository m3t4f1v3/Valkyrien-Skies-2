package org.valkyrienskies.mod.mixin.feature.spawn_player_on_ship;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.networking.PacketChangeKnownShips;
import org.valkyrienskies.mod.mixinducks.feature.tickets.PlayerKnownShipsDuck;

@Mixin(Player.class)
public abstract class MixinPlayer extends LivingEntity implements PlayerKnownShipsDuck {

    @Unique
    private final LongSet vs_knownShips = new LongOpenHashSet();

    protected MixinPlayer(EntityType<? extends LivingEntity> entityType,
        Level level) {
        super(entityType, level);
    }

    @Override
    public void vs_addKnownShip(long shipId) {
        vs_knownShips.add(shipId);
        if (level().isClientSide) {
            var packet = new PacketChangeKnownShips(true, shipId);
            ValkyrienSkiesMod.getVsCore().getSimplePacketNetworking().sendToServer(packet);
        }
    }

    @Override
    public void vs_removeKnownShip(long shipId) {
        vs_knownShips.remove(shipId);
        if (level().isClientSide) {
            var packet = new PacketChangeKnownShips(false, shipId);
            ValkyrienSkiesMod.getVsCore().getSimplePacketNetworking().sendToServer(packet);
        }
    }

    @Override
    public boolean vs_isKnownShip(long shipId) {
        return vs_knownShips.contains(shipId);
    }
}
