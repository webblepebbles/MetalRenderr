package com.metalrender.util;

import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class OcclusionCuller {
   private static final int AZIMUTH_BINS = 72;
   private static final int ELEVATION_BINS = 36;
   private static final float MARGIN = 2.0F;
   private final float[] depthGrid = new float[2592];
   private double camX;
   private double camY;
   private double camZ;

   public void beginFrame(Camera camera) {
      for(int i = 0; i < this.depthGrid.length; ++i) {
         this.depthGrid[i] = Float.POSITIVE_INFINITY;
      }

      Vec3d pos = camera.getPos();
      this.camX = pos.x;
      this.camY = pos.y;
      this.camZ = pos.z;
   }

   public boolean isChunkOccluded(BlockPos chunkPos, Camera camera) {
      double cx = (double)(chunkPos.getX() << 4) + 8.0D;
      double cy = (double)chunkPos.getY();
      double cz = (double)(chunkPos.getZ() << 4) + 8.0D;
      double dx = cx - this.camX;
      double dy = cy - this.camY;
      double dz = cz - this.camZ;
      double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
      if (dist < 0.001D) {
         dist = 0.001D;
      }

      double az = Math.atan2(dz, dx);
      double el = Math.asin(dy / dist);
      int ai = (int)Math.floor((az + 3.141592653589793D) / 6.283185307179586D * 72.0D);
      int ei = (int)Math.floor((el + 1.5707963267948966D) / 3.141592653589793D * 36.0D);
      if (ai < 0) {
         ai = 0;
      } else if (ai >= 72) {
         ai = 71;
      }

      if (ei < 0) {
         ei = 0;
      } else if (ei >= 36) {
         ei = 35;
      }

      int idx = ei * 72 + ai;
      float nearest = this.depthGrid[idx];
      boolean occluded = nearest + 2.0F < (float)dist;
      if (!occluded && (float)dist < nearest) {
         this.depthGrid[idx] = (float)dist;
      }

      return occluded;
   }
}
