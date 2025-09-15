package com.metalrender.nativebridge;

import com.metalrender.config.MetalRenderConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class MetalShaderManager {
    public static String loadShader(String name) {
        String path = "src/main/resources/shaders/" + name;
        try {
            return new String(Files.readAllBytes(Paths.get(path)));
        } catch (IOException e) {
            com.metalrender.util.MetalLogger.error("Failed to load shader: " + name + " (" + e.getMessage() + ")");
            return "";
        }
    }
    public static boolean isIrisEnabled() {
        return MetalRenderConfig.get().enableIrisShaders;
    }

    public static boolean isSimpleLightingEnabled() {
        return MetalRenderConfig.get().enableSimpleLighting;
    }

    public static boolean isShadersEnabled() {
        return MetalRenderConfig.get().enableShaders;
    }
}
