package com.metalrender.sync;

import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;


public final class MetalFence implements AutoCloseable {
    private static final AtomicLong FENCE_COUNTER = new AtomicLong(0);
    private static final long DEFAULT_TIMEOUT_NS = 1_000_000_000L; 

    private final long id;
    private final long nativeHandle;
    private final long fenceHandle;
    private final AtomicBoolean signaled = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final long creationTime;

    
    public MetalFence(long nativeContextHandle) {
        this.id = FENCE_COUNTER.incrementAndGet();
        this.nativeHandle = nativeContextHandle;
        this.creationTime = System.nanoTime();

        
        this.fenceHandle = NativeBridge.nCreateFence(nativeContextHandle);

        if (this.fenceHandle == 0L) {
            MetalLogger.warn("[MetalFence] Failed to create fence %d", id);
        } else {
            MetalLogger.debug("[MetalFence] Created fence %d (handle=%d)", id, fenceHandle);
        }
    }

    
    public boolean poll() {
        if (destroyed.get()) {
            return true; 
        }

        if (signaled.get()) {
            return true;
        }

        if (fenceHandle == 0L) {
            signaled.set(true);
            return true;
        }

        boolean isSignaled = NativeBridge.nPollFence(nativeHandle, fenceHandle);
        if (isSignaled) {
            signaled.set(true);
        }
        return isSignaled;
    }

    
    public WaitResult await() {
        return await(DEFAULT_TIMEOUT_NS);
    }

    
    public WaitResult await(long timeoutNanos) {
        if (destroyed.get()) {
            return WaitResult.CONDITION_SATISFIED;
        }

        if (signaled.get()) {
            return WaitResult.CONDITION_SATISFIED;
        }

        if (fenceHandle == 0L) {
            signaled.set(true);
            return WaitResult.CONDITION_SATISFIED;
        }

        int result = NativeBridge.nWaitFence(nativeHandle, fenceHandle, timeoutNanos);

        switch (result) {
            case 0:
                signaled.set(true);
                return WaitResult.CONDITION_SATISFIED;
            case 1:
                return WaitResult.TIMEOUT_EXPIRED;
            default:
                MetalLogger.warn("[MetalFence] Wait failed for fence %d with error %d", id, result);
                return WaitResult.WAIT_FAILED;
        }
    }

    
    public void sync() {
        if (destroyed.get() || signaled.get() || fenceHandle == 0L) {
            return;
        }

        
        while (!signaled.get() && !destroyed.get()) {
            WaitResult result = await(100_000_000L); 
            if (result == WaitResult.CONDITION_SATISFIED || result == WaitResult.WAIT_FAILED) {
                break;
            }
        }
    }

    
    public boolean isValid() {
        return !destroyed.get() && fenceHandle != 0L;
    }

    
    public long getId() {
        return id;
    }

    
    public long getAgeNanos() {
        return System.nanoTime() - creationTime;
    }

    @Override
    public void close() {
        destroy();
    }

    
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            if (fenceHandle != 0L) {
                NativeBridge.nDestroyFence(nativeHandle, fenceHandle);
                MetalLogger.debug("[MetalFence] Destroyed fence %d", id);
            }
        }
    }

    
    public enum WaitResult {
        
        CONDITION_SATISFIED,
        
        TIMEOUT_EXPIRED,
        
        WAIT_FAILED
    }

    
    public static MetalFence create(long nativeHandle) {
        return new MetalFence(nativeHandle);
    }

    
    public static void finish(long nativeHandle) {
        try (MetalFence fence = create(nativeHandle)) {
            fence.sync();
        }
    }
}
