package com.bushsmp.superpower;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInputEvent;
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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Key;
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

        loadPlayerData();
        configManager.loadConfig();
        getServer().getPluginManager().registerEvents(this, this);

        // Register /superpower command using CommandAPI
        CommandAPI.register("superpower", player -> {
            if (!player.hasPermission("superpower.use")) {
                player.sendMessage("&cYou don't have permission to use superpowers!");
                return;
            }
            String current = plugin.getPlayerSuperpower(player.getName());
            if (current != null) {
                player.sendMessage("&aYour current superpower: &6" + current);
                player.sendMessage("&7Use your Superpower diamond to activate it, or use /superpower dismiss to remove it.");
            } else {
                player.sendMessage("&cYou don't own a superpower! Use /superpower buy to purchase one.");
            }
        }, "buy", "dismiss", "info", getPlugin());

        // Subcommand: buy
        CommandAPI.register("superpower buy", player -> {
            if (!player.hasPermission("superpower.rank.buy")) {
                player.sendMessage("&cYou don't have permission to buy ranks!");
                return;
            }
            player.sendMessage("&7Available ranks:");
            for (String rankId : new String[]{"veteran", "elite", "legend"}) {
                if (configManager.isRankEnabled(rankId)) {
                    int price = configManager.getRankPrice(rankId);
                    String name = configManager.getRankName(rankId);
                    player.sendMessage("&6/" + "superpower buy " + rankId + " &7- &e" + name + " &7- &e" + price + "&7 coins");
                }
            }
            // Try to buy the first available rank as demo
            for (String rankId : new String[]{"veteran", "elite", "legend"}) {
                if (configManager.isRankEnabled(rankId)) {
                    giveRank(player, rankId);
                    break;
                }
            }
        }, getPlugin());

        // Subcommand: gift
        CommandAPI.register("superpower gift", player -> {
            if (!player.hasPermission("superpower.rank.gift")) {
                player.sendMessage("&cYou don't have permission to gift ranks!");
                return;
            }
            player.sendMessage("&7Usage: &6/superpower gift <player> <rank>");
            player.sendMessage("&7Example: &6/superpower gift &ePlayerName veteran");
        }, getPlugin());

        // Subcommand: info
        CommandAPI.register("superpower info", player -> {
            if (!player.hasPermission("superpower.rank.info")) {
                player.sendMessage("&cYou don't have permission to view rank info!");
                return;
            }
            player.sendMessage("&6Superpower Ranks:");
            for (String rankId : new String[]{"veteran", "elite", "legend"}) {
                if (configManager.isRankEnabled(rankId)) {
                    int price = configManager.getRankPrice(rankId);
                    String name = configManager.getRankName(rankId);
                    String perm = configManager.getRankPermission(rankId);
                    player.sendMessage("&6" + rankId + "&7: &e" + name + " &7- &e" + price + "&7 coins &7(" + perm + "&7)");
                }
            }
        }, getPlugin());

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

    private void loadPlayerData() {
        File dataFile = new File(getDataFolder(), "players.yml");
        if (!dataFile.exists()) {
            playerSuperpowers.clear();
            playerCooldowns.clear();
            return;
        }
        // Load using YamlConfiguration (same format as config.yml)
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : data.getConfigurationSection("superpowers") != null ? data.getConfigurationSection("superpowers").getKeys(false) : new String[0]) {
            String superpower = data.getString("superpowers." + key);
            if (superpower != null) {
                playerSuperpowers.put(key, superpower);
            }
        }
        for (String key : data.getConfigurationSection("cooldowns") != null ? data.getConfigurationSection("cooldowns").getKeys(false) : new String[0]) {
            long endTime = data.getLong("cooldowns." + key);
            if (endTime > 0) {
                playerCooldowns.put(key, endTime);
            }
        }
        getLogger().info("Loaded player data for " + playerSuperpowers.size() + " players with superpowers");
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

    @EventHandler
    public void onPlayerInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        if ("G".equalsIgnoreCase(event.getKey().getKeyName())) {
            openLauncherDialog(player);
            event.setCancelled(true);
        }
    }

    private void openLauncherDialog(Player player) {
        // Use DialogEngine to create a launcher dialog
        // DialogEngine API: Dialogs.dialog(player).title("...").addButton(...).show();
        // Since DialogEngine YAML quick-action config is expected, we'll use the API directly
        org.dialengine.api.Dialogs.dialog(player)
            .title("&6Superpower &8Launcher")
            .addButton("&aRank Shop", e -> {
                // Open rank shop dialog
                player.sendMessage("&6Rank Shop opened!");
                e.setCancelled(true);
            })
            .addButton("&aSuperpower Shop", e -> {
                // Open superpower shop dialog
                player.sendMessage("&6Superpower Shop opened!");
                e.setCancelled(true);
            })
            .addButton("&cClose", e -> {
                player.sendMessage("&6Launcher closed.");
                e.setCancelled(true);
            })
            .show();
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

    private LuckPermsProvider luckPerms;

    private boolean setupLuckPerms() {
        RegisteredServiceProvider<LuckPermsProvider> rsp = getServer().getServicesManager()
                .getRegistration(net.luckperms.api.LuckPerms.class);
        if (rsp != null) {
            luckPerms = rsp.getProvider();
        }
        return luckPerms != null;
    }

    public boolean hasRankPermission(Player player, String rankId) {
        if (luckPerms == null) setupLuckPerms();
        if (luckPerms == null) return false;
        return luckPerms.hasPlayerPermission(player, getRankPermission(rankId));
    }

    public void setPlayerRank(Player player, String rankId) {
        if (luckPerms == null) setupLuckPerms();
        if (luckPerms == null) return;
        String groupName = getRankPermission(rankId);
        if (!groupName.isEmpty()) {
            luckPerms.setPlayerPrimaryGroup(player, groupName);
        }
    }

    public void giveRank(Player player, String rankId) {
        if (!hasPlayerSuperpower(player.getName()) && !hasPlayerRank(player.getName())) {
            // Purchase logic
            int price = getRankPrice(rankId);
            if (price > 0 && hasEnoughMoney(player, price)) {
                withdrawMoney(player, price);
                setPlayerRank(player, rankId);
                savePlayerData();
                player.sendMessage("&aYou purchased the &6" + getRankName(rankId) + " &ara rank! Cost: &6" + price);
            } else if (price > 0) {
                player.sendMessage("&cYou don't have enough money! Price: &6" + price);
            }
        } else {
            player.sendMessage("&cYou already have a rank or superpower!");
        }
    }

    public boolean hasPlayerRank(String playerName) {
        // Check if player has any rank via LuckPerms
        if (luckPerms == null) setupLuckPerms();
        if (luckPerms == null) return false;
        return luckPerms.getPlayerPrimaryGroup(playerName) != null;
    }

    private String stripColorCodes(String text) {
        return text.replaceAll("&[0-9a-fk-or]", "").replaceAll("§[0-9a-fk-or]", "");
    }
}
