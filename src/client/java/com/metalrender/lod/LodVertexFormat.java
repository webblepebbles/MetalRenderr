package com.metalrender.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class LodVertexFormat {
    public static final int STRIDE_LOD_TEXTURED = 12;
    public static final int STRIDE_LOD_COMPACT = 8;

    public static void putCompactVertex(ByteBuffer buf,
            float x, float y, float z,
            int colorIndex, int skyLight, int ao) {
        buf.putShort(floatToHalf(x));
        buf.putShort(floatToHalf(y));
        buf.putShort(floatToHalf(z));
        buf.put((byte) (colorIndex & 0xFF));
        buf.put((byte) (((skyLight & 0xF) << 4) | (ao & 0xF)));
    }

    public static void putTexturedVertex(ByteBuffer buf,
            float x, float y, float z, int faceDir,
            float u, float v,
            int r, int g, int b, int skyLight) {

        int qx = Math.min(1023, Math.max(0, (int) (x * 63.9375f)));
        int qy = Math.min(1023, Math.max(0, (int) (y * 63.9375f)));
        int qz = Math.min(1023, Math.max(0, (int) (z * 63.9375f)));
        int packed = (qx) | (qy << 10) | (qz << 20) | ((faceDir & 0x3) << 30);
        buf.putInt(packed);

        buf.putShort(floatToHalf(u));
        buf.putShort(floatToHalf(v));

        buf.putInt((r & 0xFF) | ((g & 0xFF) << 8) | ((b & 0xFF) << 16) | ((skyLight & 0xFF) << 24));
    }

    public static short floatToHalf(float value) {
        int fbits = Float.floatToRawIntBits(value);
        int sign = (fbits >>> 16) & 0x8000;
        int val = (fbits & 0x7FFFFFFF) + 0x1000;

        if (val >= 0x47800000) {

            if ((fbits & 0x7FFFFFFF) >= 0x47800000) {
                if (val < 0x7F800000) {
                    return (short) (sign | 0x7C00);
                }
                return (short) (sign | 0x7C00 | ((fbits & 0x007FFFFF) >>> 13));
            }
            return (short) (sign | 0x7BFF);
        }
        if (val >= 0x38800000) {

            return (short) (sign | ((val - 0x38000000) >>> 13));
        }
        if (val < 0x33000000) {

            return (short) sign;
        }

        val = (fbits & 0x7FFFFFFF) >>> 23;
        return (short) (sign | (((fbits & 0x007FFFFF) | 0x00800000) + (0x00800000 >>> (val - 102)) >>> (126 - val)));
    }

    public static float halfToFloat(short half) {
        int h = half & 0xFFFF;
        int sign = (h >>> 15) & 1;
        int exp = (h >>> 10) & 0x1F;
        int mant = h & 0x3FF;

        if (exp == 0) {
            if (mant == 0)
                return sign == 0 ? 0.0f : -0.0f;

            float val = mant / 1024.0f;
            val *= (1.0f / 16384.0f);
            return sign == 0 ? val : -val;
        }
        if (exp == 31) {
            if (mant == 0)
                return sign == 0 ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
            return Float.NaN;
        }

        float val = (1.0f + mant / 1024.0f) * (float) Math.pow(2, exp - 15);
        return sign == 0 ? val : -val;
    }

    private LodVertexFormat() {
    }
}
