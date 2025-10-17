package com.metalrender.util;

import org.joml.Matrix4f;

public class FrustumCuller {
   private final float[][] planes = new float[6][4];
   private boolean valid;

   public void update(Matrix4f viewProjection) {
      if (viewProjection == null) {
         this.valid = false;
      } else {
         this.extractPlanes(viewProjection);
         this.valid = true;
      }
   }

   private void extractPlanes(Matrix4f m) {
      this.setPlane(0, m.m30() + m.m00(), m.m31() + m.m01(), m.m32() + m.m02(), m.m33() + m.m03());
      this.setPlane(1, m.m30() - m.m00(), m.m31() - m.m01(), m.m32() - m.m02(), m.m33() - m.m03());
      this.setPlane(2, m.m30() + m.m10(), m.m31() + m.m11(), m.m32() + m.m12(), m.m33() + m.m13());
      this.setPlane(3, m.m30() - m.m10(), m.m31() - m.m11(), m.m32() - m.m12(), m.m33() - m.m13());
      this.setPlane(4, m.m30() + m.m20(), m.m31() + m.m21(), m.m32() + m.m22(), m.m33() + m.m23());
      this.setPlane(5, m.m30() - m.m20(), m.m31() - m.m21(), m.m32() - m.m22(), m.m33() - m.m23());

      for(int i = 0; i < 6; ++i) {
         this.normalizePlane(i);
      }

   }

   private void setPlane(int idx, float a, float b, float c, float d) {
      this.planes[idx][0] = a;
      this.planes[idx][1] = b;
      this.planes[idx][2] = c;
      this.planes[idx][3] = d;
   }

   private void normalizePlane(int idx) {
      float a = this.planes[idx][0];
      float b = this.planes[idx][1];
      float c = this.planes[idx][2];
      float d = this.planes[idx][3];
      float len = (float)Math.sqrt((double)(a * a + b * b + c * c));
      if (len > 1.0E-6F) {
         this.planes[idx][0] = a / len;
         this.planes[idx][1] = b / len;
         this.planes[idx][2] = c / len;
         this.planes[idx][3] = d / len;
      }

   }

   public boolean isRegionVisible(int regionX, int regionZ, int minY, int maxY) {
      if (!this.valid) {
         return true;
      } else {
         float minX = (float)regionX * 16.0F;
         float minZ = (float)regionZ * 16.0F;
         float maxX = minX + 16.0F;
         float maxZ = minZ + 16.0F;
         float minYf = (float)minY;
         float maxYf = (float)maxY;
         return this.aabbIntersectsFrustum(minX, minYf, minZ, maxX, maxYf, maxZ);
      }
   }

   public boolean aabbIntersectsFrustum(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
      for(int i = 0; i < 6; ++i) {
         float a = this.planes[i][0];
         float b = this.planes[i][1];
         float c = this.planes[i][2];
         float d = this.planes[i][3];
         float px = a >= 0.0F ? maxX : minX;
         float py = b >= 0.0F ? maxY : minY;
         float pz = c >= 0.0F ? maxZ : minZ;
         if (a * px + b * py + c * pz + d < 0.0F) {
            return false;
         }
      }

      return true;
   }

   public static enum MovementState {
      STANDING,
      WALKING,
      FLYING;

      // $FF: synthetic method
      private static FrustumCuller.MovementState[] $values() {
         return new FrustumCuller.MovementState[]{STANDING, WALKING, FLYING};
      }
   }
}
