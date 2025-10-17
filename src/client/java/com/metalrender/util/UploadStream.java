package com.metalrender.util;

import java.nio.ByteBuffer;

public class UploadStream {
   private final BufferArena arena;
   private final int blockSize;

   public UploadStream(BufferArena arena, int blockSize) {
      this.arena = arena;
      this.blockSize = blockSize;
   }

   public ByteBuffer begin() {
      return this.arena.acquire();
   }

   public long commit(ByteBuffer buf) {
      return this.arena.upload(buf);
   }

   public int getBlockSize() {
      return this.blockSize;
   }
}
