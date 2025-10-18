package com.metalrender.culling;

import net.minecraft.client.render.Camera;

public class FrustumCuller {
  public void updateFrustum(Camera camera, FrustumCuller.MovementState movement,
                            float aspect, float zNear, float zFar) {}

  public boolean isRegionVisible(int regionX, int regionZ, int minY, int maxY) {
    return true;
  }

  public static enum MovementState {
    STANDING,
    WALKING,
    FLYING;

    private static FrustumCuller.MovementState[] $values() {
      return new FrustumCuller.MovementState[] {STANDING, WALKING, FLYING};
    }
  }
}
