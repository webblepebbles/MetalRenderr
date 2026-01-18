package com.metalrender.render.text;

import com.metalrender.entity.EntityTextureCache;
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

    /** Text vertex stride: pos (12) + uv (8) + color (4) = 24 bytes */
    public static final int TEXT_VERTEX_STRIDE = 24;

    /** Maximum vertices per batch */
    private static final int MAX_VERTICES = 65536;

    /** Initial capacity */
    private static final int INITIAL_CAPACITY = 16384;

    /** Native device handle */
    private long deviceHandle = 0;

    /** Whether text rendering is enabled */
    private volatile boolean enabled = false;

    /** Text vertex buffer */
    private ByteBuffer vertexBuffer;
    private int vertexCount = 0;

    /** Pending text meshes */
    private final List<TextMesh> pendingMeshes = new ArrayList<>();

    /** Current font texture */
    private String currentFontTexture = null;

    /** Screen dimensions */
    private int screenWidth = 0;
    private int screenHeight = 0;

    /** Whether rendering world text (name tags) or screen text (GUI) */
    private boolean worldSpace = false;

    /** World-space uniforms */
    private final float[] viewProjection = new float[16];
    private final float[] cameraPos = new float[4];

    /** Debug counters */
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

    /**
     * Initialize with Metal device handle.
     */
    public void initialize(long device) {
        this.deviceHandle = device;
        this.enabled = true;
        MetalLogger.info("[MetalTextRenderer] Initialized with device: {}", device);
    }

    public boolean isEnabled() {
        return enabled && deviceHandle != 0;
    }

    /**
     * Begin screen-space text rendering (GUI).
     */
    public void beginScreenText(int width, int height) {
        if (!enabled)
            return;

        frameCount++;
        totalGlyphs = 0;

        screenWidth = width;
        screenHeight = height;
        worldSpace = false;

        // Reset state
        vertexBuffer.clear();
        vertexCount = 0;
        pendingMeshes.clear();
        currentFontTexture = null;
    }

    /**
     * Begin world-space text rendering (name tags).
     */
    public void beginWorldText(Matrix4f viewProj, float camX, float camY, float camZ) {
        if (!enabled)
            return;

        worldSpace = true;

        viewProj.get(viewProjection);
        cameraPos[0] = camX;
        cameraPos[1] = camY;
        cameraPos[2] = camZ;
        cameraPos[3] = 1.0f;

        // Reset state
        vertexBuffer.clear();
        vertexCount = 0;
        pendingMeshes.clear();
        currentFontTexture = null;
    }

    /**
     * Set the current font texture.
     */
    public void setFontTexture(String fontTexture) {
        if (currentFontTexture != null && !currentFontTexture.equals(fontTexture) && vertexCount > 0) {
            flushMesh();
        }
        currentFontTexture = fontTexture;
    }

    /**
     * Add a text glyph quad.
     * 
     * @param x1    Left edge
     * @param y1    Top edge
     * @param x2    Right edge
     * @param y2    Bottom edge
     * @param u1    Left UV
     * @param v1    Top UV
     * @param u2    Right UV
     * @param v2    Bottom UV
     * @param color Text color (ARGB)
     */
    public void addGlyph(float x1, float y1, float x2, float y2,
            float u1, float v1, float u2, float v2,
            int color) {
        if (!enabled)
            return;

        if (vertexCount >= MAX_VERTICES - 6) {
            flushMesh();
        }

        if (worldSpace) {
            // World space - positions are already in world coordinates
            addVertex(x1, y1, 0, u1, v1, color);
            addVertex(x1, y2, 0, u1, v2, color);
            addVertex(x2, y2, 0, u2, v2, color);

            addVertex(x1, y1, 0, u1, v1, color);
            addVertex(x2, y2, 0, u2, v2, color);
            addVertex(x2, y1, 0, u2, v1, color);
        } else {
            // Screen space - convert to NDC
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

    /**
     * Add a glyph with z-depth for layering.
     */
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

        // Position (12 bytes)
        vertexBuffer.putFloat(x);
        vertexBuffer.putFloat(y);
        vertexBuffer.putFloat(z);

        // UV (8 bytes)
        vertexBuffer.putFloat(u);
        vertexBuffer.putFloat(v);

        // Color (4 bytes)
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

    /**
     * Flush current glyphs to a mesh.
     */
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

    /**
     * End text rendering and submit to Metal.
     */
    public void endText() {
        if (!enabled || deviceHandle == 0)
            return;

        flushMesh();

        if (pendingMeshes.isEmpty())
            return;

        // Build projection matrix
        float[] projection;
        float[] camera;

        if (worldSpace) {
            projection = viewProjection;
            camera = cameraPos;
        } else {
            // Orthographic for screen space
            Matrix4f ortho = new Matrix4f().setOrtho(-1, 1, -1, 1, -1000, 1000);
            projection = new float[16];
            ortho.get(projection);
            camera = new float[] { 0, 0, 0, 1 };
        }

        try {
            NativeBridge.nBeginEntityPass(deviceHandle, projection, camera);

            for (TextMesh mesh : pendingMeshes) {
                if (mesh.vertexCount <= 0)
                    continue;

                long textureHandle = 0;
                if (mesh.fontTexture != null) {
                    textureHandle = EntityTextureCache.getInstance().getOrCreateTexture(mesh.fontTexture);
                }
                NativeBridge.nDrawEntity(deviceHandle, mesh.vertexData, mesh.vertexCount, textureHandle);
            }

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

    /**
     * Get total glyphs rendered this frame.
     */
    public int getTotalGlyphs() {
        return totalGlyphs;
    }

    // ========== HIGH-LEVEL TEXT DRAWING METHODS ==========
    // These are called from DrawContextMixin to render text via Metal

    private static int drawTextCount = 0;

    /**
     * Draw a text string at the given position.
     * This is called from DrawContextMixin.drawText interception.
     * 
     * @param textRenderer The Minecraft TextRenderer (for glyph info)
     * @param text         The text string to draw
     * @param x            X position in screen coordinates
     * @param y            Y position in screen coordinates
     * @param color        Text color (ARGB)
     * @param shadow       Whether to draw shadow
     */
    public void drawText(TextRenderer textRenderer, String text, int x, int y, int color, boolean shadow) {
        if (!enabled || text == null || text.isEmpty()) {
            return;
        }

        drawTextCount++;

        // Ensure screen dimensions are set
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getWindow() != null) {
            screenWidth = client.getWindow().getScaledWidth();
            screenHeight = client.getWindow().getScaledHeight();
        }

        // Use MetalGuiRenderer for text since it handles GUI pass properly
        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (!gui.isEnabled()) {
            return;
        }

        // Check if frame is actually started - if not, text won't render!
        if (!gui.isFrameStarted()) {
            gui.ensureFrameStarted();
            if (!gui.isFrameStarted()) {
                return;
            }
        }

        // Extract color components from ARGB
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        // If alpha is very low (< 16), use full alpha
        // MC sometimes passes colors like 0x1ffffff where alpha is 1 (nearly
        // transparent)
        if (a < 16)
            a = 255;

        // TEXT RENDERING FIX: Use Minecraft's TextRenderer to draw text properly
        // We render using the font atlas texture with proper glyph UVs
        // The font atlas is at "minecraft:textures/font/ascii.png" for basic ASCII
        // or "minecraft:textures/font/unicode_page_XX.png" for unicode

        // Set font atlas texture - use ASCII atlas for basic text
        Identifier fontAtlas = Identifier.of("minecraft", "textures/font/ascii.png");
        gui.setTexture(fontAtlas);

        // Render each character as a textured quad from the font atlas
        // ASCII font atlas is 16x16 grid of 8x8 pixel glyphs (128x128 texture)
        // Each glyph is at position (char % 16, char / 16) in the grid
        float glyphWidth = 1.0f / 16.0f; // UV width per glyph
        float glyphHeight = 1.0f / 16.0f; // UV height per glyph
        int charHeight = 8; // Minecraft uses 8-pixel tall glyphs in ASCII font

        float currentX = x;

        // Draw shadow first if requested
        if (shadow) {
            int shadowColor = 0xFF000000 | ((r / 4) << 16) | ((g / 4) << 8) | (b / 4);
            float shadowX = x + 1;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                // Get character width from Minecraft's TextRenderer for proper proportional
                // spacing
                int charWidth = getCharacterWidth(c);

                if (c == ' ') {
                    shadowX += charWidth;
                    continue;
                }
                if (c < 32 || c > 126) {
                    shadowX += charWidth;
                    continue; // Skip non-printable/non-ASCII
                }

                // Calculate UV coordinates in the font atlas
                int gridX = c % 16;
                int gridY = c / 16;
                float u1 = gridX * glyphWidth;
                float v1 = gridY * glyphHeight;
                float u2 = u1 + glyphWidth;
                float v2 = v1 + glyphHeight;

                // Add glyph quad for shadow (use 8 for render width, actual width for advance)
                gui.addQuad((int) shadowX, y + 1, (int) (shadowX + 8), y + 1 + charHeight,
                        0f, u1, v1, u2, v2, shadowColor);
                shadowX += charWidth;
            }
        }

        // Draw the actual text
        int textColor = (a << 24) | (r << 16) | (g << 8) | b;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Get character width from Minecraft's TextRenderer for proper proportional
            // spacing
            int charWidth = getCharacterWidth(c);

            if (c == ' ') {
                currentX += charWidth;
                continue;
            }
            if (c < 32 || c > 126) {
                currentX += charWidth;
                continue; // Skip non-printable/non-ASCII
            }

            // Calculate UV coordinates in the font atlas
            int gridX = c % 16;
            int gridY = c / 16;
            float u1 = gridX * glyphWidth;
            float v1 = gridY * glyphHeight;
            float u2 = u1 + glyphWidth;
            float v2 = v1 + glyphHeight;

            // Add glyph quad (use 8 for render width, actual width for advance)
            gui.addQuad((int) currentX, y, (int) (currentX + 8), y + charHeight,
                    0f, u1, v1, u2, v2, textColor);
            currentX += charWidth;
        }

        totalGlyphs += text.length();
    }

    /**
     * Draw OrderedText (rich text) at the given position.
     * This is called from DrawContextMixin.drawText interception.
     * 
     * @param textRenderer The Minecraft TextRenderer (for glyph info)
     * @param text         The OrderedText to draw
     * @param x            X position in screen coordinates
     * @param y            Y position in screen coordinates
     * @param color        Text color (ARGB)
     * @param shadow       Whether to draw shadow
     */
    public void drawOrderedText(TextRenderer textRenderer, OrderedText text, int x, int y, int color, boolean shadow) {
        if (!enabled || text == null) {
            return;
        }

        // Convert OrderedText to plain text for width calculation
        StringBuilder sb = new StringBuilder();
        text.accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        String plainText = sb.toString();

        // Debug: Log if this is in-game menu text
        if (plainText.contains("Advancements") || plainText.contains("Statistics") ||
                plainText.contains("Mods") || plainText.contains("Options")) {
            System.out.println("[Metal] drawOrderedText: '" + plainText + "' at (" + x + "," + y + ")");
        }

        // Use the string drawing method
        drawText(textRenderer, plainText, x, y, color, shadow);
    }

    /**
     * Clean up resources.
     */
    public void destroy() {
        enabled = false;
        vertexBuffer.clear();
        pendingMeshes.clear();
        deviceHandle = 0;
    }

    /**
     * A text mesh for rendering.
     */
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

    /**
     * Get the width of a character in the Minecraft font.
     * Minecraft's default font has variable character widths.
     * This is a hardcoded table matching Minecraft's default font.
     */
    private static int getCharacterWidth(char c) {
        // Minecraft's default font character widths
        // Most characters are 5-6 pixels wide, but some are narrower/wider
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
            // Most lowercase letters
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
            // Most uppercase letters
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
            // Numbers
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
                return 6; // Default width
        }
    }
}
