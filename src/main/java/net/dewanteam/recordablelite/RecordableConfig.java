package net.dewanteam.recordablelite;

import net.fabricmc.loader.api.FabricLoader;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public final class RecordableConfig {
    private static final RecordableConfig INSTANCE = new RecordableConfig();
    private static final String FILE = "recordable-lite.properties";

    public boolean enabled = true;
    public int fps = 3;
    public String outputDirectory = "captures";

    private RecordableConfig() {}

    public static RecordableConfig get() { return INSTANCE; }

    public static RecordableConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE);
        Properties p = new Properties();

        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            } catch (IOException e) {
                RecordableMod.LOGGER.warn("Could not read {}: {}", path, e.toString());
            }
        }

        INSTANCE.fps = parseInt(p.getProperty("fps"), 3);
        INSTANCE.fps = Math.max(1, Math.min(30, INSTANCE.fps));
        INSTANCE.outputDirectory = p.getProperty("outputDirectory", "captures").trim();
        if (INSTANCE.outputDirectory.isBlank()) INSTANCE.outputDirectory = "captures";

        save();
        return INSTANCE;
    }

    public static void save() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE);
        Properties p = new Properties();
        p.setProperty("fps", Integer.toString(INSTANCE.fps));
        p.setProperty("outputDirectory", INSTANCE.outputDirectory);

        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                p.store(out, "Cyber Capture – minimal recorder configuration");
            }
        } catch (IOException e) {
            RecordableMod.LOGGER.warn("Could not save {}: {}", path, e.toString());
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}