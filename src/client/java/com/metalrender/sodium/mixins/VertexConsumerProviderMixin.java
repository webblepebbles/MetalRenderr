package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import com.metalrender.entity.MetalEntityRenderer;
import com.metalrender.entity.MetalVertexConsumer;
import com.metalrender.render.gui.MetalGuiRenderer;
import com.metalrender.render.gui.MetalGuiVertexConsumer;
import com.metalrender.render.item.MetalItemRenderer;
import com.metalrender.render.unified.MetalRenderCoordinator;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mixin to intercept entity rendering vertex buffer requests.
 * 
 * When entities request a VertexConsumer via getBuffer(), we can return
 * our MetalVertexConsumer to capture the vertex data for Metal rendering.
 * 
 * For GUI text, we route to MetalGuiVertexConsumer which renders via the GUI
 * pass.
 */
@Mixin(VertexConsumerProvider.Immediate.class)
public abstract class VertexConsumerProviderMixin {

    private static int callCount = 0;

    // Pattern to extract texture location from RenderLayer toString
    // Matches "location=minecraft:textures/..." in the output
    private static final Pattern TEXTURE_PATTERN = Pattern.compile("location=([^,\\]]+)");

    /**
     * Extract the texture identifier from a RenderLayer's toString output.
     * Returns null if no texture found.
     */
    private static String extractTextureId(String layerString) {
        Matcher matcher = TEXTURE_PATTERN.matcher(layerString);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static int debugTextCount = 0;

    /**
     * Intercept getBuffer() to return our MetalVertexConsumer for entity layers,
     * or MetalGuiVertexConsumer for GUI text layers.
     */
    @Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true)
    private void metalrender$interceptGetBuffer(RenderLayer layer, CallbackInfoReturnable<VertexConsumer> cir) {
        callCount++;
        String layerName = layer.toString();

        // Debug: log ALL entity layers regardless of capture state
        if (layerName.contains("entity_cutout") || layerName.contains("entity_solid") ||
                layerName.contains("entity_translucent") || layerName.contains("armor") ||
                layerName.contains("eyes") || layerName.contains("player")) {
            if (callCount <= 500) {
                System.out.println("[VCPMixin] *** ENTITY LAYER SEEN *** enabled=" + MetalRenderClient.isEnabled() +
                        ", captureEnabled=" + EntityCaptureState.isEnabled() +
                        ", layer=" + layerName.substring(0, Math.min(80, layerName.length())));
            }
        }

        // Debug: log every Nth call to see if mixin is even being invoked
        if (callCount <= 200 || callCount % 1000 == 0) {
            System.out.println("[VCPMixin] getBuffer called #" + callCount + " for layer: " +
                    layerName.substring(0, Math.min(60, layerName.length())));
        }
        callCount++;

        // Debug: log ALL item layers regardless of state
        if (layerName.contains("item_entity") || layerName.contains("entity_translucent_cull") ||
                layerName.contains("cutout_block") || layerName.contains("solid_block")) {
            if (callCount <= 100) {
                System.out.println(
                        "[VCPMixin] EARLY CHECK: layer=" + layerName.substring(0, Math.min(60, layerName.length())) +
                                "... enabled=" + MetalRenderClient.isEnabled() +
                                ", captureEnabled=" + EntityCaptureState.isEnabled() +
                                ", guiMode=" + EntityCaptureState.isGuiMode());
            }
        }

        if (!MetalRenderClient.isEnabled()) {
            return;
        }

        // For entity layers, ALWAYS capture regardless of EntityCaptureState
        // because MC 1.21.11 uses deferred command queue which executes later
        boolean isEntityLayerEarly = layerName.contains("entity_cutout") ||
                layerName.contains("entity_solid") ||
                layerName.contains("entity_translucent") ||
                layerName.contains("armor_cutout") ||
                layerName.contains("eyes") ||
                layerName.contains("player");

        // For non-entity layers (items, text), check capture state
        if (!isEntityLayerEarly && !EntityCaptureState.isEnabled()) {
            return;
        }

        // Check if this is an entity render layer we want to capture

        // Debug: log text layers
        if (layerName.contains("text") || layerName.contains("TEXT")) {
            if (debugTextCount < 30) {
                debugTextCount++;
                MetalLogger.info("[VCPMixin] Text layer detected: " + layerName + " (guiMode=" +
                        EntityCaptureState.isGuiMode() + ", enabled=" + EntityCaptureState.isEnabled() + ")");
            }
        }

        // Skip glint layers - they cause "Duplicate delegates" error when unionized
        if (layerName.contains("glint") || layerName.contains("Glint")) {
            return;
        }

        // Skip particle layers to fix "blue lines" rain issue
        if (layerName.contains("particle") || layerName.contains("PARTICLE")) {
            return;
        }

        // ITEM LAYERS: Route to MetalItemRenderer for GUI item rendering
        // These are the main layers used for items in inventory/hotbar
        //
        // Flat items (swords, diamonds, etc.) use:
        // - entity_translucent_cull (main item layer)
        // - item_entity (dropped items)
        // - direct_entity (direct item rendering)
        //
        // 3D Block items (dirt, stone, etc.) use:
        // - cutout_block_sheet (solid/cutout blocks via Sheets.cutoutBlockSheet())
        // - translucent_item_sheet (glass, ice via Sheets.translucentItemSheet())
        // - solid_block_sheet (opaque blocks)
        // Note: actual layer names have "item_" prefix: item_entity_translucent_cull
        // NOTE: entity_cutout_no_cull is for ENTITIES (mobs), NOT block items!
        boolean isFlatItemLayer = layerName.contains("item_entity_translucent_cull") ||
                layerName.contains("item_entity") ||
                layerName.contains("direct_entity");

        boolean isBlockItemLayer = layerName.contains("cutout_block") ||
                layerName.contains("solid_block") ||
                layerName.contains("translucent_item") ||
                layerName.contains("block_sheet");

        boolean isItemLayer = isFlatItemLayer || isBlockItemLayer;

        // Debug: Log ALL item layers we see with their guiMode status
        if (isItemLayer) {
            System.out.println("[VCPMixin] ITEM LAYER DETECTED: "
                    + layerName.substring(0, Math.min(80, layerName.length())) +
                    " (guiMode=" + EntityCaptureState.isGuiMode() + ", enabled=" + EntityCaptureState.isEnabled() +
                    ", isBlock=" + isBlockItemLayer + ")");
        }

        boolean isLineLayer = layerName.contains("lines") ||
                layerName.contains("debug_line") ||
                layerName.contains("LINE");

        // Entity layers - but EXCLUDE item layers to prevent double-capture
        // When guiMode=false, we want OpenGL to render hotbar items normally
        boolean isEntityLayer = !isItemLayer && (layerName.contains("entity_cutout") ||
                layerName.contains("entity_solid") ||
                layerName.contains("entity_translucent") ||
                layerName.contains("entity_no_outline") ||
                layerName.contains("armor_cutout") ||
                layerName.contains("eyes") ||
                layerName.contains("outer") ||
                layerName.contains("player")); // Capture all player-related layers

        // Debug: Log when we detect an entity layer
        if (isEntityLayer && callCount <= 100) {
            System.out.println("[VCPMixin] ENTITY LAYER MATCHED: " +
                    layerName.substring(0, Math.min(80, layerName.length())));
        }

        // Extra debug for entity_translucent specifically
        if (layerName.contains("entity_translucent") && !layerName.contains("item_entity")) {
            System.out.println("[VCPMixin] *** ENTITY_TRANSLUCENT (non-item) FOUND! ***");
            System.out.println("[VCPMixin]   isItemLayer=" + isItemLayer + ", isEntityLayer=" + isEntityLayer);
            System.out.println(
                    "[VCPMixin]   isFlatItemLayer=" + isFlatItemLayer + ", isBlockItemLayer=" + isBlockItemLayer);
        }

        // Text layers - must specifically match text render types, NOT "texturing"
        boolean isTextLayer = layerName.contains("text_intensity") ||
                layerName.contains("text_see_through") ||
                layerName.contains("text_polygon_offset") ||
                layerName.contains("TEXT_INTENSITY") ||
                layerName.contains("text:RenderSetup");

        // In GUI mode, route text to GUI vertex consumer for proper rendering
        if (EntityCaptureState.isGuiMode() && isTextLayer) {
            MetalGuiRenderer guiRenderer = MetalGuiRenderer.getInstance();
            if (guiRenderer.isEnabled()) {
                MetalGuiVertexConsumer consumer = guiRenderer.getTextVertexConsumer();
                String textureId = extractTextureId(layerName);
                consumer.setTexture(textureId);
                cir.setReturnValue(consumer);
            }
            return;
        }

        // ITEM LAYERS: Always capture for Metal rendering
        // In guiMode (inventory screens): capture to MetalItemRenderer (screen-space)
        // Otherwise (hotbar, held item): capture to MetalItemRenderer (3D GUI space)
        // This ensures ALL items are rendered via Metal, not OpenGL
        if (isItemLayer) {
            MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
            boolean coordNull = coordinator == null;
            boolean coordInit = coordNull ? false : coordinator.isInitialized();

            if (callCount <= 30) {
                System.out.println("[VCPMixin] ITEM LAYER CHECK: coordinator=" + (coordNull ? "NULL" : "OK") +
                        ", initialized=" + coordInit);
            }

            if (coordinator != null && coordinator.isInitialized()) {
                MetalItemRenderer itemRenderer = coordinator.getItemRenderer();
                if (itemRenderer != null && itemRenderer.isEnabled()) {
                    // Set whether this is a 3D block item or flat item
                    itemRenderer.setIs3DBlockItem(isBlockItemLayer);
                    // Track if this is GUI mode (inventory) vs world mode (hotbar/held)
                    itemRenderer.setGuiMode(EntityCaptureState.isGuiMode());

                    VertexConsumer consumer = itemRenderer.getVertexConsumer();
                    String textureId = extractTextureId(layerName);
                    // textureId is already a full identifier like
                    // "minecraft:textures/atlas/items.png"
                    itemRenderer.setItemTexture(
                            textureId != null ? net.minecraft.util.Identifier.of(textureId) : null);

                    if (callCount <= 30) {
                        System.out.println("[VCPMixin] ITEM CAPTURED (guiMode=" + EntityCaptureState.isGuiMode() +
                                ", block=" + isBlockItemLayer + "): " +
                                layerName.substring(0, Math.min(50, layerName.length())) + "...");
                    }

                    cir.setReturnValue(consumer);
                    return;
                }
            }
        }

        // For world-space entities, text (name tags), and lines (block outlines), use
        // entity renderer
        // Lines will be converted to thin quads by MetalVertexConsumer (line mode)
        if (isEntityLayer || isTextLayer || isLineLayer) {
            // Debug: Log EVERY entity layer to see if we're reaching this point
            if (layerName.contains("entity_cutout_no_cull")) {
                System.out.println("[VCPMixin] *** entity_cutout_no_cull REACHED ROUTING SECTION ***");
                System.out.println("[VCPMixin]   FULL LAYER NAME: " + layerName);
                System.out.println("[VCPMixin]   isEntityLayer=" + isEntityLayer +
                        ", isTextLayer=" + isTextLayer + ", isLineLayer=" + isLineLayer);
            }

            MetalEntityRenderer entityRenderer = MetalEntityRenderer.getInstance();
            if (!entityRenderer.isEnabled()) {
                if (isEntityLayer && callCount <= 50) {
                    System.out.println("[VCPMixin] ENTITY LAYER FOUND but entityRenderer not enabled! layer=" +
                            layerName.substring(0, Math.min(80, layerName.length())));
                }
                return;
            }

            if (isEntityLayer && callCount <= 50) {
                System.out.println("[VCPMixin] ROUTING ENTITY LAYER to MetalVertexConsumer: " +
                        layerName.substring(0, Math.min(80, layerName.length())));
            }

            MetalVertexConsumer consumer = entityRenderer.getVertexConsumer();

            // CRITICAL: For ENTITY layers (entity_cutout, entity_solid, etc.), extract
            // texture
            // from the layer name. MC 1.21.11 uses deferred rendering which means the
            // texture
            // set by LivingEntityRendererMixin.render() may be for a DIFFERENT entity than
            // the one whose vertices are being written now.
            // The layer name contains:
            // textures={Sampler0=TextureSpec[location=minecraft:textures/entity/...]}
            if (isEntityLayer) {
                String textureId = extractTextureId(layerName);
                if (textureId != null && textureId.contains(":")) {
                    consumer.setTexture(textureId);
                }
                // If no texture in layer name, keep whatever was set before
            } else {
                // For non-entity layers (text, lines), set texture from layer
                String textureId = extractTextureId(layerName);
                // For line layers (block outlines), don't use texture - will use white fallback
                // Only set actual texture paths, not RenderType strings
                if (textureId != null && textureId.contains(":")) {
                    consumer.setTexture(textureId);
                } else {
                    consumer.setTexture(null); // Will use white fallback texture
                }
            }

            // Enable line mode for line layers (converts line pairs to thin quads)
            consumer.setLineMode(isLineLayer);

            cir.setReturnValue(consumer);
        }
    }
}
