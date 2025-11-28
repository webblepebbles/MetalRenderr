package com.metalrender.memory;

import com.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PersistentSparseAddressableBuffer - Sparse buffer with page-level allocation.
 * 
 * This is a key Nvidium feature that allows efficient GPU memory management for
 * large world rendering. Instead of allocating one giant contiguous buffer,
 * this system divides memory into pages that can be individually committed
 * or decommitted.
 * 
 * On Nvidia with ARB_sparse_buffer, the GPU handles page-level residency.
 * On Metal, we simulate this with:
 * - Virtual address space tracking in Java
 * - Physical backing buffer that grows on demand
 * - Page table mapping virtual to physical pages
 * 
 * Key features:
 * - Virtual address space up to MAX_VIRTUAL_SIZE
 * - Physical memory allocated on demand
 * - Page-level commit/decommit for memory efficiency
 * - Defragmentation support
 * - Thread-safe allocation/deallocation
 */
public final class PersistentSparseAddressableBuffer {
    // Page size matches Metal's typical page size (64KB)
    public static final int PAGE_SIZE = 64 * 1024;
    public static final int PAGE_SHIFT = 16; // log2(PAGE_SIZE)

    // Virtual address space: 4GB maximum
    public static final long MAX_VIRTUAL_SIZE = 4L * 1024L * 1024L * 1024L;
    public static final int MAX_VIRTUAL_PAGES = (int) (MAX_VIRTUAL_SIZE / PAGE_SIZE);

    // Physical buffer grows in chunks
    private static final int PHYSICAL_CHUNK_PAGES = 256; // 16MB per chunk

    private final long maxVirtualSize;
    private final int maxVirtualPages;
    private final int pageSize;

    // Page tracking
    private final BitSet committedPages; // Which virtual pages are backed
    private final int[] pageTable; // Virtual page -> physical page mapping
    private final BitSet freePhysicalPages; // Available physical pages

    // Physical buffer
    private ByteBuffer physicalBuffer;
    private int physicalPageCount;
    private int committedPageCount;

    // Allocation tracking
    private final AtomicInteger allocatedBytes = new AtomicInteger(0);
    private final Object lock = new Object();

    // Statistics
    private int peakCommittedPages = 0;
    private long totalAllocations = 0;
    private long totalDeallocations = 0;

    /**
     * Create a sparse buffer with default parameters.
     */
    public PersistentSparseAddressableBuffer() {
        this(MAX_VIRTUAL_SIZE, PAGE_SIZE);
    }

    /**
     * Create a sparse buffer with custom parameters.
     * 
     * @param maxVirtualSize Maximum virtual address space in bytes
     * @param pageSize       Page size in bytes (must be power of 2)
     */
    public PersistentSparseAddressableBuffer(long maxVirtualSize, int pageSize) {
        if ((pageSize & (pageSize - 1)) != 0) {
            throw new IllegalArgumentException("Page size must be power of 2");
        }

        this.maxVirtualSize = maxVirtualSize;
        this.pageSize = pageSize;
        this.maxVirtualPages = (int) (maxVirtualSize / pageSize);

        this.committedPages = new BitSet(maxVirtualPages);
        this.pageTable = new int[maxVirtualPages];
        this.freePhysicalPages = new BitSet();

        // Initialize page table to -1 (unmapped)
        for (int i = 0; i < maxVirtualPages; i++) {
            pageTable[i] = -1;
        }

        // Start with initial physical allocation
        growPhysicalBuffer(PHYSICAL_CHUNK_PAGES);

        MetalLogger.info("[SparseBuffer] Created with %d MB virtual space, %d KB pages",
                maxVirtualSize / (1024 * 1024), pageSize / 1024);
    }

    /**
     * Allocate a contiguous region in virtual address space.
     * Returns the virtual offset, or -1 if allocation failed.
     */
    public long allocate(int size) {
        if (size <= 0 || size > maxVirtualSize) {
            return -1;
        }

        int pagesNeeded = (size + pageSize - 1) / pageSize;

        synchronized (lock) {
            // Find contiguous free virtual pages
            int startPage = findContiguousFreePages(pagesNeeded);
            if (startPage < 0) {
                MetalLogger.warn("[SparseBuffer] Failed to find %d contiguous pages", pagesNeeded);
                return -1;
            }

            // Commit pages
            for (int i = 0; i < pagesNeeded; i++) {
                int virtualPage = startPage + i;
                if (!commitPage(virtualPage)) {
                    // Rollback on failure
                    for (int j = 0; j < i; j++) {
                        decommitPage(startPage + j);
                    }
                    MetalLogger.warn("[SparseBuffer] Failed to commit page %d", virtualPage);
                    return -1;
                }
            }

            allocatedBytes.addAndGet(pagesNeeded * pageSize);
            totalAllocations++;

            long virtualOffset = (long) startPage * pageSize;
            MetalLogger.debug("[SparseBuffer] Allocated %d bytes at virtual offset %d (%d pages)",
                    size, virtualOffset, pagesNeeded);

            return virtualOffset;
        }
    }

    /**
     * Free a previously allocated region.
     */
    public void free(long virtualOffset, int size) {
        if (virtualOffset < 0 || size <= 0) {
            return;
        }

        int startPage = (int) (virtualOffset / pageSize);
        int pageCount = (size + pageSize - 1) / pageSize;

        synchronized (lock) {
            for (int i = 0; i < pageCount; i++) {
                int virtualPage = startPage + i;
                if (virtualPage < maxVirtualPages && committedPages.get(virtualPage)) {
                    decommitPage(virtualPage);
                }
            }

            allocatedBytes.addAndGet(-pageCount * pageSize);
            totalDeallocations++;
        }
    }

    /**
     * Write data to the buffer at the given virtual offset.
     */
    public boolean write(long virtualOffset, ByteBuffer data) {
        if (virtualOffset < 0 || data == null || !data.hasRemaining()) {
            return false;
        }

        int size = data.remaining();
        int startPage = (int) (virtualOffset / pageSize);
        int endPage = (int) ((virtualOffset + size - 1) / pageSize);

        synchronized (lock) {
            // Verify all pages are committed
            for (int page = startPage; page <= endPage; page++) {
                if (page >= maxVirtualPages || !committedPages.get(page)) {
                    MetalLogger.warn("[SparseBuffer] Write to uncommitted page %d", page);
                    return false;
                }
            }

            // Write data page by page
            int dataPos = data.position();
            int remaining = size;
            long currentOffset = virtualOffset;

            while (remaining > 0) {
                int page = (int) (currentOffset / pageSize);
                int physicalPage = pageTable[page];

                if (physicalPage < 0) {
                    return false;
                }

                int pageOffset = (int) (currentOffset % pageSize);
                int bytesToWrite = Math.min(remaining, pageSize - pageOffset);

                int physicalOffset = physicalPage * pageSize + pageOffset;

                // Copy data
                ByteBuffer slice = data.duplicate();
                slice.position(dataPos + (size - remaining));
                slice.limit(slice.position() + bytesToWrite);

                physicalBuffer.position(physicalOffset);
                physicalBuffer.put(slice);

                currentOffset += bytesToWrite;
                remaining -= bytesToWrite;
            }

            return true;
        }
    }

    /**
     * Get a read-only view of data at the given virtual offset.
     */
    public ByteBuffer read(long virtualOffset, int size) {
        if (virtualOffset < 0 || size <= 0) {
            return null;
        }

        ByteBuffer result = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());

        synchronized (lock) {
            int remaining = size;
            long currentOffset = virtualOffset;

            while (remaining > 0) {
                int page = (int) (currentOffset / pageSize);

                if (page >= maxVirtualPages || !committedPages.get(page)) {
                    return null;
                }

                int physicalPage = pageTable[page];
                if (physicalPage < 0) {
                    return null;
                }

                int pageOffset = (int) (currentOffset % pageSize);
                int bytesToRead = Math.min(remaining, pageSize - pageOffset);

                int physicalOffset = physicalPage * pageSize + pageOffset;

                physicalBuffer.position(physicalOffset);
                ByteBuffer slice = physicalBuffer.slice();
                slice.limit(bytesToRead);
                result.put(slice);

                currentOffset += bytesToRead;
                remaining -= bytesToRead;
            }
        }

        result.flip();
        return result;
    }

    /**
     * Get the underlying physical buffer for direct GPU access.
     */
    public ByteBuffer getPhysicalBuffer() {
        return physicalBuffer;
    }

    /**
     * Translate virtual offset to physical offset.
     * Returns -1 if the virtual address is not committed.
     */
    public long translateAddress(long virtualOffset) {
        int page = (int) (virtualOffset / pageSize);
        int offset = (int) (virtualOffset % pageSize);

        synchronized (lock) {
            if (page >= maxVirtualPages || !committedPages.get(page)) {
                return -1;
            }

            int physicalPage = pageTable[page];
            if (physicalPage < 0) {
                return -1;
            }

            return (long) physicalPage * pageSize + offset;
        }
    }

    /**
     * Get allocation statistics.
     */
    public Stats getStats() {
        return new Stats(
                committedPageCount * pageSize,
                physicalPageCount * pageSize,
                peakCommittedPages * pageSize,
                totalAllocations,
                totalDeallocations);
    }

    /**
     * Defragment the buffer by compacting committed pages.
     * This is expensive and should be called sparingly.
     */
    public void defragment() {
        synchronized (lock) {
            // TODO: Implement defragmentation
            // This would compact physical pages and update page table
            MetalLogger.info("[SparseBuffer] Defragmentation not yet implemented");
        }
    }

    /**
     * Destroy the buffer and release all resources.
     */
    public void destroy() {
        synchronized (lock) {
            committedPages.clear();
            freePhysicalPages.clear();
            physicalBuffer = null;
            physicalPageCount = 0;
            committedPageCount = 0;
        }
        MetalLogger.info("[SparseBuffer] Destroyed");
    }

    // ========== Private Methods ==========

    private int findContiguousFreePages(int count) {
        int consecutive = 0;
        int startPage = -1;

        for (int i = 0; i < maxVirtualPages; i++) {
            if (!committedPages.get(i)) {
                if (consecutive == 0) {
                    startPage = i;
                }
                consecutive++;
                if (consecutive >= count) {
                    return startPage;
                }
            } else {
                consecutive = 0;
                startPage = -1;
            }
        }

        return -1;
    }

    private boolean commitPage(int virtualPage) {
        if (virtualPage < 0 || virtualPage >= maxVirtualPages) {
            return false;
        }

        if (committedPages.get(virtualPage)) {
            return true; // Already committed
        }

        // Find or allocate physical page
        int physicalPage = findFreePhysicalPage();
        if (physicalPage < 0) {
            // Grow physical buffer
            if (!growPhysicalBuffer(PHYSICAL_CHUNK_PAGES)) {
                return false;
            }
            physicalPage = findFreePhysicalPage();
            if (physicalPage < 0) {
                return false;
            }
        }

        // Map virtual to physical
        committedPages.set(virtualPage);
        pageTable[virtualPage] = physicalPage;
        freePhysicalPages.clear(physicalPage);
        committedPageCount++;

        if (committedPageCount > peakCommittedPages) {
            peakCommittedPages = committedPageCount;
        }

        return true;
    }

    private void decommitPage(int virtualPage) {
        if (virtualPage < 0 || virtualPage >= maxVirtualPages) {
            return;
        }

        if (!committedPages.get(virtualPage)) {
            return;
        }

        int physicalPage = pageTable[virtualPage];

        committedPages.clear(virtualPage);
        pageTable[virtualPage] = -1;

        if (physicalPage >= 0) {
            freePhysicalPages.set(physicalPage);
        }

        committedPageCount--;
    }

    private int findFreePhysicalPage() {
        int page = freePhysicalPages.nextSetBit(0);
        return page >= physicalPageCount ? -1 : page;
    }

    private boolean growPhysicalBuffer(int additionalPages) {
        int newPageCount = physicalPageCount + additionalPages;
        int newSize = newPageCount * pageSize;

        // Limit physical buffer growth
        if (newSize > maxVirtualSize) {
            return false;
        }

        try {
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(newSize)
                    .order(ByteOrder.nativeOrder());

            // Copy existing data
            if (physicalBuffer != null) {
                physicalBuffer.position(0);
                physicalBuffer.limit(physicalPageCount * pageSize);
                newBuffer.put(physicalBuffer);
                newBuffer.position(0);
            }

            // Mark new pages as free
            for (int i = physicalPageCount; i < newPageCount; i++) {
                freePhysicalPages.set(i);
            }

            physicalBuffer = newBuffer;
            physicalPageCount = newPageCount;

            MetalLogger.info("[SparseBuffer] Grew physical buffer to %d MB (%d pages)",
                    newSize / (1024 * 1024), newPageCount);

            return true;
        } catch (OutOfMemoryError e) {
            MetalLogger.warn("[SparseBuffer] Failed to grow physical buffer: %s", e.getMessage());
            return false;
        }
    }

    // ========== Statistics Class ==========

    public static final class Stats {
        public final long committedBytes;
        public final long physicalBytes;
        public final long peakCommittedBytes;
        public final long totalAllocations;
        public final long totalDeallocations;

        Stats(long committed, long physical, long peak, long allocs, long deallocs) {
            this.committedBytes = committed;
            this.physicalBytes = physical;
            this.peakCommittedBytes = peak;
            this.totalAllocations = allocs;
            this.totalDeallocations = deallocs;
        }

        @Override
        public String toString() {
            return String.format("Committed: %d MB, Physical: %d MB, Peak: %d MB, Allocs: %d, Frees: %d",
                    committedBytes / (1024 * 1024),
                    physicalBytes / (1024 * 1024),
                    peakCommittedBytes / (1024 * 1024),
                    totalAllocations,
                    totalDeallocations);
        }
    }
}
