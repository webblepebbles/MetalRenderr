package com.metalrender.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class PackedSectionData {

    public static final int L0_SIZE = 16 * 16 * 16;
    public static final int L1_SIZE = 8 * 8 * 8;
    public static final int L2_SIZE = 4 * 4 * 4;
    public static final int TOTAL_ENTRIES = L0_SIZE + L1_SIZE + L2_SIZE;

    public static final int L1_OFFSET = L0_SIZE;
    public static final int L2_OFFSET = L0_SIZE + L1_SIZE;
    public final int[] blockStates;
    public final byte[] packedLight;
    public int nonAirCount;
    public final int sectionX, sectionY, sectionZ;
    public long lastUpdatedMs;

    public boolean mipsGenerated;

    public PackedSectionData(int sectionX, int sectionY, int sectionZ) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
        this.blockStates = new int[TOTAL_ENTRIES];
        this.packedLight = new byte[TOTAL_ENTRIES];
        this.nonAirCount = 0;
        this.lastUpdatedMs = System.currentTimeMillis();
        this.mipsGenerated = false;
    }

    public static int l0Index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public static int l1Index(int x, int y, int z) {
        return L1_OFFSET + (y << 6) | (z << 3) | x;
    }

    public static int l2Index(int x, int y, int z) {
        return L2_OFFSET + (y << 4) | (z << 2) | x;
    }

    public void setBlock(int x, int y, int z, int stateId, int skyLight, int blockLight) {
        int idx = l0Index(x, y, z);
        int old = blockStates[idx];
        blockStates[idx] = stateId;
        packedLight[idx] = (byte) (((skyLight & 0xF) << 4) | (blockLight & 0xF));

        if (old == 0 && stateId != 0)
            nonAirCount++;
        else if (old != 0 && stateId == 0)
            nonAirCount--;

        mipsGenerated = false;
    }

    public boolean isEmpty() {
        return nonAirCount == 0;
    }

    public void generateMips() {
        for (int y = 0; y < 8; y++) {
            for (int z = 0; z < 8; z++) {
                for (int x = 0; x < 8; x++) {
                    int dominantBlock = 0;
                    int maxLight = 0;
                    int bestCount = 0;

                    int bx = x * 2, by = y * 2, bz = z * 2;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dz = 0; dz < 2; dz++) {
                            for (int dx = 0; dx < 2; dx++) {
                                int l0i = l0Index(bx + dx, by + dy, bz + dz);
                                int block = blockStates[l0i];
                                int light = packedLight[l0i] & 0xFF;
                                if (block != 0) {
                                    bestCount++;
                                    dominantBlock = block;
                                }
                                maxLight = Math.max(maxLight, light);
                            }
                        }
                    }

                    int l1i = L1_OFFSET + (y << 6) + (z << 3) + x;
                    blockStates[l1i] = dominantBlock;
                    packedLight[l1i] = (byte) maxLight;
                }
            }
        }

        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    int dominantBlock = 0;
                    int maxLight = 0;

                    int bx = x * 2, by = y * 2, bz = z * 2;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dz = 0; dz < 2; dz++) {
                            for (int dx = 0; dx < 2; dx++) {
                                int l1i = L1_OFFSET + ((by + dy) << 6) + ((bz + dz) << 3) + (bx + dx);
                                int block = blockStates[l1i];
                                int light = packedLight[l1i] & 0xFF;
                                if (block != 0) {
                                    dominantBlock = block;
                                }
                                maxLight = Math.max(maxLight, light);
                            }
                        }
                    }

                    int l2i = L2_OFFSET + (y << 4) + (z << 2) + x;
                    blockStates[l2i] = dominantBlock;
                    packedLight[l2i] = (byte) maxLight;
                }
            }
        }

        mipsGenerated = true;
    }

    public void computeDistantShadows() {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                boolean underSolid = false;
                for (int y = 15; y >= 0; y--) {
                    int idx = l0Index(x, y, z);
                    if (blockStates[idx] != 0) {
                        underSolid = true;
                        packedLight[idx] = (byte) 0xA0;
                    } else if (underSolid) {
                        packedLight[idx] = 0;
                    } else {
                        packedLight[idx] = (byte) 0xF0;
                    }
                }
            }
        }
    }

    public int estimatedBytes() {
        return (TOTAL_ENTRIES * 4) + TOTAL_ENTRIES + 64;
    }

    private static final int SERIAL_MAGIC = 0x4D505344;
    private static final byte SERIAL_VERSION = 2;

    public ByteBuffer serialize() {
        int size = 30 + (TOTAL_ENTRIES * 4) + TOTAL_ENTRIES;
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);

        buf.putInt(SERIAL_MAGIC);
        buf.put(SERIAL_VERSION);
        byte flags = 0;
        if (isEmpty())
            flags |= 1;
        if (mipsGenerated)
            flags |= 2;
        buf.put(flags);
        buf.putInt(sectionX);
        buf.putInt(sectionY);
        buf.putInt(sectionZ);
        buf.putInt(nonAirCount);
        buf.putLong(lastUpdatedMs);

        if (!isEmpty()) {
            for (int i = 0; i < TOTAL_ENTRIES; i++) {
                buf.putInt(blockStates[i]);
            }
            buf.put(packedLight, 0, TOTAL_ENTRIES);
        }

        buf.flip();
        return buf;
    }

    public static PackedSectionData deserialize(ByteBuffer buf) {
        if (buf == null || buf.remaining() < 30)
            return null;
        buf = buf.order(ByteOrder.LITTLE_ENDIAN);

        int magic = buf.getInt();
        if (magic != SERIAL_MAGIC)
            return null;

        byte version = buf.get();
        if (version != SERIAL_VERSION)
            return null;

        byte flags = buf.get();
        boolean empty = (flags & 1) != 0;
        boolean hasMips = (flags & 2) != 0;

        int sx = buf.getInt();
        int sy = buf.getInt();
        int sz = buf.getInt();
        int nonAir = buf.getInt();
        long timestamp = buf.getLong();

        PackedSectionData data = new PackedSectionData(sx, sy, sz);
        data.nonAirCount = nonAir;
        data.lastUpdatedMs = timestamp;
        data.mipsGenerated = hasMips;

        if (!empty && buf.remaining() >= (TOTAL_ENTRIES * 4) + TOTAL_ENTRIES) {
            for (int i = 0; i < TOTAL_ENTRIES; i++) {
                data.blockStates[i] = buf.getInt();
            }
            buf.get(data.packedLight, 0, TOTAL_ENTRIES);
        }

        return data;
    }

    private static final ThreadLocal<PackedSectionData> CACHE = ThreadLocal
            .withInitial(() -> new PackedSectionData(0, 0, 0));

    public static PackedSectionData borrowCached(int sx, int sy, int sz) {
        PackedSectionData cached = CACHE.get();
        Arrays.fill(cached.blockStates, 0);
        Arrays.fill(cached.packedLight, (byte) 0);
        cached.nonAirCount = 0;
        cached.mipsGenerated = false;
        cached.lastUpdatedMs = System.currentTimeMillis();
        return new PackedSectionData(sx, sy, sz);
    }
}
