
package com.metalrender.util;

import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class DataCompressionUtil {
  private static final int COMPRESSION_LEVEL = Deflater.DEFAULT_COMPRESSION;
  private static final float COMPRESSION_THRESHOLD = 0.85F;

  private DataCompressionUtil() {}

  public static CompressionResult compress(ByteBuffer data) {
    if (data == null || data.remaining() < 64) {

      return new CompressionResult(false, data.duplicate());
    }

    byte[] input = new byte[data.remaining()];
    data.duplicate().get(input);

    byte[] compressed = new byte[input.length + 128];
    Deflater deflater = new Deflater(COMPRESSION_LEVEL);
    try {
      deflater.setInput(input);
      deflater.finish();
      int compressedSize = deflater.deflate(compressed);

      if (compressedSize < input.length * COMPRESSION_THRESHOLD) {
        ByteBuffer result = ByteBuffer.allocateDirect(compressedSize);
        result.put(compressed, 0, compressedSize);
        result.flip();
        return new CompressionResult(true, result);
      } else {

        return new CompressionResult(false, data.duplicate());
      }
    } finally {
      deflater.end();
    }
  }

  public static ByteBuffer decompress(ByteBuffer data, int originalSize) {
    if (data == null || data.remaining() == 0) {
      return ByteBuffer.allocateDirect(0);
    }

    byte[] input = new byte[data.remaining()];
    data.duplicate().get(input);

    byte[] decompressed = new byte[Math.max(originalSize, 1024)];
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(input);
      int decompressedSize = inflater.inflate(decompressed);

      ByteBuffer result = ByteBuffer.allocateDirect(decompressedSize);
      result.put(decompressed, 0, decompressedSize);
      result.flip();
      return result;
    } catch (Exception e) {
      MetalLogger.error("Decompression failed: {}", e.getMessage());
      return ByteBuffer.allocateDirect(0);
    } finally {
      inflater.end();
    }
  }

  public static final class CompressionResult {
    public final boolean compressed;
    public final ByteBuffer data;

    public CompressionResult(boolean compressed, ByteBuffer data) {
      this.compressed = compressed;
      this.data = data;
    }

    public int getSize() { return data.remaining(); }
  }
}
