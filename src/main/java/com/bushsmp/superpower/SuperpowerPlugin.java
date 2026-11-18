package com.bushsmp.superpower;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class SuperpowerPlugin extends JavaPlugin implements Listener {

    private Economy economy;
    private Map<String, String> playerSuperpowers = new ConcurrentHashMap<>();
    private Map<String, Long> playerCooldowns = new ConcurrentHashMap<>();
    private ConfigManager configManager;
    private MessageManager messageManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        messageManager = new MessageManager(this, configManager);

        if (!setupEconomy()) {
            getLogger().severe("Vault economy not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        configManager.loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("SuperpowerPlugin enabled!");
    }

    @Override
    public void onDisable() {
        savePlayerData();
        getLogger().info("SuperpowerPlugin disabled!");
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager()
                .getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
        }
        return economy != null;
    }

    public Economy getEconomy() {
        return economy;
    }

    public boolean hasPlayerSuperpower(String playerName) {
        return playerSuperpowers.containsKey(playerName);
    }

    public String getPlayerSuperpower(String playerName) {
        return playerSuperpowers.getOrDefault(playerName, null);
    }

    public boolean hasPlayerCooldown(String playerName) {
        return playerCooldowns.containsKey(playerName);
    }

    public long getRemainingCooldown(String playerName) {
        if (!playerCooldowns.containsKey(playerName)) return 0;
        long endTime = playerCooldowns.get(playerName);
        long remaining = endTime - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    public void setPlayerSuperpower(String playerName, String superpower) {
        playerSuperpowers.put(playerName, superpower);
        savePlayerData();
    }

    public void setPlayerCooldown(String playerName, long cooldownEndTime) {
        playerCooldowns.put(playerName, cooldownEndTime);
        savePlayerData();
    }

    public void clearPlayerSuperpower(String playerName) {
        playerSuperpowers.remove(playerName);
        savePlayerData();
    }

    public void savePlayerData() {
        // Save player data to players.yml
        File dataFile = new File(getDataFolder(), "players.yml");
        getLogger().info("Saving player data...");
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (item == null || item.getType() == Material.AIR) return;

        if (!item.getType().equals(Material.DIAMOND)) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        String displayName = meta.getDisplayName();
        if (displayName == null) return;

        String cleanName = stripColorCodes(displayName);
        if (!cleanName.equalsIgnoreCase("Superpower")) return;

        if (!hasPlayerSuperpower(player.getName())) {
            player.sendMessage(messageManager.getMessage("no-superpower"));
            event.setCancelled(true);
            return;
        }

        if (hasPlayerCooldown(player.getName())) {
            long remaining = getRemainingCooldown(player.getName());
            if (remaining > 0) {
                long seconds = TimeUnit.MILLISECONDS.toSeconds(remaining);
                player.sendMessage(messageManager.getMessage("cooldown-remaining")
                        .replace("{time}", String.valueOf(seconds)));
                event.setCancelled(true);
                return;
            }
        }

        activateSuperpower(player);
        event.setCancelled(true);
    }

    private void activateSuperpower(Player player) {
        String superpower = getPlayerSuperpower(player.getName());

        long cooldownDuration = configManager.getCooldownDuration(superpower);
        long cooldownEndTime = System.currentTimeMillis() + cooldownDuration * 1000;
        setPlayerCooldown(player.getName(), cooldownEndTime);

        superpowerManager(player, superpower);

        player.sendMessage(messageManager.getMessage("activate-success")
                .replace("{superpower}", superpower));
    }

    private void superpowerManager(Player player, String superpower) {
        switch (superpower) {
            case "Magneto":
                activateMagneto(player);
                break;
            case "Firey":
                activateFirey(player);
                break;
            case "Frozen":
                activateFrozen(player);
                break;
            case "Arise":
                activateArise(player);
                break;
            case "Flash":
                activateFlash(player);
                break;
            case "Hulk":
                activateHulk(player);
                break;
        }
    }

    private void activateMagneto(Player player) {
        player.sendMessage("Magneto activated! Hovering 5 blocks above ground for 30 seconds.");
    }

    private void activateFirey(Player player) {
        player.sendMessage("Firey activated! Right-click to shoot fireballs (5s cooldown).");
    }

    private void activateFrozen(Player player) {
        int radius = configManager.getFrozenRadius();
        player.sendMessage("Frozen activated! Freezing players within " + radius + " blocks for 3 seconds.");
    }

    private void activateArise(Player player) {
        int mobCount = configManager.getAriseMobCount();
        int enchantedCount = configManager.getAriseEnchantedCount();
        player.sendMessage("Arise activated! Summoning " + mobCount + " undead mobs with " + enchantedCount + " enchanted.");
    }

    private void activateFlash(Player player) {
        player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SPEED,
                600,
                19));
        player.sendMessage("Flash activated! Speed amplifier 19 for 30 seconds.");
    }

    private void activateHulk(Player player) {
        player.sendMessage("Hulk activated! Jump Boost V + Strength II for 30 seconds.");
    }

    public boolean hasEnoughMoney(Player player, double amount) {
        return economy != null && economy.getBalance(player) >= amount;
    }

    public void withdrawMoney(Player player, double amount) {
        if (economy != null) {
            economy.withdrawPlayer(player, amount);
        }
    }

    private String stripColorCodes(String text) {
        return text.replaceAll("&[0-9a-fk-or]", "").replaceAll("§[0-9a-fk-or]", "");
    }
}
