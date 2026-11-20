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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.YamlConfiguration;

public class SuperpowerPlugin extends JavaPlugin implements Listener {

    private Economy economy;
    private Map<UUID, String> playerSuperpowers = new ConcurrentHashMap<>();
    private Map<UUID, Long> playerCooldowns = new ConcurrentHashMap<>();
    private Map<UUID, String> playerRanks = new ConcurrentHashMap<>();
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
            UUID uuid = player.getUniqueId();
            String current = playerSuperpowers.get(uuid);
            if (current != null) {
                player.sendMessage("&aYour current superpower: &6" + current);
                player.sendMessage("&7Use your Superpower diamond to activate it, or use /superpower dismiss to remove it.");
            } else {
                player.sendMessage("&cYou don't own a superpower! Use /superpower buy to purchase one.");
            }
        }, "buy", "dismiss", "info", this);

        // Subcommand: buy - list ranks without purchasing automatically
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
                    player.sendMessage("&6/superpower buy " + rankId + " &7- &e" + name + " &7- &e" + price + "&7 coins");
                }
            }
            player.sendMessage("&7Use &6/superpower buy &7<rank> &7to purchase a rank explicitly.");
        }, this);

        // Subcommand: gift
        CommandAPI.register("superpower gift", player -> {
            if (!player.hasPermission("superpower.rank.gift")) {
                player.sendMessage("&cYou don't have permission to gift ranks!");
                return;
            }
            player.sendMessage("&7Usage: &6/superpower gift <player> <rank>");
            player.sendMessage("&7Example: &6/superpower gift &ePlayerName veteran");
        }, this);

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
        }, this);

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

    public boolean hasPlayerSuperpower(UUID uuid) {
        return playerSuperpowers.containsKey(uuid);
    }

    public String getPlayerSuperpower(UUID uuid) {
        return playerSuperpowers.get(uuid);
    }

    public boolean hasPlayerCooldown(UUID uuid) {
        return playerCooldowns.containsKey(uuid);
    }

    public long getRemainingCooldown(UUID uuid) {
        if (!playerCooldowns.containsKey(uuid)) return 0;
        long endTime = playerCooldowns.get(uuid);
        long remaining = endTime - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    public void setPlayerSuperpower(UUID uuid, String superpower) {
        playerSuperpowers.put(uuid, superpower);
        savePlayerData();
    }

    public void setPlayerCooldown(UUID uuid, long cooldownEndTime) {
        playerCooldowns.put(uuid, cooldownEndTime);
        savePlayerData();
    }

    public void clearPlayerSuperpower(UUID uuid) {
        playerSuperpowers.remove(uuid);
        savePlayerData();
    }

    public void savePlayerData() {
        File dataFile = new File(getDataFolder(), "players.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Failed to create players.yml");
                return;
            }
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);

        // Save superpowers using UUID strings as keys
        Map<String, String> superpowers = new HashMap<>();
        for (Map.Entry<UUID, String> entry : playerSuperpowers.entrySet()) {
            superpowers.put(entry.getKey().toString(), entry.getValue());
        }
        data.set("superpowers", superpowers);

        // Save cooldowns using UUID strings as keys
        Map<String, Long> cooldowns = new HashMap<>();
        for (Map.Entry<UUID, Long> entry : playerCooldowns.entrySet()) {
            cooldowns.put(entry.getKey().toString(), entry.getValue());
        }
        data.set("cooldowns", cooldowns);

        // Save ranks using UUID strings as keys
        Map<String, String> ranks = new HashMap<>();
        for (Map.Entry<UUID, String> entry : playerRanks.entrySet()) {
            ranks.put(entry.getKey().toString(), entry.getValue());
        }
        data.set("ranks", ranks);

        try {
            data.save(dataFile);
            getLogger().info("Saved player data for " + playerSuperpowers.size() + " players");
        } catch (IOException e) {
            getLogger().severe("Failed to save player data: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private boolean loadPlayerData() {
        File dataFile = new File(getDataFolder(), "players.yml");
        if (!dataFile.exists()) {
            playerSuperpowers.clear();
            playerCooldowns.clear();
            playerRanks.clear();
            return false;
        }

        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);

        // Load superpowers
        Map<String, String> loadedSuperpowers = (Map<String, String>) data.get("superpowers");
        if (loadedSuperpowers != null) {
            for (Map.Entry<String, String> entry : loadedSuperpowers.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(entry.getKey());
                    playerSuperpowers.put(uuid, entry.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Skip invalid UUIDs
                }
            }
        }

        // Load cooldowns
        Map<String, Long> loadedCooldowns = (Map<String, Long>) data.get("cooldowns");
        if (loadedCooldowns != null) {
            for (Map.Entry<String, Long> entry : loadedCooldowns.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(entry.getKey());
                    playerCooldowns.put(uuid, entry.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Skip invalid UUIDs
                }
            }
        }

        // Load ranks
        Map<String, String> loadedRanks = (Map<String, String>) data.get("ranks");
        if (loadedRanks != null) {
            for (Map.Entry<String, String> entry : loadedRanks.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(entry.getKey());
                    playerRanks.put(uuid, entry.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Skip invalid UUIDs
                }
            }
        }

        getLogger().info("Loaded player data for " + playerSuperpowers.size() + " players with superpowers, "
                + playerCooldowns.size() + " with cooldowns, " + playerRanks.size() + " with ranks");
        return true;
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

        if (!hasPlayerSuperpower(player.getUniqueId())) {
            player.sendMessage(messageManager.getMessage("no-superpower"));
            event.setCancelled(true);
            return;
        }

        if (hasPlayerCooldown(player.getUniqueId())) {
            long remaining = getRemainingCooldown(player.getUniqueId());
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
        String superpower = getPlayerSuperpower(player.getUniqueId());

        long cooldownDuration = configManager.getCooldownDuration(superpower);
        long cooldownEndTime = System.currentTimeMillis() + cooldownDuration * 1000;
        setPlayerCooldown(player.getUniqueId(), cooldownEndTime);

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

        // Check if player has any rank via LuckPerms
        if (luckPerms == null) setupLuckPerms();
        if (luckPerms == null) return false;
        return luckPerms.getPlayerPrimaryGroup(playerName) != null;
    }

    public boolean buyRank(Player player, String rankId) {
        UUID uuid = player.getUniqueId();

        if (hasPlayerSuperpower(uuid) && !player.hasPermission("superpower.rank.multiply")) {
            player.sendMessage("&cYou already own a superpower! Use /superpower dismiss to remove it before purchasing a rank.");
            return false;
        }

        if (playerRanks.containsKey(uuid) && playerRanks.get(uuid).equals(rankId)) {
            player.sendMessage("&cYou already have the &6" + configManager.getRankName(rankId) + "&c rank!");
            return false;
        }

        if (!configManager.isRankEnabled(rankId)) {
            player.sendMessage("&cThe &6" + configManager.getRankName(rankId) + "&c rank is currently disabled.");
            return false;
        }

        int price = configManager.getRankPrice(rankId);
        if (price <= 0) {
            player.sendMessage("&cThe &6" + configManager.getRankName(rankId) + "&c rank has an invalid price configuration.");
            return false;
        }

        if (!hasEnoughMoney(player, price)) {
            player.sendMessage("&cYou don't have enough money! &7Price: &6" + price + "&7 coins");
            return false;
        }

        boolean success = setPlayerRank(uuid, rankId);
        if (!success) {
            player.sendMessage("&cFailed to apply the &6" + configManager.getRankName(rankId) + "&c rank. Please try again later.");
            return false;
        }

        withdrawMoney(player, price);
        savePlayerData();

        player.sendMessage("&aYou purchased the &6" + configManager.getRankName(rankId) + "&a rank! Cost: &6" + price + "&a coins");
        return true;
    }

    public boolean giftRank(Player player, String targetName, String rankId) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            player.sendMessage("&cRecipient not found online.");
            return false;
        }
        UUID targetUuid = target.getUniqueId();
        UUID payerUuid = player.getUniqueId();

        if (playerRanks.containsKey(targetUuid) && playerRanks.get(targetUuid).equals(rankId)) {
            player.sendMessage("&cThe recipient already has the &6" + configManager.getRankName(rankId) + "&c rank.");
            return false;
        }

        if (!configManager.isRankEnabled(rankId)) {
            player.sendMessage("&cThe &6" + configManager.getRankName(rankId) + "&c rank is currently disabled.");
            return false;
        }

        int price = configManager.getRankPrice(rankId);
        if (price <= 0) {
            player.sendMessage("&cThe &6" + configManager.getRankName(rankId) + "&c rank has an invalid price configuration.");
            return false;
        }

        if (!hasEnoughMoney(player, price)) {
            player.sendMessage("&cYou don't have enough money! &7Price: &6" + price + "&7 coins");
            return false;
        }

        boolean success = setPlayerRank(targetUuid, rankId);
        if (!success) {
            player.sendMessage("&cFailed to apply the &6" + configManager.getRankName(rankId) + "&c rank to the recipient. Please try again later.");
            return false;
        }

        withdrawMoney(player, price);
        savePlayerData();

        player.sendMessage("&aYou gifted the &6" + configManager.getRankName(rankId) + "&a rank to &e" + target.getName() + "&a! Cost: &6" + price + "&a coins");
        return true;
    }

    public boolean hasPlayerRank(UUID uuid) {
        if (luckPerms == null) setupLuckPerms();
        if (luckPerms == null) return false;
        String primaryGroup = luckPerms.getPlayerPrimaryGroup(uuid);
        if (primaryGroup == null) return false;
        for (String rankId : new String[]{"veteran", "elite", "legend"}) {
            if (configManager.isRankEnabled(rankId)) {
                String groupId = configManager.getRankPermission(rankId);
                if (groupId != null && !groupId.isEmpty() && groupId.equals(primaryGroup)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String stripColorCodes(String text) {
        return text.replaceAll("&[0-9a-fk-or]", "").replaceAll("§[0-9a-fk-or]", "");
    }
