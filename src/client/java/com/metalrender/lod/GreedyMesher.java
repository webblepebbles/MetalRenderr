package com.metalrender.lod;

import com.metalrender.util.MetalLogger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class GreedyMesher {

    private static final int FACE_NEG_Y = 0;
    private static final int FACE_POS_Y = 1;
    private static final int FACE_NEG_Z = 2;
    private static final int FACE_POS_Z = 3;
    private static final int FACE_NEG_X = 4;
    private static final int FACE_POS_X = 5;
    private static final int NUM_FACES = 6;

    private static final int MAX_QUADS_PER_SECTION = 16 * 16 * 16 * 3;
    private static final int VERTS_PER_QUAD = 6;

    private static final ThreadLocal<GreedyMesher> THREAD_LOCAL = ThreadLocal.withInitial(GreedyMesher::new);

    private final boolean[] visited = new boolean[16 * 16];
    private final int[] colorPalette = new int[256];
    private int paletteSize;

    private GreedyMesher() {
    }

    public static GreedyMesher get() {
        return THREAD_LOCAL.get();
    }

    public static final class MeshResult {
        public final ByteBuffer texturedVertices;
        public final int texturedVertexCount;
        public final ByteBuffer compactVertices;
        public final int compactVertexCount;
        public final int[] colorPalette;
        public final int paletteSize;
        public final float boundsRadius;

        MeshResult(ByteBuffer texturedVertices, int texturedVertexCount,
                ByteBuffer compactVertices, int compactVertexCount,
                int[] colorPalette, int paletteSize, float boundsRadius) {
            this.texturedVertices = texturedVertices;
            this.texturedVertexCount = texturedVertexCount;
            this.compactVertices = compactVertices;
            this.compactVertexCount = compactVertexCount;
            this.colorPalette = colorPalette;
            this.paletteSize = paletteSize;
            this.boundsRadius = boundsRadius;
        }
    }

    public MeshResult meshSection(
            int[][][] blockIds,
            int[] blockColors,
            int[][][] skyLight,
            int[][][][] aoValues,
            boolean[][] neighbors) {

        paletteSize = 0;
        Arrays.fill(colorPalette, 0);

        int maxVerts = MAX_QUADS_PER_SECTION * VERTS_PER_QUAD;
        ByteBuffer texturedBuf = ByteBuffer.allocateDirect(maxVerts * LodVertexFormat.STRIDE_LOD_TEXTURED)
                .order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer compactBuf = ByteBuffer.allocateDirect(maxVerts * LodVertexFormat.STRIDE_LOD_COMPACT)
                .order(ByteOrder.LITTLE_ENDIAN);

        int texturedCount = 0;
        int compactCount = 0;
        float maxDist = 0;

        for (int face = 0; face < NUM_FACES; face++) {

            int layerCount = 16;
            for (int layer = 0; layer < layerCount; layer++) {
                Arrays.fill(visited, false);

                for (int v = 0; v < 16; v++) {
                    for (int u = 0; u < 16; u++) {
                        int idx = v * 16 + u;
                        if (visited[idx])
                            continue;

                        int[] worldPos = faceToWorldPos(face, layer, u, v);
                        int bx = worldPos[0], by = worldPos[1], bz = worldPos[2];

                        if (bx < 0 || bx >= 16 || by < 0 || by >= 16 || bz < 0 || bz >= 16)
                            continue;

                        int blockId = blockIds[by][bz][bx];
                        if (blockId == 0)
                            continue;

                        if (neighbors != null && neighbors[face] != null) {
                            if (neighbors[face][by * 256 + bz * 16 + bx])
                                continue;
                        }

                        int color = (blockColors != null && blockId < blockColors.length)
                                ? blockColors[blockId]
                                : 0xFF808080;
                        int colorIdx = getOrAddPaletteColor(color);

                        int light = (skyLight != null) ? skyLight[by][bz][bx] : 15;

                        int width = 1;
                        while (u + width < 16) {
                            int ni = v * 16 + (u + width);
                            if (visited[ni])
                                break;
                            int[] np = faceToWorldPos(face, layer, u + width, v);
                            if (np[0] < 0 || np[0] >= 16 || np[1] < 0 || np[1] >= 16 || np[2] < 0 || np[2] >= 16)
                                break;
                            int nBlockId = blockIds[np[1]][np[2]][np[0]];
                            if (nBlockId != blockId)
                                break;
                            if (neighbors != null && neighbors[face] != null
                                    && neighbors[face][np[1] * 256 + np[2] * 16 + np[0]])
                                break;
                            int nLight = (skyLight != null) ? skyLight[np[1]][np[2]][np[0]] : 15;
                            if (nLight != light)
                                break;
                            width++;
                        }

                        int height = 1;
                        outer: while (v + height < 16) {
                            for (int du = 0; du < width; du++) {
                                int ni = (v + height) * 16 + (u + du);
                                if (visited[ni])
                                    break outer;
                                int[] np = faceToWorldPos(face, layer, u + du, v + height);
                                if (np[0] < 0 || np[0] >= 16 || np[1] < 0 || np[1] >= 16
                                        || np[2] < 0 || np[2] >= 16)
                                    break outer;
                                int nBlockId = blockIds[np[1]][np[2]][np[0]];
                                if (nBlockId != blockId)
                                    break outer;
                                if (neighbors != null && neighbors[face] != null
                                        && neighbors[face][np[1] * 256 + np[2] * 16 + np[0]])
                                    break outer;
                                int nLight = (skyLight != null) ? skyLight[np[1]][np[2]][np[0]] : 15;
                                if (nLight != light)
                                    break outer;
                            }
                            height++;
                        }

                        for (int dv = 0; dv < height; dv++) {
                            for (int du = 0; du < width; du++) {
                                visited[(v + dv) * 16 + (u + du)] = true;
                            }
                        }

                        float[][] corners = computeQuadCorners(face, layer, u, v, width, height);

                        for (float[] c : corners) {
                            float d = c[0] * c[0] + c[1] * c[1] + c[2] * c[2];
                            maxDist = Math.max(maxDist, d);
                        }

                        int ao0 = 15, ao1 = 15, ao2 = 15, ao3 = 15;
                        if (aoValues != null) {
                            ao0 = aoValues[by][bz][bx][0];
                            ao1 = aoValues[by][bz][bx][1];
                            ao2 = aoValues[by][bz][bx][2];
                            ao3 = aoValues[by][bz][bx][3];
                        }

                        int[] aoCorners = { ao0, ao1, ao2, ao3 };
                        int[] triOrder = { 0, 1, 2, 0, 2, 3 };
                        for (int ti = 0; ti < 6; ti++) {
                            int ci = triOrder[ti];
                            LodVertexFormat.putCompactVertex(compactBuf,
                                    corners[ci][0], corners[ci][1], corners[ci][2],
                                    colorIdx, light, aoCorners[ci]);
                            compactCount++;
                        }

                        float[][] uvs = {
                                { 0, 0 }, { (float) width, 0 },
                                { (float) width, (float) height }, { 0, (float) height }
                        };
                        int r = (color >> 16) & 0xFF;
                        int g = (color >> 8) & 0xFF;
                        int b = (color) & 0xFF;
                        for (int ti = 0; ti < 6; ti++) {
                            int ci = triOrder[ti];
                            LodVertexFormat.putTexturedVertex(texturedBuf,
                                    corners[ci][0], corners[ci][1], corners[ci][2],
                                    face,
                                    uvs[ci][0] / 16.0f, uvs[ci][1] / 16.0f,
                                    r, g, b, light * 17);
                            texturedCount++;
                        }
                    }
                }
            }
        }

        if (texturedCount == 0 && compactCount == 0) {
            return null;
        }

        texturedBuf.flip();
        compactBuf.flip();

        int[] paletteCopy = Arrays.copyOf(colorPalette, paletteSize);

        float boundsRadius = (float) Math.sqrt(maxDist);

        return new MeshResult(texturedBuf, texturedCount, compactBuf, compactCount,
                paletteCopy, paletteSize, boundsRadius);
    }

    private int[] faceToWorldPos(int face, int layer, int u, int v) {
        return switch (face) {
            case FACE_NEG_Y -> new int[] { u, layer, v };
            case FACE_POS_Y -> new int[] { u, layer, v };
            case FACE_NEG_Z -> new int[] { u, v, layer };
            case FACE_POS_Z -> new int[] { u, v, layer };
            case FACE_NEG_X -> new int[] { layer, v, u };
            case FACE_POS_X -> new int[] { layer, v, u };
            default -> new int[] { 0, 0, 0 };
        };
    }

    private float[][] computeQuadCorners(int face, int layer, int u, int v, int width, int height) {

        float faceOffset = (face == FACE_POS_Y || face == FACE_POS_Z || face == FACE_POS_X)
                ? layer + 1.0f
                : layer;

        float u0 = u, u1 = u + width;
        float v0 = v, v1 = v + height;

        return switch (face) {
            case FACE_NEG_Y -> new float[][] {
                    { u0, faceOffset, v0 }, { u1, faceOffset, v0 },
                    { u1, faceOffset, v1 }, { u0, faceOffset, v1 }
            };
            case FACE_POS_Y -> new float[][] {
                    { u0, faceOffset, v0 }, { u1, faceOffset, v0 },
                    { u1, faceOffset, v1 }, { u0, faceOffset, v1 }
            };
            case FACE_NEG_Z -> new float[][] {
                    { u0, v0, faceOffset }, { u1, v0, faceOffset },
                    { u1, v1, faceOffset }, { u0, v1, faceOffset }
            };
            case FACE_POS_Z -> new float[][] {
                    { u0, v0, faceOffset }, { u1, v0, faceOffset },
                    { u1, v1, faceOffset }, { u0, v1, faceOffset }
            };
            case FACE_NEG_X -> new float[][] {
                    { faceOffset, v0, u0 }, { faceOffset, v0, u1 },
                    { faceOffset, v1, u1 }, { faceOffset, v1, u0 }
            };
            case FACE_POS_X -> new float[][] {
                    { faceOffset, v0, u0 }, { faceOffset, v0, u1 },
                    { faceOffset, v1, u1 }, { faceOffset, v1, u0 }
            };
            default -> new float[][] { { 0, 0, 0 }, { 0, 0, 0 }, { 0, 0, 0 }, { 0, 0, 0 } };
        };
    }

    private int getOrAddPaletteColor(int argb) {

        for (int i = 0; i < paletteSize; i++) {
            if (colorPalette[i] == argb)
                return i;
        }

        if (paletteSize < 256) {
            colorPalette[paletteSize] = argb;
            return paletteSize++;
        }

        int bestIdx = 0;
        int bestDist = Integer.MAX_VALUE;
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        for (int i = 0; i < 256; i++) {
            int pr = (colorPalette[i] >> 16) & 0xFF;
            int pg = (colorPalette[i] >> 8) & 0xFF;
            int pb = colorPalette[i] & 0xFF;
            int dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb);
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }
        return bestIdx;
    }
}
