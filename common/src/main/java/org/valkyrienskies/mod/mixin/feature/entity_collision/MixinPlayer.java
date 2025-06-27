package org.valkyrienskies.mod.mixin.feature.entity_collision;

import static org.valkyrienskies.mod.common.util.EntityDragger.backOff;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.world.ClientShipWorld;
import org.valkyrienskies.mod.api.ValkyrienSkies;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

@Mixin(Player.class)
public abstract class MixinPlayer implements IEntityDraggingInformationProvider {

    @Shadow
    protected abstract boolean isStayingOnGroundSurface();

    // Allow players to crouch walk on ships
    @Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
    private void preMaybeBackOffFromEdge(final Vec3 vec3, final MoverType moverType,
        final CallbackInfoReturnable<Vec3> callbackInfoReturnable) {
        if (getDraggingInformation().isEntityBeingDraggedByAShip() && getDraggingInformation().getLastShipStoodOn() != null) {
            Player player = (Player) (Object) this;
            Level level = player.level();
            ClientLevel cLevel = level instanceof ClientLevel ? (ClientLevel) level : null;
            if (level != null && cLevel != null && level.isClientSide) {
                ClientShipWorld shipWorld = ValkyrienSkies.api().getClientShipWorld(Minecraft.getInstance());
                if (shipWorld == null) {
                    return;
                }
                ClientShip ship = shipWorld.getLoadedShips().getById(
                    getDraggingInformation().getLastShipStoodOn());
                if (ship == null) {
                    return;
                }
                if (vec3.y <= 0.0f && (moverType == MoverType.SELF || moverType == MoverType.PLAYER) && this.isStayingOnGroundSurface()) {
                    Vec3 adjustedVec = backOff(vec3, ship, player, cLevel);

                    callbackInfoReturnable.setReturnValue(adjustedVec);
                }
            }
        }
    }
}
