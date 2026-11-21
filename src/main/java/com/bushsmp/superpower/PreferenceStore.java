package com.bushsmp.superpower;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stores explicit per-player auto-transfer overrides for this backend. */
final class PreferenceStore {
    private final JavaPlugin plugin;
    private final File file;
    private final boolean defaultEnabled;
    private final Map<UUID, Boolean> overrides = new ConcurrentHashMap<>();
    private final Object saveLock = new Object();
    private YamlConfiguration yaml;

    PreferenceStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.defaultEnabled = plugin.getConfig().getBoolean(
                "family-routing.preferences.default-enabled", true);

        String fileName = plugin.getConfig().getString(
                "family-routing.preferences.file", "autotransfer-preferences.yml");
        if (fileName == null || fileName.isBlank() || fileName.contains("..")
                || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException(
                    "family-routing.preferences.file must be a simple filename.");
        }

        this.file = new File(plugin.getDataFolder(), fileName);
        load();
    }

    boolean isEnabled(UUID playerId) {
        return overrides.getOrDefault(playerId, defaultEnabled);
    }

    void setEnabled(UUID playerId, boolean enabled) {
        synchronized (saveLock) {
            if (enabled == defaultEnabled) {
                overrides.remove(playerId);
                yaml.set(playerId.toString(), null);
            } else {
                overrides.put(playerId, enabled);
                yaml.set(playerId.toString(), enabled);
            }

            try {
                yaml.save(file);
            } catch (IOException exception) {
                load();
                throw new IllegalStateException(
                        "Could not save automatic-transfer preference.", exception);
            }
        }
    }

    boolean getDefaultEnabled() {
        return defaultEnabled;
    }

    private void load() {
        synchronized (saveLock) {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("Could not create plugin data directory: " + parent);
            }

            yaml = YamlConfiguration.loadConfiguration(file);
            overrides.clear();
            for (String key : yaml.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    Object value = yaml.get(key);
                    if (value instanceof Boolean enabled) {
                        overrides.put(uuid, enabled);
                    }
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Ignoring invalid auto-transfer preference UUID: " + key);
                }
            }
        }
    }
}
