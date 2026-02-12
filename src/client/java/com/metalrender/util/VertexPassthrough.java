package com.metalrender.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.util.math.BlockPos;

public final class VertexPassthrough {
    public static final int SODIUM_STRIDE = 20;

    public static final int OUTPUT_STRIDE = 20;

    private VertexPassthrough() {
    }

    public static PassthroughMesh passthrough(BlockPos origin, ByteBuffer source, int inputVertexCount) {
        if (inputVertexCount <= 0 || source == null) {
            return PassthroughMesh.empty();
        }

        int quadCount = inputVertexCount / 4;
        if (quadCount == 0) {
            return PassthroughMesh.empty();
        }

        int outputVertexCount = quadCount * 6;

        ByteBuffer input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        input.clear();

        ByteBuffer target = ByteBuffer.allocateDirect(outputVertexCount * OUTPUT_STRIDE)
                .order(ByteOrder.LITTLE_ENDIAN);
        byte[][] quadVerts = new byte[4][SODIUM_STRIDE];

        for (int quadIndex = 0; quadIndex < quadCount; quadIndex++) {
            for (int v = 0; v < 4; v++) {
                int base = (quadIndex * 4 + v) * SODIUM_STRIDE;
                for (int b = 0; b < SODIUM_STRIDE; b++) {
                    quadVerts[v][b] = input.get(base + b);
                }
            }
            int[] indices = { 0, 1, 2, 2, 3, 0 };
            for (int idx : indices) {
                target.put(quadVerts[idx]);
            }
        }

        target.flip();

        if (!target.hasRemaining()) {
            return PassthroughMesh.empty();
        }

        return new PassthroughMesh(target, outputVertexCount, origin);
    }

    public static final class PassthroughMesh {
        private static final PassthroughMesh EMPTY = new PassthroughMesh(
                ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN), 0, BlockPos.ORIGIN);

        public final ByteBuffer buffer;
        public final int vertexCount;
        public final BlockPos origin;

        public PassthroughMesh(ByteBuffer buffer, int vertexCount, BlockPos origin) {
            this.buffer = buffer;
            this.vertexCount = vertexCount;
            this.origin = origin;
        }

        public static PassthroughMesh empty() {
            return EMPTY;
        }
    }
}
