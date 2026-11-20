package com.bushsmp.superpower;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Reads the configurable power and rank catalogue. */
public final class ConfigManager {
    private final JavaPlugin plugin;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public List<String> getPowerIds() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("superpowers");
        if (section == null) return List.of();
        return new ArrayList<>(section.getKeys(false)).stream().sorted().toList();
    }

    public boolean isPowerEnabled(String powerId) {
        return plugin.getConfig().getBoolean("superpowers." + powerId + ".enabled", false);
    }

    public String getPowerName(String powerId) {
        return plugin.getConfig().getString("superpowers." + powerId + ".name", titleCase(powerId));
    }

    public String getPowerDescription(String powerId) {
        return plugin.getConfig().getString("superpowers." + powerId + ".description", "");
    }

    public double getPowerPrice(String powerId) {
        return plugin.getConfig().getDouble("superpowers." + powerId + ".price", -1.0D);
    }

    public int getPowerDurationSeconds(String powerId) {
        return Math.max(1, plugin.getConfig().getInt("superpowers." + powerId + ".duration", 30));
    }

    public int getPowerCooldownSeconds(String powerId) {
        return Math.max(0, plugin.getConfig().getInt("cooldowns." + powerId,
                plugin.getConfig().getInt("cooldowns.default", 1800)));
    }

    public int getMagnetoHeight() {
        return Math.max(1, plugin.getConfig().getInt("superpowers.magneto.height", 5));
    }

    public int getFireyVisualCooldownSeconds() {
        return Math.max(1, plugin.getConfig().getInt("superpowers.firey.visual-cooldown", 5));
    }

    public int getFrozenRadius() {
        return Math.max(1, plugin.getConfig().getInt("superpowers.frozen.radius", 10));
    }

    public int getFrozenDurationSeconds() {
        return Math.max(1, plugin.getConfig().getInt("superpowers.frozen.freeze-duration", 3));
    }

    public int getAriseMobCount() {
        return Math.max(1, Math.min(50, plugin.getConfig().getInt("superpowers.arise.mob-count", 20)));
    }

    public int getAriseEnchantedCount() {
        return Math.max(0, Math.min(getAriseMobCount(), plugin.getConfig().getInt("superpowers.arise.enchanted-count", 7)));
    }

    public String getAriseArmorTier() {
        return plugin.getConfig().getString("superpowers.arise.armor-tier", "NETHERITE").toUpperCase(Locale.ROOT);
    }

    public int getFlashAmplifier() {
        return Math.max(0, plugin.getConfig().getInt("superpowers.flash.amplifier", 19));
    }

    public int getHulkJumpAmplifier() {
        return Math.max(0, plugin.getConfig().getInt("superpowers.hulk.jump-amplifier", 4));
    }

    public int getHulkStrengthAmplifier() {
        return Math.max(0, plugin.getConfig().getInt("superpowers.hulk.strength-amplifier", 1));
    }

    public List<String> getConfiguredRankIds() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("ranks");
        if (section == null) return List.of();
        return section.getKeys(false).stream()
                .sorted(Comparator.comparingInt(this::getRankOrder))
                .toList();
    }

    public List<String> getRankIds() {
        return getConfiguredRankIds().stream().filter(this::isRankEnabled).toList();
    }

    public boolean isRankEnabled(String rankId) {
        return plugin.getConfig().getBoolean("ranks." + rankId + ".enabled", false);
    }

    public String getRankName(String rankId) {
        return plugin.getConfig().getString("ranks." + rankId + ".name", titleCase(rankId));
    }

    public String getRankGroup(String rankId) {
        return plugin.getConfig().getString("ranks." + rankId + ".group", "").trim();
    }

    public double getRankPrice(String rankId) {
        return plugin.getConfig().getDouble("ranks." + rankId + ".price", -1.0D);
    }

    public int getRankOrder(String rankId) {
        return plugin.getConfig().getInt("ranks." + rankId + ".order", Integer.MAX_VALUE);
    }

    public int getRankDurationBonus(String rankId) {
        return Math.max(0, plugin.getConfig().getInt("ranks." + rankId + ".duration-bonus", 0));
    }

    public List<String> getRankBenefits(String rankId) {
        return plugin.getConfig().getStringList("ranks." + rankId + ".benefits");
    }

    public int getDurationBonusForRank(String rankId) {
        return rankId == null ? 0 : getRankDurationBonus(rankId);
    }

    public int getRankOrderForGroup(String groupName) {
        if (groupName == null || groupName.isBlank()) return -1;
        for (String rankId : getConfiguredRankIds()) {
            if (getRankGroup(rankId).equalsIgnoreCase(groupName)) return getRankOrder(rankId);
        }
        return -1;
    }

    private static String titleCase(String value) {
        if (value == null || value.isBlank()) return "Unknown";
        String[] words = value.replace('-', ' ').replace('_', ' ').split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) result.append(word.substring(1));
        }
        return result.toString();
    }
}
