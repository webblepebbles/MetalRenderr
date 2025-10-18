package com.metalrender.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.util.math.BlockPos;

public final class VertexCompressor {
  /** Size of a vanilla chunk vertex in bytes. */
  public static final int INPUT_STRIDE = 32;

  /** Size of the compressed vertex format in bytes. */
  public static final int OUTPUT_STRIDE = 24;

  private static final float POSITION_SCALE = 256.0F;

  private VertexCompressor() {}

  public static CompressedMesh compress(BlockPos origin, ByteBuffer source,
                                        int vertexCount) {
    if (vertexCount <= 0 || source == null) {
      return CompressedMesh.empty();
    }

    ByteBuffer input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    input.clear();

    ByteBuffer target = ByteBuffer.allocateDirect(vertexCount * OUTPUT_STRIDE)
                            .order(ByteOrder.LITTLE_ENDIAN);

    float minX = Float.POSITIVE_INFINITY;
    float minY = Float.POSITIVE_INFINITY;
    float minZ = Float.POSITIVE_INFINITY;
    float maxX = Float.NEGATIVE_INFINITY;
    float maxY = Float.NEGATIVE_INFINITY;
    float maxZ = Float.NEGATIVE_INFINITY;

    int originX = origin.getX();
    int originY = origin.getY();
    int originZ = origin.getZ();

    for (int vertexIndex = 0; vertexIndex < vertexCount; vertexIndex++) {
      int base = vertexIndex * INPUT_STRIDE;

      float x = input.getFloat(base);
      float y = input.getFloat(base + 4);
      float z = input.getFloat(base + 8);

      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      minZ = Math.min(minZ, z);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
      maxZ = Math.max(maxZ, z);

      short qx = quantizePosition(x - originX);
      short qy = quantizePosition(y - originY);
      short qz = quantizePosition(z - originZ);

      int normalPacked = packNormal(input.get(base + 28), input.get(base + 29),
                                    input.get(base + 30));
      int color = input.getInt(base + 12);
      int uvPacked =
          packHalf2(input.getFloat(base + 16), input.getFloat(base + 20));
      int lightPacked =
          packLight(input.getShort(base + 24), input.getShort(base + 26));

      target.putShort(qx);
      target.putShort(qy);
      target.putShort(qz);
      target.putShort((short)0);
      target.putInt(normalPacked);
      target.putInt(color);
      target.putInt(uvPacked);
      target.putInt(lightPacked);
    }

    target.flip();

    if (!target.hasRemaining()) {
      return CompressedMesh.empty();
    }

    Bounds bounds = Bounds.from(minX, minY, minZ, maxX, maxY, maxZ);
    return new CompressedMesh(target, vertexCount, bounds);
  }

  private static short quantizePosition(float relative) {
    int quantized = Math.round(relative * POSITION_SCALE);
    if (quantized > Short.MAX_VALUE) {
      quantized = Short.MAX_VALUE;
    } else if (quantized < Short.MIN_VALUE) {
      quantized = Short.MIN_VALUE;
    }
    return (short)quantized;
  }

  private static int packNormal(byte nx, byte ny, byte nz) {
    int sx = toSnorm10(nx);
    int sy = toSnorm10(ny);
    int sz = toSnorm10(nz);
    return (sx & 0x3FF) | ((sy & 0x3FF) << 10) | ((sz & 0x3FF) << 20);
  }

  private static int toSnorm10(byte value) {
    int signed = value;
    float normalized = Math.max(-1.0F, Math.min(1.0F, signed / 127.0F));
    return Math.round(normalized * 511.0F) & 0x3FF;
  }

  private static int packLight(short block, short sky) {
    int blockInt = block & 0xFFFF;
    int skyInt = sky & 0xFFFF;
    return blockInt | (skyInt << 16);
  }

  private static int packHalf2(float u, float v) {
    int uh = floatToHalf(u) & 0xFFFF;
    int vh = floatToHalf(v) & 0xFFFF;
    return uh | (vh << 16);
  }

  private static int floatToHalf(float value) {
    int floatBits = Float.floatToIntBits(value);
    int sign = (floatBits >>> 16) & 0x8000;
    int mantissa = floatBits & 0x007FFFFF;
    int exponent = (floatBits >>> 23) & 0xFF;

    if (exponent == 0xFF) {
      if (mantissa != 0) {
        return sign | 0x7E00;
      }
      return sign | 0x7C00;
    }

    exponent = exponent - 127 + 15;
    if (exponent >= 0x1F) {
      return sign | 0x7C00;
    }
    if (exponent <= 0) {
      if (exponent < -10) {
        return sign;
      }
      mantissa = (mantissa | 0x00800000) >> (1 - exponent);
      return sign | (mantissa >> 13);
    }

    return sign | (exponent << 10) | (mantissa >> 13);
  }

  public static final class CompressedMesh {
    private final ByteBuffer data;
    public final int vertexCount;
    public final Bounds bounds;

    private CompressedMesh(ByteBuffer data, int vertexCount, Bounds bounds) {
      this.data = data;
      this.vertexCount = vertexCount;
      this.bounds = bounds;
    }

    private static CompressedMesh empty() {
      return new CompressedMesh(ByteBuffer.allocateDirect(0), 0, Bounds.EMPTY);
    }

    public ByteBuffer buffer() {
      return this.data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }
  }

  public static final class Bounds {
    public static final Bounds EMPTY = new Bounds(0, 0, 0, 0, 0, 0, 0);

    public final float minX;
    public final float minY;
    public final float minZ;
    public final float maxX;
    public final float maxY;
    public final float maxZ;
    public final float radius;

    private Bounds(float minX, float minY, float minZ, float maxX, float maxY,
                   float maxZ, float radius) {
      this.minX = minX;
      this.minY = minY;
      this.minZ = minZ;
      this.maxX = maxX;
      this.maxY = maxY;
      this.maxZ = maxZ;
      this.radius = radius;
    }

    private static Bounds from(float minX, float minY, float minZ, float maxX,
                               float maxY, float maxZ) {
      float centerX = (minX + maxX) * 0.5F;
      float centerY = (minY + maxY) * 0.5F;
      float centerZ = (minZ + maxZ) * 0.5F;
      float dx = maxX - centerX;
      float dy = maxY - centerY;
      float dz = maxZ - centerZ;
      float radius = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
      return new Bounds(minX, minY, minZ, maxX, maxY, maxZ, radius);
    }

    public float centerX() { return (this.minX + this.maxX) * 0.5F; }

    public float centerY() { return (this.minY + this.maxY) * 0.5F; }

    public float centerZ() { return (this.minZ + this.maxZ) * 0.5F; }
  }
}