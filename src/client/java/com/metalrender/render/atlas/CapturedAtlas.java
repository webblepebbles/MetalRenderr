package com.metalrender.render.atlas;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Immutable container for BGRA atlas pixel data captured from the sprite atlas.
 */
public final class CapturedAtlas {
  private final byte[] data;
  private final int width;
  private final int height;

  public CapturedAtlas(byte[] data, int width, int height) {
    if (data == null) {
      throw new IllegalArgumentException("Atlas data cannot be null");
    }
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException(
          "Invalid atlas dimensions: " + width + "x" + height);
    }
    if (data.length != width * height * 4) {
      throw new IllegalArgumentException(
          "Atlas buffer length " + data.length +
              " does not match dimensions " + width + "x" + height);
    }
    this.data = data;
    this.width = width;
    this.height = height;
  }

  public int width() {
    return this.width;
  }

  public int height() {
    return this.height;
  }

  public int stride() {
    return this.width * 4;
  }

  public int byteSize() {
    return this.data.length;
  }

  public ByteBuffer toDirectBuffer() {
    ByteBuffer buffer = ByteBuffer.allocateDirect(this.data.length)
        .order(ByteOrder.nativeOrder());
    buffer.put(this.data);
    buffer.flip();
    return buffer;
  }

  public CapturedAtlas copy() {
    return new CapturedAtlas(Arrays.copyOf(this.data, this.data.length),
        this.width, this.height);
  }
}
