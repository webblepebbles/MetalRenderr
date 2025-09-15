package com.metalrender.nativebridge;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

@Environment(EnvType.CLIENT)
public final class MetalHardwareChecker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile Boolean compatible = null;
    private static volatile boolean checkScheduled = false;
    private static volatile String lastFailureReason = "";

    static {
        ClientTickEvents.END_CLIENT_TICK.register(client -> scheduleCheck());
    }

    public static boolean isCompatible() {
        scheduleCheck();
        return compatible == null || compatible;
    }

    public static void showIncompatibleScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.execute(() -> client.setScreen(new IncompatibleHardwareScreen(lastFailureReason)));
        }
    }

    private static void scheduleCheck() {
        if (compatible != null || checkScheduled) return;
        checkScheduled = true;
        try {
            MinecraftClient.getInstance().execute(() -> {
                try {
                    long ctx = GLFW.glfwGetCurrentContext();
                    if (ctx == 0L) {
                        checkScheduled = false;
                        return;
                    }
                    String vendor = GL11.glGetString(GL11.GL_VENDOR);
                    boolean isApple = vendor != null && vendor.toLowerCase().contains("apple");
                    compatible = isApple;
                    if (!isApple) {
                        lastFailureReason = "GPU vendor is not Apple (GL_VENDOR=\"" + vendor + "\")";
                        LOGGER.warn("[MetalRender] Hardware incompatible: {}", lastFailureReason);
                        MinecraftClient.getInstance().execute(MetalHardwareChecker::showIncompatibleScreen);
                    } else {
                        LOGGER.info("[MetalRender] Hardware check passed (vendor={})", vendor);
                    }
                } catch (Throwable t) {
                    compatible = true;
                    LOGGER.warn("[MetalRender] Hardware check failed unexpectedly, allowing safe run", t);
                } finally {
                    checkScheduled = false;
                }
            });
        } catch (Throwable t) {
            compatible = true;
            LOGGER.warn("[MetalRender] Could not schedule GL capability check, allowing fallback", t);
        }
    }

    private static final class IncompatibleHardwareScreen extends Screen {
        private final String reason;

        protected IncompatibleHardwareScreen(String reason) {
            super(Text.literal("MetalRender disabled"));
            this.reason = reason == null || reason.isEmpty() ? "Unknown reason" : reason;
        }

        @Override
        protected void init() {
            this.addDrawableChild(
                    ButtonWidget.builder(Text.literal("Back to Title"), b ->
                                    MinecraftClient.getInstance().setScreen(new TitleScreen()))
                            .dimensions(this.width / 2 - 100, this.height / 2 + 20, 200, 20).build()
            );
        }
        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            this.renderBackground(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    "MetalRender has been disabled", this.width / 2, this.height / 2 - 24, 0xFF5555);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    "Reason: " + this.reason, this.width / 2, this.height / 2, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
        }
    }
}