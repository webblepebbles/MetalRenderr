package com.pebbles_boon.metalrender.render.chunk;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.nativebridge.NativeMemory;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.render.block.BlockModels;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import org.joml.Vector3fc;
public class CustomChunkMesher {
  private static final int VERTEX_STRIDE = 16;
  private static final int SECTION_SIZE = 16;
  private static final int MAX_QUADS = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE * 6;
  private static final int VERTEX_BUF_SIZE = MAX_QUADS * 4 * VERTEX_STRIDE;
  private static final ThreadLocal<ByteBuffer> VERTEX_BUF_POOL = ThreadLocal
      .withInitial(() -> ByteBuffer.allocateDirect(VERTEX_BUF_SIZE)
          .order(ByteOrder.nativeOrder()));
  private static final ThreadLocal<ByteBuffer> WATER_BUF_POOL = ThreadLocal
      .withInitial(() -> ByteBuffer.allocateDirect(VERTEX_BUF_SIZE)
          .order(ByteOrder.nativeOrder()));
  private final ConcurrentHashMap<Long, ChunkMeshData> meshCache;
  private final Set<Long> pendingKeys = ConcurrentHashMap.newKeySet();
  private final Set<Long> dirtyKeys = ConcurrentHashMap.newKeySet();
  private final ConcurrentHashMap<Long, Long> dirtyGeneration = new ConcurrentHashMap<>();
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
    this.builderPool = Executors.newFixedThreadPool(Math.max(2, processors / 2));
    this.dirtyRebuildPool = Executors.newFixedThreadPool(Math.max(2, processors / 4));
  }
  public long getGlobalIndexBuffer() {
    return globalIndexBufferHandle;
  }
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
    return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) |
        (z & 0x3FFFFF);
  }
  private static final byte OPACITY_OPAQUE = 0;
  private static final byte OPACITY_TRANSPARENT = 1;
  private static final byte OPACITY_LEAF = 2;
  private static final ThreadLocal<HashMap<Integer, Byte>> OPACITY_CACHE = ThreadLocal
      .withInitial(() -> new HashMap<>(256));
  private static byte computeOpacityFlag(int stateId) {
    if (stateId == 0)
      return OPACITY_TRANSPARENT;
    try {
      BlockState state = Block.getStateFromRawId(stateId);
      if (state.isAir())
        return OPACITY_TRANSPARENT;
      if (isLeafBlock(state.getBlock()))
        return OPACITY_LEAF;
      if (!state.getFluidState().isEmpty()) {
        Block fluidBlk = state.getBlock();
        if (fluidBlk == Blocks.WATER)
          return OPACITY_TRANSPARENT;
        if (fluidBlk == Blocks.LAVA)
          return OPACITY_OPAQUE;
        return state.isOpaqueFullCube() ? OPACITY_OPAQUE : OPACITY_TRANSPARENT;
      }
      return state.isOpaqueFullCube() ? OPACITY_OPAQUE : OPACITY_TRANSPARENT;
    } catch (Exception e) {
      return OPACITY_TRANSPARENT;
    }
  }
  private static byte getOpacityFlag(HashMap<Integer, Byte> cache, int stateId) {
    Byte cached = cache.get(stateId);
    if (cached != null)
      return cached;
    byte flag = computeOpacityFlag(stateId);
    cache.put(stateId, flag);
    return flag;
  }
  private static boolean isTransparentFast(int[] states, int x, int y, int z,
      int leafMode, int[] nXNeg, int[] nXPos, int[] nYNeg, int[] nYPos,
      int[] nZNeg, int[] nZPos, HashMap<Integer, Byte> opacityCache) {
    int stateId = 0;
    if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
      int idx = y * 256 + z * 16 + x;
      if (states == null || states.length <= idx)
        return true;
      stateId = states[idx];
    } else {
      if (x < 0 && nXNeg != null)
        stateId = nXNeg[y * 16 + z];
      else if (x >= 16 && nXPos != null)
        stateId = nXPos[y * 16 + z];
      else if (y < 0 && nYNeg != null)
        stateId = nYNeg[z * 16 + x];
      else if (y >= 16 && nYPos != null)
        stateId = nYPos[z * 16 + x];
      else if (z < 0 && nZNeg != null)
        stateId = nZNeg[y * 16 + x];
      else if (z >= 16 && nZPos != null)
        stateId = nZPos[y * 16 + x];
      else
        return true;
    }
    if (stateId == 0)
      return true;
    byte flag = getOpacityFlag(opacityCache, stateId);
    if (flag == OPACITY_LEAF)
      return leafMode == 1;
    return flag == OPACITY_TRANSPARENT;
  }
  private static boolean isTransparentFlat(int[] states, int x, int y, int z,
      int leafMode, int[] nXNeg, int[] nXPos, int[] nYNeg, int[] nYPos,
      int[] nZNeg, int[] nZPos, byte[] oFlag) {
    int stateId = 0;
    if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
      int idx = y * 256 + z * 16 + x;
      if (states == null || states.length <= idx)
        return true;
      stateId = states[idx];
    } else {
      if (x < 0 && nXNeg != null)
        stateId = nXNeg[y * 16 + z];
      else if (x >= 16 && nXPos != null)
        stateId = nXPos[y * 16 + z];
      else if (y < 0 && nYNeg != null)
        stateId = nYNeg[z * 16 + x];
      else if (y >= 16 && nYPos != null)
        stateId = nYPos[z * 16 + x];
      else if (z < 0 && nZNeg != null)
        stateId = nZNeg[y * 16 + x];
      else if (z >= 16 && nZPos != null)
        stateId = nZPos[y * 16 + x];
      else
        return true;
    }
    if (stateId == 0)
      return true;
    if (stateId >= oFlag.length)
      return true; 
    byte f = oFlag[stateId];
    if (f == 0) {
      f = (byte) (computeOpacityFlag(stateId) + 1);
      oFlag[stateId] = f;
    }
    return f == 2 || (f == 3 && leafMode == 1); 
  }
  private static boolean isWaterAt(int[] blockStates, int x, int y, int z,
      int[] nXNeg, int[] nXPos, int[] nYNeg, int[] nYPos, int[] nZNeg, int[] nZPos) {
    int sid = 0;
    if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
      sid = blockStates[y * 256 + z * 16 + x];
    } else if (x < 0 && nXNeg != null) {
      sid = nXNeg[y * 16 + z];
    } else if (x >= 16 && nXPos != null) {
      sid = nXPos[y * 16 + z];
    } else if (y < 0 && nYNeg != null) {
      sid = nYNeg[z * 16 + x];
    } else if (y >= 16 && nYPos != null) {
      sid = nYPos[z * 16 + x];
    } else if (z < 0 && nZNeg != null) {
      sid = nZNeg[y * 16 + x];
    } else if (z >= 16 && nZPos != null) {
      sid = nZPos[y * 16 + x];
    }
    if (sid == 0)
      return false;
    BlockState bs = Block.getStateFromRawId(sid);
    if (bs.getBlock() == Blocks.WATER)
      return true;
    return !bs.getFluidState().isEmpty() && bs.getBlock() != Blocks.LAVA;
  }
  private static volatile int dbgBoundaryCullHit = 0;
  private static volatile int dbgBoundaryWaterMiss = 0;
  private static volatile int dbgBoundaryNullArr = 0;
  private static volatile int dbgWaterBaked = 0;
  private static volatile int dbgWaterFallback = 0;
  private static volatile int dbgWaterBakedCull = 0;
  private static volatile int dbgNonFullSkip = 0;
  private static boolean shouldCullFace(boolean isWater,
      int[] blockStates, int idx, int coord, int boundary,
      boolean[] sidOpaque, boolean[] sidIsWater, int[] nArr, int nIdx) {
    int nSid;
    if (coord != boundary) {
      nSid = blockStates[idx];
    } else {
      if (nArr == null) {
        if (isWater)
          dbgBoundaryNullArr++;
        return false; 
      }
      nSid = nArr[nIdx];
    }
    if (nSid == 0)
      return false; 
    if (nSid < sidOpaque.length && sidOpaque[nSid])
      return true;
    if (isWater && nSid < sidIsWater.length && sidIsWater[nSid]) {
      if (coord == boundary)
        dbgBoundaryCullHit++;
      return true;
    }
    if (isWater && coord == boundary)
      dbgBoundaryWaterMiss++;
    return false;
  }
  public int getMeshCount() {
    return meshCache.size();
  }
  public int getPendingCount() {
    return pendingKeys.size();
  }
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
    dirtyGeneration.merge(key, 1L, Long::sum);
    pendingKeys.remove(key);
  }
  public void markAllDirty() {
    dirtyKeys.addAll(meshCache.keySet());
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
    int[] nXNeg = null, nXPos = null, nYNeg = null, nYPos = null, nZNeg = null, nZPos = null;
    try {
      MinecraftClient mc = MinecraftClient.getInstance();
      ClientWorld world = mc != null ? mc.world : null;
      if (world != null) {
        nXNeg = readNeighborFace(world, chunkX - 1, chunkY, chunkZ, 4);
        nXPos = readNeighborFace(world, chunkX + 1, chunkY, chunkZ, 5);
        nYNeg = readNeighborFace(world, chunkX, chunkY - 1, chunkZ, 0);
        nYPos = readNeighborFace(world, chunkX, chunkY + 1, chunkZ, 1);
        nZNeg = readNeighborFace(world, chunkX, chunkY, chunkZ - 1, 2);
        nZPos = readNeighborFace(world, chunkX, chunkY, chunkZ + 1, 3);
      }
    } catch (Exception ignored) {
    }
    doMeshBuild(chunkX, chunkY, chunkZ, blockStates, lightData, key, 0,
        nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
  }
  private static boolean shouldRenderAtLod(BlockState state, int lodLevel) {
    if (lodLevel == 0)
      return true;
    if (state.isAir())
      return false;
    if (state.getFluidState() != null && !state.getFluidState().isEmpty())
      return true;
    if (isLeafBlock(state.getBlock())) {
      return true;
    }
    if (state.getBlock() == Blocks.SNOW) {
      return true;
    }
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
  private static volatile int leafDebugFrames = 0;
  private static int meshBuildCount = 0;
  private static volatile long meshBuildTimeAcc = 0;
  private static volatile int meshBuildTimeSamples = 0;
  private static volatile long lodSlowTimeAcc = 0;
  private static volatile int lodSlowCount = 0;
  private static volatile long lodFastTimeAcc = 0;
  private static volatile int lodFastCount = 0;
  private static volatile long pipelineTimeAcc = 0;
  private static volatile int pipelineCount = 0;
  private static final ThreadLocal<Random> REUSABLE_RANDOM = ThreadLocal.withInitial(() -> Random.create(0));
  private static final ThreadLocal<int[]> BLOCK_STATES_POOL = ThreadLocal.withInitial(() -> new int[4096]);
  private static final ThreadLocal<byte[]> LIGHT_DATA_POOL = ThreadLocal.withInitial(() -> new byte[4096]);
  private void doMeshBuild(int chunkX, int chunkY, int chunkZ,
      int[] blockStates, byte[] lightData, long key,
      int lodLevel, int[] nXNeg, int[] nXPos, int[] nYNeg,
      int[] nYPos, int[] nZNeg, int[] nZPos) {
    long buildStart = System.nanoTime();
    try {
      meshBuildCount++;
      ByteBuffer vertexBuffer = VERTEX_BUF_POOL.get();
      ByteBuffer waterBuffer = WATER_BUF_POOL.get();
      vertexBuffer.clear();
      waterBuffer.clear();
      int opaqueQuadCount = 0;
      int waterQuadCount = 0;
      int bakedQuadBlocks = 0;
      int fallbackBlocks = 0;
      MinecraftClient mc = MinecraftClient.getInstance();
      BlockModels blockModels = null;
      if (mc != null && mc.getBlockRenderManager() != null) {
        blockModels = mc.getBlockRenderManager().getModels();
      }
      MetalRenderConfig leafCfg = MetalRenderClient.getConfig();
      int leafMode = (leafCfg != null) ? leafCfg.leafCullingMode : 0;
      boolean useFastPath = (lodLevel >= 1);
      boolean skipNonDirectionalQuads = (lodLevel >= 1);
      boolean[] skipFace = null;
      if (lodLevel >= 1 && mc != null && mc.player != null) {
        skipFace = new boolean[6];
        int pcx = mc.player.getChunkPos().x;
        int pcz = mc.player.getChunkPos().z;
        int pcy = (int) Math.floor(mc.player.getY()) >> 4;
        skipFace[0] = (pcy > chunkY);
        skipFace[1] = (pcy < chunkY);
        skipFace[2] = (pcz > chunkZ);
        skipFace[3] = (pcz < chunkZ);
        skipFace[4] = (pcx > chunkX);
        skipFace[5] = (pcx < chunkX);
      }
      if (useFastPath) {
        int[] fastBiomeColors = getSectionBiomeColors(
            mc != null ? mc.world : null, chunkX, chunkY, chunkZ);
        int maxSid = 0;
        for (int i = 0; i < 4096; i++) {
          if (blockStates != null && blockStates[i] > maxSid)
            maxSid = blockStates[i];
        }
        int[][] neighborArrays = { nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos };
        for (int[] nArr : neighborArrays) {
          if (nArr != null)
            for (int s : nArr) {
              if (s > maxSid)
                maxSid = s;
            }
        }
        boolean[] sidComputed = new boolean[maxSid + 1];
        boolean[] sidSkip = new boolean[maxSid + 1]; 
        boolean[] sidOpaque = new boolean[maxSid + 1]; 
        byte[] sidR = new byte[maxSid + 1];
        byte[] sidG = new byte[maxSid + 1];
        byte[] sidB = new byte[maxSid + 1];
        byte[] sidAlpha = new byte[maxSid + 1];
        int uvSize = (maxSid + 1) * 6;
        short[] sidFaceUMin = new short[uvSize];
        short[] sidFaceUMax = new short[uvSize];
        short[] sidFaceVMin = new short[uvSize];
        short[] sidFaceVMax = new short[uvSize];
        boolean[] sidFaceHasSprite = new boolean[uvSize];
        boolean[] sidFaceHasTint = new boolean[uvSize]; 
        boolean[] sidIsWater = new boolean[maxSid + 1]; 
        sidSkip[0] = true; 
        for (int i = 0; i < 4096; i++) {
          int sid = blockStates != null ? blockStates[i] : 0;
          if (sid == 0 || sidComputed[sid])
            continue;
          sidComputed[sid] = true;
          BlockState bs = Block.getStateFromRawId(sid);
          if (bs.isAir()) {
            sidSkip[sid] = true;
            continue;
          }
          if (lodLevel > 0 && !shouldRenderAtLod(bs, lodLevel)) {
            sidSkip[sid] = true;
            continue;
          }
          Block blk = bs.getBlock();
          boolean isWater = (blk == Blocks.WATER);
          boolean isLava = (blk == Blocks.LAVA);
          boolean isFluid = isWater || isLava;
          boolean isLeaf = isLeafBlock(blk);
          sidIsWater[sid] = isWater;
          if (!bs.isOpaqueFullCube() && !isFluid && !isLeaf) {
            boolean isWaterlogged = !bs.getFluidState().isEmpty();
            if (!isWaterlogged) {
              sidOpaque[sid] = false;
              sidSkip[sid] = true;
              dbgNonFullSkip++;
              continue;
            }
            sidIsWater[sid] = true;
            sidOpaque[sid] = false;
          }
          if (isLeaf) {
            sidOpaque[sid] = (leafMode != 1); 
          } else if (isFluid) {
            sidOpaque[sid] = !isWater; 
          } else if (!sidIsWater[sid]) {
            if (!bs.getFluidState().isEmpty()) {
              sidOpaque[sid] = bs.isOpaqueFullCube();
            } else {
              sidOpaque[sid] = bs.isOpaqueFullCube();
            }
          }
          int color;
          if (sidIsWater[sid] && !isWater) {
            color = fastBiomeColors[TINT_WATER];
          } else {
            byte tintType = getBiomeTintType(blk);
            if (tintType != TINT_NONE && tintType < fastBiomeColors.length) {
              color = fastBiomeColors[tintType];
            } else {
              color = getBlockColor(bs);
            }
          }
          sidR[sid] = (byte) ((color >> 16) & 0xFF);
          sidG[sid] = (byte) ((color >> 8) & 0xFF);
          sidB[sid] = (byte) (color & 0xFF);
          if (isWater || sidIsWater[sid]) {
            sidAlpha[sid] = (byte) 220;
          } else if (isLava) {
            sidAlpha[sid] = (byte) 255;
          } else {
            sidAlpha[sid] = (byte) 255;
          }
          if (blockModels != null) {
            try {
              BlockState uvState = (sidIsWater[sid] && !isWater)
                  ? Blocks.WATER.getDefaultState()
                  : bs;
              var model = blockModels.getModel(uvState);
              if (model != null) {
                Random rand = REUSABLE_RANDOM.get();
                rand.setSeed(42L); 
                List<BlockModelPart> parts = model.getParts(rand);
                Sprite fallbackSpr = model.particleSprite();
                for (int d = 0; d < 6; d++) {
                  int uvIdx = sid * 6 + d;
                  Direction dir = ALL_DIRECTIONS[d];
                  boolean found = false;
                  for (BlockModelPart part : parts) {
                    List<BakedQuad> quads = part.getQuads(dir);
                    if (quads != null && !quads.isEmpty()) {
                      BakedQuad q = quads.get(0);
                      float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE;
                      float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
                      for (int vi = 0; vi < 4; vi++) {
                        long packedUV = q.getTexcoords(vi);
                        float u = Float.intBitsToFloat((int) (packedUV >> 32));
                        float v = Float.intBitsToFloat((int) packedUV);
                        if (u < minU)
                          minU = u;
                        if (u > maxU)
                          maxU = u;
                        if (v < minV)
                          minV = v;
                        if (v > maxV)
                          maxV = v;
                      }
                      sidFaceHasSprite[uvIdx] = true;
                      sidFaceHasTint[uvIdx] = q.hasTint();
                      sidFaceUMin[uvIdx] = (short) (minU * 65535.0f);
                      sidFaceUMax[uvIdx] = (short) (maxU * 65535.0f);
                      sidFaceVMin[uvIdx] = (short) (minV * 65535.0f);
                      sidFaceVMax[uvIdx] = (short) (maxV * 65535.0f);
                      found = true;
                      break;
                    }
                  }
                  if (!found && fallbackSpr != null) {
                    sidFaceHasSprite[uvIdx] = true;
                    if (isFluid)
                      sidFaceHasTint[uvIdx] = true;
                    sidFaceUMin[uvIdx] = (short) (fallbackSpr.getMinU() * 65535.0f);
                    sidFaceUMax[uvIdx] = (short) (fallbackSpr.getMaxU() * 65535.0f);
                    sidFaceVMin[uvIdx] = (short) (fallbackSpr.getMinV() * 65535.0f);
                    sidFaceVMax[uvIdx] = (short) (fallbackSpr.getMaxV() * 65535.0f);
                  }
                }
              }
            } catch (Exception ignored) {
            }
          }
        }
        for (int[] nArr : neighborArrays) {
          if (nArr == null)
            continue;
          for (int s : nArr) {
            if (s == 0 || s >= sidComputed.length || sidComputed[s])
              continue;
            sidComputed[s] = true;
            BlockState bs = Block.getStateFromRawId(s);
            if (bs.isAir()) {
              sidSkip[s] = true;
              continue;
            }
            Block blk = bs.getBlock();
            sidIsWater[s] = (blk == Blocks.WATER) ||
                (!bs.getFluidState().isEmpty() && blk != Blocks.LAVA);
            boolean isWaterN = sidIsWater[s];
            if (isLeafBlock(blk)) {
              sidOpaque[s] = (leafMode != 1);
            } else if (!bs.getFluidState().isEmpty()) {
              sidOpaque[s] = isWaterN ? false : bs.isOpaqueFullCube();
            } else {
              sidOpaque[s] = bs.isOpaqueFullCube();
            }
          }
        }
        for (int y = 0; y < SECTION_SIZE; y++) {
          for (int z = 0; z < SECTION_SIZE; z++) {
            for (int x = 0; x < SECTION_SIZE; x++) {
              int idx = y * 256 + z * 16 + x;
              int sid = blockStates != null ? blockStates[idx] : 0;
              if (sid == 0 || sidSkip[sid])
                continue;
              byte light = lightData != null && idx < lightData.length ? lightData[idx] : 0;
              byte pLight = (byte) ((light & 0xF) | (((light >> 4) & 0xF) << 4));
              byte r = sidR[sid], g = sidG[sid], b = sidB[sid], a = sidAlpha[sid];
              int sidBase = sid * 6; 
              boolean isWater = sidIsWater[sid];
              int waterDrop = isWater ? 32 : 0; 
              boolean[] sf = isWater ? null : skipFace;
              ByteBuffer targetBuf = isWater ? waterBuffer : vertexBuffer;
              if ((sf == null || !sf[1]) &&
                  !shouldCullFace(isWater, blockStates, idx + 256, y, 15, sidOpaque, sidIsWater, nYPos, z * 16 + x)) {
                int uv = sidBase + 1;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : b;
                if (isWater)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                emitFaceInlineWater(targetBuf, x, y, z, 1, pLight, fr, fg, fb, a,
                    sidFaceHasSprite[uv], sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                    waterDrop);
              }
              if ((sf == null || !sf[0]) &&
                  !shouldCullFace(isWater, blockStates, idx - 256, y, 0, sidOpaque, sidIsWater, nYNeg, z * 16 + x)) {
                int uv = sidBase + 0;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : b;
                if (isWater)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                emitFaceInlineWater(targetBuf, x, y, z, 0, pLight, fr, fg, fb, a,
                    sidFaceHasSprite[uv], sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv], 0);
              }
              if ((sf == null || !sf[3]) &&
                  !shouldCullFace(isWater, blockStates, idx + 16, z, 15, sidOpaque, sidIsWater, nZPos, y * 16 + x)) {
                int uv = sidBase + 3;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : b;
                if (isWater)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                emitFaceInlineWater(targetBuf, x, y, z, 3, pLight, fr, fg, fb, a,
                    sidFaceHasSprite[uv], sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                    waterDrop);
              }
              if ((sf == null || !sf[2]) &&
                  !shouldCullFace(isWater, blockStates, idx - 16, z, 0, sidOpaque, sidIsWater, nZNeg, y * 16 + x)) {
                int uv = sidBase + 2;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : b;
                if (isWater)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                emitFaceInlineWater(targetBuf, x, y, z, 2, pLight, fr, fg, fb, a,
                    sidFaceHasSprite[uv], sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                    waterDrop);
              }
              if ((sf == null || !sf[5]) &&
                  !shouldCullFace(isWater, blockStates, idx + 1, x, 15, sidOpaque, sidIsWater, nXPos, y * 16 + z)) {
                int uv = sidBase + 5;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : b;
                if (isWater)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                emitFaceInlineWater(targetBuf, x, y, z, 5, pLight, fr, fg, fb, a,
                    sidFaceHasSprite[uv], sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                    waterDrop);
              }
              if ((sf == null || !sf[4]) &&
                  !shouldCullFace(isWater, blockStates, idx - 1, x, 0, sidOpaque, sidIsWater, nXNeg, y * 16 + z)) {
                int uv = sidBase + 4;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv]) ? (byte) 0xFF : b;
                if (isWater)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                emitFaceInlineWater(targetBuf, x, y, z, 4, pLight, fr, fg, fb, a,
                    sidFaceHasSprite[uv], sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                    waterDrop);
              }
            }
          }
        }
        fallbackBlocks = 4096; 
      } else {
        int maxSidLod01 = 0;
        if (blockStates != null)
          for (int i = 0; i < 4096; i++) {
            if (blockStates[i] > maxSidLod01)
              maxSidLod01 = blockStates[i];
          }
        if (nXNeg != null)
          for (int s : nXNeg) {
            if (s > maxSidLod01)
              maxSidLod01 = s;
          }
        if (nXPos != null)
          for (int s : nXPos) {
            if (s > maxSidLod01)
              maxSidLod01 = s;
          }
        if (nYNeg != null)
          for (int s : nYNeg) {
            if (s > maxSidLod01)
              maxSidLod01 = s;
          }
        if (nYPos != null)
          for (int s : nYPos) {
            if (s > maxSidLod01)
              maxSidLod01 = s;
          }
        if (nZNeg != null)
          for (int s : nZNeg) {
            if (s > maxSidLod01)
              maxSidLod01 = s;
          }
        if (nZPos != null)
          for (int s : nZPos) {
            if (s > maxSidLod01)
              maxSidLod01 = s;
          }
        byte[] oFlag = new byte[maxSidLod01 + 1];
        BlockState[] stateArr = new BlockState[maxSidLod01 + 1];
        BlockStateModel[] modelArr = new BlockStateModel[maxSidLod01 + 1];
        boolean[] modelComputed = new boolean[maxSidLod01 + 1];
        boolean[] sidPropsComputed = new boolean[maxSidLod01 + 1];
        boolean[] sidIsAir = new boolean[maxSidLod01 + 1];
        boolean[] sidShouldSkip = new boolean[maxSidLod01 + 1]; 
        byte[] sidTintR = new byte[maxSidLod01 + 1];
        byte[] sidTintG = new byte[maxSidLod01 + 1];
        byte[] sidTintB = new byte[maxSidLod01 + 1];
        boolean[] sidIsLeaf = new boolean[maxSidLod01 + 1];
        boolean[] sidForceOpaque = new boolean[maxSidLod01 + 1];
        byte[] sidBlockAlpha = new byte[maxSidLod01 + 1];
        boolean[] sidIsNonFull = new boolean[maxSidLod01 + 1];
        boolean[] sidIsWaterLod0 = new boolean[maxSidLod01 + 1];
        boolean[] sidIsWaterloggedLod0 = new boolean[maxSidLod01 + 1]; 
        byte[] sidBiomeTintType = new byte[maxSidLod01 + 1]; 
        Random rand = REUSABLE_RANDOM.get();
        int[] sectionBiomeColors = getSectionBiomeColors(
            mc.world, chunkX, chunkY, chunkZ);
        sidShouldSkip[0] = true; 
        if (blockStates != null) {
          for (int i = 0; i < 4096; i++) {
            int sid = blockStates[i];
            if (sid == 0 || sidPropsComputed[sid])
              continue;
            sidPropsComputed[sid] = true;
            BlockState bs = Block.getStateFromRawId(sid);
            stateArr[sid] = bs;
            if (bs.isAir()) {
              sidShouldSkip[sid] = true;
              sidIsAir[sid] = true;
              continue;
            }
            if (lodLevel > 0 && !shouldRenderAtLod(bs, lodLevel)) {
              sidShouldSkip[sid] = true;
              continue;
            }
            Block blk = bs.getBlock();
            boolean isLeaf = isLeafBlock(blk);
            boolean isWater = (blk == Blocks.WATER);
            boolean isLava = (blk == Blocks.LAVA);
            boolean isFluid = isWater || isLava;
            sidIsLeaf[sid] = isLeaf;
            sidForceOpaque[sid] = isLeaf && leafMode == 0;
            sidBlockAlpha[sid] = isWater ? (byte) 220 : (byte) 255;
            sidIsNonFull[sid] = !bs.isOpaqueFullCube() && !isFluid && !isLeaf;
            sidIsWaterLod0[sid] = isWater;
            sidIsWaterloggedLod0[sid] = !isWater && !isLava && !bs.getFluidState().isEmpty();
            byte tintType = getBiomeTintType(blk);
            sidBiomeTintType[sid] = tintType;
            int tintColor;
            if (tintType != TINT_NONE && tintType < sectionBiomeColors.length) {
              tintColor = sectionBiomeColors[tintType];
            } else {
              tintColor = getBlockColor(bs);
            }
            sidTintR[sid] = (byte) ((tintColor >> 16) & 0xFF);
            sidTintG[sid] = (byte) ((tintColor >> 8) & 0xFF);
            sidTintB[sid] = (byte) (tintColor & 0xFF);
            if (blockModels != null) {
              try {
                modelArr[sid] = blockModels.getModel(bs);
              } catch (Exception ignored) {
              }
              modelComputed[sid] = true;
            }
          }
        }
        Sprite waterSpriteLod0 = null;
        if (blockModels != null) {
          try {
            var wModel = blockModels.getModel(Blocks.WATER.getDefaultState());
            if (wModel != null)
              waterSpriteLod0 = wModel.particleSprite();
          } catch (Exception ignored) {
          }
        }
        for (int y = 0; y < SECTION_SIZE; y++) {
          for (int z = 0; z < SECTION_SIZE; z++) {
            for (int x = 0; x < SECTION_SIZE; x++) {
              int idx = y * 256 + z * 16 + x;
              int stateId = blockStates != null && idx < blockStates.length
                  ? blockStates[idx]
                  : 0;
              if (stateId == 0 || sidShouldSkip[stateId])
                continue;
              byte light = lightData != null && idx < lightData.length
                  ? lightData[idx]
                  : 0;
              byte packedLight = (byte) ((light & 0xF) | ((light >> 4) & 0xF) << 4);
              byte tintR = sidTintR[stateId];
              byte tintG = sidTintG[stateId];
              byte tintB = sidTintB[stateId];
              boolean forceOpaque = sidForceOpaque[stateId];
              byte blockAlpha = sidBlockAlpha[stateId];
              boolean isNonFullBlock = sidIsNonFull[stateId];
              boolean isWaterBaked = sidIsWaterLod0[stateId];
              if (blockModels != null) {
                try {
                  BlockStateModel model = modelArr[stateId];
                  if (model != null) {
                    long seed = (long) (chunkX * 16 + x) * 3129871L ^
                        (long) (chunkZ * 16 + z) * 116129781L ^
                        (long) (chunkY * 16 + y);
                    rand.setSeed(seed);
                    int quadsThisBlock = 0;
                    List<BlockModelPart> parts = model.getParts(rand);
                    for (BlockModelPart part : parts) {
                      for (Direction dir : ALL_DIRECTIONS) {
                        if (skipFace != null && skipFace[dir.ordinal()])
                          continue;
                        int nx = x + dir.getOffsetX();
                        int ny = y + dir.getOffsetY();
                        int nz = z + dir.getOffsetZ();
                        if (isWaterBaked && isWaterAt(blockStates, nx, ny, nz,
                            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos)) {
                          dbgWaterBakedCull++;
                          continue;
                        }
                        if (!isTransparentFlat(blockStates, nx, ny, nz, leafMode,
                            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos, oFlag))
                          continue;
                        List<BakedQuad> quads = part.getQuads(dir);
                        if (quads != null) {
                          for (BakedQuad quad : quads) {
                            if (opaqueQuadCount + waterQuadCount >= MAX_QUADS)
                              break;
                            ByteBuffer tbuf = isWaterBaked ? waterBuffer : vertexBuffer;
                            int emitted = emitBakedQuad(tbuf, quad, x, y,
                                z, packedLight, tintR,
                                tintG, tintB, forceOpaque,
                                blockAlpha);
                            if (isWaterBaked)
                              waterQuadCount += emitted;
                            else
                              opaqueQuadCount += emitted;
                            quadsThisBlock++;
                          }
                        }
                      }
                      if (!skipNonDirectionalQuads) {
                        List<BakedQuad> nonDirQuads = part.getQuads(null);
                        if (nonDirQuads != null) {
                          for (BakedQuad quad : nonDirQuads) {
                            if (opaqueQuadCount + waterQuadCount >= MAX_QUADS)
                              break;
                            ByteBuffer tbuf = isWaterBaked ? waterBuffer : vertexBuffer;
                            int emitted = emitBakedQuad(tbuf, quad, x, y, z,
                                packedLight, tintR, tintG,
                                tintB, forceOpaque,
                                blockAlpha);
                            if (isWaterBaked)
                              waterQuadCount += emitted;
                            else
                              opaqueQuadCount += emitted;
                            quadsThisBlock++;
                          }
                        }
                      }
                    }
                    if (quadsThisBlock > 0) {
                      if (isWaterBaked)
                        dbgWaterBaked++;
                      bakedQuadBlocks++;
                      if (sidIsWaterloggedLod0[stateId] && waterSpriteLod0 != null) {
                        byte wAlpha = (byte) 220;
                        int wDrop2 = 32;
                        if (!isWaterAt(blockStates, x, y + 1, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                            isTransparentFlat(blockStates, x, y + 1, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg,
                                nZPos, oFlag))
                          waterQuadCount += emitFaceWater(waterBuffer, x, y, z, 1, waterSpriteLod0, packedLight, tintR,
                              tintG, tintB, wAlpha, wDrop2);
                        if (!isWaterAt(blockStates, x, y - 1, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                            isTransparentFlat(blockStates, x, y - 1, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg,
                                nZPos, oFlag))
                          waterQuadCount += emitFaceWater(waterBuffer, x, y, z, 0, waterSpriteLod0, packedLight, tintR,
                              tintG, tintB, wAlpha, 0);
                        if (!isWaterAt(blockStates, x, y, z + 1, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                            isTransparentFlat(blockStates, x, y, z + 1, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg,
                                nZPos, oFlag))
                          waterQuadCount += emitFaceWater(waterBuffer, x, y, z, 3, waterSpriteLod0, packedLight, tintR,
                              tintG, tintB, wAlpha, wDrop2);
                        if (!isWaterAt(blockStates, x, y, z - 1, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                            isTransparentFlat(blockStates, x, y, z - 1, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg,
                                nZPos, oFlag))
                          waterQuadCount += emitFaceWater(waterBuffer, x, y, z, 2, waterSpriteLod0, packedLight, tintR,
                              tintG, tintB, wAlpha, wDrop2);
                        if (!isWaterAt(blockStates, x + 1, y, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                            isTransparentFlat(blockStates, x + 1, y, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg,
                                nZPos, oFlag))
                          waterQuadCount += emitFaceWater(waterBuffer, x, y, z, 5, waterSpriteLod0, packedLight, tintR,
                              tintG, tintB, wAlpha, wDrop2);
                        if (!isWaterAt(blockStates, x - 1, y, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                            isTransparentFlat(blockStates, x - 1, y, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg,
                                nZPos, oFlag))
                          waterQuadCount += emitFaceWater(waterBuffer, x, y, z, 4, waterSpriteLod0, packedLight, tintR,
                              tintG, tintB, wAlpha, wDrop2);
                      }
                      continue;
                    }
                  }
                } catch (Exception e) {
                }
              }
              fallbackBlocks++;
              if (isWaterBaked)
                dbgWaterFallback++;
              if (isNonFullBlock) {
                if (sidIsWaterloggedLod0[stateId] && waterSpriteLod0 != null) {
                  byte wAlpha = (byte) 220;
                  int wDrop2 = 32;
                  if (!isWaterAt(blockStates, x, y + 1, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                      isTransparentFlat(blockStates, x, y + 1, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                          oFlag))
                    waterQuadCount += emitFaceWater(waterBuffer, x, y, z, 1, waterSpriteLod0, packedLight, tintR, tintG,
                        tintB, wAlpha, wDrop2);
                  if (!isWaterAt(blockStates, x, y - 1, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                      isTransparentFlat(blockStates, x, y - 1, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                          oFlag))
                    waterQuadCount += emitFaceWater(waterBuffer, x, y, z, 0, waterSpriteLod0, packedLight, tintR, tintG,
                        tintB, wAlpha, 0);
                  if (!isWaterAt(blockStates, x, y, z + 1, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                      isTransparentFlat(blockStates, x, y, z + 1, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                          oFlag))
                    waterQuadCount += emitFaceWater(waterBuffer, x, y, z, 3, waterSpriteLod0, packedLight, tintR, tintG,
                        tintB, wAlpha, wDrop2);
                  if (!isWaterAt(blockStates, x, y, z - 1, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                      isTransparentFlat(blockStates, x, y, z - 1, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                          oFlag))
                    waterQuadCount += emitFaceWater(waterBuffer, x, y, z, 2, waterSpriteLod0, packedLight, tintR, tintG,
                        tintB, wAlpha, wDrop2);
                  if (!isWaterAt(blockStates, x + 1, y, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                      isTransparentFlat(blockStates, x + 1, y, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                          oFlag))
                    waterQuadCount += emitFaceWater(waterBuffer, x, y, z, 5, waterSpriteLod0, packedLight, tintR, tintG,
                        tintB, wAlpha, wDrop2);
                  if (!isWaterAt(blockStates, x - 1, y, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                      isTransparentFlat(blockStates, x - 1, y, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                          oFlag))
                    waterQuadCount += emitFaceWater(waterBuffer, x, y, z, 4, waterSpriteLod0, packedLight, tintR, tintG,
                        tintB, wAlpha, wDrop2);
                }
                continue;
              }
              Sprite sprite = (modelArr[stateId] != null) ? modelArr[stateId].particleSprite() : null;
              boolean isWaterBlock = sidIsWaterLod0[stateId];
              int wDrop = isWaterBlock ? 32 : 0;
              ByteBuffer fbuf = isWaterBlock ? waterBuffer : vertexBuffer;
              if ((!isWaterBlock || !isWaterAt(blockStates, x, y + 1, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos)) &&
                  isTransparentFlat(blockStates, x, y + 1, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                      oFlag)) {
                int emitted = emitFaceWater(fbuf, x, y, z, 1, sprite, packedLight, tintR, tintG, tintB,
                    blockAlpha, wDrop);
                if (isWaterBlock)
                  waterQuadCount += emitted;
                else
                  opaqueQuadCount += emitted;
              }
              if ((!isWaterBlock || !isWaterAt(blockStates, x, y - 1, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos)) &&
                  isTransparentFlat(blockStates, x, y - 1, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                      oFlag)) {
                int emitted = emitFaceWater(fbuf, x, y, z, 0, sprite, packedLight, tintR, tintG, tintB,
                    blockAlpha, wDrop);
                if (isWaterBlock)
                  waterQuadCount += emitted;
                else
                  opaqueQuadCount += emitted;
              }
              if ((!isWaterBlock || !isWaterAt(blockStates, x, y, z + 1, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos)) &&
                  isTransparentFlat(blockStates, x, y, z + 1, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                      oFlag)) {
                int emitted = emitFaceWater(fbuf, x, y, z, 3, sprite, packedLight, tintR, tintG, tintB,
                    blockAlpha, wDrop);
                if (isWaterBlock)
                  waterQuadCount += emitted;
                else
                  opaqueQuadCount += emitted;
              }
              if ((!isWaterBlock || !isWaterAt(blockStates, x, y, z - 1, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos)) &&
                  isTransparentFlat(blockStates, x, y, z - 1, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                      oFlag)) {
                int emitted = emitFaceWater(fbuf, x, y, z, 2, sprite, packedLight, tintR, tintG, tintB,
                    blockAlpha, wDrop);
                if (isWaterBlock)
                  waterQuadCount += emitted;
                else
                  opaqueQuadCount += emitted;
              }
              if ((!isWaterBlock || !isWaterAt(blockStates, x + 1, y, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos)) &&
                  isTransparentFlat(blockStates, x + 1, y, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                      oFlag)) {
                int emitted = emitFaceWater(fbuf, x, y, z, 5, sprite, packedLight, tintR, tintG, tintB,
                    blockAlpha, wDrop);
                if (isWaterBlock)
                  waterQuadCount += emitted;
                else
                  opaqueQuadCount += emitted;
              }
              if ((!isWaterBlock || !isWaterAt(blockStates, x - 1, y, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos)) &&
                  isTransparentFlat(blockStates, x - 1, y, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                      oFlag)) {
                int emitted = emitFaceWater(fbuf, x, y, z, 4, sprite, packedLight, tintR, tintG, tintB,
                    blockAlpha, wDrop);
                if (isWaterBlock)
                  waterQuadCount += emitted;
                else
                  opaqueQuadCount += emitted;
              }
            }
          }
        }
      } 
      int quadCount = opaqueQuadCount + waterQuadCount;
      if (waterQuadCount > 0) {
        waterBuffer.flip();
        vertexBuffer.put(waterBuffer);
      }
      if (meshBuildCount <= 3) {
        MetalLogger.info("[BUILD_V9 DIAG] chunk[%d,%d,%d] quads=%d opaque=%d water=%d baked=%d fallback=%d lod=%d",
            chunkX, chunkY, chunkZ, quadCount, opaqueQuadCount, waterQuadCount,
            bakedQuadBlocks, fallbackBlocks, lodLevel);
      }
      if (quadCount == 0) {
        ChunkMeshData old = meshCache.remove(key);
        if (old != null) {
          NativeBridge.nUnregisterChunkMesh(chunkX, chunkY, chunkZ);
          NativeBridge.nDestroyBuffer(old.bufferHandle);
        }
        return;
      }
      vertexBuffer.flip();
      int dataLen = quadCount * 4 * VERTEX_STRIDE;
      long bufferHandle = NativeBridge.nCreateBuffer(
          deviceHandle, dataLen, NativeMemory.STORAGE_MODE_SHARED);
      NativeBridge.nUploadBufferDataDirect(bufferHandle, vertexBuffer, 0,
          dataLen);
      ChunkMeshData mesh = new ChunkMeshData(bufferHandle, quadCount, chunkX,
          chunkY, chunkZ, lodLevel);
      ChunkMeshData old = meshCache.put(key, mesh);
      NativeBridge.nRegisterChunkMesh(chunkX, chunkY, chunkZ,
          bufferHandle, quadCount, opaqueQuadCount, lodLevel);
      if (old != null) {
        NativeBridge.nDestroyBuffer(old.bufferHandle);
      }
      dirtyKeys.remove(key);
    } catch (Exception e) {
      MetalLogger.error("Meshing error for chunk [%d,%d,%d]", chunkX, chunkY,
          chunkZ);
    } finally {
      long buildElapsed = System.nanoTime() - buildStart;
      meshBuildTimeAcc += buildElapsed;
      if (lodLevel < 1) {
        lodSlowTimeAcc += buildElapsed;
        lodSlowCount++;
      } else {
        lodFastTimeAcc += buildElapsed;
        lodFastCount++;
      }
      int samples = ++meshBuildTimeSamples;
      if (samples % 500 == 0) {
        double avgMs = (meshBuildTimeAcc / 1e6) / samples;
        double slowAvg = lodSlowCount > 0 ? (lodSlowTimeAcc / 1e6) / lodSlowCount : 0;
        double fastAvg = lodFastCount > 0 ? (lodFastTimeAcc / 1e6) / lodFastCount : 0;
        MetalLogger.info(
            "MESH_PERF: avg=%.2fms over %d builds | LOD0: %.2fms (%d) LOD1+: %.2fms (%d) | pipeline=%.2fms (%d)",
            avgMs, samples, slowAvg, lodSlowCount, fastAvg, lodFastCount,
            pipelineCount > 0 ? (pipelineTimeAcc / 1e6) / pipelineCount : 0.0, pipelineCount);
        MetalLogger.info(
            "WATER_DBG: boundaryCullHit=%d boundaryWaterMiss=%d boundaryNullArr=%d | waterBaked=%d waterFallback=%d waterBakedFaceCull=%d nonFullSkip=%d",
            dbgBoundaryCullHit, dbgBoundaryWaterMiss, dbgBoundaryNullArr,
            dbgWaterBaked, dbgWaterFallback, dbgWaterBakedCull, dbgNonFullSkip);
      }
      pendingKeys.remove(key);
    }
  }
  public ChunkMeshData buildMesh(int chunkX, int chunkY, int chunkZ,
      int[] blockStates, byte[] lightData) {
    buildMeshAsync(chunkX, chunkY, chunkZ, blockStates, lightData);
    return null;
  }
  private int[] readNeighborFace(ClientWorld world, int nCx, int nCy, int nCz,
      int faceDir) {
    if (world == null)
      return null;
    WorldChunk nChunk = world.getChunkManager().getWorldChunk(nCx, nCz);
    if (nChunk == null)
      return null;
    int sectionIdx = nCy - nChunk.getBottomSectionCoord();
    ChunkSection[] sections = nChunk.getSectionArray();
    if (sectionIdx < 0 || sectionIdx >= sections.length)
      return null;
    ChunkSection section = sections[sectionIdx];
    if (section == null || section.isEmpty())
      return null;
    int[] face = new int[256];
    boolean hasAny = false;
    switch (faceDir) {
      case 0:
        for (int z = 0; z < 16; z++)
          for (int x = 0; x < 16; x++) {
            BlockState bs = section.getBlockState(x, 15, z);
            if (!bs.isAir()) {
              face[z * 16 + x] = Block.getRawIdFromState(bs);
              hasAny = true;
            }
          }
        break;
      case 1:
        for (int z = 0; z < 16; z++)
          for (int x = 0; x < 16; x++) {
            BlockState bs = section.getBlockState(x, 0, z);
            if (!bs.isAir()) {
              face[z * 16 + x] = Block.getRawIdFromState(bs);
              hasAny = true;
            }
          }
        break;
      case 2:
        for (int y = 0; y < 16; y++)
          for (int x = 0; x < 16; x++) {
            BlockState bs = section.getBlockState(x, y, 15);
            if (!bs.isAir()) {
              face[y * 16 + x] = Block.getRawIdFromState(bs);
              hasAny = true;
            }
          }
        break;
      case 3:
        for (int y = 0; y < 16; y++)
          for (int x = 0; x < 16; x++) {
            BlockState bs = section.getBlockState(x, y, 0);
            if (!bs.isAir()) {
              face[y * 16 + x] = Block.getRawIdFromState(bs);
              hasAny = true;
            }
          }
        break;
      case 4:
        for (int y = 0; y < 16; y++)
          for (int z = 0; z < 16; z++) {
            BlockState bs = section.getBlockState(15, y, z);
            if (!bs.isAir()) {
              face[y * 16 + z] = Block.getRawIdFromState(bs);
              hasAny = true;
            }
          }
        break;
      case 5:
        for (int y = 0; y < 16; y++)
          for (int z = 0; z < 16; z++) {
            BlockState bs = section.getBlockState(0, y, z);
            if (!bs.isAir()) {
              face[y * 16 + z] = Block.getRawIdFromState(bs);
              hasAny = true;
            }
          }
        break;
    }
    return hasAny ? face : null;
  }
  public void buildMeshFromWorld(int chunkX, int chunkY, int chunkZ) {
    buildMeshFromWorld(chunkX, chunkY, chunkZ, 0);
  }
  public void buildMeshFromWorld(int chunkX, int chunkY, int chunkZ,
      int lodLevel) {
    buildMeshFromWorld(chunkX, chunkY, chunkZ, lodLevel, false);
  }
  public void buildMeshFromWorld(int chunkX, int chunkY, int chunkZ,
      int lodLevel, boolean highPriority) {
    if (!initialized)
      return;
    long key = packChunkKey(chunkX, chunkY, chunkZ);
    boolean wasDirty = dirtyKeys.contains(key);
    long genAtSubmit = dirtyGeneration.getOrDefault(key, 0L);
    if (!pendingKeys.add(key)) {
      return;
    }
    ExecutorService pool;
    if (highPriority) {
      pool = builderPool; 
    } else {
      pool = wasDirty ? dirtyRebuildPool : builderPool;
    }
    pool.submit(() -> {
      long genNow = dirtyGeneration.getOrDefault(key, 0L);
      if (genNow != genAtSubmit) {
        pendingKeys.remove(key);
        return;
      }
      try {
        long pipelineStart = System.nanoTime();
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
        WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
        if (chunk == null) {
          pendingKeys.remove(key);
          return;
        }
        int sectionIdx = chunkY - chunk.getBottomSectionCoord();
        ChunkSection[] chunkSections = chunk.getSectionArray();
        if (sectionIdx < 0 || sectionIdx >= chunkSections.length) {
          pendingKeys.remove(key);
          return;
        }
        ChunkSection section = chunkSections[sectionIdx];
        if (section == null || section.isEmpty()) {
          pendingKeys.remove(key);
          return;
        }
        int[] blockStates = BLOCK_STATES_POOL.get();
        java.util.Arrays.fill(blockStates, 0); 
        boolean hasAnyBlock = false;
        for (int y = 0; y < 16; y++) {
          for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
              BlockState bs = section.getBlockState(x, y, z);
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
        int[] neighborXNeg = null, neighborXPos = null;
        int[] neighborYNeg = null, neighborYPos = null;
        int[] neighborZNeg = null, neighborZPos = null;
        neighborXNeg = readNeighborFace(world, chunkX - 1, chunkY, chunkZ, 4);
        neighborXPos = readNeighborFace(world, chunkX + 1, chunkY, chunkZ, 5);
        neighborYNeg = readNeighborFace(world, chunkX, chunkY - 1, chunkZ, 0);
        neighborYPos = readNeighborFace(world, chunkX, chunkY + 1, chunkZ, 1);
        neighborZNeg = readNeighborFace(world, chunkX, chunkY, chunkZ - 1, 2);
        neighborZPos = readNeighborFace(world, chunkX, chunkY, chunkZ + 1, 3);
        byte[] lightData = LIGHT_DATA_POOL.get();
        try {
          LightingProvider lightProvider = world.getLightingProvider();
          BlockPos.Mutable mutablePos = new BlockPos.Mutable();
          int baseX = chunkX * 16, baseY = chunkY * 16, baseZ = chunkZ * 16;
          for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
              for (int x = 0; x < 16; x++) {
                mutablePos.set(baseX + x, baseY + y, baseZ + z);
                int bl = lightProvider.get(LightType.BLOCK).getLightLevel(mutablePos);
                int sl = lightProvider.get(LightType.SKY).getLightLevel(mutablePos);
                lightData[y * 256 + z * 16 + x] = (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
              }
            }
          }
        } catch (Exception e) {
          java.util.Arrays.fill(lightData, (byte) 0xF0);
        }
        doMeshBuild(chunkX, chunkY, chunkZ, blockStates, lightData, key,
            lodLevel, neighborXNeg, neighborXPos, neighborYNeg, neighborYPos,
            neighborZNeg, neighborZPos);
        long pipeElapsed = System.nanoTime() - pipelineStart;
        pipelineTimeAcc += pipeElapsed;
        pipelineCount++;
      } catch (Exception e) {
        pendingKeys.remove(key);
        MetalLogger.error("Async mesh build error for [%d,%d,%d]: %s", chunkX,
            chunkY, chunkZ, e.getMessage());
      }
    });
  }
  private int emitBakedQuad(ByteBuffer buf, BakedQuad quad, int blockX,
      int blockY, int blockZ, byte packedLight,
      byte tintR, byte tintG, byte tintB,
      boolean forceOpaque, byte vertAlpha) {
    Direction face = quad.face();
    byte normalIndex = dirToNormalIndex(face);
    float shade = quad.shade() ? getFaceShade(normalIndex) : 1.0f;
    byte baseR, baseG, baseB;
    if (quad.hasTint()) {
      baseR = tintR;
      baseG = tintG;
      baseB = tintB;
    } else {
      baseR = (byte) 255;
      baseG = (byte) 255;
      baseB = (byte) 255;
    }
    byte sr = (byte) ((baseR & 0xFF) * shade);
    byte sg = (byte) ((baseG & 0xFF) * shade);
    byte sb = (byte) ((baseB & 0xFF) * shade);
    byte light = packedLight;
    int emission = quad.lightEmission();
    if (emission > 0) {
      int bl = Math.max(light & 0xF, emission);
      int sl = (light >> 4) & 0xF;
      light = (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
    }
    for (int v = 0; v < 4; v++) {
      Vector3fc pos = quad.getPosition(v);
      long packedUV = quad.getTexcoords(v);
      float vx = pos.x() + blockX;
      float vy = pos.y() + blockY;
      float vz = pos.z() + blockZ;
      buf.putShort((short) (vx * 256.0f));
      buf.putShort((short) (vy * 256.0f));
      buf.putShort((short) (vz * 256.0f));
      float u = Float.intBitsToFloat((int) (packedUV >> 32));
      float vCoord = Float.intBitsToFloat((int) packedUV);
      buf.putShort((short) (u * 65535.0f));
      buf.putShort((short) (vCoord * 65535.0f));
      buf.put(sr);
      buf.put(sg);
      buf.put(sb);
      buf.put(forceOpaque ? (byte) 254 : vertAlpha);
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
      byte b, byte alpha) {
    return emitFaceScaled(buf, x, y, z, normalIndex, sprite, packedLight, r, g, b, alpha, 1, 0);
  }
  private int emitFaceWater(ByteBuffer buf, int x, int y, int z, int normalIndex,
      Sprite sprite, byte packedLight, byte r, byte g,
      byte b, byte alpha, int topDrop) {
    return emitFaceScaled(buf, x, y, z, normalIndex, sprite, packedLight, r, g, b, alpha, 1, topDrop);
  }
  private static final float[] FACE_SHADE = { 0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f };
  private static void emitFaceInline(ByteBuffer buf, int x, int y, int z,
      int normalIdx, byte light, byte r, byte g, byte b, byte a,
      boolean hasSpr, short uMin, short uMax, short vMin, short vMax) {
    emitFaceInlineWater(buf, x, y, z, normalIdx, light, r, g, b, a, hasSpr, uMin, uMax, vMin, vMax, 0);
  }
  private static void emitFaceInlineWater(ByteBuffer buf, int x, int y, int z,
      int normalIdx, byte light, byte r, byte g, byte b, byte a,
      boolean hasSpr, short uMin, short uMax, short vMin, short vMax,
      int topDrop) {
    float shade = FACE_SHADE[normalIdx];
    byte sr = (byte) ((r & 0xFF) * shade);
    byte sg = (byte) ((g & 0xFF) * shade);
    byte sb = (byte) ((b & 0xFF) * shade);
    byte nIdx = (byte) normalIdx;
    if (!hasSpr) {
      uMin = 0;
      uMax = (short) 65535;
      vMin = 0;
      vMax = (short) 65535;
    }
    short sx = (short) (x * 256), sy = (short) (y * 256), sz = (short) (z * 256);
    short ex = (short) ((x + 1) * 256), ey = (short) ((y + 1) * 256 - topDrop), ez = (short) ((z + 1) * 256);
    switch (normalIdx) {
      case 1: 
        emitVertex(buf, sx, ey, sz, uMin, vMin, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, ey, ez, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, ey, ez, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, ey, sz, uMax, vMin, sr, sg, sb, a, light, nIdx);
        break;
      case 0: 
        emitVertex(buf, sx, sy, ez, uMin, vMin, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, sz, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, sz, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, ez, uMax, vMin, sr, sg, sb, a, light, nIdx);
        break;
      case 3: 
        emitVertex(buf, sx, ey, ez, uMin, vMin, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, ez, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, ez, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, ey, ez, uMax, vMin, sr, sg, sb, a, light, nIdx);
        break;
      case 2: 
        emitVertex(buf, ex, ey, sz, uMin, vMin, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, sz, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, sz, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, ey, sz, uMax, vMin, sr, sg, sb, a, light, nIdx);
        break;
      case 5: 
        emitVertex(buf, ex, ey, ez, uMin, vMin, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, ez, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, sz, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, ey, sz, uMax, vMin, sr, sg, sb, a, light, nIdx);
        break;
      case 4: 
        emitVertex(buf, sx, ey, sz, uMin, vMin, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, sz, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, ez, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, ey, ez, uMax, vMin, sr, sg, sb, a, light, nIdx);
        break;
    }
  }
  private static void emitVertex(ByteBuffer buf, short px, short py, short pz,
      short u, short v, byte r, byte g, byte b, byte a, byte light, byte nIdx) {
    buf.putShort(px);
    buf.putShort(py);
    buf.putShort(pz);
    buf.putShort(u);
    buf.putShort(v);
    buf.put(r);
    buf.put(g);
    buf.put(b);
    buf.put(a);
    buf.put(light);
    buf.put(nIdx);
  }
  private int emitFaceScaled(ByteBuffer buf, int x, int y, int z, int normalIndex,
      Sprite sprite, byte packedLight, byte r, byte g,
      byte b, byte alpha, int scale, int topDrop) {
    short[][] positions = getFaceVerticesScaled(x, y, z, normalIndex, scale, topDrop);
    short uMin = 0, uMax = (short) 65535, vMin = 0, vMax = (short) 65535;
    if (sprite != null) {
      uMin = (short) (sprite.getMinU() * 65535.0f);
      uMax = (short) (sprite.getMaxU() * 65535.0f);
      vMin = (short) (sprite.getMinV() * 65535.0f);
      vMax = (short) (sprite.getMaxV() * 65535.0f);
    }
    short[][] uvs = { { uMin, vMin }, { uMin, vMax }, { uMax, vMax }, { uMax, vMin } };
    byte a = alpha;
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
    byte sr = (byte) ((r & 0xFF) * shade);
    byte sg = (byte) ((g & 0xFF) * shade);
    byte sb = (byte) ((b & 0xFF) * shade);
    byte nIdx = (byte) normalIndex;
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
  private static boolean isLeafBlock(Block block) {
    return block == Blocks.OAK_LEAVES || block == Blocks.BIRCH_LEAVES ||
        block == Blocks.SPRUCE_LEAVES || block == Blocks.JUNGLE_LEAVES ||
        block == Blocks.ACACIA_LEAVES || block == Blocks.DARK_OAK_LEAVES ||
        block == Blocks.AZALEA_LEAVES ||
        block == Blocks.FLOWERING_AZALEA_LEAVES ||
        block == Blocks.MANGROVE_LEAVES || block == Blocks.CHERRY_LEAVES;
  }
  private static final Map<Block, Integer> BLOCK_COLORS = new IdentityHashMap<>();
  private static final int DEFAULT_BLOCK_COLOR = 0xB0B0B0;
  private static final byte TINT_NONE = 0;
  private static final byte TINT_GRASS = 1;    
  private static final byte TINT_FOLIAGE = 2;  
  private static final byte TINT_WATER = 3;    
  private static final IdentityHashMap<Block, Byte> BIOME_TINT_TYPE = new IdentityHashMap<>();
  static {
    BLOCK_COLORS.put(Blocks.GRASS_BLOCK, 0x7CBE3F);
    BLOCK_COLORS.put(Blocks.DIRT, 0x866043);
    BLOCK_COLORS.put(Blocks.COARSE_DIRT, 0x866043);
    BLOCK_COLORS.put(Blocks.ROOTED_DIRT, 0x866043);
    BLOCK_COLORS.put(Blocks.STONE, 0x7D7D7D);
    BLOCK_COLORS.put(Blocks.SMOOTH_STONE, 0x7D7D7D);
    BLOCK_COLORS.put(Blocks.COBBLESTONE, 0x7A7A7A);
    BLOCK_COLORS.put(Blocks.MOSSY_COBBLESTONE, 0x7A7A7A);
    BLOCK_COLORS.put(Blocks.GRANITE, 0x9A6B53);
    BLOCK_COLORS.put(Blocks.DIORITE, 0xBFBFBF);
    BLOCK_COLORS.put(Blocks.ANDESITE, 0x888888);
    BLOCK_COLORS.put(Blocks.DEEPSLATE, 0x505050);
    BLOCK_COLORS.put(Blocks.COBBLED_DEEPSLATE, 0x505050);
    BLOCK_COLORS.put(Blocks.TUFF, 0x6B6B5E);
    BLOCK_COLORS.put(Blocks.BEDROCK, 0x3A3A3A);
    BLOCK_COLORS.put(Blocks.SAND, 0xDCCD82);
    BLOCK_COLORS.put(Blocks.SANDSTONE, 0xDCCD82);
    BLOCK_COLORS.put(Blocks.RED_SAND, 0xBE6621);
    BLOCK_COLORS.put(Blocks.RED_SANDSTONE, 0xBE6621);
    BLOCK_COLORS.put(Blocks.GRAVEL, 0x857E79);
    BLOCK_COLORS.put(Blocks.CLAY, 0x9EA4B0);
    BLOCK_COLORS.put(Blocks.COAL_ORE, 0x4A4A4A);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_COAL_ORE, 0x4A4A4A);
    BLOCK_COLORS.put(Blocks.IRON_ORE, 0xB08D63);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_IRON_ORE, 0xB08D63);
    BLOCK_COLORS.put(Blocks.GOLD_ORE, 0xDBCD34);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_GOLD_ORE, 0xDBCD34);
    BLOCK_COLORS.put(Blocks.DIAMOND_ORE, 0x5DDCD3);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_DIAMOND_ORE, 0x5DDCD3);
    BLOCK_COLORS.put(Blocks.COPPER_ORE, 0xA86340);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_COPPER_ORE, 0xA86340);
    BLOCK_COLORS.put(Blocks.LAPIS_ORE, 0x3450AB);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_LAPIS_ORE, 0x3450AB);
    BLOCK_COLORS.put(Blocks.REDSTONE_ORE, 0xAA0000);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_REDSTONE_ORE, 0xAA0000);
    BLOCK_COLORS.put(Blocks.EMERALD_ORE, 0x17DD62);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_EMERALD_ORE, 0x17DD62);
    BLOCK_COLORS.put(Blocks.OAK_LOG, 0x6B5534);
    BLOCK_COLORS.put(Blocks.OAK_WOOD, 0x6B5534);
    BLOCK_COLORS.put(Blocks.OAK_PLANKS, 0xAF8F55);
    BLOCK_COLORS.put(Blocks.BIRCH_LOG, 0xD5CB93);
    BLOCK_COLORS.put(Blocks.BIRCH_WOOD, 0xD5CB93);
    BLOCK_COLORS.put(Blocks.BIRCH_PLANKS, 0xC5B77B);
    BLOCK_COLORS.put(Blocks.SPRUCE_LOG, 0x3D2813);
    BLOCK_COLORS.put(Blocks.SPRUCE_WOOD, 0x3D2813);
    BLOCK_COLORS.put(Blocks.SPRUCE_PLANKS, 0x674B2B);
    BLOCK_COLORS.put(Blocks.DARK_OAK_LOG, 0x372814);
    BLOCK_COLORS.put(Blocks.DARK_OAK_WOOD, 0x372814);
    BLOCK_COLORS.put(Blocks.DARK_OAK_PLANKS, 0x422C14);
    BLOCK_COLORS.put(Blocks.JUNGLE_LOG, 0x554A2F);
    BLOCK_COLORS.put(Blocks.JUNGLE_WOOD, 0x554A2F);
    BLOCK_COLORS.put(Blocks.ACACIA_LOG, 0x676157);
    BLOCK_COLORS.put(Blocks.ACACIA_WOOD, 0x676157);
    BLOCK_COLORS.put(Blocks.OAK_LEAVES, 0x4BA836);
    BLOCK_COLORS.put(Blocks.JUNGLE_LEAVES, 0x4BA836);
    BLOCK_COLORS.put(Blocks.ACACIA_LEAVES, 0x4BA836);
    BLOCK_COLORS.put(Blocks.DARK_OAK_LEAVES, 0x4BA836);
    BLOCK_COLORS.put(Blocks.BIRCH_LEAVES, 0x6B9940);
    BLOCK_COLORS.put(Blocks.SPRUCE_LEAVES, 0x3B6126);
    BLOCK_COLORS.put(Blocks.MANGROVE_LEAVES, 0x6A9B2D);
    BLOCK_COLORS.put(Blocks.CHERRY_LEAVES, 0xE8A5C8);
    BLOCK_COLORS.put(Blocks.AZALEA_LEAVES, 0x4BA836);
    BLOCK_COLORS.put(Blocks.FLOWERING_AZALEA_LEAVES, 0x4BA836);
    BLOCK_COLORS.put(Blocks.WATER, 0x3F76E4);
    BLOCK_COLORS.put(Blocks.ICE, 0x8DB3E2);
    BLOCK_COLORS.put(Blocks.PACKED_ICE, 0x8DB3E2);
    BLOCK_COLORS.put(Blocks.BLUE_ICE, 0x8DB3E2);
    BLOCK_COLORS.put(Blocks.SNOW_BLOCK, 0xF0F0F0);
    BLOCK_COLORS.put(Blocks.SNOW, 0xF0F0F0);
    BLOCK_COLORS.put(Blocks.POWDER_SNOW, 0xF0F0F0);
    BLOCK_COLORS.put(Blocks.NETHERRACK, 0x6B3430);
    BLOCK_COLORS.put(Blocks.SOUL_SAND, 0x513F32);
    BLOCK_COLORS.put(Blocks.SOUL_SOIL, 0x513F32);
    BLOCK_COLORS.put(Blocks.BASALT, 0x4B4B4F);
    BLOCK_COLORS.put(Blocks.SMOOTH_BASALT, 0x4B4B4F);
    BLOCK_COLORS.put(Blocks.BLACKSTONE, 0x2C2630);
    BLOCK_COLORS.put(Blocks.GLOWSTONE, 0xAB8048);
    BLOCK_COLORS.put(Blocks.GLASS, 0xFFFFFF);
    BIOME_TINT_TYPE.put(Blocks.GRASS_BLOCK, TINT_GRASS);
    BIOME_TINT_TYPE.put(Blocks.SHORT_GRASS, TINT_GRASS);
    BIOME_TINT_TYPE.put(Blocks.TALL_GRASS, TINT_GRASS);
    BIOME_TINT_TYPE.put(Blocks.FERN, TINT_GRASS);
    BIOME_TINT_TYPE.put(Blocks.LARGE_FERN, TINT_GRASS);
    BIOME_TINT_TYPE.put(Blocks.SUGAR_CANE, TINT_GRASS);
    BIOME_TINT_TYPE.put(Blocks.POTTED_FERN, TINT_GRASS);
    BIOME_TINT_TYPE.put(Blocks.OAK_LEAVES, TINT_FOLIAGE);
    BIOME_TINT_TYPE.put(Blocks.JUNGLE_LEAVES, TINT_FOLIAGE);
    BIOME_TINT_TYPE.put(Blocks.ACACIA_LEAVES, TINT_FOLIAGE);
    BIOME_TINT_TYPE.put(Blocks.DARK_OAK_LEAVES, TINT_FOLIAGE);
    BIOME_TINT_TYPE.put(Blocks.MANGROVE_LEAVES, TINT_FOLIAGE);
    BIOME_TINT_TYPE.put(Blocks.VINE, TINT_FOLIAGE);
    BIOME_TINT_TYPE.put(Blocks.WATER, TINT_WATER);
    try {
      var leafLitterField = Blocks.class.getField("LEAF_LITTER");
      if (leafLitterField != null) {
        Block leafLitter = (Block) leafLitterField.get(null);
        BIOME_TINT_TYPE.put(leafLitter, TINT_FOLIAGE);
      }
    } catch (Exception ignored) {
    }
  }
  private static byte getBiomeTintType(Block block) {
    Byte type = BIOME_TINT_TYPE.get(block);
    return type != null ? type : TINT_NONE;
  }
  private static int getBiomeColor(ClientWorld world, int worldX, int worldY, int worldZ, byte tintType) {
    if (world == null || tintType == TINT_NONE)
      return DEFAULT_BLOCK_COLOR;
    try {
      BlockPos pos = new BlockPos(worldX, worldY, worldZ);
      return switch (tintType) {
        case TINT_GRASS -> world.getColor(pos, net.minecraft.world.biome.BiomeKeys.PLAINS != null
            ? net.minecraft.client.color.world.BiomeColors.GRASS_COLOR : net.minecraft.client.color.world.BiomeColors.GRASS_COLOR);
        case TINT_FOLIAGE -> world.getColor(pos, net.minecraft.client.color.world.BiomeColors.FOLIAGE_COLOR);
        case TINT_WATER -> world.getColor(pos, net.minecraft.client.color.world.BiomeColors.WATER_COLOR);
        default -> DEFAULT_BLOCK_COLOR;
      };
    } catch (Exception e) {
      return DEFAULT_BLOCK_COLOR;
    }
  }
  private static int[] getSectionBiomeColors(ClientWorld world, int chunkX, int chunkY, int chunkZ) {
    int[] colors = new int[4]; 
    colors[0] = DEFAULT_BLOCK_COLOR;
    if (world == null)
      return colors;
    try {
      int cx = chunkX * 16 + 8, cy = chunkY * 16 + 8, cz = chunkZ * 16 + 8;
      BlockPos center = new BlockPos(cx, cy, cz);
      colors[TINT_GRASS] = world.getColor(center, net.minecraft.client.color.world.BiomeColors.GRASS_COLOR);
      colors[TINT_FOLIAGE] = world.getColor(center, net.minecraft.client.color.world.BiomeColors.FOLIAGE_COLOR);
      colors[TINT_WATER] = world.getColor(center, net.minecraft.client.color.world.BiomeColors.WATER_COLOR);
    } catch (Exception e) {
      colors[TINT_GRASS] = 0x7CBE3F;
      colors[TINT_FOLIAGE] = 0x4BA836;
      colors[TINT_WATER] = 0x3F76E4;
    }
    return colors;
  }
  private static int getBlockColor(BlockState state) {
    if (state == null)
      return 0xC8C8C8;
    return BLOCK_COLORS.getOrDefault(state.getBlock(), DEFAULT_BLOCK_COLOR);
  }
  private short[][] getFaceVertices(int x, int y, int z, int normalIndex) {
    return getFaceVerticesScaled(x, y, z, normalIndex, 1, 0);
  }
  private short[][] getFaceVerticesScaled(int x, int y, int z, int normalIndex, int scale, int topDrop) {
    short sx = (short) (x * 256), sy = (short) (y * 256), sz = (short) (z * 256);
    short nex = (short) ((x + scale) * 256), ney = (short) ((y + scale) * 256 - topDrop),
        nez = (short) ((z + scale) * 256);
    if (normalIndex == 1)
      return new short[][] {
          { sx, ney, sz }, { sx, ney, nez }, { nex, ney, nez }, { nex, ney, sz } };
    if (normalIndex == 0)
      return new short[][] {
          { sx, sy, nez }, { sx, sy, sz }, { nex, sy, sz }, { nex, sy, nez } };
    if (normalIndex == 3)
      return new short[][] {
          { sx, ney, nez }, { sx, sy, nez }, { nex, sy, nez }, { nex, ney, nez } };
    if (normalIndex == 2)
      return new short[][] {
          { nex, ney, sz }, { nex, sy, sz }, { sx, sy, sz }, { sx, ney, sz } };
    if (normalIndex == 5)
      return new short[][] {
          { nex, ney, nez }, { nex, sy, nez }, { nex, sy, sz }, { nex, ney, sz } };
    if (normalIndex == 4)
      return new short[][] {
          { sx, ney, sz }, { sx, sy, sz }, { sx, sy, nez }, { sx, ney, nez } };
    return new short[][] {
        { sx, sy, sz }, { sx, sy, sz }, { sx, sy, sz }, { sx, sy, sz } };
  }
  public ChunkMeshData getMesh(int cx, int cy, int cz) {
    return meshCache.get(packChunkKey(cx, cy, cz));
  }
  public void removeMesh(int cx, int cy, int cz) {
    ChunkMeshData mesh = meshCache.remove(packChunkKey(cx, cy, cz));
    if (mesh != null) {
      NativeBridge.nUnregisterChunkMesh(cx, cy, cz);
      NativeBridge.nDestroyBuffer(mesh.bufferHandle);
    }
  }
  public void clear() {
    for (ChunkMeshData mesh : meshCache.values()) {
      NativeBridge.nUnregisterChunkMesh(mesh.chunkX, mesh.chunkY, mesh.chunkZ);
      NativeBridge.nDestroyBuffer(mesh.bufferHandle);
    }
    meshCache.clear();
    if (globalIndexBufferHandle != 0) {
      NativeBridge.nDestroyBuffer(globalIndexBufferHandle);
      globalIndexBufferHandle = 0;
    }
  }
  public Collection<ChunkMeshData> getAllMeshes() {
    return meshCache.values();
  }
}
