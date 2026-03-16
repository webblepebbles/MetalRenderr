package com.pebbles_boon.metalrender.entity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.render.VertexConsumer;


public class MetalVertexConsumer implements VertexConsumer {

  private static final int VERTEX_STRIDE = 32;

  private final ByteBuffer buffer;
  private int vertexCount;
  private final int maxVertices;


  private final float[][] quadPos = new float[4][3];
  private final float[][] quadTex = new float[4][2];
  private final int[] quadColor = new int[4];
  private final int[] quadLight = new int[4];
  private final float[][] quadNormal = new float[4][3];
  private final int[][] quadOverlay = new int[4][2];
  private int quadVertexIndex = 0;


  private float posX, posY, posZ;
  private float texU, texV;
  private int colorARGB = 0xFFFFFFFF;
  private int lightPacked;
  private float normalX, normalY, normalZ;
  private int overlayU, overlayV;

  public MetalVertexConsumer(ByteBuffer buffer, int maxVertices) {
    this.buffer = buffer;
    this.maxVertices = maxVertices;
    this.vertexCount = 0;
  }

  public int getVertexCount() { return vertexCount; }

  @Override
  public VertexConsumer vertex(float x, float y, float z) {
    this.posX = x;
    this.posY = y;
    this.posZ = z;
    return this;
  }

  @Override
  public VertexConsumer color(int red, int green, int blue, int alpha) {
    this.colorARGB = (alpha << 24) | (red << 16) | (green << 8) | blue;
    return this;
  }

  @Override
  public VertexConsumer color(int argb) {
    this.colorARGB = argb;
    return this;
  }

  @Override
  public VertexConsumer texture(float u, float v) {
    this.texU = u;
    this.texV = v;
    return this;
  }

  @Override
  public VertexConsumer overlay(int u, int v) {

    this.overlayU = u;
    this.overlayV = v;
    return this;
  }

  @Override
  public VertexConsumer light(int u, int v) {
    this.lightPacked = (v << 16) | u;
    return this;
  }

  @Override
  public VertexConsumer normal(float x, float y, float z) {
    this.normalX = x;
    this.normalY = y;
    this.normalZ = z;

    commitToQuadBuffer();
    return this;
  }

  @Override
  public VertexConsumer lineWidth(float width) {
    return this;
  }


  @Override
  public void vertex(float x, float y, float z, int color, float u, float v,
                     int overlay, int light, float nx, float ny, float nz) {
    this.posX = x;
    this.posY = y;
    this.posZ = z;
    this.colorARGB = color;
    this.texU = u;
    this.texV = v;
    this.overlayU = overlay & 0xFFFF;
    this.overlayV = (overlay >> 16) & 0xFFFF;
    this.lightPacked = light;
    this.normalX = nx;
    this.normalY = ny;
    this.normalZ = nz;
    commitToQuadBuffer();
  }


  private void commitToQuadBuffer() {
    int idx = quadVertexIndex;
    quadPos[idx][0] = posX;
    quadPos[idx][1] = posY;
    quadPos[idx][2] = posZ;
    quadTex[idx][0] = texU;
    quadTex[idx][1] = texV;
    quadColor[idx] = colorARGB;
    quadLight[idx] = lightPacked;
    quadNormal[idx][0] = normalX;
    quadNormal[idx][1] = normalY;
    quadNormal[idx][2] = normalZ;
    quadOverlay[idx][0] = overlayU;
    quadOverlay[idx][1] = overlayV;

    quadVertexIndex++;
    if (quadVertexIndex == 4) {

      writeTriangleVertex(0);
      writeTriangleVertex(1);
      writeTriangleVertex(2);
      writeTriangleVertex(0);
      writeTriangleVertex(2);
      writeTriangleVertex(3);
      quadVertexIndex = 0;
    }
  }


  private void writeTriangleVertex(int qi) {
    if (vertexCount >= maxVertices || buffer.remaining() < VERTEX_STRIDE)
      return;

    float px = quadPos[qi][0], py = quadPos[qi][1], pz = quadPos[qi][2];
    float tu = quadTex[qi][0], tv = quadTex[qi][1];
    int col = quadColor[qi];
    int lit = quadLight[qi];
    float nx = quadNormal[qi][0], ny = quadNormal[qi][1],
          nz = quadNormal[qi][2];
    int ovlU = quadOverlay[qi][0], ovlV = quadOverlay[qi][1];


    buffer.putFloat(px);
    buffer.putFloat(py);
    buffer.putFloat(pz);


    int iU = (int)(Math.min(Math.max(tu, 0.0f), 1.0f) * 32767.0f);
    int iV = (int)(Math.min(Math.max(tv, 0.0f), 1.0f) * 32767.0f);
    buffer.putShort((short)(iU & 0x7FFF));
    buffer.putShort((short)(iV & 0x7FFF));


    int r = (col >> 16) & 0xFF;
    int g = (col >> 8) & 0xFF;
    int b = col & 0xFF;
    int a = (col >> 24) & 0xFF;
    buffer.put((byte)r);
    buffer.put((byte)g);
    buffer.put((byte)b);
    buffer.put((byte)a);


    buffer.put((byte)(int)((nx * 0.5f + 0.5f) * 255.0f));
    buffer.put((byte)(int)((ny * 0.5f + 0.5f) * 255.0f));
    buffer.put((byte)(int)((nz * 0.5f + 0.5f) * 255.0f));
    buffer.put((byte)255);


    buffer.putShort((short)(ovlU & 0xFFFF));
    buffer.putShort((short)(ovlV & 0xFFFF));


    int blockL = (lit & 0xFFFF) >> 4;
    int skyL = ((lit >> 16) & 0xFFFF) >> 4;
    buffer.putShort((short)(blockL & 0xFFFF));
    buffer.putShort((short)(skyL & 0xFFFF));

    vertexCount++;
  }


  public void reset() {
    vertexCount = 0;
    quadVertexIndex = 0;
    buffer.clear();
  }


  public static int getVertexStride() { return VERTEX_STRIDE; }
}
