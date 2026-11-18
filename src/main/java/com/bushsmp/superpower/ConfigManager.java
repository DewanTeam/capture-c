package com.bushsmp.superpower;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class ConfigManager {
    private final JavaPlugin plugin;
    private File configFile;
    private YamlConfiguration config;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
    }

    public void loadConfig() {
        if (!configFile.exists()) {
            plugin.saveDefaultConfig();
        }
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    public void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save config: " + e.getMessage());
        }
    }

    public boolean isSuperpowerEnabled(String superpower) {
        return config.getBoolean("superpowers." + superpower + ".enabled", true);
    }

    public int getSuperpowerPrice(String superpower) {
        return config.getInt("superpowers." + superpower + ".price", 0);
    }

    public String getSuperpowerDescription(String superpower) {
        return config.getString("superpowers." + superpower + ".description", "");
    }

    public int getFrozenRadius() {
        return config.getInt("superpowers.frozen.radius", 10);
    }

    public int getFrozenDuration() {
        return config.getInt("superpowers.frozen.duration", 3);
    }

    public int getAriseMobCount() {
        return config.getInt("superpowers.arise.mob-count", 20);
    }

    public int getAriseEnchantedCount() {
        return config.getInt("superpowers.arise.enchanted-count", 7);
    }

    public String getArmorTier() {
        return config.getString("superpowers.arise.armor-tier", "NETHERITE");
    }

    public int getFlashDuration() {
        return config.getInt("superpowers.flash.duration", 30);
    }

    public int getHulkDuration() {
        return config.getInt("superpowers.hulk.duration", 30);
    }

    public long getCooldownDuration(String superpower) {
        String path = "cooldowns." + superpower;
        if (config.contains(path)) {
            return config.getLong(path, 1800);
        }
        return 1800;
    }

    public long getFireballCooldown() {
        return config.getLong("cooldowns.fireball", 5);
    }
}
