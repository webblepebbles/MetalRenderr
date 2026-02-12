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

    
    private static final Identifier GUI_ATLAS_ID = Identifier.of("minecraft", "textures/atlas/gui.png");

    @Shadow
    public abstract int getScaledWindowWidth();

    @Shadow
    public abstract int getScaledWindowHeight();

    @Shadow
    public abstract Matrix3x2fStack getMatrices();

    
    private Vector2f transformPoint(float x, float y) {
        Matrix3x2fStack matrices = getMatrices();
        Vector2f point = new Vector2f(x, y);
        matrices.transformPosition(point);
        return point;
    }

    
    private boolean shouldIntercept() {
        return false;
    }
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
    @Inject(method = "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawGuiTexture_IIII(
            RenderPipeline pipeline, Identifier spriteId,
            int x, int y, int width, int height,
            CallbackInfo ci) {
        if (interceptGuiSprite("gui_IIII", spriteId, x, y, width, height, 0xFFFFFFFF, 0)) {
            ci.cancel();
        }
    }
    @Inject(method = "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIIF)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawGuiTexture_IIIIF(
            RenderPipeline pipeline, Identifier spriteId,
            int x, int y, int width, int height, float alpha,
            CallbackInfo ci) {
        int color = ((int) (alpha * 255) << 24) | 0xFFFFFF;
        if (interceptGuiSprite("gui_IIIIF", spriteId, x, y, width, height, color, 0)) {
            ci.cancel();
        }
    }
    @Inject(method = "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawGuiTexture_IIIII(
            RenderPipeline pipeline, Identifier spriteId,
            int x, int y, int width, int height, int z,
            CallbackInfo ci) {
        if (interceptGuiSprite("gui_IIIII", spriteId, x, y, width, height, 0xFFFFFFFF, z)) {
            ci.cancel();
        }
    }
    @Inject(method = "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIIIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawGuiTexture_IIIIIIII(
            RenderPipeline pipeline, Identifier spriteId,
            int x, int y, int width, int height, int u, int v, int texW, int texH,
            CallbackInfo ci) {
        if (interceptGuiSpriteWithUV("gui_IIIIIIII", spriteId, x, y, width, height, u, v, texW, texH, 0xFFFFFFFF, 0)) {
            ci.cancel();
        }
    }
    @Inject(method = "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIIIIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawGuiTexture_IIIIIIIII(
            RenderPipeline pipeline, Identifier spriteId,
            int x, int y, int width, int height, int u, int v, int texW, int texH, int z,
            CallbackInfo ci) {
        if (interceptGuiSpriteWithUV("gui_IIIIIIIII", spriteId, x, y, width, height, u, v, texW, texH, 0xFFFFFFFF, z)) {
            ci.cancel();
        }
    }

    private static int itemInterceptCount = 0;
    private static long lastItemLogTime = 0;
    private static int lastLoggedFrame = 0;

    
    private static void resetItemCounterIfNewFrame() {
        int currentFrame = net.minecraft.client.MinecraftClient.getInstance().inGameHud.getTicks();
        if (currentFrame != lastLoggedFrame) {
            lastLoggedFrame = currentFrame;
            itemInterceptCount = 0;
        }
    }

    
    @Inject(method = "drawItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawItem_EntityXYZ(
            LivingEntity entity, ItemStack stack,
            int x, int y, int z,
            CallbackInfo ci) {
        if (interceptItem("drawItem_EntityXYZ", stack, x, y, z)) {
            ci.cancel();
        }
    }

    
    @Inject(method = "drawItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawItem_EntityWorldXYZ(
            LivingEntity entity, World world, ItemStack stack,
            int x, int y, int z,
            CallbackInfo ci) {
        if (interceptItem("drawItem_EntityWorldXYZ", stack, x, y, z)) {
            ci.cancel();
        }
    }

    
    @Inject(method = "drawItem(Lnet/minecraft/item/ItemStack;II)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawItem_XY(
            ItemStack stack,
            int x, int y,
            CallbackInfo ci) {
        if (interceptItem("drawItem_XY", stack, x, y, 0)) {
            ci.cancel();
        }
    }

    
    @Inject(method = "drawItem(Lnet/minecraft/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawItem_XYZ(
            ItemStack stack,
            int x, int y, int z,
            CallbackInfo ci) {
        if (interceptItem("drawItem_XYZ", stack, x, y, z)) {
            ci.cancel();
        }
    }

    
    @Inject(method = "drawItemWithoutEntity(Lnet/minecraft/item/ItemStack;II)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawItemWithoutEntity_XY(
            ItemStack stack,
            int x, int y,
            CallbackInfo ci) {
        if (interceptItem("drawItemWithoutEntity_XY", stack, x, y, 0)) {
            ci.cancel();
        }
    }

    
    @Inject(method = "drawItemWithoutEntity(Lnet/minecraft/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawItemWithoutEntity_XYZ(
            ItemStack stack,
            int x, int y, int z,
            CallbackInfo ci) {
        if (interceptItem("drawItemWithoutEntity_XYZ", stack, x, y, z)) {
            ci.cancel();
        }
    }

    
    private static int itemDebugCount = 0;

    
    private static final Identifier BLOCKS_ATLAS_ID = Identifier.of("minecraft", "textures/atlas/blocks.png");
    
    private static final Identifier ITEMS_ATLAS_ID = Identifier.of("minecraft", "textures/atlas/items.png");

    
    private Identifier lastSpriteAtlas = null;

    
    private static int spriteLookupDebugCount = 0;
    private static boolean loggedAllSprites = false;

    private Sprite lookupItemSprite(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        lastSpriteAtlas = null;

        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            Identifier itemId = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
            AbstractTexture itemsTexture = mc.getTextureManager().getTexture(ITEMS_ATLAS_ID);
            if (itemsTexture instanceof SpriteAtlasTexture itemsAtlas) {
                SpriteAtlasTextureAccessor accessor = (SpriteAtlasTextureAccessor) itemsAtlas;
                Map<Identifier, Sprite> sprites = accessor.metalrender$getSprites();
                if (sprites != null) {
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
                    Identifier spriteId = Identifier.of(itemId.getNamespace(), "item/" + itemId.getPath());
                    Sprite sprite = sprites.get(spriteId);

                    if (sprite != null) {
                        lastSpriteAtlas = ITEMS_ATLAS_ID;
                        return sprite;
                    }
                }
            }
            AbstractTexture blocksTexture = mc.getTextureManager().getTexture(BLOCKS_ATLAS_ID);
            if (blocksTexture instanceof SpriteAtlasTexture blocksAtlas) {
                SpriteAtlasTextureAccessor accessor = (SpriteAtlasTextureAccessor) blocksAtlas;
                Map<Identifier, Sprite> sprites = accessor.metalrender$getSprites();
                if (sprites != null) {
                    Identifier spriteId = Identifier.of(itemId.getNamespace(), "block/" + itemId.getPath());
                    Sprite sprite = sprites.get(spriteId);

                    if (sprite != null) {
                        lastSpriteAtlas = BLOCKS_ATLAS_ID;
                        return sprite;
                    }
                }
            }
        } catch (Exception e) {
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
            int itemSize = 16;
            Matrix3x2fStack matrices = getMatrices();
            Vector2f pos1 = new Vector2f(x, y);
            matrices.transformPosition(pos1);
            float screenX1 = pos1.x;
            float screenY1 = pos1.y;
            Vector2f pos2 = new Vector2f(x + itemSize, y + itemSize);
            matrices.transformPosition(pos2);
            float screenX2 = pos2.x;
            float screenY2 = pos2.y;
            Sprite sprite = lookupItemSprite(stack);
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
                gui.setTexture(lastSpriteAtlas);

                float u1 = sprite.getMinU();
                float v1 = sprite.getMinV();
                float u2 = sprite.getMaxU();
                float v2 = sprite.getMaxV();
                if (screenName.contains("Inventory")) {
                    System.out.println("[DrawContextMixin] INVENTORY QUAD: " + stack.getItem() +
                            " local=(" + x + "," + y + ") screen=(" + screenX1 + "," + screenY1 + ") atlas="
                            + lastSpriteAtlas);
                }
                float guiZ = 100.0f; 
                gui.addQuad(screenX1, screenY1, screenX2, screenY2, guiZ, u1, v1, u2, v2, 0xFFFFFFFF);

                if (itemDebugCount <= 30) {
                    System.out.println("[DrawContextMixin]   -> atlas=" + lastSpriteAtlas + " UV=(" + u1 + "," + v1
                            + ")-(" + u2 + "," + v2 + ")");
                }
            } else {
                if (itemDebugCount <= 30) {
                    System.out.println("[DrawContextMixin]   -> NO SPRITE - letting vanilla render 3D model");
                }
                return false; 
            }

            return true; 

        } catch (Exception e) {
            System.err.println("[DrawContextMixin] Error rendering item via Metal: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    @Inject(method = "fill(IIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$fill_IIIII(int x1, int y1, int x2, int y2, int color, CallbackInfo ci) {
        if (interceptFill("fill_IIIII", x1, y1, x2, y2, color, 0)) {
            ci.cancel();
        }
    }
    @Inject(method = "fill(Lcom/mojang/blaze3d/pipeline/RenderPipeline;IIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$fill_IIIII_pipeline(RenderPipeline pipeline, int x1, int y1, int x2, int y2, int color,
            CallbackInfo ci) {
        if (interceptFill("fill_P_IIIII", x1, y1, x2, y2, color, 0)) {
            ci.cancel();
        }
    }
    @Inject(method = "fillGradient(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$fillGradient_IIIIII(int x1, int y1, int x2, int y2, int colorTop, int colorBottom,
            CallbackInfo ci) {
        if (interceptFill("fillGrad", x1, y1, x2, y2, colorTop, 0)) {
            ci.cancel();
        }
    }
    @Inject(method = "drawSpriteStretched(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/client/texture/Sprite;IIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawSpriteStretched_IIII(
            RenderPipeline pipeline, Sprite sprite,
            int x, int y, int width, int height,
            CallbackInfo ci) {
        if (interceptSprite("sprite_IIII", x, y, 0, width, height, sprite, 0xFFFFFFFF)) {
            ci.cancel();
        }
    }
    @Inject(method = "drawSpriteStretched(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/client/texture/Sprite;IIIII)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$drawSpriteStretched_IIIII(
            RenderPipeline pipeline, Sprite sprite,
            int x, int y, int width, int height, int color,
            CallbackInfo ci) {
        if (interceptSprite("sprite_IIIII", x, y, 0, width, height, sprite, color)) {
            ci.cancel();
        }
    }
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
        }
        return null;
    }

    private static int spriteNotFoundCount = 0;
    private static long lastSpriteLogTime = 0;
    private static int guiSpriteLogCount = 0;

    
    private boolean interceptGuiSprite(String type, Identifier spriteId, int x, int y,
            int width, int height, int color, int z) {
        if (!shouldIntercept())
            return false;

        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (!gui.isEnabled())
            return false;
        guiSpriteLogCount++;
        if (guiSpriteLogCount <= 50) {
            System.out.println("[DrawContextMixin] interceptGuiSprite: " + type + " sprite=" + spriteId +
                    " pos=(" + x + "," + y + ") size=" + width + "x" + height);
        }

        try {
            Sprite sprite = lookupGuiSprite(spriteId);

            if (sprite != null) {
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
            } else {
                spriteNotFoundCount++;
                long now = System.currentTimeMillis();
                if (now - lastSpriteLogTime > 5000 && spriteNotFoundCount < 100) {
                    lastSpriteLogTime = now;
                    com.metalrender.util.MetalLogger.warn("[DrawContextMixin] Sprite not found: {} (count={})",
                            spriteId, spriteNotFoundCount);
                }
                return false;
            }
        } catch (Exception e) {
            com.metalrender.util.MetalLogger.warn("[DrawContextMixin] Exception resolving sprite {}: {}",
                    spriteId, e.getMessage());
            return false;
        }

        interceptCount++;

        return true;
    }

    
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
                return false;
            }
        } catch (Exception e) {
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
        Matrix3x2fStack matrices = getMatrices();
        Vector2f pos1 = new Vector2f(x, y);
        Vector2f pos2 = new Vector2f(x + width, y + height);
        matrices.transformPosition(pos1);
        matrices.transformPosition(pos2);
        float screenX1 = pos1.x;
        float screenY1 = pos1.y;
        float screenX2 = pos2.x;
        float screenY2 = pos2.y;
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
        Matrix3x2fStack matrices = getMatrices();
        Vector2f pos1 = new Vector2f(x1, y1);
        Vector2f pos2 = new Vector2f(x2, y2);
        matrices.transformPosition(pos1);
        matrices.transformPosition(pos2);
        float screenX1 = pos1.x;
        float screenY1 = pos1.y;
        float screenX2 = pos2.x;
        float screenY2 = pos2.y;
        int alpha = (color >> 24) & 0xFF;
        fillLogCount++;
        if (fillLogCount <= 50 || fillLogCount % 100 == 0) {
            System.out.println(
                    "[DrawContextMixin] interceptFill: " + type + " local=(" + x1 + "," + y1 + ")->(" + x2 + "," + y2 +
                            ") screen=(" + screenX1 + "," + screenY1 + ")->(" + screenX2 + "," + screenY2 +
                            ") color=0x" + Integer.toHexString(color) + " alpha=" + alpha + " z=" + z);
        }
        if (alpha == 0) {
            interceptCount++;
            return true; 
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

    private static int textInterceptCount = 0;

    
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
        Vector2f transformed = transformPoint(x, y);
        metalText.drawText(textRenderer, text, (int) transformed.x, (int) transformed.y, color, shadow);

        ci.cancel();
    }

    
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
        Vector2f transformed = transformPoint(x, y);
        String str = text.getString();
        metalText.drawText(textRenderer, str, (int) transformed.x, (int) transformed.y, color, shadow);

        ci.cancel();
    }

    
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
        Vector2f transformed = transformPoint(x, y);
        metalText.drawOrderedText(textRenderer, text, (int) transformed.x, (int) transformed.y, color, shadow);

        ci.cancel();
    }
}
