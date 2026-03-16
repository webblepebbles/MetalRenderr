package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import java.util.Map;
import java.util.Queue;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.particle.ParticleRenderer;
import net.minecraft.client.particle.ParticleTextureSheet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;


@Mixin(ParticleManager.class)
public interface ParticleManagerAccessor {

  @Accessor("particles")
  Map<ParticleTextureSheet, ParticleRenderer<?>> metalrender$getParticles();
}
