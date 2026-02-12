package com.metalrender.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class LodSection {

    public final int sectionX;
    public final int sectionY;
    public final int sectionZ;

    private ByteBuffer texturedVertices;
    private int texturedVertexCount;

    private ByteBuffer compactVertices;
    private int compactVertexCount;

    private int[] colorPalette;
    private int paletteSize;

    private float boundsRadius;

    private long lastModifiedTime;
    private boolean isEmpty;
    private boolean dirty;
    private long gpuBufferOffset = -1;

    public LodSection(int sectionX, int sectionY, int sectionZ) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
        this.isEmpty = true;
        this.dirty = true;
        this.lastModifiedTime = System.currentTimeMillis();
    }

    public void setMeshData(ByteBuffer texturedVerts, int texturedCount,
            ByteBuffer compactVerts, int compactCount,
            int[] palette, int paletteSize,
            float boundsRadius) {
        this.texturedVertices = texturedVerts;
        this.texturedVertexCount = texturedCount;
        this.compactVertices = compactVerts;
        this.compactVertexCount = compactCount;
        this.colorPalette = palette;
        this.paletteSize = paletteSize;
        this.boundsRadius = boundsRadius;
        this.isEmpty = (texturedCount == 0 && compactCount == 0);
        this.dirty = true;
        this.lastModifiedTime = System.currentTimeMillis();
    }

    public boolean isEmpty() {
        return isEmpty;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        this.dirty = false;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public int getTexturedVertexCount() {
        return texturedVertexCount;
    }

    public int getCompactVertexCount() {
        return compactVertexCount;
    }

    public ByteBuffer getTexturedVertices() {
        return texturedVertices;
    }

    public ByteBuffer getCompactVertices() {
        return compactVertices;
    }

    public int[] getColorPalette() {
        return colorPalette;
    }

    public int getPaletteSize() {
        return paletteSize;
    }

    public float getBoundsRadius() {
        return boundsRadius;
    }

    public long getLastModifiedTime() {
        return lastModifiedTime;
    }

    public long getGpuBufferOffset() {
        return gpuBufferOffset;
    }

    public void setGpuBufferOffset(long offset) {
        this.gpuBufferOffset = offset;
    }

    public float worldOriginX() {
        return sectionX * 16.0f;
    }

    public float worldOriginY() {
        return sectionY * 16.0f;
    }

    public float worldOriginZ() {
        return sectionZ * 16.0f;
    }

    public long key() {
        return packKey(sectionX, sectionY, sectionZ);
    }

    public static long packKey(int sx, int sy, int sz) {
        return ((long) (sy & 0xFF) << 48)
                | ((long) (sz & 0xFFFFFF) << 24)
                | ((long) (sx & 0xFFFFFF));
    }

    public static int unpackX(long key) {
        int raw = (int) (key & 0xFFFFFF);
        return (raw << 8) >> 8;
    }

    public static int unpackY(long key) {
        return (int) ((key >> 48) & 0xFF);
    }

    public static int unpackZ(long key) {
        int raw = (int) ((key >> 24) & 0xFFFFFF);
        return (raw << 8) >> 8;
    }

    private static final int HEADER_SIZE = 28;
    private static final int MAGIC = 0x4C4F4453;
    private static final byte VERSION = 1;

    public ByteBuffer serialize() {
        int paletteBytes = paletteSize * 4;
        int texturedBytes = texturedVertexCount * LodVertexFormat.STRIDE_LOD_TEXTURED;
        int compactBytes = compactVertexCount * LodVertexFormat.STRIDE_LOD_COMPACT;
        int totalSize = HEADER_SIZE + paletteBytes + texturedBytes + compactBytes;

        ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);

        buf.putInt(MAGIC);
        buf.put(VERSION);
        buf.put((byte) (isEmpty ? 1 : 0));
        buf.putShort((short) paletteSize);
        buf.putInt(texturedVertexCount);
        buf.putInt(compactVertexCount);
        buf.putFloat(boundsRadius);
        buf.putLong(lastModifiedTime);

        if (colorPalette != null) {
            for (int i = 0; i < paletteSize; i++) {
                buf.putInt(colorPalette[i]);
            }
        }

        if (texturedVertices != null && texturedVertexCount > 0) {
            ByteBuffer src = texturedVertices.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            src.position(0).limit(texturedBytes);
            buf.put(src);
        }

        if (compactVertices != null && compactVertexCount > 0) {
            ByteBuffer src = compactVertices.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            src.position(0).limit(compactBytes);
            buf.put(src);
        }

        buf.flip();
        return buf;
    }

    public static LodSection deserialize(long key, ByteBuffer data) {
        if (data == null || data.remaining() < HEADER_SIZE)
            return null;
        data = data.order(ByteOrder.LITTLE_ENDIAN);

        int magic = data.getInt();
        if (magic != MAGIC)
            return null;

        byte version = data.get();
        if (version != VERSION)
            return null;

        byte flags = data.get();
        boolean empty = (flags & 1) != 0;
        int palSize = data.getShort() & 0xFFFF;
        int texVertCount = data.getInt();
        int compVertCount = data.getInt();
        float radius = data.getFloat();
        long timestamp = data.getLong();

        int sx = unpackX(key);
        int sy = unpackY(key);
        int sz = unpackZ(key);

        LodSection section = new LodSection(sx, sy, sz);
        section.lastModifiedTime = timestamp;

        if (empty) {
            section.isEmpty = true;
            return section;
        }

        int[] palette = new int[palSize];
        for (int i = 0; i < palSize; i++) {
            palette[i] = data.getInt();
        }

        ByteBuffer texVerts = null;
        if (texVertCount > 0) {
            int texBytes = texVertCount * LodVertexFormat.STRIDE_LOD_TEXTURED;
            texVerts = ByteBuffer.allocateDirect(texBytes).order(ByteOrder.LITTLE_ENDIAN);
            byte[] tmp = new byte[texBytes];
            data.get(tmp);
            texVerts.put(tmp).flip();
        }

        ByteBuffer compVerts = null;
        if (compVertCount > 0) {
            int compBytes = compVertCount * LodVertexFormat.STRIDE_LOD_COMPACT;
            compVerts = ByteBuffer.allocateDirect(compBytes).order(ByteOrder.LITTLE_ENDIAN);
            byte[] tmp = new byte[compBytes];
            data.get(tmp);
            compVerts.put(tmp).flip();
        }

        section.setMeshData(texVerts, texVertCount, compVerts, compVertCount,
                palette, palSize, radius);
        section.dirty = true;
        return section;
    }

    public int estimatedMemoryBytes() {
        int base = 128;
        if (texturedVertices != null)
            base += texturedVertexCount * LodVertexFormat.STRIDE_LOD_TEXTURED;
        if (compactVertices != null)
            base += compactVertexCount * LodVertexFormat.STRIDE_LOD_COMPACT;
        if (colorPalette != null)
            base += colorPalette.length * 4;
        return base;
    }
}
