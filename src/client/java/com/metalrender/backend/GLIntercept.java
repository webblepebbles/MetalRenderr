package com.metalrender.backend;

import com.metalrender.config.MetalRenderConfig;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GLIntercept {
  private static final int GL_ARRAY_BUFFER = 34962;
  private static final int GL_ELEMENT_ARRAY_BUFFER = 34963;
  private static final int GL_COPY_READ_BUFFER = 36662;
  private static final int GL_COPY_WRITE_BUFFER = 36663;

  private static final RenderBackend NO_OP_BACKEND = new NoOpRenderBackend();
  private static volatile RenderBackend backend;

  private static final ThreadLocal<Map<Integer, Integer>> boundBuffers = ThreadLocal.withInitial(HashMap::new);

  private static final Map<Integer, BufferState> bufferStates = new ConcurrentHashMap<>();

  private GLIntercept() {
  }

  private static RenderBackend backend() {
    if (!MetalRenderConfig.mirrorUploads()) {
      return NO_OP_BACKEND;
    }

    RenderBackend instance = backend;
    if (instance == null) {
      synchronized (GLIntercept.class) {
        if (backend == null) {
          try {
            backend = new MetalRenderer();
          } catch (Exception e) {
            backend = NO_OP_BACKEND;
          }
        }
        instance = backend;
      }
    }
    return instance;
  }

  private static boolean shouldMirror() {
    return MetalRenderConfig.mirrorUploads();
  }

  public static void onBindBuffer(int target, int buffer) {
    Map<Integer, Integer> bindings = boundBuffers.get();
    if (buffer == 0) {
      bindings.remove(target);
    } else {
      bindings.put(target, buffer);
    }
  }

  public static void onBufferData(int target, ByteBuffer data, int usage,
      int guessedStride) {
    int bufferId = currentBufferId(target);
    if (bufferId == 0 || data == null) {
      return;
    }
    BufferState state = ensureState(bufferId, kindForTarget(target));
    state.replaceContent(data, guessedStride);
  }

  public static void onBufferData(int target, long size, int usage) {
    int bufferId = currentBufferId(target);
    if (bufferId == 0 || size <= 0 || size > Integer.MAX_VALUE) {
      return;
    }
    BufferState state = ensureState(bufferId, kindForTarget(target));
    state.allocateZeros((int) size);
  }

  public static void onBufferSubData(int target, long offset, ByteBuffer data) {
    int bufferId = currentBufferId(target);
    if (bufferId == 0 || offset < 0 || offset > Integer.MAX_VALUE) {
      return;
    }
    BufferState state = bufferStates.get(bufferId);
    if (state == null) {
      return;
    }
    state.updateRange((int) offset, data);
  }

  public static boolean onCopyBufferSubData(int readTarget, int writeTarget,
      long readOffset, long writeOffset,
      long size) {
    if (size <= 0 || size > Integer.MAX_VALUE || readOffset < 0 ||
        writeOffset < 0 || readOffset > Integer.MAX_VALUE ||
        writeOffset > Integer.MAX_VALUE) {
      return false;
    }

    int readId = currentBufferId(readTarget);
    int writeId = currentBufferId(writeTarget);
    if (readId == 0 || writeId == 0) {
      return false;
    }

    BufferState source = bufferStates.get(readId);
    BufferState target = bufferStates.get(writeId);
    if (source == null || target == null) {
      return false;
    }

    int length = (int) size;
    ByteBuffer snapshot = source.snapshot((int) readOffset, length);
    if (snapshot == null) {
      return false;
    }

    target.writeRange((int) writeOffset, snapshot);
    return true;
  }

  public static void onDeleteBuffer(int id) {
    BufferState state = bufferStates.remove(id);
    if (state != null) {
      state.destroy();
    }

    Map<Integer, Integer> bindings = boundBuffers.get();
    bindings.entrySet().removeIf(entry -> entry.getValue() != null &&
        entry.getValue() == id);
  }

  public static boolean onDrawElements(int mode, int count, int type,
      long indicesOffset, float[] viewProj) {
    return false;
  }

  private static int currentBufferId(int target) {
    Map<Integer, Integer> bindings = boundBuffers.get();
    Integer bound = bindings.get(target);
    if (bound != null && bound != 0) {
      return bound;
    }
    if (target == GL_COPY_READ_BUFFER || target == GL_COPY_WRITE_BUFFER) {
      return bindings.getOrDefault(GL_ARRAY_BUFFER, 0);
    }
    return 0;
  }

  private static BufferState ensureState(int bufferId, BufferKind kind) {
    return bufferStates.compute(bufferId, (id, existing) -> {
      if (existing == null) {
        return new BufferState(kind);
      }
      if (kind != BufferKind.OTHER) {
        existing.updateKind(kind);
      }
      return existing;
    });
  }

  private static BufferKind kindForTarget(int target) {
    return switch (target) {
      case GL_ARRAY_BUFFER -> BufferKind.VERTEX;
      case GL_ELEMENT_ARRAY_BUFFER -> BufferKind.INDEX;
      default -> BufferKind.OTHER;
    };
  }

  private enum BufferKind {
    VERTEX, INDEX, OTHER
  }

  private static final class BufferState {
    private final Object lock = new Object();
    private BufferKind kind;
    private ByteBuffer shadow;
    private int size;
    private int stride;
    private long handle;

    BufferState(BufferKind kind) {
      this.kind = kind;
    }

    void updateKind(BufferKind kind) {
      synchronized (lock) {
        this.kind = kind;
      }
    }

    void replaceContent(ByteBuffer data, int stride) {
      ByteBuffer owned = copyToDirect(data);
      if (owned == null) {
        return;
      }
      synchronized (lock) {
        this.shadow = owned;
        this.size = owned.remaining();
        this.stride = stride;
        resetShadowLocked();
        uploadOrDestroyLocked();
      }
    }

    void allocateZeros(int newSize) {
      ByteBuffer owned = ByteBuffer.allocateDirect(newSize);
      synchronized (lock) {
        this.shadow = owned;
        this.size = newSize;
        resetShadowLocked();
        uploadOrDestroyLocked();
      }
    }

    void updateRange(int offset, ByteBuffer data) {
      ByteBuffer owned = copyToDirect(data);
      if (owned == null) {
        return;
      }
      writeRange(offset, owned);
    }

    ByteBuffer snapshot(int offset, int length) {
      synchronized (lock) {
        if (shadow == null || offset < 0 || length < 0 ||
            offset + length > size) {
          return null;
        }
        ByteBuffer copy = ByteBuffer.allocateDirect(length);
        ByteBuffer src = shadow.duplicate();
        src.position(offset);
        src.limit(offset + length);
        copy.put(src);
        copy.flip();
        return copy;
      }
    }

    void writeRange(int offset, ByteBuffer owned) {
      if (owned == null) {
        return;
      }
      int length = owned.remaining();
      synchronized (lock) {
        if (!ensureCapacityLocked(offset + length)) {
          return;
        }
        ByteBuffer dst = shadow.duplicate();
        dst.position(offset);
        dst.put(owned);
        size = Math.max(size, offset + length);
        resetShadowLocked();
        uploadOrDestroyLocked();
      }
    }

    void destroy() {
      synchronized (lock) {
        destroyHandleLocked();
        shadow = null;
        size = 0;
      }
    }

    private boolean ensureCapacityLocked(int required) {
      if (required < 0) {
        return false;
      }
      if (shadow != null && shadow.capacity() >= required) {
        return true;
      }
      int newCapacity = required;
      if (shadow != null) {
        newCapacity = Math.max(required, shadow.capacity() * 2);
      }
      ByteBuffer newBuffer = ByteBuffer.allocateDirect(newCapacity);
      if (shadow != null) {
        ByteBuffer old = shadow.duplicate();
        old.position(0);
        old.limit(size);
        newBuffer.put(old);
      }
      shadow = newBuffer;
      return true;
    }

    private void resetShadowLocked() {
      if (shadow != null) {
        shadow.position(0);
        shadow.limit(Math.max(size, 0));
      }
    }

    private void uploadOrDestroyLocked() {
      if (!shouldMirror() || shadow == null || size <= 0 ||
          (kind != BufferKind.VERTEX && kind != BufferKind.INDEX)) {
        destroyHandleLocked();
        return;
      }

      RenderBackend backendInstance = backend();
      if (backendInstance == NO_OP_BACKEND) {
        destroyHandleLocked();
        return;
      }

      ByteBuffer upload = shadow.duplicate();
      upload.position(0);
      upload.limit(size);
      long newHandle = kind == BufferKind.INDEX
          ? backendInstance.createIndexBuffer(upload, size)
          : backendInstance.createVertexBuffer(upload, size,
              stride > 0 ? stride : 32);
      if (newHandle != 0L) {
        destroyHandleLocked();
        handle = newHandle;
      }
    }

    private void destroyHandleLocked() {
      if (handle != 0L) {
        RenderBackend existing = backend;
        if (existing != null && existing != NO_OP_BACKEND) {
          existing.destroyBuffer(handle);
        }
        handle = 0L;
      }
    }

    private static ByteBuffer copyToDirect(ByteBuffer buffer) {
      if (buffer == null) {
        return null;
      }
      ByteBuffer src = buffer.duplicate();
      src = src.slice();
      ByteBuffer copy = ByteBuffer.allocateDirect(src.remaining());
      copy.put(src);
      copy.flip();
      return copy;
    }
  }

  private static final class NoOpRenderBackend implements RenderBackend {
    @Override
    public long createVertexBuffer(ByteBuffer data, int size, int stride) {
      return 0L;
    }

    @Override
    public long createIndexBuffer(ByteBuffer data, int size) {
      return 0L;
    }

    @Override
    public void destroyBuffer(long handle) {
    }

    @Override
    public void beginFrame(float[] viewProj) {
    }

    @Override
    public void drawIndexed(long vbo, long ibo, int indexCount, int firstIndex,
        int baseVertex) {
    }

    @Override
    public void endFrame() {
    }
  }
}
