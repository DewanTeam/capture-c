package net.dewanteam.recordablelite.mixin;

import net.dewanteam.recordablelite.RecordingManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    private static boolean f9WasDown = false;

    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderEnd(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        RecordingManager.getInstance().onFrame();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) return;

        long window = client.getWindow().getHandle();
        boolean f9Down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F9) == GLFW.GLFW_PRESS;

        if (f9Down && !f9WasDown) {
            RecordingManager.getInstance().toggleRecording(client);
        }
        f9WasDown = f9Down;
    }
}