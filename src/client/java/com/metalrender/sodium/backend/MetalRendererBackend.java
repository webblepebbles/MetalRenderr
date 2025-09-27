
package com.metalrender.sodium.backend;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.metalrender.nativebridge.MetalBackend;
import com.metalrender.nativebridge.NativeMemory;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.Box;
import org.joml.Matrix4f;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Set;
import java.util.HashSet;

@Environment(EnvType.CLIENT)
public final class MetalRendererBackend {
    

    private final MinecraftClient client;
    private long handle = 0L;
    private boolean initialized = false;
    private long startNanos = 0L;
    

    private final Set<BlockPos> meshedChunks = new HashSet<>();

    public MetalRendererBackend(MinecraftClient client) {
        this.client = client;
        MetalLogger.info("MetalRendererBackend constructor, client=" + client);
    }

    public MetalRendererBackend() {
        this.client = null;
        MetalLogger.info("MetalRendererBackend default constructor, client=null");

    }

    public boolean initIfNeeded() {
        if (initialized) {
            MetalLogger.info("MetalRendererBackend already initialized");
            return true;
        }
        MetalLogger.info("MetalRendererBackend initializing");
        try {
            long ctx = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
            MetalLogger.info("GLFW context=" + ctx);
            if (ctx == 0L) {
                MetalLogger.error("GLFW context is 0L");
                return false;
            }
            long nsWindow = org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(ctx);
            MetalLogger.info("nsWindow=" + nsWindow);
            boolean srgb = true;
            handle = MetalBackend.init(nsWindow, srgb);
            MetalLogger.info("MetalBackend.init returned handle=" + handle);
            if (handle == 0L) {
                MetalLogger.error("MetalBackend.init returned 0L");
                return false;
            }
            startNanos = System.nanoTime();
            batchUploadVisibleChunkMeshes();
            initialized = true;
            MetalLogger.info("MetalRendererBackend initialized");
            return true;
        } catch (Throwable t) {
            MetalLogger.error("Exception during MetalRendererBackend initialization: " + t);
            initialized = false;
            return false;
        }
    }


    private void batchUploadVisibleChunkMeshes() {
        if (client.world == null || client.player == null) return;
        Camera camera = client.gameRenderer.getCamera();
        ClientWorld world = client.world;
        BlockPos playerPos = client.player.getBlockPos();
        int chunkRadius = 6;
        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                int chunkX = (playerPos.getX() >> 4) + cx;
                int chunkZ = (playerPos.getZ() >> 4) + cz;
                BlockPos chunkPos = new BlockPos(chunkX, 0, chunkZ);
                if (!isChunkInView(chunkPos, camera)) continue;
                WorldChunk chunk = world.getChunk(chunkX, chunkZ);
                if (chunk != null && !meshedChunks.contains(chunkPos)) {
                    uploadChunkMesh(chunk, chunkPos);
                    meshedChunks.add(chunkPos);
                }
            }
        }
    }

    private boolean isChunkInView(BlockPos chunkPos, Camera camera) {
        double camX = camera.getPos().x;
        double camZ = camera.getPos().z;
        double chunkCenterX = (chunkPos.getX() << 4) + 8;
        double chunkCenterZ = (chunkPos.getZ() << 4) + 8;
        double dx = camX - chunkCenterX;
        double dz = camZ - chunkCenterZ;
        return (dx * dx + dz * dz) < (128 * 128);
    }


    private void uploadChunkMesh(WorldChunk chunk, BlockPos chunkPos) {
    int minY = chunk.getBottomY();
    int maxY = client.world.getHeight();
        java.util.List<Short> vertices = new java.util.ArrayList<>();
        java.util.List<Integer> colors = new java.util.ArrayList<>();
        int stride = 3 * 2 + 4; 
        for (int x = 0; x < 16; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos pos = new BlockPos(chunk.getPos().getStartX() + x, y, chunk.getPos().getStartZ() + z);
                    BlockState state = chunk.getBlockState(pos);
                    if (!state.isAir() && !isBlockSurrounded(chunk, pos)) {
                        for (Direction dir : Direction.values()) {
                            BlockPos adj = pos.offset(dir);
                            BlockState adjState = chunk.getBlockState(adj);
                            if (adjState.isAir()) {
                                short bx = (short) pos.getX();
                                short by = (short) pos.getY();
                                short bz = (short) pos.getZ();
                                short[][] faceVerts = getFaceVerticesShort(bx, by, bz, dir);
                                int packedColor = packColor(by);
                                for (short[] v : faceVerts) {
                                    vertices.add(v[0]);
                                    vertices.add(v[1]);
                                    vertices.add(v[2]);
                                    colors.add(packedColor);
                                }
                            }
                        }
                    }
                }
            }
        }
        int vertexCount = vertices.size() / 3;
        ByteBuffer buf = NativeMemory.alloc(vertexCount * stride);
        ShortBuffer sbuf = buf.asShortBuffer();
        int colorOffset = vertexCount * 3;
        for (int i = 0; i < vertexCount; i++) {
            sbuf.put(vertices.get(i * 3));
            sbuf.put(vertices.get(i * 3 + 1));
            sbuf.put(vertices.get(i * 3 + 2));
        }
        buf.position(colorOffset * 2);
        for (int i = 0; i < vertexCount; i++) {
            buf.putInt(colors.get(i));
        }
        buf.rewind();
    
        com.metalrender.sodium.backend.MeshShaderBackend mesh = com.metalrender.MetalRenderClient.getMeshBackend();
        if (mesh != null) {
            try {
        
                if (mesh.initIfNeeded() && mesh.isMeshEnabled()) {
                    mesh.uploadChunkMeshAsync(chunkPos, buf, vertexCount, stride, null, 0, 0);
                    meshedChunks.add(chunkPos);
                    return;
                }
            } catch (Throwable t) {
            }
        }
        MetalBackend.uploadStaticMesh(handle, buf, vertexCount, stride);
        meshedChunks.add(chunkPos);
    }

    private boolean isBlockSurrounded(WorldChunk chunk, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos adj = pos.offset(dir);
            if (chunk.getBlockState(adj).isAir()) return false;
        }
        return true;
    }
    private short[][] getFaceVerticesShort(short bx, short by, short bz, Direction dir) {
        short s = 1;
        switch (dir) {
            case UP:
                return new short[][] {
                    {(short)(bx-s), (short)(by+s), (short)(bz-s)}, {(short)(bx+s), (short)(by+s), (short)(bz-s)}, {(short)(bx+s), (short)(by+s), (short)(bz+s)},
                    {(short)(bx-s), (short)(by+s), (short)(bz-s)}, {(short)(bx+s), (short)(by+s), (short)(bz+s)}, {(short)(bx-s), (short)(by+s), (short)(bz+s)}
                };
            case DOWN:
                return new short[][] {
                    {(short)(bx-s), (short)(by-s), (short)(bz-s)}, {(short)(bx+s), (short)(by-s), (short)(bz+s)}, {(short)(bx+s), (short)(by-s), (short)(bz-s)},
                    {(short)(bx-s), (short)(by-s), (short)(bz-s)}, {(short)(bx-s), (short)(by-s), (short)(bz+s)}, {(short)(bx+s), (short)(by-s), (short)(bz+s)}
                };
            case NORTH:
                return new short[][] {
                    {(short)(bx-s), (short)(by-s), (short)(bz-s)}, {(short)(bx+s), (short)(by+s), (short)(bz-s)}, {(short)(bx+s), (short)(by-s), (short)(bz-s)},
                    {(short)(bx-s), (short)(by-s), (short)(bz-s)}, {(short)(bx-s), (short)(by+s), (short)(bz-s)}, {(short)(bx+s), (short)(by+s), (short)(bz-s)}
                };
            case SOUTH:
                return new short[][] {
                    {(short)(bx-s), (short)(by-s), (short)(bz+s)}, {(short)(bx+s), (short)(by-s), (short)(bz+s)}, {(short)(bx+s), (short)(by+s), (short)(bz+s)},
                    {(short)(bx-s), (short)(by-s), (short)(bz+s)}, {(short)(bx+s), (short)(by+s), (short)(bz+s)}, {(short)(bx-s), (short)(by+s), (short)(bz+s)}
                };
            case WEST:
                return new short[][] {
                    {(short)(bx-s), (short)(by-s), (short)(bz-s)}, {(short)(bx-s), (short)(by-s), (short)(bz+s)}, {(short)(bx-s), (short)(by+s), (short)(bz+s)},
                    {(short)(bx-s), (short)(by-s), (short)(bz-s)}, {(short)(bx-s), (short)(by+s), (short)(bz+s)}, {(short)(bx-s), (short)(by+s), (short)(bz-s)}
                };
            case EAST:
                return new short[][] {
                    {(short)(bx+s), (short)(by-s), (short)(bz-s)}, {(short)(bx+s), (short)(by+s), (short)(bz+s)}, {(short)(bx+s), (short)(by-s), (short)(bz+s)},
                    {(short)(bx+s), (short)(by-s), (short)(bz-s)}, {(short)(bx+s), (short)(by+s), (short)(bz-s)}, {(short)(bx+s), (short)(by+s), (short)(bz+s)}
                };
            default:
                return new short[0][0];
        }
    }

    private int packColor(int y) {
        int c = Math.max(32, Math.min(255, y));
        return (0xFF << 24) | (c << 16) | (c << 8) | c;
    }

    public void resizeIfNeeded() {
        if (!initialized) return;
        Window w = client.getWindow();
        MetalBackend.resize(handle, w.getFramebufferWidth(), w.getFramebufferHeight());
    }
    public void updateChunkMesh(WorldChunk chunk) {
        BlockPos chunkPos = new BlockPos(chunk.getPos().x, 0, chunk.getPos().z);
        uploadChunkMesh(chunk, chunkPos);
        meshedChunks.add(chunkPos);
    }


    public void removeChunkMesh(WorldChunk chunk) {
        BlockPos chunkPos = new BlockPos(chunk.getPos().x, 0, chunk.getPos().z);
        meshedChunks.remove(chunkPos);
        com.metalrender.sodium.backend.MeshShaderBackend mesh = com.metalrender.MetalRenderClient.getMeshBackend();
        if (mesh != null) {
            try { mesh.removeChunkMesh(chunkPos); } catch (Throwable t) { }
            return;
        }
    }

    public void sendCamera(float fovDegrees) {
        if (!initialized) return;
        Window w = client.getWindow();
        float width = w.getFramebufferWidth();
        float height = w.getFramebufferHeight();
        float aspect = height == 0 ? 1f : width / height;
        float f = (float)(1.0 / Math.tan(Math.toRadians(fovDegrees) * 0.5));
        float zNear = 0.05f;
        float zFar = 1000f;
        float[] proj = new float[] {
                f/aspect,0,0,0,
                0,f,0,0,
                0,0,(zFar+zNear)/(zNear-zFar),-1,
                0,0,(2*zFar*zNear)/(zNear-zFar),0
        };
        float[] view = new float[] {
                1,0,0,0,
                0,1,0,0,
                0,0,1,0,
                0,0,-3,1
        };
        float[] m = new float[16];
        for (int r=0;r<4;r++) for (int c=0;c<4;c++) {
            m[r*4+c]=view[r*4+0]*proj[0*4+c]+view[r*4+1]*proj[1*4+c]+view[r*4+2]*proj[2*4+c]+view[r*4+3]*proj[3*4+c];
        }
        MetalBackend.setCamera(handle, m);
    }

    public void onSetupTerrain(float fovDegrees) {
        if (!initIfNeeded()) return;
        resizeIfNeeded();
        sendCamera(fovDegrees);
        float t = (System.nanoTime() - startNanos) / 1_000_000_000f;
        MetalBackend.render(handle, t);
    }

    public boolean drawChunkLayerSodiumOverride(int layerId) {
        if (!initialized) return false;
        MetalBackend.render(handle, (System.nanoTime() - startNanos) / 1_000_000_000f);
        return true;
    }

    public void destroy() {
        if (!initialized) return;
        com.metalrender.sodium.backend.MeshShaderBackend mesh = com.metalrender.MetalRenderClient.getMeshBackend();
        if (mesh != null) {
            try { mesh.destroy(); } catch (Throwable t) { }
        }
        meshedChunks.clear();
        MetalBackend.destroy(handle);
        handle = 0L;
        initialized = false;
    }
}