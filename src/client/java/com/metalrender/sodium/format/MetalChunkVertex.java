package com.metalrender.sodium.format;

import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.api.util.ColorU8;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.system.MemoryUtil;

/**
 * MetalChunkVertex implements Sodium's ChunkVertexType using Nvidium's compact
 * 16-byte format.
 * 
 * Layout (16 bytes total):
 * - bytes 0-3: posX (16-bit) | posY (16-bit)
 * - bytes 4-7: posZ (16-bit) | material (8-bit) | blockLight (8-bit)
 * - bytes 8-11: color RGB (24-bit) | skyLight (8-bit)
 * - bytes 12-15: texU (16-bit) | texV (16-bit)
 */
public class MetalChunkVertex implements ChunkVertexType {
    public static final int STRIDE = 16;
    public static final MetalChunkVertex INSTANCE = new MetalChunkVertex();

    private static final int POSITION_MAX_VALUE = 65536;
    public static final int TEXTURE_MAX_VALUE = 32768;

    private static final float MODEL_ORIGIN = 8.0f;
    private static final float MODEL_RANGE = 32.0f;
    private static final float MODEL_SCALE_INV = POSITION_MAX_VALUE / MODEL_RANGE;

    // Dummy vertex format - Metal uses its own pipeline
    public static final GlVertexFormat VERTEX_FORMAT = GlVertexFormat.builder(STRIDE).build();

    @Override
    public GlVertexFormat getVertexFormat() {
        return VERTEX_FORMAT;
    }

    @Override
    public ChunkVertexEncoder getEncoder() {
        return (ptr, materialBits, vertices, sectionIndex) -> {
            for (int i = 0; i < 4; i++) {
                var vertex = vertices[i];

                int light = compactLight(vertex.light);

                // Byte 0-3: posX | posY
                MemoryUtil.memPutInt(ptr + 0,
                        (encodePosition(vertex.x) << 0) | (encodePosition(vertex.y) << 16));

                // Byte 4-7: posZ | material | blockLight
                MemoryUtil.memPutInt(ptr + 4,
                        (encodePosition(vertex.z) << 0) |
                                ((materialBits & 0xFF) << 16) |
                                ((light & 0xFF) << 24));

                // Byte 8-11: color RGB | skyLight
                MemoryUtil.memPutInt(ptr + 8,
                        (encodeColor(vertex.color, vertex.ao) << 0) |
                                (((light >> 8) & 0xFF) << 24));

                // Byte 12-15: texU | texV
                MemoryUtil.memPutInt(ptr + 12, encodeTexture(vertex.u, vertex.v));

                ptr += STRIDE;
            }
            return ptr;
        };
    }

    private static int compactLight(int light) {
        int sky = MathHelper.clamp((light >>> 16) & 0xFF, 8, 248);
        int block = MathHelper.clamp((light >>> 0) & 0xFF, 8, 248);
        return (block << 0) | (sky << 8);
    }

    private static int encodePosition(float v) {
        return (int) ((MODEL_ORIGIN + v) * MODEL_SCALE_INV);
    }

    private static int encodeColor(int color, float ao) {
        // Apply AO to RGB
        int r = ColorU8.normalizedFloatToByte(ColorU8.byteToNormalizedFloat(ColorABGR.unpackRed(color)) * ao);
        int g = ColorU8.normalizedFloatToByte(ColorU8.byteToNormalizedFloat(ColorABGR.unpackGreen(color)) * ao);
        int b = ColorU8.normalizedFloatToByte(ColorU8.byteToNormalizedFloat(ColorABGR.unpackBlue(color)) * ao);

        return ColorABGR.pack(r, g, b, 0x00);
    }

    private static int encodeTexture(float u, float v) {
        return ((Math.round(u * TEXTURE_MAX_VALUE) & 0xFFFF) << 0) |
                ((Math.round(v * TEXTURE_MAX_VALUE) & 0xFFFF) << 16);
    }
}
