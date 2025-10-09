package com.metalrender.util;

import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Lightweight camera-centric angular occlusion grid.
 *
 * Idea: partition the view into azimuth x elevation bins. For each bin we store the
 * nearest observed depth this frame. A candidate chunk is considered occluded if a
 * previously recorded depth in the same bin is significantly closer than the chunk.
 *
 * Assumptions: best-effort heuristic, safe to use as a conservative test when paired
 * with frustum culling. Order-agnostic but performs best if nearer chunks are tested first.
 */
public class OcclusionCuller {
    // Tunables (small grid for speed; tweakable via future config if needed)
    private static final int AZIMUTH_BINS = 72; // 5 degrees per bin
    private static final int ELEVATION_BINS = 36; // ~5 degrees per bin
    private static final float MARGIN = 2.0f; // meters margin to avoid over-occlusion

    private final float[] depthGrid = new float[AZIMUTH_BINS * ELEVATION_BINS];
    private Vec3d camPos = Vec3d.ZERO;

    public void beginFrame(Camera camera) {
        // Reset grid to "far"
        for (int i = 0; i < depthGrid.length; i++) depthGrid[i] = Float.POSITIVE_INFINITY;
        camPos = camera.getPos();
    }

    public boolean isChunkOccluded(BlockPos chunkPos, Camera camera) {
        // Center of chunk in world space
        double cx = (chunkPos.getX() << 4) + 8.0;
        double cy = chunkPos.getY();
        double cz = (chunkPos.getZ() << 4) + 8.0;

        double dx = cx - camPos.x;
        double dy = cy - camPos.y;
        double dz = cz - camPos.z;

        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1e-3)
            dist = 1e-3;

        // Convert to spherical angles relative to camera forward
        // Compute azimuth [-pi, pi] around Y axis and elevation [-pi/2, pi/2]
        double az = Math.atan2(dz, dx); // XZ-plane angle
        double el = Math.asin(dy / dist);

        int ai = (int) Math.floor((az + Math.PI) / (2.0 * Math.PI) * AZIMUTH_BINS);
        int ei = (int) Math.floor((el + Math.PI / 2.0) / Math.PI * ELEVATION_BINS);
        if (ai < 0)
            ai = 0;
        else if (ai >= AZIMUTH_BINS)
            ai = AZIMUTH_BINS - 1;
        if (ei < 0)
            ei = 0;
        else if (ei >= ELEVATION_BINS)
            ei = ELEVATION_BINS - 1;

        int idx = ei * AZIMUTH_BINS + ai;
        float nearest = depthGrid[idx];

        // If we already have a nearer sample in this bin, consider occluded
        boolean occluded = nearest + MARGIN < (float) dist;
        if (!occluded) {
            // Update the bin with the nearer depth to occlude farther samples later
            if ((float) dist < nearest)
                depthGrid[idx] = (float) dist;
        }
        return occluded;
    }
}
