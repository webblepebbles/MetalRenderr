package com.metalrender.memory;

import com.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;

public final class PersistentSparseAddressableBuffer {

    public static final int PAGE_SIZE = 64 * 1024;
    public static final int PAGE_SHIFT = 16;

    public static final long MAX_VIRTUAL_SIZE = 4L * 1024L * 1024L * 1024L;
    public static final int MAX_VIRTUAL_PAGES = (int) (MAX_VIRTUAL_SIZE / PAGE_SIZE);

    private static final int PHYSICAL_CHUNK_PAGES = 256;

    private final long maxVirtualSize;
    private final int maxVirtualPages;
    private final int pageSize;

    private final BitSet committedPages;
    private final int[] pageTable;
    private final BitSet freePhysicalPages;

    private ByteBuffer physicalBuffer;
    private int physicalPageCount;
    private int committedPageCount;

    private final AtomicInteger allocatedBytes = new AtomicInteger(0);
    private final Object lock = new Object();

    private int peakCommittedPages = 0;
    private long totalAllocations = 0;
    private long totalDeallocations = 0;

    public PersistentSparseAddressableBuffer() {
        this(MAX_VIRTUAL_SIZE, PAGE_SIZE);
    }

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

        for (int i = 0; i < maxVirtualPages; i++) {
            pageTable[i] = -1;
        }

        growPhysicalBuffer(PHYSICAL_CHUNK_PAGES);

        MetalLogger.info("[SparseBuffer] Created with %d MB virtual space, %d KB pages",
                maxVirtualSize / (1024 * 1024), pageSize / 1024);
    }

    public long allocate(int size) {
        if (size <= 0 || size > maxVirtualSize) {
            return -1;
        }

        int pagesNeeded = (size + pageSize - 1) / pageSize;

        synchronized (lock) {

            int startPage = findContiguousFreePages(pagesNeeded);
            if (startPage < 0) {
                MetalLogger.warn("[SparseBuffer] Failed to find %d contiguous pages", pagesNeeded);
                return -1;
            }

            for (int i = 0; i < pagesNeeded; i++) {
                int virtualPage = startPage + i;
                if (!commitPage(virtualPage)) {

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

    public boolean write(long virtualOffset, ByteBuffer data) {
        if (virtualOffset < 0 || data == null || !data.hasRemaining()) {
            return false;
        }

        int size = data.remaining();
        int startPage = (int) (virtualOffset / pageSize);
        int endPage = (int) ((virtualOffset + size - 1) / pageSize);

        synchronized (lock) {

            for (int page = startPage; page <= endPage; page++) {
                if (page >= maxVirtualPages || !committedPages.get(page)) {
                    MetalLogger.warn("[SparseBuffer] Write to uncommitted page %d", page);
                    return false;
                }
            }

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

    public ByteBuffer getPhysicalBuffer() {
        return physicalBuffer;
    }

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

    public Stats getStats() {
        return new Stats(
                committedPageCount * pageSize,
                physicalPageCount * pageSize,
                peakCommittedPages * pageSize,
                totalAllocations,
                totalDeallocations);
    }

    public void defragment() {
        synchronized (lock) {
            if (committedPageCount == 0) {
                return;
            }

            int moved = 0;
            int nextFreeSlot = 0;
            for (int v = 0; v < maxVirtualPages; v++) {
                if (!committedPages.get(v)) {
                    continue;
                }

                int physPage = pageTable[v];
                if (physPage < 0) {
                    continue;
                }
                while (nextFreeSlot < physicalPageCount && !freePhysicalPages.get(nextFreeSlot)) {
                    if (nextFreeSlot == physPage) {
                        break;
                    }
                    nextFreeSlot++;
                }

                if (nextFreeSlot >= physicalPageCount || nextFreeSlot >= physPage) {
                    nextFreeSlot = physPage + 1;
                    continue;
                }
                int srcOffset = physPage * pageSize;
                int dstOffset = nextFreeSlot * pageSize;

                physicalBuffer.position(srcOffset);
                ByteBuffer src = physicalBuffer.slice();
                src.limit(pageSize);

                physicalBuffer.position(dstOffset);
                physicalBuffer.put(src);
                freePhysicalPages.set(physPage); 
                freePhysicalPages.clear(nextFreeSlot); 
                pageTable[v] = nextFreeSlot;

                moved++;
                nextFreeSlot++;
            }

            if (moved > 0) {
                MetalLogger.info("[SparseBuffer] Defragmentation moved %d pages", moved);
            } else {
                MetalLogger.info("[SparseBuffer] Defragmentation: already compact");
            }
        }
    }

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
            return true;
        }

        int physicalPage = findFreePhysicalPage();
        if (physicalPage < 0) {

            if (!growPhysicalBuffer(PHYSICAL_CHUNK_PAGES)) {
                return false;
            }
            physicalPage = findFreePhysicalPage();
            if (physicalPage < 0) {
                return false;
            }
        }

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

        if (newSize > maxVirtualSize) {
            return false;
        }

        try {
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(newSize)
                    .order(ByteOrder.nativeOrder());

            if (physicalBuffer != null) {
                physicalBuffer.position(0);
                physicalBuffer.limit(physicalPageCount * pageSize);
                newBuffer.put(physicalBuffer);
                newBuffer.position(0);
            }

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
