package org.valkyrienskies.mod.mixin.feature.air_pockets.compat.vs2;

import org.joml.Vector3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.util.BuoyancyHandlerAttachment;
import org.valkyrienskies.mod.mixinducks.feature.air_pockets.compat.vs2.ValkyrienAirBuoyancyAttachmentDuck;

@Mixin(value = BuoyancyHandlerAttachment.class, remap = false)
public abstract class MixinBuoyancyHandlerAttachment implements ValkyrienAirBuoyancyAttachmentDuck {

    @Unique
    private static final Logger valkyrienair$LOGGER = LogManager.getLogger("ValkyrienAir Buoyancy");

    @Unique
    private static final double valkyrienair$WATER_DENSITY = 1000.0;

    @Unique
    private static final double valkyrienair$GRAVITY_MAGNITUDE = 10.0;

    @Unique
    private static final double valkyrienair$MAX_POCKET_BUOYANCY_WEIGHT_MULT = 1.0;

    @Unique
    private static final double valkyrienair$DEFAULT_FLUID_VISCOSITY = 1000.0;

    @Unique
    private static final double valkyrienair$OVERLAP_EPS = 1.0e-6;

    @Unique
    private static final double valkyrienair$SMOOTH_DISPLACED_ALPHA = 0.35;

    @Unique
    private static final double valkyrienair$SMOOTH_CENTER_ALPHA = 0.25;

    @Unique
    private static final double valkyrienair$MAX_FORCE_SLEW_G_PER_TICK = 0.35;

    @Unique
    private static final double valkyrienair$MAX_BALANCED_LEVER_ARM = 32.0;

    @Unique
    private static final double valkyrienair$MIN_COM_BLEND = 0.55;

    @Unique
    private static final double valkyrienair$MAX_DAMPING_FORCE_MULT = 3.0;

    @Unique
    private volatile double valkyrienair$displacedVolume = 0.0;

    @Unique
    private volatile boolean valkyrienair$hasPocketCenter = false;

    @Unique
    private volatile double valkyrienair$buoyancyFluidDensity = valkyrienair$WATER_DENSITY;

    @Unique
    private volatile double valkyrienair$buoyancyFluidViscosity = valkyrienair$DEFAULT_FLUID_VISCOSITY;

    @Unique
    private volatile double valkyrienair$pocketCenterX = 0.0;

    @Unique
    private volatile double valkyrienair$pocketCenterY = 0.0;

    @Unique
    private volatile double valkyrienair$pocketCenterZ = 0.0;

    @Unique
    private final Vector3d valkyrienair$tmpForce = new Vector3d();

    @Unique
    private final Vector3d valkyrienair$tmpPos = new Vector3d();

    @Unique
    private double valkyrienair$smoothedDisplacedVolume = 0.0;

    @Unique
    private boolean valkyrienair$hasSmoothedCenter = false;

    @Unique
    private double valkyrienair$smoothedCenterX = 0.0;

    @Unique
    private double valkyrienair$smoothedCenterY = 0.0;

    @Unique
    private double valkyrienair$smoothedCenterZ = 0.0;

    @Unique
    private double valkyrienair$lastAppliedBuoyancyForce = 0.0;

    @Unique
    private long valkyrienair$diagOverlapClampCount = 0L;

    @Unique
    private long valkyrienair$diagForceSlewClampCount = 0L;

    @Unique
    private long valkyrienair$diagLeverClampCount = 0L;

    @Unique
    private long valkyrienair$diagDampingClampCount = 0L;

    @Override
    public double valkyrienair$getDisplacedVolume() {
        return valkyrienair$displacedVolume;
    }

    @Override
    public void valkyrienair$setDisplacedVolume(final double volume) {
        valkyrienair$displacedVolume = volume;
    }

    @Override
    public boolean valkyrienair$hasPocketCenter() {
        return valkyrienair$hasPocketCenter;
    }

    @Override
    public double valkyrienair$getPocketCenterX() {
        return valkyrienair$pocketCenterX;
    }

    @Override
    public double valkyrienair$getPocketCenterY() {
        return valkyrienair$pocketCenterY;
    }

    @Override
    public double valkyrienair$getPocketCenterZ() {
        return valkyrienair$pocketCenterZ;
    }

    @Override
    public void valkyrienair$setPocketCenter(final double x, final double y, final double z) {
        valkyrienair$pocketCenterX = x;
        valkyrienair$pocketCenterY = y;
        valkyrienair$pocketCenterZ = z;
        valkyrienair$hasPocketCenter = true;
    }

    @Override
    public double valkyrienair$getBuoyancyFluidDensity() {
        return valkyrienair$buoyancyFluidDensity;
    }

    @Override
    public double valkyrienair$getBuoyancyFluidViscosity() {
        return valkyrienair$buoyancyFluidViscosity;
    }

    @Override
    public void valkyrienair$setBuoyancyFluidDensity(final double density) {
        valkyrienair$buoyancyFluidDensity = density;
    }

    @Override
    public void valkyrienair$setBuoyancyFluidViscosity(final double viscosity) {
        valkyrienair$buoyancyFluidViscosity = viscosity;
    }

    @Inject(method = "physTick", at = @At("HEAD"), cancellable = true)
    private void valkyrienair$disableVs2PocketBuoyancy(final PhysShip physShip, final PhysLevel physLevel,
        final CallbackInfo ci) {
        if (!VSGameConfig.COMMON.getEnableAirPockets()) return;

        // Disable VS2's experimental pocket buoyancy and apply Valkyrien-Air's own buoyancy forces instead.
        ci.cancel();

        // Reset VS2's buoyancy scaling to a stable baseline (VS2's pocket buoyancy works by inflating this value).
        // Setting this to 0.0 can destabilize physics, so we keep the default scale and apply our own pocket forces.
        physShip.setBuoyantFactor(1.0);

        if (!valkyrienair$hasPocketCenter) {
            valkyrienair$lastAppliedBuoyancyForce = 0.0;
            valkyrienair$smoothedDisplacedVolume = 0.0;
            valkyrienair$hasSmoothedCenter = false;
            return;
        }

        final double displacedRaw = valkyrienair$displacedVolume;
        if (!Double.isFinite(displacedRaw) || displacedRaw <= valkyrienair$OVERLAP_EPS) {
            valkyrienair$smoothedDisplacedVolume *= 0.75;
            if (valkyrienair$smoothedDisplacedVolume <= valkyrienair$OVERLAP_EPS) {
                valkyrienair$smoothedDisplacedVolume = 0.0;
            }
            valkyrienair$lastAppliedBuoyancyForce = 0.0;
            return;
        }

        final double overlapRaw = physShip.getLiquidOverlap();
        if (!Double.isFinite(overlapRaw)) return;
        final double overlap = clamp(overlapRaw, 0.0, 1.0);
        if (Math.abs(overlap - overlapRaw) > 1.0e-9) {
            valkyrienair$logClamp("overlap", overlapRaw, overlap);
        }
        if (overlap <= valkyrienair$OVERLAP_EPS) {
            valkyrienair$lastAppliedBuoyancyForce = 0.0;
            return;
        }

        final double mass = physShip.getMass();
        if (!Double.isFinite(mass) || mass <= valkyrienair$OVERLAP_EPS) return;

        double density = valkyrienair$buoyancyFluidDensity;
        if (!Double.isFinite(density) || density <= 0.0) density = valkyrienair$WATER_DENSITY;
        density = Math.max(100.0, Math.min(density, 20_000.0));

        valkyrienair$smoothedDisplacedVolume +=
            (Math.max(0.0, displacedRaw) - valkyrienair$smoothedDisplacedVolume) * valkyrienair$SMOOTH_DISPLACED_ALPHA;
        final double displaced = Math.max(0.0, valkyrienair$smoothedDisplacedVolume);
        if (!Double.isFinite(displaced) || displaced <= valkyrienair$OVERLAP_EPS) {
            valkyrienair$lastAppliedBuoyancyForce = 0.0;
            return;
        }

        final var com = physShip.getCenterOfMass();
        final double comX = com.x();
        final double comY = com.y();
        final double comZ = com.z();
        if (!Double.isFinite(comX) || !Double.isFinite(comY) || !Double.isFinite(comZ)) return;

        final double rawPocketX = valkyrienair$pocketCenterX;
        final double rawPocketY = valkyrienair$pocketCenterY;
        final double rawPocketZ = valkyrienair$pocketCenterZ;
        final double pocketX = Double.isFinite(rawPocketX) ? rawPocketX : comX;
        final double pocketY = Double.isFinite(rawPocketY) ? rawPocketY : comY;
        final double pocketZ = Double.isFinite(rawPocketZ) ? rawPocketZ : comZ;

        if (!valkyrienair$hasSmoothedCenter) {
            valkyrienair$smoothedCenterX = pocketX;
            valkyrienair$smoothedCenterY = pocketY;
            valkyrienair$smoothedCenterZ = pocketZ;
            valkyrienair$hasSmoothedCenter = true;
        } else {
            valkyrienair$smoothedCenterX += (pocketX - valkyrienair$smoothedCenterX) * valkyrienair$SMOOTH_CENTER_ALPHA;
            valkyrienair$smoothedCenterY += (pocketY - valkyrienair$smoothedCenterY) * valkyrienair$SMOOTH_CENTER_ALPHA;
            valkyrienair$smoothedCenterZ += (pocketZ - valkyrienair$smoothedCenterZ) * valkyrienair$SMOOTH_CENTER_ALPHA;
        }

        double leverX = valkyrienair$smoothedCenterX - comX;
        double leverY = valkyrienair$smoothedCenterY - comY;
        double leverZ = valkyrienair$smoothedCenterZ - comZ;
        final double leverLenSq = leverX * leverX + leverY * leverY + leverZ * leverZ;
        if (leverLenSq > valkyrienair$MAX_BALANCED_LEVER_ARM * valkyrienair$MAX_BALANCED_LEVER_ARM) {
            final double leverLen = Math.sqrt(leverLenSq);
            final double scale = valkyrienair$MAX_BALANCED_LEVER_ARM / leverLen;
            leverX *= scale;
            leverY *= scale;
            leverZ *= scale;
            valkyrienair$logClamp("leverArm", leverLen, valkyrienair$MAX_BALANCED_LEVER_ARM);
        }

        final double clampedLeverLen =
            Math.sqrt(leverX * leverX + leverY * leverY + leverZ * leverZ);
        final double leverRatio = clamp(clampedLeverLen / valkyrienair$MAX_BALANCED_LEVER_ARM, 0.0, 1.0);
        final double comBlend = clamp(1.0 - 0.45 * leverRatio, valkyrienair$MIN_COM_BLEND, 1.0);

        final double applyX = comX + leverX * comBlend;
        final double applyY = comY + leverY * comBlend;
        final double applyZ = comZ + leverZ * comBlend;

        double upwardForceTarget = displaced * density * valkyrienair$GRAVITY_MAGNITUDE * overlap;
        if (!Double.isFinite(upwardForceTarget) || upwardForceTarget <= 0.0) {
            valkyrienair$lastAppliedBuoyancyForce = 0.0;
            return;
        }

        final double maxForce = mass * valkyrienair$GRAVITY_MAGNITUDE * valkyrienair$MAX_POCKET_BUOYANCY_WEIGHT_MULT;
        if (Double.isFinite(maxForce) && maxForce > 0.0) {
            final double clamped = Math.min(upwardForceTarget, maxForce);
            if (Math.abs(clamped - upwardForceTarget) > 1.0e-9) {
                valkyrienair$logClamp("forceSlew", upwardForceTarget, clamped);
            }
            upwardForceTarget = clamped;
        }

        final double prevForce =
            (Double.isFinite(valkyrienair$lastAppliedBuoyancyForce) && valkyrienair$lastAppliedBuoyancyForce > 0.0) ?
                valkyrienair$lastAppliedBuoyancyForce : 0.0;
        final double maxDeltaForce = mass * valkyrienair$GRAVITY_MAGNITUDE * valkyrienair$MAX_FORCE_SLEW_G_PER_TICK;
        final double minForceThisTick = Math.max(0.0, prevForce - maxDeltaForce);
        final double maxForceThisTick = prevForce + maxDeltaForce;
        final double upwardForce = clamp(upwardForceTarget, minForceThisTick, maxForceThisTick);
        if (Math.abs(upwardForce - upwardForceTarget) > 1.0e-9) {
            valkyrienair$logClamp("forceSlew", upwardForceTarget, upwardForce);
        }
        valkyrienair$lastAppliedBuoyancyForce = upwardForce;
        if (upwardForce <= valkyrienair$OVERLAP_EPS) return;

        valkyrienair$tmpForce.set(0.0, upwardForce, 0.0);
        valkyrienair$tmpPos.set(applyX, applyY, applyZ);
        physShip.applyWorldForceToModelPos(valkyrienair$tmpForce, valkyrienair$tmpPos);
        physShip.setDoFluidDrag(true);

        // Extra damping for very viscous fluids (e.g. lava) to prevent "bounce"/launch oscillations at depth.
        double viscosity = valkyrienair$buoyancyFluidViscosity;
        if (Double.isFinite(viscosity) && viscosity > valkyrienair$DEFAULT_FLUID_VISCOSITY * 1.5) {
            viscosity = Math.max(100.0, Math.min(viscosity, 200_000.0));
            final double viscosityScale = Math.max(0.25, Math.min(viscosity / valkyrienair$DEFAULT_FLUID_VISCOSITY, 20.0));

            final var vel = physShip.getVelocity();
            final double baseDamping = 0.35;
            final double damping = baseDamping * viscosityScale * overlap;

            double fx = -vel.x() * mass * damping;
            double fy = -vel.y() * mass * damping;
            double fz = -vel.z() * mass * damping;

            final double maxDamp = mass * valkyrienair$GRAVITY_MAGNITUDE * valkyrienair$MAX_DAMPING_FORCE_MULT;
            final double dampLenSq = fx * fx + fy * fy + fz * fz;
            if (dampLenSq > maxDamp * maxDamp && dampLenSq > 1.0e-12) {
                final double rawLen = Math.sqrt(dampLenSq);
                final double scale = maxDamp / rawLen;
                fx *= scale;
                fy *= scale;
                fz *= scale;
                valkyrienair$logClamp("damping", rawLen, maxDamp);
            }

            valkyrienair$tmpForce.set(fx, fy, fz);
            physShip.applyWorldForceToModelPos(valkyrienair$tmpForce, com);
        }
    }

    @Unique
    private void valkyrienair$logClamp(final String kind, final double raw, final double clamped) {
        if (!Double.isFinite(raw) || !Double.isFinite(clamped)) return;
        if (Math.abs(raw - clamped) <= 1.0e-9) return;

        final long count;
        switch (kind) {
            case "overlap":
                count = ++valkyrienair$diagOverlapClampCount;
                break;
            case "leverArm":
                count = ++valkyrienair$diagLeverClampCount;
                break;
            case "damping":
                count = ++valkyrienair$diagDampingClampCount;
                break;
            default:
                count = ++valkyrienair$diagForceSlewClampCount;
                break;
        }

        if (count <= 4L || count % 512L == 0L) {
            valkyrienair$LOGGER.debug(
                "Pocket buoyancy clamp kind={} raw={} clamped={} count={}",
                kind,
                raw,
                clamped,
                count
            );
        }
    }

    @Unique
    private static double clamp(final double v, final double min, final double max) {
        return v < min ? min : Math.min(v, max);
    }
}
