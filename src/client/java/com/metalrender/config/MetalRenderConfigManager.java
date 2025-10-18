package com.metalrender.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.metalrender.util.MetalLogger;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Handles loading and saving user configurable MetalRender options.
 */
public final class MetalRenderConfigManager {
  private static final Gson GSON =
      new GsonBuilder().setPrettyPrinting().create();
  private static final Path CONFIG_PATH =
      FabricLoader.getInstance().getConfigDir().resolve("metalrender.json");

  private static MetalRenderConfigData current = MetalRenderConfig.capture();
  private static boolean dirty;

  private MetalRenderConfigManager() {}

  public static synchronized void load() {
    MetalRenderConfigData loaded = null;
    if (Files.exists(CONFIG_PATH)) {
      try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
        loaded = GSON.fromJson(reader, MetalRenderConfigData.class);
      } catch (JsonIOException | JsonSyntaxException | IOException e) {
        MetalLogger.warn("Failed to load config, using defaults (%s)",
                         e.getMessage());
      }
    }

    if (loaded == null) {
      loaded = MetalRenderConfig.capture();
    }

    current = loaded;
    MetalRenderConfig.apply(current);
    dirty = false;
  }

  public static synchronized void
  update(Consumer<MetalRenderConfigData> mutator) {
    mutator.accept(current);
    MetalRenderConfig.apply(current);
    dirty = true;
    MetalLogger.debug("[ConfigManager] Config updated, marked as dirty");
  }

  public static synchronized MetalRenderConfigData getCurrent() {
    return current;
  }

  public static synchronized void resetToDefaults() {
    current = new MetalRenderConfigData();
    MetalRenderConfig.apply(current);
    dirty = true;
  }

  public static synchronized void saveIfDirty() {
    if (!dirty) {
      MetalLogger.debug("[ConfigManager] No changes to save");
      return;
    }
    save();
  }

  public static synchronized void syncFromRuntime(boolean markDirty) {
    current = MetalRenderConfig.capture();
    dirty = markDirty;
  }

  private static void save() {
    try {
      Files.createDirectories(CONFIG_PATH.getParent());
      try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
        GSON.toJson(current, writer);
      }
      dirty = false;
      MetalLogger.info("[ConfigManager] Config saved to %s", CONFIG_PATH);
    } catch (IOException e) {
      MetalLogger.error("Failed to save config (%s)", e.getMessage());
    }
  }
}
