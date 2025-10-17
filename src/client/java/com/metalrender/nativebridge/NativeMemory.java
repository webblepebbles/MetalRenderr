package com.metalrender.nativebridge;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import sun.misc.Unsafe;

public final class NativeMemory {
   private static final Unsafe UNSAFE;

   private NativeMemory() {
   }

   public static long address(ByteBuffer directBuffer) {
      if (!directBuffer.isDirect()) {
         throw new IllegalArgumentException("Buffer must be direct");
      } else {
         try {
            Field f = Buffer.class.getDeclaredField("address");
            f.setAccessible(true);
            return f.getLong(directBuffer);
         } catch (Throwable var2) {
            throw new RuntimeException("Cannot access direct buffer address", var2);
         }
      }
   }

   public static ByteBuffer wrap(long addr, int size) {
      ByteBuffer buf = ByteBuffer.allocateDirect(size);
      return buf;
   }

   public static long malloc(long bytes) {
      return UNSAFE.allocateMemory(bytes);
   }

   public static void free(long addr) {
      UNSAFE.freeMemory(addr);
   }

   static {
      try {
         Field f = Unsafe.class.getDeclaredField("theUnsafe");
         f.setAccessible(true);
         UNSAFE = (Unsafe)f.get((Object)null);
      } catch (Throwable var1) {
         throw new RuntimeException(var1);
      }
   }
}
