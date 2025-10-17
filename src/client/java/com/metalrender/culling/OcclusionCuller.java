package com.metalrender.culling;

import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class OcclusionCuller {
   private final RegionManager regions;

   public OcclusionCuller(RegionManager regions) {
      this.regions = regions;
   }

   public void update(Camera camera) {
   }

   public boolean isChunkOccluded(BlockPos chunkPos, Camera camera) {
      Vec3d cam = camera.getPos();
      double cx = (double)(chunkPos.getX() << 4) + 8.0D;
      double cy = (double)chunkPos.getY();
      double cz = (double)(chunkPos.getZ() << 4) + 8.0D;
      double dx = cx - cam.x;
      double dy = cy - cam.y;
      double dz = cz - cam.z;
      double dist = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1.0E-5D;
      dx /= dist;
      dy /= dist;
      dz /= dist;
      int blockers = this.regions.countBlockersAlongRay(cam.x, cam.y, cam.z, dx, dy, dz, dist);
      return blockers >= 3;
   }
}
