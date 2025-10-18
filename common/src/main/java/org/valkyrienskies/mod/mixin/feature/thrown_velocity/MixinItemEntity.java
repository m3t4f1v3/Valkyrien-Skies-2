package org.valkyrienskies.mod.mixin.feature.thrown_velocity;

import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.api.ValkyrienSkies;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

@Mixin(ItemEntity.class)
public abstract class MixinItemEntity extends Entity implements TraceableEntity {

    @Shadow
    public abstract @Nullable Entity getOwner();

    @Shadow
    private @Nullable UUID thrower;

    public MixinItemEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    /**
     * If the item entity was thrown by someone, add the ship's velocity, and set it to be dragged.
     */
    @Inject(
        method = "tick",
        at = @At("HEAD")
    )
    private void preFirstTick(CallbackInfo ci) {
        Entity owner = getOwner();
        if(!firstTick || owner == null) return;
        Ship ship = VSGameUtilsKt.getShipMountedTo(owner);
        EntityDraggingInformation info = ((IEntityDraggingInformationProvider)owner).getDraggingInformation();
        if (ship == null && info.isEntityBeingDraggedByAShip()) {
            ship = ValkyrienSkies.getShipById(level(), info.getLastShipStoodOn());
        }
        if (ship != null) {
            VSGameUtilsKt.applyShipVelocity(this, ship);
        }
    }
}
