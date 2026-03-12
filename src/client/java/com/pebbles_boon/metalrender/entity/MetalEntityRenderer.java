package com.pebbles_boon.metalrender.entity;

import com.pebbles_boon.metalrender.util.MetalLogger;

public class MetalEntityRenderer {

  private long deviceHandle;
  private long pipelineHandle;
  private boolean active;

  public MetalEntityRenderer() {}

  public void setDeviceAndPipeline(long device, long pipeline) {
    this.deviceHandle = device;
    this.pipelineHandle = pipeline;
  }

  public void setActive(boolean active) { this.active = active; }

  public boolean isActive() { return active; }

  public void renderCapturedEntities(long frameContext) {}

  public void shutdown() {
    active = false;
    deviceHandle = 0;
    pipelineHandle = 0;
  }
}
