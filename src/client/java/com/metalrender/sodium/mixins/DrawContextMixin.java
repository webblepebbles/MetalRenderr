package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.render.gui.MetalGuiRenderer;
import com.metalrender.render.text.MetalTextRenderer;
import com.metalrender.sodium.mixins.accessor.SpriteAtlasTextureAccessor;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.item.KeyedItemRenderState;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector2f;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(DrawContext.class)
public abstract class DrawContextMixin {

    private static int interceptCount = 0;
    private static long lastLogTime = 0;

    /** GUI atlas identifier */
    private static final Identifier GUI_ATLAS_ID = Identifier.of("minecraft", "textures/atlas/gui.png");

    @Shadow
    public abstract int getScaledWindowWidth();

    @Shadow
    public abstract int getScaledWindowHeight();

    @Shadow
    public abstract Matrix3x2fStack getMatrices();

    /**
     * Transform a point using the current matrix stack.
     * This is crucial for inventory rendering where MC applies translations.
     */
    private Vector2f transformPoint(float x, float y) {
        Matrix3x2fStack matrices = getMatrices();
        Vector2f point = new Vector2f(x, y);
        matrices.transformPosition(point);
        return point;
    }

    /**
     * Check if we should intercept rendering (In-Game only).
     * Returns true if we are in a world and should render via Metal.
     * 
     * ENABLED: Metal renders ALL GUI elements (terrain + GUI + everything).
     * OpenGL and Metal cannot share depth buffers, so Metal must do everything.
     */
    private boolean shouldIntercept() {
        if (!MetalRenderClient.isEnabled())
            return false;
        MinecraftClient client = MinecraftClient.getInstance();
        // Only intercept if we are in-game (world is loaded)
        // This leaves Main Menu, Loading Screen, etc. to Vanilla OpenGL
        return client.world != null;
    }

    // ========== drawTexture: IIFFIIIII (9 primitives) ==========
    // descriptor:
    // (Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIIII)V
    // int x, int y, float u, float v, int w, int h, int texW, int texH, int z
    @Inject(method = "drawTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawTexture_9(
            RenderPipeline pipeline, Identifier texture,
            int x, int y, float u, float v,
            int width, int height, int texWidth, int texHeight, int z,
            CallbackInfo ci) {
        if (intercept("tex9", texture, x, y, width, height, u, v, (float) width, (float) height, texWidth, texHeight,
                0xFFFFFFFF, z)) {
            ci.cancel();
        }
    }

    // ========== drawTexture: IIFFIIII (8 primitives) ==========
    // descriptor:
    // (Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIII)V
    // int x, int y, float u, float v, int w, int h, int texW, int texH
    @Inject(method = "drawTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawTexture_8(
            RenderPipeline pipeline, Identifier texture,
            int x, int y, float u, float v,
            int width, int height, int texWidth, int texHeight,
            CallbackInfo ci) {
        if (intercept("tex8", texture, x, y, width, height, u, v, (float) width, (float) height, texWidth, texHeight,
                0xFFFFFFFF, 0)) {
            ci.cancel();
        }
    }

    // ========== drawTexture: IIFFIIIIII (10 primitives) ==========
    // descriptor:
    // (Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIIIII)V
    // int x, int y, float u, float v, int w, int h, int regionW, int regionH, int
    // texW, int texH
    @Inject(method = "drawTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawTexture_10(
            RenderPipeline pipeline, Identifier texture,
            int x, int y, float u, float v,
            int width, int height, int regionW, int regionH, int texWidth, int texHeight,
            CallbackInfo ci) {
        if (intercept("tex10", texture, x, y, width, height, u, v, (float) regionW, (float) regionH, texWidth,
                texHeight, 0xFFFFFFFF, 0)) {
            ci.cancel();
        }
    }

    // ========== drawTexture: IIFFIIIIIII (11 primitives) ==========
    // descriptor:
    // (Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIIIIII)V
    // int x, int y, float u, float v, int w, int h, int regionW, int regionH, int
    // texW, int texH, int z
    @Inject(method = "drawTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIIIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawTexture_11(
            RenderPipeline pipeline, Identifier texture,
            int x, int y, float u, float v,
            int width, int height, int regionW, int regionH, int texWidth, int texHeight, int z,
            CallbackInfo ci) {
        if (intercept("tex11", texture, x, y, width, height, u, v, (float) regionW, (float) regionH, texWidth,
                texHeight, 0xFFFFFFFF, z)) {
            ci.cancel();
        }
    }

    // ========== drawGuiTexture variants ==========
    // These methods receive SPRITE IDs like "minecraft:hud/crosshair" which must be
    // resolved from the GUI atlas to get the actual texture coordinates.

    // drawGuiTexture(RenderPipeline, Identifier, int x, int y, int width, int
    // height)
    @Inject(method = "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawGuiTexture_IIII(
            RenderPipeline pipeline, Identifier spriteId,
            int x, int y, int width, int height,
            CallbackInfo ci) {
        // Intercept GUI sprites (crosshair, hotbar icons, etc.) for Metal rendering
        if (interceptGuiSprite("gui_IIII", spriteId, x, y, width, height, 0xFFFFFFFF, 0)) {
            ci.cancel();
        }
    }

    // drawGuiTexture(RenderPipeline, Identifier, int x, int y, int width, int
    // height, float alpha)
    @Inject(method = "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIIF)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawGuiTexture_IIIIF(
            RenderPipeline pipeline, Identifier spriteId,
            int x, int y, int width, int height, float alpha,
            CallbackInfo ci) {
        // Intercept GUI sprites with alpha for Metal rendering
        int color = ((int) (alpha * 255) << 24) | 0xFFFFFF;
        if (interceptGuiSprite("gui_IIIIF", spriteId, x, y, width, height, color, 0)) {
            ci.cancel();
        }
    }

    // drawGuiTexture(RenderPipeline, Identifier, int, int, int, int, int) - with z
    @Inject(method = "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawGuiTexture_IIIII(
            RenderPipeline pipeline, Identifier spriteId,
            int x, int y, int width, int height, int z,
            CallbackInfo ci) {
        // INVENTORY FIX: Enable this variant for proper button/slot rendering
        if (interceptGuiSprite("gui_IIIII", spriteId, x, y, width, height, 0xFFFFFFFF, z)) {
            ci.cancel();
        }
    }

    // drawGuiTexture(RenderPipeline, Identifier, int, int, int, int, int, int, int,
    // int)
    @Inject(method = "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIIIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawGuiTexture_IIIIIIII(
            RenderPipeline pipeline, Identifier spriteId,
            int x, int y, int width, int height, int u, int v, int texW, int texH,
            CallbackInfo ci) {
        // INVENTORY FIX: Enable this variant for custom UV sprite rendering
        if (interceptGuiSpriteWithUV("gui_IIIIIIII", spriteId, x, y, width, height, u, v, texW, texH, 0xFFFFFFFF, 0)) {
            ci.cancel();
        }
    }

    // drawGuiTexture(RenderPipeline, Identifier, int, int, int, int, int, int, int,
    // int, int) - with z
    @Inject(method = "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIIIIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawGuiTexture_IIIIIIIII(
            RenderPipeline pipeline, Identifier spriteId,
            int x, int y, int width, int height, int u, int v, int texW, int texH, int z,
            CallbackInfo ci) {
        // INVENTORY FIX: Enable this variant for custom UV sprite rendering with
        // z-depth
        if (interceptGuiSpriteWithUV("gui_IIIIIIIII", spriteId, x, y, width, height, u, v, texW, texH, 0xFFFFFFFF, z)) {
            ci.cancel();
        }
    }

    // ========== ITEM RENDERING INTERCEPTION ==========
    // Creative inventory and all GUI item slots use DrawContext.drawItem()
    // These must be intercepted to render items via Metal instead of OpenGL

    private static int itemInterceptCount = 0;
    private static long lastItemLogTime = 0;
    private static int lastLoggedFrame = 0;

    /**
     * Reset item counter at the start of each frame for fresh logging
     */
    private static void resetItemCounterIfNewFrame() {
        int currentFrame = net.minecraft.client.MinecraftClient.getInstance().inGameHud.getTicks();
        if (currentFrame != lastLoggedFrame) {
            lastLoggedFrame = currentFrame;
            itemInterceptCount = 0;
        }
    }

    /**
     * Intercept drawItem(LivingEntity, ItemStack, int x, int y, int z)
     * This is called for inventory slots with an entity context.
     */
    @Inject(method = "drawItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawItem_EntityXYZ(
            LivingEntity entity, ItemStack stack,
            int x, int y, int z,
            CallbackInfo ci) {
        if (interceptItem("drawItem_EntityXYZ", stack, x, y, z)) {
            ci.cancel();
        }
    }

    /**
     * Intercept drawItem(LivingEntity, World, ItemStack, int x, int y, int z)
     * This is called for inventory slots with entity and world context.
     */
    @Inject(method = "drawItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawItem_EntityWorldXYZ(
            LivingEntity entity, World world, ItemStack stack,
            int x, int y, int z,
            CallbackInfo ci) {
        if (interceptItem("drawItem_EntityWorldXYZ", stack, x, y, z)) {
            ci.cancel();
        }
    }

    /**
     * Intercept drawItem(ItemStack, int x, int y) - simple variant
     */
    @Inject(method = "drawItem(Lnet/minecraft/item/ItemStack;II)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawItem_XY(
            ItemStack stack,
            int x, int y,
            CallbackInfo ci) {
        if (interceptItem("drawItem_XY", stack, x, y, 0)) {
            ci.cancel();
        }
    }

    /**
     * Intercept drawItem(ItemStack, int x, int y, int z) - simple variant with z
     */
    @Inject(method = "drawItem(Lnet/minecraft/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawItem_XYZ(
            ItemStack stack,
            int x, int y, int z,
            CallbackInfo ci) {
        if (interceptItem("drawItem_XYZ", stack, x, y, z)) {
            ci.cancel();
        }
    }

    /**
     * Intercept drawItemWithoutEntity(ItemStack, int x, int y)
     */
    @Inject(method = "drawItemWithoutEntity(Lnet/minecraft/item/ItemStack;II)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawItemWithoutEntity_XY(
            ItemStack stack,
            int x, int y,
            CallbackInfo ci) {
        if (interceptItem("drawItemWithoutEntity_XY", stack, x, y, 0)) {
            ci.cancel();
        }
    }

    /**
     * Intercept drawItemWithoutEntity(ItemStack, int x, int y, int z)
     */
    @Inject(method = "drawItemWithoutEntity(Lnet/minecraft/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawItemWithoutEntity_XYZ(
            ItemStack stack,
            int x, int y, int z,
            CallbackInfo ci) {
        if (interceptItem("drawItemWithoutEntity_XYZ", stack, x, y, z)) {
            ci.cancel();
        }
    }

    /**
     * Helper to intercept item rendering and render directly via Metal.
     * 
     * PHASE 1: Render items as colored placeholders.
     * Each item gets a unique color based on its registry ID.
     * 
     * Returns true to cancel vanilla OpenGL rendering.
     */
    private static int itemDebugCount = 0;

    /** Block atlas identifier */
    private static final Identifier BLOCKS_ATLAS_ID = Identifier.of("minecraft", "textures/atlas/blocks.png");
    /** Items atlas identifier - separate from blocks atlas! */
    private static final Identifier ITEMS_ATLAS_ID = Identifier.of("minecraft", "textures/atlas/items.png");

    /** Cached atlas we found the sprite in - for texture binding */
    private Identifier lastSpriteAtlas = null;

    /**
     * Look up an item's sprite from the items atlas or blocks atlas.
     * Items atlas contains item textures (arrow, bow, etc).
     * Blocks atlas contains block textures (dirt, stone, etc).
     */
    private static int spriteLookupDebugCount = 0;
    private static boolean loggedAllSprites = false;

    private Sprite lookupItemSprite(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        lastSpriteAtlas = null;

        try {
            MinecraftClient mc = MinecraftClient.getInstance();

            // Get the item's registry ID
            Identifier itemId = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());

            // Try items atlas first (for items like arrow, bow, bone, etc)
            AbstractTexture itemsTexture = mc.getTextureManager().getTexture(ITEMS_ATLAS_ID);
            if (itemsTexture instanceof SpriteAtlasTexture itemsAtlas) {
                SpriteAtlasTextureAccessor accessor = (SpriteAtlasTextureAccessor) itemsAtlas;
                Map<Identifier, Sprite> sprites = accessor.metalrender$getSprites();
                if (sprites != null) {
                    // Debug: log first 50 sprite names on first call
                    if (!loggedAllSprites) {
                        loggedAllSprites = true;
                        System.out.println(
                                "[DrawContextMixin] ITEMS Atlas has " + sprites.size() + " sprites. First 50:");
                        int count = 0;
                        for (Identifier spriteKey : sprites.keySet()) {
                            if (count++ < 50) {
                                System.out.println("[DrawContextMixin]   ITEMS: " + spriteKey);
                            }
                        }
                    }

                    // Try to find the item sprite - items use "item/name" format
                    Identifier spriteId = Identifier.of(itemId.getNamespace(), "item/" + itemId.getPath());
                    Sprite sprite = sprites.get(spriteId);

                    if (sprite != null) {
                        lastSpriteAtlas = ITEMS_ATLAS_ID;
                        return sprite;
                    }
                }
            }

            // Try blocks atlas (for blocks like dirt, stone, etc)
            AbstractTexture blocksTexture = mc.getTextureManager().getTexture(BLOCKS_ATLAS_ID);
            if (blocksTexture instanceof SpriteAtlasTexture blocksAtlas) {
                SpriteAtlasTextureAccessor accessor = (SpriteAtlasTextureAccessor) blocksAtlas;
                Map<Identifier, Sprite> sprites = accessor.metalrender$getSprites();
                if (sprites != null) {
                    // For blocks rendered as items, try "block/name" format
                    Identifier spriteId = Identifier.of(itemId.getNamespace(), "block/" + itemId.getPath());
                    Sprite sprite = sprites.get(spriteId);

                    if (sprite != null) {
                        lastSpriteAtlas = BLOCKS_ATLAS_ID;
                        return sprite;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore - will fall back to solid color
        }
        return null;
    }

    private boolean interceptItem(String source, ItemStack stack, int x, int y, int z) {
        if (!shouldIntercept() || stack == null || stack.isEmpty()) {
            return false;
        }

        try {
            MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
            if (!gui.isEnabled()) {
                return false;
            }

            // Item size in GUI (standard is 16x16 pixels)
            int itemSize = 16;

            // IMPORTANT: Transform coordinates by the current matrix
            // When rendering inventory items, MC applies a translation to center the window
            // We need to apply that same transformation to get correct screen coordinates

            // Access the stack via method
            Matrix3x2fStack matrices = getMatrices();

            // Transform the top-left corner
            Vector2f pos1 = new Vector2f(x, y);
            matrices.transformPosition(pos1);
            float screenX1 = pos1.x;
            float screenY1 = pos1.y;

            // Transform the bottom-right corner
            Vector2f pos2 = new Vector2f(x + itemSize, y + itemSize);
            matrices.transformPosition(pos2);
            float screenX2 = pos2.x;
            float screenY2 = pos2.y;

            // Try to look up the item's sprite from the items atlas or blocks atlas
            Sprite sprite = lookupItemSprite(stack);

            // Debug logging - increased limit and add screen context
            itemDebugCount++;
            MinecraftClient mc = MinecraftClient.getInstance();
            String screenName = mc.currentScreen != null ? mc.currentScreen.getClass().getSimpleName() : "InGame";
            if (itemDebugCount <= 100 || itemDebugCount % 200 == 0) {
                Identifier itemRegId = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
                System.out.println("[DrawContextMixin] ITEM: " + source + " screen=" + screenName +
                        " item=" + itemRegId +
                        " localPos=(" + x + "," + y + ")" +
                        " screenPos=(" + screenX1 + "," + screenY1 + ")" +
                        " sprite=" + (sprite != null ? "FOUND" : "NOT_FOUND"));
            }

            if (sprite != null && lastSpriteAtlas != null) {
                // Use the sprite's atlas texture and UV coordinates
                gui.setTexture(lastSpriteAtlas);

                float u1 = sprite.getMinU();
                float v1 = sprite.getMinV();
                float u2 = sprite.getMaxU();
                float v2 = sprite.getMaxV();

                // Log EVERY inventory item quad addition - always log for inventory
                if (screenName.contains("Inventory")) {
                    System.out.println("[DrawContextMixin] INVENTORY QUAD: " + stack.getItem() +
                            " local=(" + x + "," + y + ") screen=(" + screenX1 + "," + screenY1 + ") atlas="
                            + lastSpriteAtlas);
                }

                // Normalize z to a reasonable GUI depth range
                // MC passes large z-values (depth offsets) but we want small GUI z-values
                // Use a fixed small z for GUI items to keep them in the GUI layer
                float guiZ = 100.0f; // Fixed z for GUI items

                // Render with transformed screen coordinates
                gui.addQuad(screenX1, screenY1, screenX2, screenY2, guiZ, u1, v1, u2, v2, 0xFFFFFFFF);

                if (itemDebugCount <= 30) {
                    System.out.println("[DrawContextMixin]   -> atlas=" + lastSpriteAtlas + " UV=(" + u1 + "," + v1
                            + ")-(" + u2 + "," + v2 + ")");
                }
            } else {
                // No sprite found - this is likely a 3D block item (stairs, fences, etc.)
                // DON'T intercept - let vanilla rendering handle 3D model items
                // They will be captured through VCP mixin instead
                if (itemDebugCount <= 30) {
                    System.out.println("[DrawContextMixin]   -> NO SPRITE - letting vanilla render 3D model");
                }
                return false; // Let vanilla handle 3D block items
            }

            return true; // Cancel vanilla OpenGL rendering for items with sprites

        } catch (Exception e) {
            System.err.println("[DrawContextMixin] Error rendering item via Metal: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // ========== fill variants ==========

    // fill(int x1, int y1, int x2, int y2, int color) - no pipeline
    @Inject(method = "fill(IIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$fill_IIIII(int x1, int y1, int x2, int y2, int color, CallbackInfo ci) {
        if (interceptFill("fill_IIIII", x1, y1, x2, y2, color, 0)) {
            ci.cancel();
        }
    }

    // fill(RenderPipeline, int x1, int y1, int x2, int y2, int color)
    @Inject(method = "fill(Lcom/mojang/blaze3d/pipeline/RenderPipeline;IIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$fill_IIIII_pipeline(RenderPipeline pipeline, int x1, int y1, int x2, int y2, int color,
            CallbackInfo ci) {
        if (interceptFill("fill_P_IIIII", x1, y1, x2, y2, color, 0)) {
            ci.cancel();
        }
    }

    // fillGradient(int x1, int y1, int x2, int y2, int colorTop, int colorBottom)
    @Inject(method = "fillGradient(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$fillGradient_IIIIII(int x1, int y1, int x2, int y2, int colorTop, int colorBottom,
            CallbackInfo ci) {
        // Use top color as solid fill for now
        if (interceptFill("fillGrad", x1, y1, x2, y2, colorTop, 0)) {
            ci.cancel();
        }
    }

    // ========== drawSpriteStretched ==========

    // drawSpriteStretched(RenderPipeline, Sprite, int x, int y, int width, int
    // height)
    @Inject(method = "drawSpriteStretched(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/client/texture/Sprite;IIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawSpriteStretched_IIII(
            RenderPipeline pipeline, Sprite sprite,
            int x, int y, int width, int height,
            CallbackInfo ci) {
        if (interceptSprite("sprite_IIII", x, y, 0, width, height, sprite, 0xFFFFFFFF)) {
            ci.cancel();
        }
    }

    // drawSpriteStretched(RenderPipeline, Sprite, int x, int y, int width, int
    // height, int color)
    @Inject(method = "drawSpriteStretched(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/client/texture/Sprite;IIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawSpriteStretched_IIIII(
            RenderPipeline pipeline, Sprite sprite,
            int x, int y, int width, int height, int color,
            CallbackInfo ci) {
        if (interceptSprite("sprite_IIIII", x, y, 0, width, height, sprite, color)) {
            ci.cancel();
        }
    }

    // ========== drawTexturedQuad (used by many internal methods) ==========
    // drawTexturedQuad(Identifier, int x1, int y1, int x2, int y2, float u1, float
    // v1, float u2, float v2)
    @Inject(method = "drawTexturedQuad(Lnet/minecraft/util/Identifier;IIIIFFFF)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawTexturedQuad_IIIIFFFF(
            Identifier texture,
            int x1, int y1, int x2, int y2,
            float u1, float v1, float u2, float v2,
            CallbackInfo ci) {
        if (!shouldIntercept())
            return;

        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (!gui.isEnabled())
            return;

        // Transform coordinates using the current matrix stack
        Matrix3x2fStack matrices = getMatrices();
        Vector2f pos1 = new Vector2f(x1, y1);
        Vector2f pos2 = new Vector2f(x2, y2);
        matrices.transformPosition(pos1);
        matrices.transformPosition(pos2);
        float screenX1 = pos1.x;
        float screenY1 = pos1.y;
        float screenX2 = pos2.x;
        float screenY2 = pos2.y;

        gui.setTexture(texture);
        gui.addQuad(screenX1, screenY1, screenX2, screenY2, 0f, u1, v1, u2, v2, 0xFFFFFFFF);

        interceptCount++;

        ci.cancel();
    }

    // ========== Helper methods ==========

    /**
     * Look up a sprite from the GUI atlas.
     */
    private Sprite lookupGuiSprite(Identifier spriteId) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            AbstractTexture texture = mc.getTextureManager().getTexture(GUI_ATLAS_ID);

            if (texture instanceof SpriteAtlasTexture atlas) {
                SpriteAtlasTextureAccessor accessor = (SpriteAtlasTextureAccessor) atlas;
                Map<Identifier, Sprite> sprites = accessor.metalrender$getSprites();
                if (sprites != null) {
                    return sprites.get(spriteId);
                }
            }
        } catch (Exception e) {
            // Ignore - will fall back
        }
        return null;
    }

    private static int spriteNotFoundCount = 0;
    private static long lastSpriteLogTime = 0;
    private static int guiSpriteLogCount = 0;

    /**
     * Intercept GUI sprite draws by resolving the sprite from the GUI atlas.
     * This is the key fix: sprite IDs like "minecraft:hud/crosshair" must be
     * looked up in the GUI atlas to get the actual texture and UV coordinates.
     */
    private boolean interceptGuiSprite(String type, Identifier spriteId, int x, int y,
            int width, int height, int color, int z) {
        if (!shouldIntercept())
            return false;

        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (!gui.isEnabled())
            return false;

        // Debug logging for GUI sprites
        guiSpriteLogCount++;
        if (guiSpriteLogCount <= 50) {
            System.out.println("[DrawContextMixin] interceptGuiSprite: " + type + " sprite=" + spriteId +
                    " pos=(" + x + "," + y + ") size=" + width + "x" + height);
        }

        try {
            // Look up sprite from GUI atlas
            Sprite sprite = lookupGuiSprite(spriteId);

            if (sprite != null) {
                // Transform coordinates using the current matrix stack
                Matrix3x2fStack matrices = getMatrices();
                Vector2f pos1 = new Vector2f(x, y);
                Vector2f pos2 = new Vector2f(x + width, y + height);
                matrices.transformPosition(pos1);
                matrices.transformPosition(pos2);
                float screenX1 = pos1.x;
                float screenY1 = pos1.y;
                float screenX2 = pos2.x;
                float screenY2 = pos2.y;

                // Use the atlas texture and the sprite's UV coordinates
                Identifier atlasId = sprite.getAtlasId();
                gui.setTexture(atlasId);

                float u1 = sprite.getMinU();
                float v1 = sprite.getMinV();
                float u2 = sprite.getMaxU();
                float v2 = sprite.getMaxV();

                gui.addQuad(screenX1, screenY1, screenX2, screenY2, (float) z, u1, v1, u2, v2, color);
            } else {
                // Sprite not found in atlas - log this for debugging
                spriteNotFoundCount++;
                long now = System.currentTimeMillis();
                if (now - lastSpriteLogTime > 5000 && spriteNotFoundCount < 100) {
                    lastSpriteLogTime = now;
                    com.metalrender.util.MetalLogger.warn("[DrawContextMixin] Sprite not found: {} (count={})",
                            spriteId, spriteNotFoundCount);
                }
                // Fall back to trying as regular texture - DON'T render with 0,0,1,1 UVs
                // which would show the entire atlas. Instead, let vanilla handle it.
                return false;
            }
        } catch (Exception e) {
            // Let vanilla handle it if we can't resolve the sprite
            com.metalrender.util.MetalLogger.warn("[DrawContextMixin] Exception resolving sprite {}: {}",
                    spriteId, e.getMessage());
            return false;
        }

        interceptCount++;

        return true;
    }

    /**
     * Intercept GUI sprite draws with custom UV offsets within the sprite.
     */
    private boolean interceptGuiSpriteWithUV(String type, Identifier spriteId, int x, int y,
            int width, int height, int u, int v, int texW, int texH, int color, int z) {
        if (!shouldIntercept())
            return false;

        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (!gui.isEnabled())
            return false;

        try {
            Sprite sprite = lookupGuiSprite(spriteId);

            if (sprite != null) {
                // Transform coordinates using the current matrix stack
                Matrix3x2fStack matrices = getMatrices();
                Vector2f pos1 = new Vector2f(x, y);
                Vector2f pos2 = new Vector2f(x + width, y + height);
                matrices.transformPosition(pos1);
                matrices.transformPosition(pos2);
                float screenX1 = pos1.x;
                float screenY1 = pos1.y;
                float screenX2 = pos2.x;
                float screenY2 = pos2.y;

                Identifier atlasId = sprite.getAtlasId();
                gui.setTexture(atlasId);

                // Calculate UVs with offset within the sprite
                float spriteU1 = sprite.getMinU();
                float spriteV1 = sprite.getMinV();
                float spriteURange = sprite.getMaxU() - sprite.getMinU();
                float spriteVRange = sprite.getMaxV() - sprite.getMinV();

                float u1 = spriteU1 + (spriteURange * u / texW);
                float v1 = spriteV1 + (spriteVRange * v / texH);
                float u2 = spriteU1 + (spriteURange * (u + width) / texW);
                float v2 = spriteV1 + (spriteVRange * (v + height) / texH);

                gui.addQuad(screenX1, screenY1, screenX2, screenY2, (float) z, u1, v1, u2, v2, color);
            } else {
                // Sprite not found - let vanilla handle it instead of potentially
                // rendering the wrong thing
                return false;
            }
        } catch (Exception e) {
            // Let vanilla handle exceptions
            return false;
        }

        interceptCount++;

        return true;
    }

    private static int textureLogCount = 0;

    private boolean intercept(String type, Identifier texture, int x, int y, int width, int height,
            float u, float v, float regionW, float regionH, int texWidth, int texHeight,
            int color, int z) {
        if (!shouldIntercept())
            return false;

        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (!gui.isEnabled())
            return false;

        // Transform coordinates using the current matrix stack
        Matrix3x2fStack matrices = getMatrices();
        Vector2f pos1 = new Vector2f(x, y);
        Vector2f pos2 = new Vector2f(x + width, y + height);
        matrices.transformPosition(pos1);
        matrices.transformPosition(pos2);
        float screenX1 = pos1.x;
        float screenY1 = pos1.y;
        float screenX2 = pos2.x;
        float screenY2 = pos2.y;

        // Debug logging for textures
        textureLogCount++;
        if (textureLogCount <= 50) {
            System.out.println("[DrawContextMixin] intercept TEXTURE: " + type + " tex=" + texture +
                    " local=(" + x + "," + y + ") screen=(" + screenX1 + "," + screenY1 + ") size=" + width + "x"
                    + height);
        }

        gui.setTexture(texture);

        float u1 = u / texWidth;
        float v1 = v / texHeight;
        float u2 = (u + regionW) / texWidth;
        float v2 = (v + regionH) / texHeight;

        gui.addQuad(screenX1, screenY1, screenX2, screenY2, (float) z, u1, v1, u2, v2, color);

        interceptCount++;

        return true;
    }

    private static int fillLogCount = 0;

    private boolean interceptFill(String type, int x1, int y1, int x2, int y2, int color, int z) {
        if (!shouldIntercept())
            return false;

        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (!gui.isEnabled())
            return false;

        // Transform coordinates using the current matrix stack
        // MC's fill() applies matrix transforms internally, but we intercept before
        // that
        Matrix3x2fStack matrices = getMatrices();
        Vector2f pos1 = new Vector2f(x1, y1);
        Vector2f pos2 = new Vector2f(x2, y2);
        matrices.transformPosition(pos1);
        matrices.transformPosition(pos2);
        float screenX1 = pos1.x;
        float screenY1 = pos1.y;
        float screenX2 = pos2.x;
        float screenY2 = pos2.y;

        // Extract alpha for transparency check
        int alpha = (color >> 24) & 0xFF;

        // Debug logging for fills
        fillLogCount++;
        if (fillLogCount <= 50 || fillLogCount % 100 == 0) {
            System.out.println(
                    "[DrawContextMixin] interceptFill: " + type + " local=(" + x1 + "," + y1 + ")->(" + x2 + "," + y2 +
                            ") screen=(" + screenX1 + "," + screenY1 + ")->(" + screenX2 + "," + screenY2 +
                            ") color=0x" + Integer.toHexString(color) + " alpha=" + alpha + " z=" + z);
        }

        // Skip fully transparent fills (they're invisible anyway)
        if (alpha == 0) {
            interceptCount++;
            return true; // Cancel the original call but don't render
        }

        gui.setTexture(null);
        gui.addQuad(screenX1, screenY1, screenX2, screenY2, (float) z, 0, 0, 1, 1, color);

        interceptCount++;

        return true;
    }

    private boolean interceptSprite(String type, int x, int y, int z, int width, int height,
            Sprite sprite, int color) {
        if (!shouldIntercept())
            return false;

        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (!gui.isEnabled())
            return false;

        // Transform coordinates using the current matrix stack
        Matrix3x2fStack matrices = getMatrices();
        Vector2f pos1 = new Vector2f(x, y);
        Vector2f pos2 = new Vector2f(x + width, y + height);
        matrices.transformPosition(pos1);
        matrices.transformPosition(pos2);
        float screenX1 = pos1.x;
        float screenY1 = pos1.y;
        float screenX2 = pos2.x;
        float screenY2 = pos2.y;

        Identifier atlasId = sprite.getAtlasId();
        gui.setTexture(atlasId);

        float u1 = sprite.getMinU();
        float v1 = sprite.getMinV();
        float u2 = sprite.getMaxU();
        float v2 = sprite.getMaxV();

        gui.addQuad(screenX1, screenY1, screenX2, screenY2, (float) z, u1, v1, u2, v2, color);

        interceptCount++;

        return true;
    }

    // ========== TEXT RENDERING INTERCEPTION ==========
    // All text in Minecraft 1.21.11 goes through DrawContext.drawText() methods
    // Verified via javap: these are VOID methods, NOT int returns!
    // - drawText(TextRenderer, String, int, int, int, boolean) -> void
    // - drawText(TextRenderer, Text, int, int, int, boolean) -> void
    // - drawText(TextRenderer, OrderedText, int, int, int, boolean) -> void

    private static int textInterceptCount = 0;

    /**
     * Intercept drawText(TextRenderer, String, int x, int y, int color, boolean
     * shadow)
     * Method returns void, uses CallbackInfo.
     */
    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawText_String(
            TextRenderer textRenderer, String text,
            int x, int y, int color, boolean shadow,
            CallbackInfo ci) {
        if (!shouldIntercept())
            return;

        MetalTextRenderer metalText = MetalTextRenderer.getInstance();
        if (!metalText.isEnabled())
            return;

        textInterceptCount++;

        // Transform coordinates using the current matrix stack (same as items)
        Vector2f transformed = transformPoint(x, y);
        metalText.drawText(textRenderer, text, (int) transformed.x, (int) transformed.y, color, shadow);

        ci.cancel();
    }

    /**
     * Intercept drawText(TextRenderer, Text, int x, int y, int color, boolean
     * shadow)
     * Method returns void.
     */
    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawText_Text(
            TextRenderer textRenderer, Text text,
            int x, int y, int color, boolean shadow,
            CallbackInfo ci) {
        if (!shouldIntercept())
            return;

        MetalTextRenderer metalText = MetalTextRenderer.getInstance();
        if (!metalText.isEnabled())
            return;

        textInterceptCount++;

        // Transform coordinates using the current matrix stack (same as items)
        Vector2f transformed = transformPoint(x, y);
        String str = text.getString();
        metalText.drawText(textRenderer, str, (int) transformed.x, (int) transformed.y, color, shadow);

        ci.cancel();
    }

    /**
     * Intercept drawText(TextRenderer, OrderedText, int x, int y, int color,
     * boolean shadow)
     * This is the core method - all other text methods call this.
     */
    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawText_OrderedText(
            TextRenderer textRenderer, OrderedText text,
            int x, int y, int color, boolean shadow,
            CallbackInfo ci) {
        if (!shouldIntercept())
            return;

        MetalTextRenderer metalText = MetalTextRenderer.getInstance();
        if (!metalText.isEnabled())
            return;

        textInterceptCount++;

        // Transform coordinates using the current matrix stack (same as items)
        Vector2f transformed = transformPoint(x, y);
        metalText.drawOrderedText(textRenderer, text, (int) transformed.x, (int) transformed.y, color, shadow);

        ci.cancel();
    }
}
