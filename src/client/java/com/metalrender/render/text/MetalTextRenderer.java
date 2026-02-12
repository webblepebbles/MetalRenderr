package com.metalrender.render.text;

import com.metalrender.entity.EntityTextureCache;
import com.metalrender.nativebridge.EntityBatchBuilder;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.render.gui.MetalGuiRenderer;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class MetalTextRenderer {

    private static final MetalTextRenderer INSTANCE = new MetalTextRenderer();

    public static final int TEXT_VERTEX_STRIDE = 24;

    private static final int MAX_VERTICES = 65536;

    private static final int INITIAL_CAPACITY = 16384;

    private long deviceHandle = 0;
    private final EntityBatchBuilder entityBatch = new EntityBatchBuilder();

    private volatile boolean enabled = false;

    private ByteBuffer vertexBuffer;
    private int vertexCount = 0;

    private final List<TextMesh> pendingMeshes = new ArrayList<>();

    private String currentFontTexture = null;

    private int screenWidth = 0;
    private int screenHeight = 0;

    private boolean worldSpace = false;

    private final float[] viewProjection = new float[16];
    private final float[] cameraPos = new float[4];

    private int frameCount = 0;
    private int totalGlyphs = 0;

    private MetalTextRenderer() {
        allocateBuffer(INITIAL_CAPACITY);
    }

    public static MetalTextRenderer getInstance() {
        return INSTANCE;
    }

    private void allocateBuffer(int capacity) {
        vertexBuffer = ByteBuffer.allocateDirect(capacity * TEXT_VERTEX_STRIDE)
                .order(ByteOrder.nativeOrder());
    }

    public void initialize(long device) {
        this.deviceHandle = device;
        this.enabled = true;
        MetalLogger.info("[MetalTextRenderer] Initialized with device: {}", device);
    }

    public boolean isEnabled() {
        return enabled && deviceHandle != 0;
    }

    public void beginScreenText(int width, int height) {
        if (!enabled)
            return;

        frameCount++;
        totalGlyphs = 0;

        screenWidth = width;
        screenHeight = height;
        worldSpace = false;
        vertexBuffer.clear();
        vertexCount = 0;
        pendingMeshes.clear();
        currentFontTexture = null;
    }

    public void beginWorldText(Matrix4f viewProj, float camX, float camY, float camZ) {
        if (!enabled)
            return;

        worldSpace = true;

        viewProj.get(viewProjection);
        cameraPos[0] = camX;
        cameraPos[1] = camY;
        cameraPos[2] = camZ;
        cameraPos[3] = 1.0f;
        vertexBuffer.clear();
        vertexCount = 0;
        pendingMeshes.clear();
        currentFontTexture = null;
    }

    public void setFontTexture(String fontTexture) {
        if (currentFontTexture != null && !currentFontTexture.equals(fontTexture) && vertexCount > 0) {
            flushMesh();
        }
        currentFontTexture = fontTexture;
    }

    public void addGlyph(float x1, float y1, float x2, float y2,
            float u1, float v1, float u2, float v2,
            int color) {
        if (!enabled)
            return;

        if (vertexCount >= MAX_VERTICES - 6) {
            flushMesh();
        }

        if (worldSpace) {
            addVertex(x1, y1, 0, u1, v1, color);
            addVertex(x1, y2, 0, u1, v2, color);
            addVertex(x2, y2, 0, u2, v2, color);

            addVertex(x1, y1, 0, u1, v1, color);
            addVertex(x2, y2, 0, u2, v2, color);
            addVertex(x2, y1, 0, u2, v1, color);
        } else {
            float ndcX1 = (x1 / screenWidth) * 2.0f - 1.0f;
            float ndcY1 = 1.0f - (y1 / screenHeight) * 2.0f;
            float ndcX2 = (x2 / screenWidth) * 2.0f - 1.0f;
            float ndcY2 = 1.0f - (y2 / screenHeight) * 2.0f;

            addVertex(ndcX1, ndcY1, 0, u1, v1, color);
            addVertex(ndcX1, ndcY2, 0, u1, v2, color);
            addVertex(ndcX2, ndcY2, 0, u2, v2, color);

            addVertex(ndcX1, ndcY1, 0, u1, v1, color);
            addVertex(ndcX2, ndcY2, 0, u2, v2, color);
            addVertex(ndcX2, ndcY1, 0, u2, v1, color);
        }

        totalGlyphs++;
    }

    public void addGlyph(float x1, float y1, float x2, float y2, float z,
            float u1, float v1, float u2, float v2,
            int color) {
        if (!enabled)
            return;

        if (vertexCount >= MAX_VERTICES - 6) {
            flushMesh();
        }

        if (worldSpace) {
            addVertex(x1, y1, z, u1, v1, color);
            addVertex(x1, y2, z, u1, v2, color);
            addVertex(x2, y2, z, u2, v2, color);

            addVertex(x1, y1, z, u1, v1, color);
            addVertex(x2, y2, z, u2, v2, color);
            addVertex(x2, y1, z, u2, v1, color);
        } else {
            float ndcX1 = (x1 / screenWidth) * 2.0f - 1.0f;
            float ndcY1 = 1.0f - (y1 / screenHeight) * 2.0f;
            float ndcX2 = (x2 / screenWidth) * 2.0f - 1.0f;
            float ndcY2 = 1.0f - (y2 / screenHeight) * 2.0f;
            float ndcZ = z / 1000.0f;

            addVertex(ndcX1, ndcY1, ndcZ, u1, v1, color);
            addVertex(ndcX1, ndcY2, ndcZ, u1, v2, color);
            addVertex(ndcX2, ndcY2, ndcZ, u2, v2, color);

            addVertex(ndcX1, ndcY1, ndcZ, u1, v1, color);
            addVertex(ndcX2, ndcY2, ndcZ, u2, v2, color);
            addVertex(ndcX2, ndcY1, ndcZ, u2, v1, color);
        }

        totalGlyphs++;
    }

    private void addVertex(float x, float y, float z, float u, float v, int color) {
        ensureCapacity();
        vertexBuffer.putFloat(x);
        vertexBuffer.putFloat(y);
        vertexBuffer.putFloat(z);
        vertexBuffer.putFloat(u);
        vertexBuffer.putFloat(v);
        vertexBuffer.putInt(color);

        vertexCount++;
    }

    private void ensureCapacity() {
        if (vertexBuffer.remaining() < TEXT_VERTEX_STRIDE) {
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(vertexBuffer.capacity() * 2)
                    .order(ByteOrder.nativeOrder());
            vertexBuffer.flip();
            newBuffer.put(vertexBuffer);
            vertexBuffer = newBuffer;
        }
    }

    public void flushMesh() {
        if (vertexCount == 0)
            return;

        vertexBuffer.flip();
        ByteBuffer copy = ByteBuffer.allocateDirect(vertexBuffer.remaining())
                .order(ByteOrder.nativeOrder());
        copy.put(vertexBuffer);
        copy.flip();

        pendingMeshes.add(new TextMesh(copy, vertexCount, currentFontTexture));

        vertexBuffer.clear();
        vertexCount = 0;
    }

    public void endText() {
        if (!enabled || deviceHandle == 0)
            return;

        flushMesh();

        if (pendingMeshes.isEmpty())
            return;
        float[] projection;
        float[] camera;

        if (worldSpace) {
            projection = viewProjection;
            camera = cameraPos;
        } else {
            Matrix4f ortho = new Matrix4f().setOrtho(-1, 1, -1, 1, -1000, 1000);
            projection = new float[16];
            ortho.get(projection);
            camera = new float[] { 0, 0, 0, 1 };
        }

        try {
            NativeBridge.nBeginEntityPass(deviceHandle, projection, camera);

            entityBatch.begin();
            for (TextMesh mesh : pendingMeshes) {
                if (mesh.vertexCount <= 0)
                    continue;

                long textureHandle = 0;
                if (mesh.fontTexture != null) {
                    textureHandle = EntityTextureCache.getInstance().getOrCreateTexture(mesh.fontTexture);
                }
                entityBatch.addMesh(mesh.vertexData, mesh.vertexCount, textureHandle);
            }
            entityBatch.submit(deviceHandle);

            NativeBridge.nEndEntityPass(deviceHandle);

            if (frameCount <= 10 || frameCount % 300 == 0) {
                MetalLogger.info("[MetalTextRenderer] Frame {}: {} glyphs in {} meshes",
                        frameCount, totalGlyphs, pendingMeshes.size());
            }

        } catch (UnsatisfiedLinkError e) {
            if (frameCount <= 5) {
                MetalLogger.warn("[MetalTextRenderer] Native not available: {}", e.getMessage());
            }
        }

        pendingMeshes.clear();
    }

    public int getTotalGlyphs() {
        return totalGlyphs;
    }

    private static int drawTextCount = 0;

    public void drawText(TextRenderer textRenderer, String text, int x, int y, int color, boolean shadow) {
        if (!enabled || text == null || text.isEmpty()) {
            return;
        }

        drawTextCount++;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getWindow() != null) {
            screenWidth = client.getWindow().getScaledWidth();
            screenHeight = client.getWindow().getScaledHeight();
        }
        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (!gui.isEnabled()) {
            return;
        }
        if (!gui.isFrameStarted()) {
            gui.ensureFrameStarted();
            if (!gui.isFrameStarted()) {
                return;
            }
        }
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        if (a < 16)
            a = 255;
        Identifier fontAtlas = Identifier.of("minecraft", "textures/font/ascii.png");
        gui.setTexture(fontAtlas);
        float glyphWidth = 1.0f / 16.0f;
        float glyphHeight = 1.0f / 16.0f;
        int charHeight = 8;

        float currentX = x;
        if (shadow) {
            int shadowColor = 0xFF000000 | ((r / 4) << 16) | ((g / 4) << 8) | (b / 4);
            float shadowX = x + 1;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int charWidth = getCharacterWidth(c);

                if (c == ' ') {
                    shadowX += charWidth;
                    continue;
                }
                if (c < 32 || c > 126) {
                    shadowX += charWidth;
                    continue;
                }
                int gridX = c % 16;
                int gridY = c / 16;
                float u1 = gridX * glyphWidth;
                float v1 = gridY * glyphHeight;
                float u2 = u1 + glyphWidth;
                float v2 = v1 + glyphHeight;
                gui.addQuad((int) shadowX, y + 1, (int) (shadowX + 8), y + 1 + charHeight,
                        0f, u1, v1, u2, v2, shadowColor);
                shadowX += charWidth;
            }
        }
        int textColor = (a << 24) | (r << 16) | (g << 8) | b;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int charWidth = getCharacterWidth(c);

            if (c == ' ') {
                currentX += charWidth;
                continue;
            }
            if (c < 32 || c > 126) {
                currentX += charWidth;
                continue;
            }
            int gridX = c % 16;
            int gridY = c / 16;
            float u1 = gridX * glyphWidth;
            float v1 = gridY * glyphHeight;
            float u2 = u1 + glyphWidth;
            float v2 = v1 + glyphHeight;
            gui.addQuad((int) currentX, y, (int) (currentX + 8), y + charHeight,
                    0f, u1, v1, u2, v2, textColor);
            currentX += charWidth;
        }

        totalGlyphs += text.length();
    }

    public void drawOrderedText(TextRenderer textRenderer, OrderedText text, int x, int y, int color, boolean shadow) {
        if (!enabled || text == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        text.accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        String plainText = sb.toString();
        if (plainText.contains("Advancements") || plainText.contains("Statistics") ||
                plainText.contains("Mods") || plainText.contains("Options")) {
            System.out.println("[Metal] drawOrderedText: '" + plainText + "' at (" + x + "," + y + ")");
        }
        drawText(textRenderer, plainText, x, y, color, shadow);
    }

    public void destroy() {
        enabled = false;
        vertexBuffer.clear();
        pendingMeshes.clear();
        deviceHandle = 0;
    }

    public static class TextMesh {
        public final ByteBuffer vertexData;
        public final int vertexCount;
        public final String fontTexture;

        public TextMesh(ByteBuffer data, int count, String texture) {
            this.vertexData = data;
            this.vertexCount = count;
            this.fontTexture = texture;
        }
    }

    private static int getCharacterWidth(char c) {
        switch (c) {
            case ' ':
                return 4;
            case '!':
                return 2;
            case '"':
                return 5;
            case '\'':
                return 3;
            case '(':
            case ')':
                return 5;
            case '*':
                return 5;
            case ',':
            case '.':
                return 2;
            case ':':
            case ';':
                return 2;
            case '<':
            case '>':
                return 5;
            case '@':
                return 7;
            case 'I':
                return 4;
            case '[':
            case ']':
                return 4;
            case '`':
                return 3;
            case 'f':
                return 5;
            case 'i':
                return 2;
            case 'k':
                return 5;
            case 'l':
                return 3;
            case 't':
                return 4;
            case '{':
            case '}':
                return 5;
            case '|':
                return 2;
            case '~':
                return 7;
            case 'a':
            case 'b':
            case 'c':
            case 'd':
            case 'e':
            case 'g':
            case 'h':
            case 'j':
            case 'm':
            case 'n':
            case 'o':
            case 'p':
            case 'q':
            case 'r':
            case 's':
            case 'u':
            case 'v':
            case 'w':
            case 'x':
            case 'y':
            case 'z':
                return 6;
            case 'A':
            case 'B':
            case 'C':
            case 'D':
            case 'E':
            case 'F':
            case 'G':
            case 'H':
            case 'J':
            case 'K':
            case 'L':
            case 'M':
            case 'N':
            case 'O':
            case 'P':
            case 'Q':
            case 'R':
            case 'S':
            case 'T':
            case 'U':
            case 'V':
            case 'W':
            case 'X':
            case 'Y':
            case 'Z':
                return 6;
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
                return 6;
            default:
                return 6;
        }
    }
}
