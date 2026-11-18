package com.bushsmp.superpower;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class MessageManager {
    private final JavaPlugin plugin;
    private final ConfigManager config;

    public MessageManager(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public String getMessage(String key) {
        String raw = plugin.getConfig().getString("messages." + key, "");
        if (raw.isEmpty()) return "No message found for key: " + key;
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public String getPurchaseSuccessMessage(String superpower) {
        return getMessage("purchase-success")
                .replace("{superpower}", superpower);
    }

    public String getNoMoneyMessage(double price) {
        return getMessage("purchase-no-money")
                .replace("{price}", String.valueOf(price));
    }

    public String getAlreadyOwnedMessage() {
        return getMessage("already-owned");
    }

    public String getCooldownRemainingMessage(long seconds) {
        return getMessage("cooldown-remaining")
                .replace("{time}", String.valueOf(seconds));
    }

    public String getNoSuperpowerMessage() {
        return getMessage("no-superpower");
    }

    public String getDismissSuccessMessage() {
        return getMessage("dismiss-success");
    }

    public String getActivateSuccessMessage(String superpower) {
        return getMessage("activate-success")
                .replace("{superpower}", superpower);
    }

    public String translate(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
