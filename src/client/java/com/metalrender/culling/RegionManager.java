package com.metalrender.culling;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RegionManager {
   private final Map<Long, Float> density = new ConcurrentHashMap();

   public void setRegionDensity(int rx, int ry, int rz, float d) {
      this.density.put(pack(rx, ry, rz), clamp01(d));
   }

   public float getRegionDensity(int rx, int ry, int rz) {
      return (Float)this.density.getOrDefault(pack(rx, ry, rz), 0.0F);
   }

   public void clear() {
      this.density.clear();
   }

   private static long pack(int x, int y, int z) {
      return (long)x & 2097151L | ((long)y & 16383L) << 21 | ((long)z & 2097151L) << 35;
   }

   private static float clamp01(float v) {
      return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
   }

   public int countBlockersAlongRay(double ox, double oy, double oz, double dx, double dy, double dz, double maxDist) {
      double step = 8.0D;
      float threshold = 0.65F;
      int count = 0;

      for(double t = 0.0D; t < maxDist && count < 8; t += 8.0D) {
         double x = ox + dx * t;
         double y = oy + dy * t;
         double z = oz + dz * t;
         int rx = (int)Math.floor(x / 16.0D);
         int ry = (int)Math.floor(y / 16.0D);
         int rz = (int)Math.floor(z / 16.0D);
         if (this.getRegionDensity(rx, ry, rz) > 0.65F) {
            ++count;
         }
      }

      return count;
   }
}
