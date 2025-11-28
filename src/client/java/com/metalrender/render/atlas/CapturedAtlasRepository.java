package com.metalrender.render.atlas;

import com.metalrender.util.MetalLogger;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.Identifier;

public final class CapturedAtlasRepository {
  private static final ConcurrentHashMap<Identifier, CapturedAtlas> ATLASES =
      new ConcurrentHashMap<>();

  private CapturedAtlasRepository() {}

  public static void store(Identifier id, CapturedAtlas atlas) {
    if (id == null || atlas == null) {
      return;
    }
    CapturedAtlas previous = ATLASES.put(id, atlas);
    if (MetalLogger.isDebugEnabled()) {
      MetalLogger.info("[AtlasCapture] Stored atlas {} ({}x{})", id,
          atlas.width(), atlas.height());
    }
    if (previous != null && MetalLogger.isDebugEnabled()) {
      MetalLogger.info("[AtlasCapture] Replaced previous atlas for {}", id);
    }
  }

  public static Optional<CapturedAtlas> get(Identifier id) {
    return Optional.ofNullable(ATLASES.get(id));
  }

  public static void clear(Identifier id) {
    if (id != null) {
      ATLASES.remove(id);
    }
  }
}
