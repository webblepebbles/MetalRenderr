
package com.metalrender.culling;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
public class OcclusionCuller {
    private final RegionManager regions;
    public OcclusionCuller(RegionManager regions) {
        this.regions = regions;
    }
    public void update(Camera camera) {}
    public boolean isChunkOccluded(BlockPos chunkPos, Camera camera) {
        Vec3d cam = camera.getPos();
        double cx = (chunkPos.getX() << 4) + 8.0;
        double cy = (chunkPos.getY());
        double cz = (chunkPos.getZ() << 4) + 8.0;
        double dx = cx - cam.x;
        double dy = cy - cam.y;
        double dz = cz - cam.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-5;
        dx /= dist;
        dy /= dist;
        dz /= dist;
        int blockers = regions.countBlockersAlongRay(cam.x, cam.y, cam.z, dx, dy, dz, dist);
        return blockers >= 3;
    }
}
