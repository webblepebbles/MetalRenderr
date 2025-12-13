package com.metalrender.memory;

/**
 * Sodium COMPACT format - 20-byte vertex layout.
 * 
 * Layout:
 * - bytes 0-3: position high bits (x10|y10|z10 upper bits, packed)
 * - bytes 4-7: position low bits (x10|y10|z10 lower bits, packed)
 * - bytes 8-11: color ARGB (with AO pre-applied)
 * - bytes 12-15: texture UV (u15+sign | v15+sign)
 * - bytes 16-19: light + material + section
 */
public final class MetalVertexLayout {
  public static final int STRIDE = 20;

  public static final int POSITION_HI_OFFSET = 0;
  public static final int POSITION_LO_OFFSET = 4;
  public static final int COLOR_OFFSET = 8;
  public static final int UV_OFFSET = 12;
  public static final int LIGHT_MATERIAL_OFFSET = 16;

  private MetalVertexLayout() {
  }
}
