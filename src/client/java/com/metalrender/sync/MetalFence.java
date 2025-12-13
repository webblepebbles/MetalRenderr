package com.metalrender.sync;

import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MetalFence - GPU fence synchronization primitive mapped to Metal semantics.
 * 
 * In OpenGL (and Nvidium), GlFence wraps glFenceSync/glClientWaitSync.
 * In Metal, we use command buffer completion handlers and MTLSharedEvent
 * for synchronization.
 * 
 * This class provides:
 * - Creation of fence sync points after GPU work submission
 * - Non-blocking status check (poll)
 * - Blocking wait with timeout
 * - Automatic cleanup
 */
public final class MetalFence implements AutoCloseable {
    private static final AtomicLong FENCE_COUNTER = new AtomicLong(0);
    private static final long DEFAULT_TIMEOUT_NS = 1_000_000_000L; // 1 second

    private final long id;
    private final long nativeHandle;
    private final long fenceHandle;
    private final AtomicBoolean signaled = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final long creationTime;

    /**
     * Create a new fence sync point.
     * Must be called after submitting GPU work that should be tracked.
     */
    public MetalFence(long nativeContextHandle) {
        this.id = FENCE_COUNTER.incrementAndGet();
        this.nativeHandle = nativeContextHandle;
        this.creationTime = System.nanoTime();

        // Create native fence - this inserts a sync point in the command stream
        this.fenceHandle = NativeBridge.nCreateFence(nativeContextHandle);

        if (this.fenceHandle == 0L) {
            MetalLogger.warn("[MetalFence] Failed to create fence %d", id);
        } else {
            MetalLogger.debug("[MetalFence] Created fence %d (handle=%d)", id, fenceHandle);
        }
    }

    /**
     * Check if the fence has been signaled (GPU work completed) without blocking.
     * 
     * @return true if signaled, false if still pending
     */
    public boolean poll() {
        if (destroyed.get()) {
            return true; // Treat destroyed fences as signaled
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

    /**
     * Wait for the fence to be signaled with default timeout.
     * 
     * @return WaitResult indicating success, timeout, or error
     */
    public WaitResult await() {
        return await(DEFAULT_TIMEOUT_NS);
    }

    /**
     * Wait for the fence to be signaled with specified timeout.
     * 
     * @param timeoutNanos Maximum time to wait in nanoseconds
     * @return WaitResult indicating success, timeout, or error
     */
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

    /**
     * Block until the fence is signaled (no timeout).
     * Use with caution as this can stall the CPU indefinitely.
     */
    public void sync() {
        if (destroyed.get() || signaled.get() || fenceHandle == 0L) {
            return;
        }

        // Keep waiting until signaled
        while (!signaled.get() && !destroyed.get()) {
            WaitResult result = await(100_000_000L); // 100ms intervals
            if (result == WaitResult.CONDITION_SATISFIED || result == WaitResult.WAIT_FAILED) {
                break;
            }
        }
    }

    /**
     * Check if this fence is still valid (not destroyed).
     */
    public boolean isValid() {
        return !destroyed.get() && fenceHandle != 0L;
    }

    /**
     * Get the fence ID for debugging.
     */
    public long getId() {
        return id;
    }

    /**
     * Get time elapsed since fence creation in nanoseconds.
     */
    public long getAgeNanos() {
        return System.nanoTime() - creationTime;
    }

    @Override
    public void close() {
        destroy();
    }

    /**
     * Destroy the fence and release native resources.
     */
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            if (fenceHandle != 0L) {
                NativeBridge.nDestroyFence(nativeHandle, fenceHandle);
                MetalLogger.debug("[MetalFence] Destroyed fence %d", id);
            }
        }
    }

    /**
     * Result of a fence wait operation, matching GL semantics.
     */
    public enum WaitResult {
        /** The fence was signaled before the timeout */
        CONDITION_SATISFIED,
        /** The timeout expired before the fence was signaled */
        TIMEOUT_EXPIRED,
        /** An error occurred during the wait */
        WAIT_FAILED
    }

    /**
     * Create a fence and immediately return without waiting.
     * Useful for inserting sync points in the command stream.
     */
    public static MetalFence create(long nativeHandle) {
        return new MetalFence(nativeHandle);
    }

    /**
     * Create a fence, wait for it to signal, then destroy it.
     * This is equivalent to glFinish() - ensures all prior GPU work is complete.
     */
    public static void finish(long nativeHandle) {
        try (MetalFence fence = create(nativeHandle)) {
            fence.sync();
        }
    }
}
