package org.valkyrienskies.mod.compat.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity.ContraptionRotationState;
import com.simibubi.create.content.contraptions.Contraption;
import it.unimi.dsi.fastutil.Pair;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.core.api.bodies.ServerVsBody;
import org.valkyrienskies.core.api.bodies.shape.VoxelBodyShapeData;
import org.valkyrienskies.core.api.bodies.shape.VoxelType;
import org.valkyrienskies.core.api.bodies.shape.VoxelUpdate;
import org.valkyrienskies.core.api.world.ServerShipWorld;
import org.valkyrienskies.core.internal.world.chunks.VsiBlockType;
import org.valkyrienskies.mod.api.ValkyrienSkies;
import org.valkyrienskies.mod.common.DefaultBlockStateInfoProvider;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

public class ContraptionSegmentHelper {
    private static final double TICKS_PER_SECOND = 20.0;

    @Nullable
    public static VoxelBodyShapeData toShapeData(AbstractContraptionEntity entity) {
        Contraption contraption = entity.getContraption();
        if (contraption != null) {
            AABBdc contraptionBounds = VectorConversionsMCKt.toJOML(contraption.bounds);
            Vector3ic lowerCorner = new Vector3i((int) contraptionBounds.minX(), (int) contraptionBounds.minY(), (int) contraptionBounds.minZ());
            Vector3ic upperCorner = new Vector3i((int) contraptionBounds.maxX(), (int) contraptionBounds.maxY(), (int) contraptionBounds.maxZ());
            VoxelBodyShapeData data = new VoxelBodyShapeData(lowerCorner, upperCorner);
            return data;
        }
        return null;
    }

    public static VoxelUpdate[] toVoxelUpdates(AbstractContraptionEntity entity) {
        Map<BlockPos, StructureBlockInfo> blocks = entity.getContraption().getBlocks();

        if (blocks.isEmpty()) {
            return new VoxelUpdate[0];
        }

        HashMap<Vector3ic, HashSet<Pair<BlockPos, StructureBlockInfo>>> data = new HashMap<>();
        for (Map.Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
            BlockPos truePos = entry.getKey();
            int chunkX = truePos.getX() >> 4;
            int chunkY = truePos.getY() >> 4;
            int chunkZ = truePos.getZ() >> 4;
            HashSet<Pair<BlockPos, StructureBlockInfo>> chunkData = data.computeIfAbsent(new Vector3i(chunkX, chunkY, chunkZ), ignored -> new HashSet<>());
            int chunkRelativeX = truePos.getX() - (chunkX << 4);
            int chunkRelativeY = truePos.getY() - (chunkY << 4);
            int chunkRelativeZ = truePos.getZ() - (chunkZ << 4);
            chunkData.add(Pair.of(new BlockPos(chunkRelativeX, chunkRelativeY, chunkRelativeZ), entry.getValue()));
        }

        VoxelUpdate[] updates = new VoxelUpdate[data.size()];
        int index = 0;
        for (Entry<Vector3ic, HashSet<Pair<BlockPos, StructureBlockInfo>>> entry : data.entrySet()) {
            Vector3ic chunkPos = entry.getKey();
            HashSet<Pair<BlockPos, StructureBlockInfo>> chunkData = entry.getValue();

            VoxelUpdate.Builder updateBuilder = ValkyrienSkiesMod.getVsCore().newSparseVoxelUpdateBuilder(chunkPos.x(), chunkPos.y(), chunkPos.z());

            for (Pair<BlockPos, StructureBlockInfo> pair : chunkData) {
                int x = pair.first().getX();
                int y = pair.first().getY();
                int z = pair.first().getZ();
                VsiBlockType type = DefaultBlockStateInfoProvider.INSTANCE.getBlockStateType(pair.second().state());
                updateBuilder.addBlock(x, y, z, (VoxelType) type);
            }
            updates[index] = updateBuilder.build();
            index++;
        }
        return updates;
    }

    public static Vector3dc getContraptionSegmentPosition(AbstractContraptionEntity contraption) {
        return VectorConversionsMCKt.toJOML(contraption.getAnchorVec());
    }

    public static Quaterniond getContraptionSegmentRotation(AbstractContraptionEntity contraption) {
        ContraptionRotationState rotationState = contraption.getRotationState();
        Quaterniond contraptionRot = new Quaterniond(0, 0, 0, 1).rotateZYX(
            Math.toRadians(rotationState.zRotation),
            Math.toRadians(rotationState.yRotation),
            Math.toRadians(rotationState.xRotation),
            new Quaterniond()
        );
        contraptionRot.rotateLocalY(Math.toRadians(rotationState.getYawOffset()));
        contraptionRot.normalize();
        return contraptionRot;
    }

    public static int addContraptionSegment(ServerLevel level, VoxelBodyShapeData shapeData, AbstractContraptionEntity contraption, long bodyId, boolean isWorld) {
        Quaterniond contraptionRot = getContraptionSegmentRotation(contraption);
        Vector3dc anchorVec = getContraptionSegmentPosition(contraption);

        int segmentId = -1;

        VoxelUpdate[] voxelUpdates = toVoxelUpdates(contraption);
        if (voxelUpdates.length == 0) {
            return segmentId;
        }

        if (isWorld) {
            ServerShipWorld shipWorld = ValkyrienSkies.getShipWorld(level.getServer());
            if (shipWorld == null) {
                return segmentId;
            }
            String dimensionId = ValkyrienSkies.getDimensionId(level);
            segmentId = shipWorld.addWorldCollisionSegment(dimensionId, shapeData, anchorVec, contraptionRot, 1.0, new Vector3d());

            if (segmentId == -1) {
                return segmentId;
            }

            for (VoxelUpdate voxelUpdate : voxelUpdates) {
                shipWorld.applyWorldVoxelSegmentUpdate(dimensionId, segmentId, voxelUpdate);
            }
        } else {
            ServerVsBody body = ValkyrienSkies.getShipWorld(level.getServer()).getAllBodies().getById(bodyId);
            if (body == null) {
                return segmentId;
            }

            segmentId = body.addCollisionSegment(shapeData, anchorVec, contraptionRot, 1.0, new Vector3d());

            for (VoxelUpdate voxelUpdate : voxelUpdates) {
                body.applyVoxelSegmentUpdate(segmentId, voxelUpdate);
            }
        }

        return segmentId;
    }

    private static Vector3d estimateLinearVelocity(Vector3dc currentPosition, @Nullable Vector3dc previousPosition) {
        if (previousPosition == null) {
            return new Vector3d();
        }
        return new Vector3d(currentPosition).sub(previousPosition).mul(TICKS_PER_SECOND);
    }

    private static Vector3d estimateAngularVelocity(Quaterniondc currentRotation, @Nullable Quaterniondc previousRotation) {
        if (previousRotation == null) {
            return new Vector3d();
        }

        Quaterniond current = new Quaterniond(currentRotation).normalize();
        Quaterniond previous = new Quaterniond(previousRotation).normalize();
        double dot = current.x * previous.x + current.y * previous.y + current.z * previous.z + current.w * previous.w;
        if (dot < 0.0) {
            current.x = -current.x;
            current.y = -current.y;
            current.z = -current.z;
            current.w = -current.w;
        }

        Quaterniond delta = current.mul(previous.conjugate(new Quaterniond()), new Quaterniond()).normalize();
        double sinHalfAngle = Math.sqrt(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z);
        if (sinHalfAngle < 1.0e-9) {
            return new Vector3d();
        }

        double angle = 2.0 * Math.atan2(sinHalfAngle, delta.w);
        if (angle > Math.PI) {
            angle -= 2.0 * Math.PI;
        }

        double scale = angle * TICKS_PER_SECOND / sinHalfAngle;
        return new Vector3d(delta.x * scale, delta.y * scale, delta.z * scale);
    }

    public static boolean updateContraptionSegmentTransform(
        ServerLevel level,
        AbstractContraptionEntity contraption,
        int segmentId,
        long bodyId,
        boolean isWorld,
        @Nullable Vector3dc previousPosition,
        @Nullable Quaterniondc previousRotation
    ) {
        ServerShipWorld shipWorld = ValkyrienSkies.getShipWorld(level.getServer());
        if (shipWorld == null || segmentId == -1) {
            return false;
        }

        Vector3dc anchorVec = getContraptionSegmentPosition(contraption);
        Quaterniond contraptionRot = getContraptionSegmentRotation(contraption);
        Vector3d velocity = estimateLinearVelocity(anchorVec, previousPosition);
        Vector3d angularVelocity = estimateAngularVelocity(contraptionRot, previousRotation);

        if (isWorld) {
            shipWorld.setWorldCollisionSegmentTransform(
                ValkyrienSkies.getDimensionId(level),
                segmentId,
                anchorVec,
                contraptionRot,
                velocity,
                angularVelocity
            );
            return true;
        }

        ServerVsBody body = shipWorld.getAllBodies().getById(bodyId);
        if (body == null) {
            return false;
        }
        body.setCollisionSegmentTransform(segmentId, anchorVec, contraptionRot, velocity, angularVelocity);
        return true;
    }

    public static boolean removeContraptionSegment(ServerLevel level, int segmentId, long bodyId, boolean isWorld) {
        ServerShipWorld shipWorld = ValkyrienSkies.getShipWorld(level.getServer());

        if (isWorld) {
            return shipWorld.removeWorldCollisionSegment(ValkyrienSkies.getDimensionId(level), segmentId);
        }

        ServerVsBody body = shipWorld.getAllBodies().getById(bodyId);
        if (body == null) {
            return false;
        }
        return body.removeCollisionSegment(segmentId);
    }
}
