package com.metalrender.render.particle;

public class ParticleCaptureState {

    private static int frameParticleCount = 0;
    private static int totalParticleCount = 0;

    
    public static void incrementParticleCount() {
        frameParticleCount++;
        totalParticleCount++;
    }

    
    public static int getAndResetFrameCount() {
        int count = frameParticleCount;
        frameParticleCount = 0;
        return count;
    }

    
    public static int getTotalCount() {
        return totalParticleCount;
    }
}
