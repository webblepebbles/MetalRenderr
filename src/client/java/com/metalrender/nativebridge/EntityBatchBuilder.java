package com.metalrender.nativebridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class EntityBatchBuilder {

    private static final int CMD_STRIDE = 24;
    private static final int VERTEX_STRIDE = 32;
    private static final int INITIAL_VERTEX_CAPACITY = 65536 * VERTEX_STRIDE;
    private static final int INITIAL_CMD_CAPACITY = 512 * CMD_STRIDE;

    private ByteBuffer vertexBuf;
    private ByteBuffer cmdBuf;
    private int cmdCount;
    private int vertexByteOffset;

    public EntityBatchBuilder() {
        vertexBuf = ByteBuffer.allocateDirect(INITIAL_VERTEX_CAPACITY).order(ByteOrder.nativeOrder());
        cmdBuf = ByteBuffer.allocateDirect(INITIAL_CMD_CAPACITY).order(ByteOrder.nativeOrder());
    }

    public void begin() {
        vertexBuf.clear();
        cmdBuf.clear();
        cmdCount = 0;
        vertexByteOffset = 0;
    }

    public void addMesh(ByteBuffer meshVertices, int vertexCount, long textureHandle) {
        if (vertexCount <= 0)
            return;
        int meshBytes = vertexCount * VERTEX_STRIDE;
        ensureVertexCapacity(meshBytes);
        ensureCmdCapacity(CMD_STRIDE);

        int savedLimit = meshVertices.limit();
        int savedPos = meshVertices.position();
        meshVertices.position(0);
        meshVertices.limit(meshBytes);
        vertexBuf.put(meshVertices);
        meshVertices.position(savedPos);
        meshVertices.limit(savedLimit);

        cmdBuf.putInt(vertexByteOffset);
        cmdBuf.putInt(vertexCount);
        cmdBuf.putLong(textureHandle);
        cmdBuf.putInt(0);
        cmdBuf.putInt(0);

        vertexByteOffset += meshBytes;
        cmdCount++;
    }

    public int getCmdCount() {
        return cmdCount;
    }

    public void submit(long deviceHandle) {
        if (cmdCount == 0)
            return;
        vertexBuf.flip();
        cmdBuf.flip();
        NativeBridge.nBatchDrawEntities(deviceHandle, vertexBuf, vertexByteOffset, cmdBuf, cmdCount);
    }

    private void ensureVertexCapacity(int bytesNeeded) {
        if (vertexBuf.remaining() < bytesNeeded) {
            int newCap = Math.max(vertexBuf.capacity() * 2, vertexBuf.position() + bytesNeeded);
            ByteBuffer newBuf = ByteBuffer.allocateDirect(newCap).order(ByteOrder.nativeOrder());
            vertexBuf.flip();
            newBuf.put(vertexBuf);
            vertexBuf = newBuf;
        }
    }

    private void ensureCmdCapacity(int bytesNeeded) {
        if (cmdBuf.remaining() < bytesNeeded) {
            int newCap = Math.max(cmdBuf.capacity() * 2, cmdBuf.position() + bytesNeeded);
            ByteBuffer newBuf = ByteBuffer.allocateDirect(newCap).order(ByteOrder.nativeOrder());
            cmdBuf.flip();
            newBuf.put(cmdBuf);
            cmdBuf = newBuf;
        }
    }
}
