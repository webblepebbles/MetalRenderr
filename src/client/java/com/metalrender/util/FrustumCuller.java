package com.metalrender.util;

import org.joml.Matrix4f;

public class FrustumCuller {
  private final float[][] planes = new float[6][4];
  private final float[][] normals = new float[6][3];
  private final float[] distances = new float[6];
  private boolean valid;
  private int totalTestsThisFrame = 0;
  private int cullsThisFrame = 0;

  public void resetFrameStats() {
    this.totalTestsThisFrame = 0;
    this.cullsThisFrame = 0;
  }

  public void update(Matrix4f viewProjection) {
    if (viewProjection == null) {
      this.valid = false;
      MetalLogger.warn("[FrustumCuller] viewProjection is NULL!");
    } else {
      this.extractPlanes(viewProjection);
      this.valid = true;
      MetalLogger.debug(
          "[FrustumCuller] Updated with viewProjection matrix, valid=%s",
          this.valid);
    }
    this.resetFrameStats();
  }

  private void extractPlanes(Matrix4f m) {
    this.setPlane(0, m.m30() + m.m00(), m.m31() + m.m01(), m.m32() + m.m02(),
                  m.m33() + m.m03());
    this.setPlane(1, m.m30() - m.m00(), m.m31() - m.m01(), m.m32() - m.m02(),
                  m.m33() - m.m03());
    this.setPlane(2, m.m30() + m.m10(), m.m31() + m.m11(), m.m32() + m.m12(),
                  m.m33() + m.m13());
    this.setPlane(3, m.m30() - m.m10(), m.m31() - m.m11(), m.m32() - m.m12(),
                  m.m33() - m.m13());
    this.setPlane(4, m.m30() + m.m20(), m.m31() + m.m21(), m.m32() + m.m22(),
                  m.m33() + m.m23());
    this.setPlane(5, m.m30() - m.m20(), m.m31() - m.m21(), m.m32() - m.m22(),
                  m.m33() - m.m23());
  }

  private void setPlane(int idx, float a, float b, float c, float d) {
    float length = (float)Math.sqrt((double)(a * a + b * b + c * c));
    if (length <= 1.0E-6F) {
      length = 1.0F;
    }
    float inv = 1.0F / length;
    float nx = a * inv;
    float ny = b * inv;
    float nz = c * inv;
    float nd = d * inv;
    this.planes[idx][0] = nx;
    this.planes[idx][1] = ny;
    this.planes[idx][2] = nz;
    this.planes[idx][3] = nd;
    this.normals[idx][0] = nx;
    this.normals[idx][1] = ny;
    this.normals[idx][2] = nz;
    this.distances[idx] = nd;
  }

  public boolean isRegionVisible(int regionX, int regionZ, int minY, int maxY) {
    ++this.totalTestsThisFrame;

    if (!this.valid) {
      ++this.cullsThisFrame;
      return true;
    }

    float minX = (float)regionX * 16.0F;
    float minZ = (float)regionZ * 16.0F;
    float maxX = minX + 16.0F;
    float maxZ = minZ + 16.0F;
    float minYf = (float)minY;
    float maxYf = (float)maxY;
    float centerX = (minX + maxX) * 0.5F;
    float centerY = (minYf + maxYf) * 0.5F;
    float centerZ = (minZ + maxZ) * 0.5F;
    float radiusX = maxX - centerX;
    float radiusY = maxYf - centerY;
    float radiusZ = maxZ - centerZ;
    float radius = (float)Math.sqrt(radiusX * radiusX + radiusY * radiusY +
                                    radiusZ * radiusZ);

    if (!this.isSphereVisible(centerX, centerY, centerZ, radius)) {
      return false;
    }

    boolean visible =
        this.aabbIntersectsFrustum(minX, minYf, minZ, maxX, maxYf, maxZ);
    if (!visible) {
      this.cullsThisFrame++;
    }
    return visible;
  }

  public void logFrameStats(String frameLabel) {
    if (this.totalTestsThisFrame > 0) {
      float cullRate =
          (float)this.cullsThisFrame / (float)this.totalTestsThisFrame * 100.0f;
      MetalLogger.info(
          "[FrustumCuller] %s: valid=%s, tests=%d, culled=%d (%.1f%%)",
          frameLabel, this.valid, this.totalTestsThisFrame, this.cullsThisFrame,
          cullRate);
    }
  }

  public boolean isSphereVisible(float cx, float cy, float cz, float radius) {
    if (!this.valid) {
      return true;
    }
    for (int i = 0; i < 6; ++i) {
      float distance = this.normals[i][0] * cx + this.normals[i][1] * cy +
                       this.normals[i][2] * cz + this.distances[i];
      if (distance < -radius) {
        return false;
      }
    }
    return true;
  }

  public boolean aabbIntersectsFrustum(float minX, float minY, float minZ,
                                       float maxX, float maxY, float maxZ) {
    for (int i = 0; i < 6; ++i) {
      float a = this.normals[i][0];
      float b = this.normals[i][1];
      float c = this.normals[i][2];
      float d = this.distances[i];
      float px = a >= 0.0F ? maxX : minX;
      float py = b >= 0.0F ? maxY : minY;
      float pz = c >= 0.0F ? maxZ : minZ;
      if (a * px + b * py + c * pz + d < 0.0F) {
        return false;
      }
    }

    return true;
  }
}
