package net.dewanteam.recordablelite;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RecordableMod implements ClientModInitializer {
    public static final String MOD_ID = "recordable";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        RecordableConfig.load();
        LOGGER.info("Cyber Capture loaded – press F9 to toggle recording ({} FPS)", RecordableConfig.get().fps);
    }

    public static void sendClientMessage(MinecraftClient client, String message, boolean actionBar) {
        if (client == null) client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal(message), actionBar);
        }
    }
}