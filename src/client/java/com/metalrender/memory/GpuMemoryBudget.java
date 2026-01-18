package com.metalrender.memory;

import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;


public final class GpuMemoryBudget {
    private static final long MIN_BUDGET_BYTES = 128L * 1024L * 1024L; 
    private static final long DEFAULT_BUDGET_BYTES = 512L * 1024L * 1024L; 
    private static final double SAFETY_MARGIN = 0.8; 
    private static final double WARNING_THRESHOLD = 0.9; 
    private static final double CRITICAL_THRESHOLD = 0.95; 

    private final long contextHandle;
    private long detectedVram;
    private long recommendedBudget;
    private long currentBudget;
    private boolean detectionSucceeded;

    
    private MemoryPressure currentPressure = MemoryPressure.NORMAL;
    private long lastUsageCheck = 0;
    private long lastUsage = 0;

    public GpuMemoryBudget(long contextHandle) {
        this.contextHandle = contextHandle;
        this.detectMemory();
    }

    
    private void detectMemory() {
        try {
            this.detectedVram = NativeBridge.nGetDeviceMemory(contextHandle);

            if (detectedVram > 0) {
                this.recommendedBudget = (long) (detectedVram * SAFETY_MARGIN);
                this.currentBudget = Math.max(MIN_BUDGET_BYTES, recommendedBudget);
                this.detectionSucceeded = true;

                MetalLogger.info("[GpuMemoryBudget] Detected VRAM: %d MB, Budget: %d MB",
                        detectedVram / (1024 * 1024), currentBudget / (1024 * 1024));
            } else {
                this.detectedVram = 0;
                this.recommendedBudget = DEFAULT_BUDGET_BYTES;
                this.currentBudget = DEFAULT_BUDGET_BYTES;
                this.detectionSucceeded = false;

                MetalLogger.warn("[GpuMemoryBudget] VRAM detection failed, using default: %d MB",
                        currentBudget / (1024 * 1024));
            }
        } catch (Exception e) {
            this.detectedVram = 0;
            this.recommendedBudget = DEFAULT_BUDGET_BYTES;
            this.currentBudget = DEFAULT_BUDGET_BYTES;
            this.detectionSucceeded = false;

            MetalLogger.warn("[GpuMemoryBudget] VRAM detection exception, using default", e);
        }
    }

    
    public void updatePressure() {
        long now = System.currentTimeMillis();
        if (now - lastUsageCheck < 1000) {
            return; 
        }
        lastUsageCheck = now;

        try {
            long usage = NativeBridge.nGetMemoryUsage(contextHandle);
            if (usage <= 0) {
                return;
            }

            lastUsage = usage;
            double usageRatio = detectedVram > 0
                    ? (double) usage / detectedVram
                    : (double) usage / currentBudget;

            MemoryPressure newPressure;
            if (usageRatio >= CRITICAL_THRESHOLD) {
                newPressure = MemoryPressure.CRITICAL;
            } else if (usageRatio >= WARNING_THRESHOLD) {
                newPressure = MemoryPressure.HIGH;
            } else if (usageRatio >= 0.7) {
                newPressure = MemoryPressure.MODERATE;
            } else {
                newPressure = MemoryPressure.NORMAL;
            }

            if (newPressure != currentPressure) {
                MetalLogger.info("[GpuMemoryBudget] Pressure changed: %s -> %s (usage: %.1f%%)",
                        currentPressure, newPressure, usageRatio * 100);
                currentPressure = newPressure;
            }
        } catch (Exception e) {
            
        }
    }

    
    public boolean canAllocate(long bytes) {
        if (bytes <= 0)
            return true;

        updatePressure();

        switch (currentPressure) {
            case CRITICAL:
                return false; 
            case HIGH:
                return bytes < 1024 * 1024; 
            case MODERATE:
                return bytes < currentBudget / 10; 
            default:
                return lastUsage + bytes < currentBudget;
        }
    }

    
    public long getRecommendedAllocation(AllocationCategory category) {
        long baseAllocation;

        switch (category) {
            case VERTEX_BUFFER:
                baseAllocation = currentBudget / 2; 
                break;
            case TEXTURE_ATLAS:
                baseAllocation = currentBudget / 4; 
                break;
            case INDIRECT_COMMANDS:
                baseAllocation = Math.min(16 * 1024 * 1024, currentBudget / 16);
                break;
            case OCCLUSION_BUFFERS:
                baseAllocation = Math.min(8 * 1024 * 1024, currentBudget / 32);
                break;
            default:
                baseAllocation = currentBudget / 8;
        }

        
        switch (currentPressure) {
            case CRITICAL:
                return baseAllocation / 4;
            case HIGH:
                return baseAllocation / 2;
            case MODERATE:
                return (long) (baseAllocation * 0.75);
            default:
                return baseAllocation;
        }
    }

    
    public void setManualBudget(long bytes) {
        this.currentBudget = Math.max(MIN_BUDGET_BYTES, bytes);
        MetalLogger.info("[GpuMemoryBudget] Manual budget set: %d MB",
                currentBudget / (1024 * 1024));
    }

    
    public long getCurrentBudget() {
        return currentBudget;
    }

    
    public long getDetectedVram() {
        return detectedVram;
    }

    
    public long getCurrentUsage() {
        return lastUsage;
    }

    
    public MemoryPressure getPressure() {
        return currentPressure;
    }

    
    public boolean isDetectionSucceeded() {
        return detectionSucceeded;
    }

    
    public String getStatusString() {
        if (detectionSucceeded) {
            return String.format("VRAM: %d/%d MB (%.1f%%), Pressure: %s",
                    lastUsage / (1024 * 1024),
                    detectedVram / (1024 * 1024),
                    detectedVram > 0 ? (double) lastUsage / detectedVram * 100 : 0,
                    currentPressure);
        } else {
            return String.format("VRAM: Unknown, Budget: %d MB, Pressure: %s",
                    currentBudget / (1024 * 1024), currentPressure);
        }
    }

    
    public enum MemoryPressure {
        NORMAL, 
        MODERATE, 
        HIGH, 
        CRITICAL 
    }

    
    public enum AllocationCategory {
        VERTEX_BUFFER,
        TEXTURE_ATLAS,
        INDIRECT_COMMANDS,
        OCCLUSION_BUFFERS,
        MISC
    }
}
