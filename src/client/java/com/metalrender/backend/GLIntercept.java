package com.metalrender.backend;

import com.metalrender.config.MetalRenderConfig;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GLIntercept {
  private static final int GL_ARRAY_BUFFER = 34962;
  private static final int GL_ELEMENT_ARRAY_BUFFER = 34963;
  private static final ThreadLocal<Integer> boundVbo =
      ThreadLocal.withInitial(() -> { return 0; });
  private static final ThreadLocal<Integer> boundIbo =
      ThreadLocal.withInitial(() -> { return 0; });
  private static final Map<Integer, Long> vboMap = new ConcurrentHashMap();
  private static final Map<Integer, Long> iboMap = new ConcurrentHashMap();
  private static final RenderBackend NO_OP_BACKEND =
      new GLIntercept.NoOpRenderBackend();
  private static volatile RenderBackend backend;

  private static RenderBackend backend() {
    if (!MetalRenderConfig.mirrorUploads()) {
      return NO_OP_BACKEND;
    } else {
      RenderBackend b = backend;
      if (b == null) {
        Class var1 = GLIntercept.class;
        synchronized (GLIntercept.class) {
          if (backend == null) {
            try {
              backend = new MetalRenderer();
            } catch (Exception var4) {
              backend = NO_OP_BACKEND;
            }
          }

          b = backend;
        }
      }

      return b;
    }
  }

  public static void onBindBuffer(int target, int buffer) {
    if (MetalRenderConfig.mirrorUploads()) {
      if (target == 34962) {
        boundVbo.set(buffer);
      } else if (target == 34963) {
        boundIbo.set(buffer);
      }
    }
  }

  public static void onBufferData(int target, ByteBuffer data, int usage,
                                  int guessedStride) {
    if (MetalRenderConfig.mirrorUploads()) {
      ;
    }
  }

  public static void onDeleteBuffer(int id) {
    if (MetalRenderConfig.mirrorUploads()) {
      Long v = (Long)vboMap.remove(id);
      if (v != null) {
        backend().destroyBuffer(v);
      }

      Long i = (Long)iboMap.remove(id);
      if (i != null) {
        backend().destroyBuffer(i);
      }

      if ((Integer)boundVbo.get() == id) {
        boundVbo.set(0);
      }

      if ((Integer)boundIbo.get() == id) {
        boundIbo.set(0);
      }
    }
  }

  public static boolean onDrawElements(int mode, int count, int type,
                                       long indicesOffset, float[] viewProj) {
    if (!MetalRenderConfig.swapOpaque() && !MetalRenderConfig.swapCutout() &&
        !MetalRenderConfig.swapTranslucent()) {
      return false;
    } else if (mode == 4 && count > 0) {
      int vboId = (Integer)boundVbo.get();
      int iboId = (Integer)boundIbo.get();
      Long vbo = (Long)vboMap.get(vboId);
      Long ibo = (Long)iboMap.get(iboId);
      return vbo != null && ibo != null ? false : false;
    } else {
      return false;
    }
  }

  private static class NoOpRenderBackend implements RenderBackend {
    public long createVertexBuffer(ByteBuffer data, int size, int stride) {
      return 0L;
    }

    public long createIndexBuffer(ByteBuffer data, int size) { return 0L; }

    public void destroyBuffer(long handle) {}

    public void beginFrame(float[] viewProj) {}

    public void drawIndexed(long vbo, long ibo, int indexCount, int firstIndex,
                            int baseVertex) {}

    public void endFrame() {}
  }
}
