package com.metalrender.culling;

import net.minecraft.client.render.Camera;

public class FrustumCuller {
    public enum MovementState { STANDING, WALKING, FLYING }

    public void updateFrustum(Camera camera, MovementState movement, float aspect, float zNear, float zFar) {
        // TODO: implement actual frustum plane extraction
    }

    public boolean isRegionVisible(int regionX, int regionZ, int minY, int maxY) {
        // First pass: always visible
        return true;
    }
}
