package org.valkyrienskies.mod.common.render.batched;

import com.mojang.blaze3d.platform.GlStateManager;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

public final class ShipTransformStorage {

    private static final int FLOATS_PER_MATRIX = 16;
    private static final int BYTES_PER_MATRIX = FLOATS_PER_MATRIX * 4; // 64 bytes = 4 RGBA32F texels

    private long arenaPtr;
    private int capacityMatrices;
    private int count;

    private int buffer = 0;
    private int texture = 0;
    private int currentByteSize = 0;

    private final float[] scratch = new float[FLOATS_PER_MATRIX];

    public ShipTransformStorage() {
        capacityMatrices = 256;
        arenaPtr = MemoryUtil.nmemAlloc((long) capacityMatrices * BYTES_PER_MATRIX);
    }

    public void delete() {
        if (arenaPtr != 0L) {
            MemoryUtil.nmemFree(arenaPtr);
            arenaPtr = 0L;
        }
        if (buffer != 0) {
            GL15.glDeleteBuffers(buffer);
            buffer = 0;
        }
        if (texture != 0) {
            GL11.glDeleteTextures(texture);
            texture = 0;
        }
    }

    public void beginFrame() {
        count = 0;
    }

    public int append(final Matrix4f matrix) {
        if (count >= capacityMatrices) {
            grow();
        }
        final int index = count;
        matrix.get(scratch); // column-major into scratch[0..15]
        final long offset = arenaPtr + (long) index * BYTES_PER_MATRIX;
        for (int i = 0; i < FLOATS_PER_MATRIX; i++) {
            MemoryUtil.memPutFloat(offset + (long) i * 4, scratch[i]);
        }
        count++;
        return index;
    }

    private void grow() {
        final int newCap = capacityMatrices * 2;
        final long newPtr = MemoryUtil.nmemAlloc((long) newCap * BYTES_PER_MATRIX);
        MemoryUtil.memCopy(arenaPtr, newPtr, (long) count * BYTES_PER_MATRIX);
        MemoryUtil.nmemFree(arenaPtr);
        arenaPtr = newPtr;
        capacityMatrices = newCap;
    }

    public void upload() {
        ensureGlObjects();
        final int needed = Math.max(BYTES_PER_MATRIX, count * BYTES_PER_MATRIX);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer);
        final boolean orphaned = currentByteSize != needed;
        if (orphaned || count > 0) {
            GL15.nglBufferData(GL31.GL_TEXTURE_BUFFER, needed, arenaPtr, GL15.GL_DYNAMIC_DRAW);
            currentByteSize = needed;
        }
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        if (orphaned) {
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffer);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        }
    }

    public void bind(final int textureUnit) {
        ensureGlObjects();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    private void ensureGlObjects() {
        if (buffer == 0) {
            buffer = GL15.glGenBuffers();
        }
        if (texture == 0) {
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffer);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        }
    }
}
