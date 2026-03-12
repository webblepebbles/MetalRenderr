package com.pebbles_boon.metalrender.render.chunk;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.nativebridge.NativeMemory;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.BlockModels;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Vector3fc;

public class CustomChunkMesher {

  private static final int VERTEX_STRIDE = 16;
  private static final int SECTION_SIZE = 16;
  private static final int MAX_QUADS =
      SECTION_SIZE * SECTION_SIZE * SECTION_SIZE * 6;

  private final ConcurrentHashMap<Long, ChunkMeshData> meshCache;
  private final Set<Long> pendingKeys = ConcurrentHashMap.newKeySet();
  private final Set<Long> dirtyKeys = ConcurrentHashMap.newKeySet();
  private long deviceHandle;
  private boolean initialized;
  private long globalIndexBufferHandle;

  private ExecutorService builderPool;
  private ExecutorService dirtyRebuildPool;

  public static class ChunkMeshData {
    public final long bufferHandle;
    public final int quadCount;
    public final int chunkX;
    public final int chunkY;
    public final int chunkZ;
    public final int lodLevel;

    public ChunkMeshData(long bufferHandle, int quadCount, int chunkX,
                         int chunkY, int chunkZ, int lodLevel) {
      this.bufferHandle = bufferHandle;
      this.quadCount = quadCount;
      this.chunkX = chunkX;
      this.chunkY = chunkY;
      this.chunkZ = chunkZ;
      this.lodLevel = lodLevel;
    }
  }

  public CustomChunkMesher() {
    this.meshCache = new ConcurrentHashMap<>();
    int processors = Runtime.getRuntime().availableProcessors();
    this.builderPool =
        Executors.newFixedThreadPool(Math.max(2, processors - 1));

    this.dirtyRebuildPool = Executors.newFixedThreadPool(2);
  }

  public long getGlobalIndexBuffer() { return globalIndexBufferHandle; }

  public void initialize(long device) {
    this.deviceHandle = device;

    int[] indices = new int[MAX_QUADS * 6];
    for (int i = 0; i < MAX_QUADS; i++) {
      indices[i * 6 + 0] = i * 4 + 0;
      indices[i * 6 + 1] = i * 4 + 1;
      indices[i * 6 + 2] = i * 4 + 2;
      indices[i * 6 + 3] = i * 4 + 0;
      indices[i * 6 + 4] = i * 4 + 2;
      indices[i * 6 + 5] = i * 4 + 3;
    }

    ByteBuffer ib = ByteBuffer.allocateDirect(indices.length * 4)
                        .order(ByteOrder.nativeOrder());
    for (int idx : indices)
      ib.putInt(idx);
    ib.flip();

    byte[] ibData = new byte[ib.remaining()];
    ib.get(ibData);

    this.globalIndexBufferHandle = NativeBridge.nCreateBuffer(
        deviceHandle, ibData.length, NativeMemory.STORAGE_MODE_SHARED);
    NativeBridge.nUploadBufferData(this.globalIndexBufferHandle, ibData, 0,
                                   ibData.length);

    this.initialized = true;
    MetalLogger.info("CustomChunkMesher initialized (maxQuads=%d, ibSize=%d)",
                     MAX_QUADS, ibData.length);
  }

  private static long packChunkKey(int x, int y, int z) {
    return ((long)(x & 0x3FFFFF) << 42) | ((long)(y & 0xFFFFF) << 22) |
        (z & 0x3FFFFF);
  }

  private boolean isTransparent(int[] states, int x, int y, int z) {
    if (x < 0 || x >= 16 || y < 0 || y >= 16 || z < 0 || z >= 16)
      return true;
    int idx = y * 256 + z * 16 + x;
    if (states == null || states.length <= idx)
      return true;
    int stateId = states[idx];
    if (stateId == 0)
      return true;
    try {
      BlockState state = Block.getStateFromRawId(stateId);
      if (state.isAir())
        return true;

      return !state.isOpaqueFullCube();
    } catch (Exception e) {
      return true;
    }
  }

  public int getMeshCount() { return meshCache.size(); }

  public boolean hasMesh(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    if (dirtyKeys.contains(key))
      return false;
    return meshCache.containsKey(key) || pendingKeys.contains(key);
  }

  public boolean needsLodRebuild(int cx, int cy, int cz, int desiredLod) {
    long key = packChunkKey(cx, cy, cz);
    ChunkMeshData mesh = meshCache.get(key);
    if (mesh == null)
      return false;
    return mesh.lodLevel != desiredLod && !pendingKeys.contains(key);
  }

  public void markDirty(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    dirtyKeys.add(key);
    pendingKeys.remove(key);
    MetalLogger.info("[markDirty] Section [%d,%d,%d] dirty=%d pending=%d", cx,
                     cy, cz, dirtyKeys.size(), pendingKeys.size());
  }

  private static final Direction[] ALL_DIRECTIONS = Direction.values();

  public void buildMeshAsync(int chunkX, int chunkY, int chunkZ,
                             int[] blockStates, byte[] lightData) {
    if (!initialized)
      return;

    long key = packChunkKey(chunkX, chunkY, chunkZ);
    pendingKeys.add(key);

    builderPool.submit(() -> {
      try {
        doMeshBuild(chunkX, chunkY, chunkZ, blockStates, lightData, key);
      } catch (Exception e) {
        pendingKeys.remove(key);
        MetalLogger.error("Meshing error for chunk [%d,%d,%d]", chunkX, chunkY,
                          chunkZ);
      }
    });
  }

  private void doMeshBuild(int chunkX, int chunkY, int chunkZ,
                           int[] blockStates, byte[] lightData, long key) {
    doMeshBuild(chunkX, chunkY, chunkZ, blockStates, lightData, key, 0);
  }

  private static boolean shouldRenderAtLod(BlockState state, int lodLevel) {
    if (lodLevel == 0)
      return true;
    if (state.isAir())
      return false;

    if (state.getFluidState() != null && !state.getFluidState().isEmpty())
      return true;

    boolean isOpaqueFull = state.isOpaqueFullCube();
    boolean isSolid = state.isSolid();

    if (lodLevel >= 1) {
      if (!isOpaqueFull && !isSolid) {
        return false;
      }
    }

    if (lodLevel >= 2) {
      if (!isOpaqueFull && isSolid) {

        if (!state.isOpaque()) {
          return false;
        }
      }
    }

    if (lodLevel >= 3 && !isOpaqueFull) {
      return false;
    }

    if (lodLevel >= 4) {
      if (!isOpaqueFull)
        return false;

      if (!state.isOpaque())
        return false;
    }

    return true;
  }

  private void doMeshBuild(int chunkX, int chunkY, int chunkZ,
                           int[] blockStates, byte[] lightData, long key,
                           int lodLevel) {
    try {
      ByteBuffer vertexBuffer =
          ByteBuffer.allocateDirect(MAX_QUADS * 4 * VERTEX_STRIDE)
              .order(ByteOrder.nativeOrder());

      int quadCount = 0;

      MinecraftClient mc = MinecraftClient.getInstance();
      BlockModels blockModels = null;
      if (mc != null && mc.getBlockRenderManager() != null) {
        blockModels = mc.getBlockRenderManager().getModels();
      }

      for (int y = 0; y < SECTION_SIZE; y++) {
        for (int z = 0; z < SECTION_SIZE; z++) {
          for (int x = 0; x < SECTION_SIZE; x++) {
            int idx = y * 256 + z * 16 + x;
            int stateId = blockStates != null && idx < blockStates.length
                              ? blockStates[idx]
                              : 0;
            if (stateId == 0)
              continue;

            BlockState blockState = Block.getStateFromRawId(stateId);
            if (blockState.isAir())
              continue;

            if (lodLevel > 0 && !shouldRenderAtLod(blockState, lodLevel))
              continue;

            byte light = lightData != null && idx < lightData.length
                             ? lightData[idx]
                             : 0;
            int blockLight = light & 0xF;
            int skyLight = (light >> 4) & 0xF;
            byte packedLight =
                (byte)((blockLight & 0xF) | ((skyLight & 0xF) << 4));

            int tintColor = getBlockColor(blockState);
            byte tintR = (byte)((tintColor >> 16) & 0xFF);
            byte tintG = (byte)((tintColor >> 8) & 0xFF);
            byte tintB = (byte)(tintColor & 0xFF);

            if (blockModels != null) {
              try {
                BlockStateModel model = blockModels.getModel(blockState);
                if (model != null) {
                  long seed = (long)(chunkX * 16 + x) * 3129871L ^
                              (long)(chunkZ * 16 + z) * 116129781L ^
                              (long)(chunkY * 16 + y);
                  Random rand = Random.create(seed);

                  int quadsThisBlock = 0;
                  List<BlockModelPart> parts = model.getParts(rand);
                  for (BlockModelPart part : parts) {
                    for (Direction dir : ALL_DIRECTIONS) {
                      int nx = x + dir.getOffsetX();
                      int ny = y + dir.getOffsetY();
                      int nz = z + dir.getOffsetZ();
                      if (!isTransparent(blockStates, nx, ny, nz))
                        continue;

                      List<BakedQuad> quads = part.getQuads(dir);
                      if (quads != null) {
                        for (BakedQuad quad : quads) {
                          if (quadCount >= MAX_QUADS)
                            break;
                          quadCount +=
                              emitBakedQuad(vertexBuffer, quad, x, y, z,
                                            packedLight, tintR, tintG, tintB);
                          quadsThisBlock++;
                        }
                      }
                    }

                    List<BakedQuad> nonDirQuads = part.getQuads(null);
                    if (nonDirQuads != null) {
                      for (BakedQuad quad : nonDirQuads) {
                        if (quadCount >= MAX_QUADS)
                          break;
                        quadCount +=
                            emitBakedQuad(vertexBuffer, quad, x, y, z,
                                          packedLight, tintR, tintG, tintB);
                        quadsThisBlock++;
                      }
                    }
                  }
                  if (quadsThisBlock > 0)
                    continue;
                }
              } catch (Exception e) {
              }
            }

            Sprite sprite = null;
            try {
              if (blockModels != null) {
                var model = blockModels.getModel(blockState);
                if (model != null)
                  sprite = model.particleSprite();
              }
            } catch (Exception ignored) {
            }

            if (isTransparent(blockStates, x, y + 1, z))
              quadCount += emitFace(vertexBuffer, x, y, z, 1, sprite,
                                    packedLight, tintR, tintG, tintB);
            if (isTransparent(blockStates, x, y - 1, z))
              quadCount += emitFace(vertexBuffer, x, y, z, 0, sprite,
                                    packedLight, tintR, tintG, tintB);
            if (isTransparent(blockStates, x, y, z + 1))
              quadCount += emitFace(vertexBuffer, x, y, z, 3, sprite,
                                    packedLight, tintR, tintG, tintB);
            if (isTransparent(blockStates, x, y, z - 1))
              quadCount += emitFace(vertexBuffer, x, y, z, 2, sprite,
                                    packedLight, tintR, tintG, tintB);
            if (isTransparent(blockStates, x + 1, y, z))
              quadCount += emitFace(vertexBuffer, x, y, z, 5, sprite,
                                    packedLight, tintR, tintG, tintB);
            if (isTransparent(blockStates, x - 1, y, z))
              quadCount += emitFace(vertexBuffer, x, y, z, 4, sprite,
                                    packedLight, tintR, tintG, tintB);
          }
        }
      }

      if (quadCount == 0) {
        ChunkMeshData old = meshCache.remove(key);
        if (old != null)
          NativeBridge.nDestroyBuffer(old.bufferHandle);
        return;
      }

      vertexBuffer.flip();
      byte[] data = new byte[quadCount * 4 * VERTEX_STRIDE];
      vertexBuffer.get(data);

      long bufferHandle = NativeBridge.nCreateBuffer(
          deviceHandle, data.length, NativeMemory.STORAGE_MODE_SHARED);
      NativeBridge.nUploadBufferData(bufferHandle, data, 0, data.length);

      ChunkMeshData mesh = new ChunkMeshData(bufferHandle, quadCount, chunkX,
                                             chunkY, chunkZ, lodLevel);
      ChunkMeshData old = meshCache.put(key, mesh);
      if (old != null) {
        MetalLogger.info("[doMeshBuild] Replaced mesh [%d,%d,%d]: old=%d "
                             + "quads → new=%d quads",
                         chunkX, chunkY, chunkZ, old.quadCount, quadCount);
        NativeBridge.nDestroyBuffer(old.bufferHandle);
      }
      dirtyKeys.remove(key);
    } catch (Exception e) {
      MetalLogger.error("Meshing error for chunk [%d,%d,%d]", chunkX, chunkY,
                        chunkZ);
    } finally {
      pendingKeys.remove(key);
    }
  }

  public ChunkMeshData buildMesh(int chunkX, int chunkY, int chunkZ,
                                 int[] blockStates, byte[] lightData) {
    buildMeshAsync(chunkX, chunkY, chunkZ, blockStates, lightData);
    return null;
  }

  public void buildMeshFromWorld(int chunkX, int chunkY, int chunkZ) {
    buildMeshFromWorld(chunkX, chunkY, chunkZ, 0);
  }

  public void buildMeshFromWorld(int chunkX, int chunkY, int chunkZ,
                                 int lodLevel) {
    if (!initialized)
      return;
    long key = packChunkKey(chunkX, chunkY, chunkZ);
    boolean wasDirty = dirtyKeys.contains(key);
    if (!pendingKeys.add(key)) {
      if (wasDirty)
        MetalLogger.info("[buildMeshFromWorld] Section [%d,%d,%d] was dirty "
                             + "but already pending — skipped",
                         chunkX, chunkY, chunkZ);
      return;
    }
    if (wasDirty)
      MetalLogger.info(
          "[buildMeshFromWorld] Rebuilding dirty section [%d,%d,%d]", chunkX,
          chunkY, chunkZ);

    ExecutorService pool = wasDirty ? dirtyRebuildPool : builderPool;
    pool.submit(() -> {
      try {

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) {
          pendingKeys.remove(key);
          return;
        }
        ClientWorld world = mc.world;
        if (world == null) {
          pendingKeys.remove(key);
          return;
        }

        WorldChunk chunk =
            world.getChunkManager().getWorldChunk(chunkX, chunkZ);
        if (chunk == null) {
          pendingKeys.remove(key);
          return;
        }

        int[] blockStates = new int[4096];
        int baseX = chunkX * 16;
        int baseY = chunkY * 16;
        int baseZ = chunkZ * 16;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        boolean hasAnyBlock = false;
        for (int y = 0; y < 16; y++) {
          for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
              BlockState bs =
                  chunk.getBlockState(pos.set(baseX + x, baseY + y, baseZ + z));
              if (!bs.isAir()) {
                blockStates[y * 256 + z * 16 + x] = Block.getRawIdFromState(bs);
                hasAnyBlock = true;
              }
            }
          }
        }

        if (!hasAnyBlock) {
          pendingKeys.remove(key);
          return;
        }

        byte[] lightData = new byte[4096];
        java.util.Arrays.fill(lightData, (byte)0xF0);

        doMeshBuild(chunkX, chunkY, chunkZ, blockStates, lightData, key,
                    lodLevel);
      } catch (Exception e) {
        pendingKeys.remove(key);
        MetalLogger.error("Async mesh build error for [%d,%d,%d]: %s", chunkX,
                          chunkY, chunkZ, e.getMessage());
      }
    });
  }

  private int emitBakedQuad(ByteBuffer buf, BakedQuad quad, int blockX,
                            int blockY, int blockZ, byte packedLight,
                            byte tintR, byte tintG, byte tintB) {
    Direction face = quad.face();
    byte normalIndex = dirToNormalIndex(face);

    float shade = quad.shade() ? getFaceShade(normalIndex) : 1.0f;

    byte baseR, baseG, baseB;
    if (quad.hasTint()) {
      baseR = tintR;
      baseG = tintG;
      baseB = tintB;
    } else {
      baseR = (byte)255;
      baseG = (byte)255;
      baseB = (byte)255;
    }

    byte sr = (byte)((baseR & 0xFF) * shade);
    byte sg = (byte)((baseG & 0xFF) * shade);
    byte sb = (byte)((baseB & 0xFF) * shade);

    byte light = packedLight;
    int emission = quad.lightEmission();
    if (emission > 0) {
      int bl = Math.max(light & 0xF, emission);
      int sl = (light >> 4) & 0xF;
      light = (byte)((bl & 0xF) | ((sl & 0xF) << 4));
    }

    for (int v = 0; v < 4; v++) {
      Vector3fc pos = quad.getPosition(v);
      long packedUV = quad.getTexcoords(v);

      float vx = pos.x() + blockX;
      float vy = pos.y() + blockY;
      float vz = pos.z() + blockZ;

      buf.putShort((short)(vx * 256.0f));
      buf.putShort((short)(vy * 256.0f));
      buf.putShort((short)(vz * 256.0f));

      float u = Float.intBitsToFloat((int)(packedUV >> 32));
      float vCoord = Float.intBitsToFloat((int)packedUV);
      buf.putShort((short)(u * 65535.0f));
      buf.putShort((short)(vCoord * 65535.0f));

      buf.put(sr);
      buf.put(sg);
      buf.put(sb);
      buf.put((byte)255);

      buf.put(light);
      buf.put(normalIndex);
    }
    return 1;
  }

  private static byte dirToNormalIndex(Direction dir) {
    if (dir == null)
      return 1;
    return switch (dir) {
      case DOWN -> 0;
      case UP -> 1;
      case NORTH -> 2;
      case SOUTH -> 3;
      case WEST -> 4;
      case EAST -> 5;
    };
  }

  private static float getFaceShade(byte normalIndex) {
    return switch (normalIndex) {
      case 0 -> 0.5f;
      case 1 -> 1.0f;
      case 2, 3 -> 0.8f;
      case 4, 5 -> 0.6f;
      default -> 1.0f;
    };
  }

  private int emitFace(ByteBuffer buf, int x, int y, int z, int normalIndex,
                       Sprite sprite, byte packedLight, byte r, byte g,
                       byte b) {
    short[][] positions = getFaceVertices(x, y, z, normalIndex);

    short uMin = 0, uMax = (short)65535, vMin = 0, vMax = (short)65535;
    if (sprite != null) {
      uMin = (short)(sprite.getMinU() * 65535.0f);
      uMax = (short)(sprite.getMaxU() * 65535.0f);
      vMin = (short)(sprite.getMinV() * 65535.0f);
      vMax = (short)(sprite.getMaxV() * 65535.0f);
    }

    short[][] uvs = {{uMin, vMin}, {uMin, vMax}, {uMax, vMax}, {uMax, vMin}};
    byte a = (byte)255;

    float shade = 1.0f;
    switch (normalIndex) {
    case 0:
      shade = 0.5f;
      break;
    case 1:
      shade = 1.0f;
      break;
    case 2:
    case 3:
      shade = 0.8f;
      break;
    case 4:
    case 5:
      shade = 0.6f;
      break;
    }
    byte sr = (byte)((r & 0xFF) * shade);
    byte sg = (byte)((g & 0xFF) * shade);
    byte sb = (byte)((b & 0xFF) * shade);
    byte nIdx = (byte)normalIndex;

    for (int i = 0; i < 4; i++) {
      buf.putShort(positions[i][0]);
      buf.putShort(positions[i][1]);
      buf.putShort(positions[i][2]);
      buf.putShort(uvs[i][0]);
      buf.putShort(uvs[i][1]);
      buf.put(sr);
      buf.put(sg);
      buf.put(sb);
      buf.put(a);
      buf.put(packedLight);
      buf.put(nIdx);
    }
    return 1;
  }

  private static int getBlockColor(BlockState state) {
    if (state == null)
      return 0xC8C8C8;
    Block block = state.getBlock();

    if (block == Blocks.GRASS_BLOCK)
      return 0x7CBE3F;
    if (block == Blocks.DIRT || block == Blocks.COARSE_DIRT ||
        block == Blocks.ROOTED_DIRT)
      return 0x866043;
    if (block == Blocks.STONE || block == Blocks.SMOOTH_STONE)
      return 0x7D7D7D;
    if (block == Blocks.COBBLESTONE || block == Blocks.MOSSY_COBBLESTONE)
      return 0x7A7A7A;
    if (block == Blocks.GRANITE)
      return 0x9A6B53;
    if (block == Blocks.DIORITE)
      return 0xBFBFBF;
    if (block == Blocks.ANDESITE)
      return 0x888888;
    if (block == Blocks.DEEPSLATE || block == Blocks.COBBLED_DEEPSLATE)
      return 0x505050;
    if (block == Blocks.TUFF)
      return 0x6B6B5E;
    if (block == Blocks.BEDROCK)
      return 0x3A3A3A;
    if (block == Blocks.SAND || block == Blocks.SANDSTONE)
      return 0xDCCD82;
    if (block == Blocks.RED_SAND || block == Blocks.RED_SANDSTONE)
      return 0xBE6621;
    if (block == Blocks.GRAVEL)
      return 0x857E79;
    if (block == Blocks.CLAY)
      return 0x9EA4B0;

    if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE)
      return 0x4A4A4A;
    if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE)
      return 0xB08D63;
    if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE)
      return 0xDBCD34;
    if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE)
      return 0x5DDCD3;
    if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE)
      return 0xA86340;
    if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE)
      return 0x3450AB;
    if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE)
      return 0xAA0000;
    if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE)
      return 0x17DD62;

    if (block == Blocks.OAK_LOG || block == Blocks.OAK_WOOD)
      return 0x6B5534;
    if (block == Blocks.OAK_PLANKS)
      return 0xAF8F55;
    if (block == Blocks.BIRCH_LOG || block == Blocks.BIRCH_WOOD)
      return 0xD5CB93;
    if (block == Blocks.BIRCH_PLANKS)
      return 0xC5B77B;
    if (block == Blocks.SPRUCE_LOG || block == Blocks.SPRUCE_WOOD)
      return 0x3D2813;
    if (block == Blocks.SPRUCE_PLANKS)
      return 0x674B2B;
    if (block == Blocks.DARK_OAK_LOG || block == Blocks.DARK_OAK_WOOD)
      return 0x372814;
    if (block == Blocks.DARK_OAK_PLANKS)
      return 0x422C14;
    if (block == Blocks.JUNGLE_LOG || block == Blocks.JUNGLE_WOOD)
      return 0x554A2F;
    if (block == Blocks.ACACIA_LOG || block == Blocks.ACACIA_WOOD)
      return 0x676157;

    if (block == Blocks.OAK_LEAVES || block == Blocks.JUNGLE_LEAVES ||
        block == Blocks.ACACIA_LEAVES || block == Blocks.DARK_OAK_LEAVES)
      return 0x4BA836;
    if (block == Blocks.BIRCH_LEAVES)
      return 0x6B9940;
    if (block == Blocks.SPRUCE_LEAVES)
      return 0x3B6126;

    if (block == Blocks.WATER)
      return 0x3F76E4;
    if (block == Blocks.ICE || block == Blocks.PACKED_ICE ||
        block == Blocks.BLUE_ICE)
      return 0x8DB3E2;
    if (block == Blocks.SNOW_BLOCK || block == Blocks.SNOW)
      return 0xF0F0F0;
    if (block == Blocks.POWDER_SNOW)
      return 0xF0F0F0;

    if (block == Blocks.NETHERRACK)
      return 0x6B3430;
    if (block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL)
      return 0x513F32;
    if (block == Blocks.BASALT || block == Blocks.SMOOTH_BASALT)
      return 0x4B4B4F;
    if (block == Blocks.BLACKSTONE)
      return 0x2C2630;
    if (block == Blocks.GLOWSTONE)
      return 0xAB8048;

    if (block == Blocks.GLASS)
      return 0xFFFFFF;

    return 0xB0B0B0;
  }

  private short[][] getFaceVertices(int x, int y, int z, int normalIndex) {
    short sx = (short)(x * 256), sy = (short)(y * 256), sz = (short)(z * 256);
    short nex = (short)((x + 1) * 256), ney = (short)((y + 1) * 256),
          nez = (short)((z + 1) * 256);

    if (normalIndex == 1)
      return new short[][] {
          {sx, ney, sz}, {sx, ney, nez}, {nex, ney, nez}, {nex, ney, sz}};
    if (normalIndex == 0)
      return new short[][] {
          {sx, sy, nez}, {sx, sy, sz}, {nex, sy, sz}, {nex, sy, nez}};
    if (normalIndex == 3)
      return new short[][] {
          {sx, ney, nez}, {sx, sy, nez}, {nex, sy, nez}, {nex, ney, nez}};
    if (normalIndex == 2)
      return new short[][] {
          {nex, ney, sz}, {nex, sy, sz}, {sx, sy, sz}, {sx, ney, sz}};
    if (normalIndex == 5)
      return new short[][] {
          {nex, ney, nez}, {nex, sy, nez}, {nex, sy, sz}, {nex, ney, sz}};
    if (normalIndex == 4)
      return new short[][] {
          {sx, ney, sz}, {sx, sy, sz}, {sx, sy, nez}, {sx, ney, nez}};
    return new short[][] {
        {sx, sy, sz}, {sx, sy, sz}, {sx, sy, sz}, {sx, sy, sz}};
  }

  public ChunkMeshData getMesh(int cx, int cy, int cz) {
    return meshCache.get(packChunkKey(cx, cy, cz));
  }

  public void removeMesh(int cx, int cy, int cz) {
    ChunkMeshData mesh = meshCache.remove(packChunkKey(cx, cy, cz));
    if (mesh != null)
      NativeBridge.nDestroyBuffer(mesh.bufferHandle);
  }

  public void clear() {
    for (ChunkMeshData mesh : meshCache.values()) {
      NativeBridge.nDestroyBuffer(mesh.bufferHandle);
    }
    meshCache.clear();
    if (globalIndexBufferHandle != 0) {
      NativeBridge.nDestroyBuffer(globalIndexBufferHandle);
      globalIndexBufferHandle = 0;
    }
  }

  public Collection<ChunkMeshData> getAllMeshes() { return meshCache.values(); }
}
