package com.metalrender.render.unified;

public enum MetalRenderType {
    
    TERRAIN,

    
    ENTITY,

    
    ENTITY_TRANSLUCENT,

    
    BLOCK_ENTITY,

    
    ITEM,

    
    GUI,

    
    GUI_TEXT,

    
    PARTICLE,

    
    DEBUG,

    
    SKY,

    
    WEATHER,

    
    WORLD_BORDER,

    
    OUTLINE;

    
    public boolean usesDepth() {
        return switch (this) {
            case TERRAIN, ENTITY, ENTITY_TRANSLUCENT, BLOCK_ENTITY, ITEM, PARTICLE, SKY, WEATHER, WORLD_BORDER -> true;
            case GUI, GUI_TEXT, DEBUG, OUTLINE -> false;
        };
    }

    
    public boolean writesDepth() {
        return switch (this) {
            case TERRAIN, ENTITY, BLOCK_ENTITY, ITEM -> true;
            case ENTITY_TRANSLUCENT, GUI, GUI_TEXT, PARTICLE, DEBUG, SKY, WEATHER, WORLD_BORDER, OUTLINE -> false;
        };
    }

    
    public boolean usesBlending() {
        return switch (this) {
            case TERRAIN -> false; 
            case ENTITY, BLOCK_ENTITY, ITEM -> true;
            case ENTITY_TRANSLUCENT, GUI, GUI_TEXT, PARTICLE, WEATHER, WORLD_BORDER, OUTLINE -> true;
            case DEBUG, SKY -> true;
        };
    }

    
    public boolean isPostTerrain() {
        return this != TERRAIN && this != SKY;
    }

    
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
