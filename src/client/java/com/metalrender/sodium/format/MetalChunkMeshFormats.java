package com.metalrender.sodium.format;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

/**
 * Registry for Metal-specific chunk mesh vertex formats.
 * Provides a METAL vertex type that can be used to replace Sodium's default
 * COMPACT format.
 */
public class MetalChunkMeshFormats {
    /**
     * Metal-optimized vertex format with 32-byte stride.
     * Contains position, normal, color (ABGR), UV, light, material bits, and flags.
     */
    public static final ChunkVertexType METAL = MetalChunkVertex.INSTANCE;
}
