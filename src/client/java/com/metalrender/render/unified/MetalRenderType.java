package com.metalrender.render.unified;

/**
 * Enum representing different render types that Metal can handle.
 * This allows us to batch and optimize rendering by type.
 */
public enum MetalRenderType {
    /** World terrain rendered via Sodium integration */
    TERRAIN,

    /** Entity models (mobs, players, armor stands, etc.) */
    ENTITY,

    /** Entity models with translucency (ghosts, phantoms) */
    ENTITY_TRANSLUCENT,

    /** Block entities (chests, signs, banners, etc.) */
    BLOCK_ENTITY,

    /** Items in hand, dropped, in frames */
    ITEM,

    /** GUI elements (hotbar, inventory, menus) */
    GUI,

    /** GUI text rendering */
    GUI_TEXT,

    /** Particles (smoke, flames, bubbles, etc.) */
    PARTICLE,

    /** Debug overlays (F3 screen, chunk borders) */
    DEBUG,

    /** Sky, sun, moon, stars */
    SKY,

    /** Weather effects (rain, snow) */
    WEATHER,

    /** World border effect */
    WORLD_BORDER,

    /** Outline rendering (entity glows, selection) */
    OUTLINE;

    /**
     * Whether this render type uses depth testing.
     */
    public boolean usesDepth() {
        return switch (this) {
            case TERRAIN, ENTITY, ENTITY_TRANSLUCENT, BLOCK_ENTITY, ITEM, PARTICLE, SKY, WEATHER, WORLD_BORDER -> true;
            case GUI, GUI_TEXT, DEBUG, OUTLINE -> false;
        };
    }

    /**
     * Whether this render type writes to the depth buffer.
     */
    public boolean writesDepth() {
        return switch (this) {
            case TERRAIN, ENTITY, BLOCK_ENTITY, ITEM -> true;
            case ENTITY_TRANSLUCENT, GUI, GUI_TEXT, PARTICLE, DEBUG, SKY, WEATHER, WORLD_BORDER, OUTLINE -> false;
        };
    }

    /**
     * Whether this render type uses blending.
     */
    public boolean usesBlending() {
        return switch (this) {
            case TERRAIN -> false; // Cutout handled via discard
            case ENTITY, BLOCK_ENTITY, ITEM -> true;
            case ENTITY_TRANSLUCENT, GUI, GUI_TEXT, PARTICLE, WEATHER, WORLD_BORDER, OUTLINE -> true;
            case DEBUG, SKY -> true;
        };
    }

    /**
     * Whether this render type should be rendered after terrain.
     */
    public boolean isPostTerrain() {
        return this != TERRAIN && this != SKY;
    }

    /**
     * The render order priority (lower = earlier).
     */
    public int priority() {
        return switch (this) {
            case SKY -> 0;
            case TERRAIN -> 10;
            case BLOCK_ENTITY -> 20;
            case ENTITY -> 30;
            case ENTITY_TRANSLUCENT -> 35;
            case ITEM -> 40;
            case PARTICLE -> 50;
            case WEATHER -> 60;
            case WORLD_BORDER -> 70;
            case OUTLINE -> 80;
            case GUI -> 100;
            case GUI_TEXT -> 110;
            case DEBUG -> 120;
        };
    }
}
