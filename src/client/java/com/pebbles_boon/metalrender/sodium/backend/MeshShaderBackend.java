package com.pebbles_boon.metalrender.sodium.backend;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.nativebridge.MeshShaderNative;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;

public class MeshShaderBackend {

  private long meshPipelineHandle;
  private boolean active;
  private boolean meshShadersAvailable;

  public void initialize() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;

    meshShadersAvailable = MetalHardwareChecker.supportsMeshShaders();
    if (meshShadersAvailable) {
      long device = renderer.getBackend().getDeviceHandle();
      long library = renderer.getBackend().getShaderLibraryHandle();
      if (library != 0) {
        meshPipelineHandle = MeshShaderNative.createMeshPipeline(
            device, library, "object_main", "mesh_main", "fragment_main");
      }
    }

    active = true;
    MetalLogger.info("Mesh shader backend initialized (mesh shaders: " +
                     (meshShadersAvailable && meshPipelineHandle != 0
                          ? "active"
                          : "fallback") +
                     ")");
  }

  public void drawChunkMesh(long frameContext, long argumentBuffer,
                            int objectThreadgroups, int meshThreadsPerGroup) {
    if (!active || frameContext == 0)
      return;

    if (meshPipelineHandle != 0) {
      MeshShaderNative.drawMeshThreadgroups(
          frameContext, meshPipelineHandle, objectThreadgroups,
          meshThreadsPerGroup, argumentBuffer);
    }
  }

  public void shutdown() {
    if (meshPipelineHandle != 0) {
      MeshShaderNative.destroyMeshPipeline(meshPipelineHandle);
      meshPipelineHandle = 0;
    }
    active = false;
  }

  public boolean isActive() { return active; }

  public boolean areMeshShadersAvailable() { return meshShadersAvailable; }
}
