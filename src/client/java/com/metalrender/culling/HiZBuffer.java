package com.metalrender.culling;

import com.metalrender.nativebridge.NativeBridge;
import java.util.concurrent.atomic.AtomicLong;

public final class HiZBuffer {
  private final AtomicLong handle = new AtomicLong();
  private int width;
  private int height;

  public boolean ensure(long contextHandle, int w, int h) {
    if (contextHandle == 0L) {
      return false;
    }
    if (w <= 0 || h <= 0) {
      return false;
    }
    if (w == this.width && h == this.height && this.handle.get() != 0L) {
      return true;
    }
    long oldHandle = this.handle.getAndSet(0L);
    if (oldHandle != 0L) {
      NativeBridge.nDestroyHiZ(contextHandle, oldHandle);
    }
    long newHandle = NativeBridge.nEnsureHiZ(contextHandle, w, h);
    if (newHandle == 0L) {
      this.width = 0;
      this.height = 0;
      return false;
    }
    this.width = w;
    this.height = h;
    this.handle.set(newHandle);
    return true;
  }

  public void destroy(long contextHandle) {
    long h = this.handle.getAndSet(0L);
    if (h != 0L) {
      NativeBridge.nDestroyHiZ(contextHandle, h);
    }
    this.width = 0;
    this.height = 0;
  }

  public long getHandle() { return this.handle.get(); }

  public int getWidth() { return this.width; }

  public int getHeight() { return this.height; }
}
