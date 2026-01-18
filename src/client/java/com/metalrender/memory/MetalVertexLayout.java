package com.metalrender.memory;


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
