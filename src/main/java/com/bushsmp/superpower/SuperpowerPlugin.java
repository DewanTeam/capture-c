package com.bushsmp.superpower;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.StringArgument;
import dev.jorel.commandapi.executors.CommandArguments;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class SuperpowerPlugin extends JavaPlugin implements Listener {
    private Economy economy;
    private LuckPerms luckPerms;
    private ConfigManager configManager;
    private RankService rankService;

    private final Map<UUID, String> playerSuperpowers = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerRanks = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> playerCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> fireyShotCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> fireyActiveUntil = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> fireyTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> fallImmunityUntil = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> gravityTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> originalGravity = new ConcurrentHashMap<>();
    private final Set<UUID> arisenEntities = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> arisenCleanupTasks = new ConcurrentHashMap<>();

    private NamespacedKey superpowerItemKey;
    private NamespacedKey arisenEntityKey;
    private NamespacedKey arisenExpiresAtKey;
    private Object dialogApi;
    private Class<?> dialogApiType;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        superpowerItemKey = new NamespacedKey(this, "superpower_activator");
        arisenEntityKey = new NamespacedKey(this, "arisen_summon");
        arisenExpiresAtKey = new NamespacedKey(this, "arisen_expires_at");

        if (!setupEconomy()) {
            getLogger().severe("Vault economy provider not found. Install Vault and an economy provider before enabling SuperpowerPlugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!setupLuckPerms()) {
            getLogger().severe("LuckPerms API not found. Install LuckPerms before enabling rank purchases.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        loadPlayerData();
        rankService = new RankService(this, configManager, economy, luckPerms);
        setupDialogEngine();
        registerCommands();
        getServer().getPluginManager().registerEvents(this, this);
        for (Player online : Bukkit.getOnlinePlayers()) {
            syncPlayerRank(online);
            normalizeActivatorItems(online);
        }
        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) cleanupArisenChunk(chunk);
        }
        getLogger().info("SuperpowerPlugin enabled. Powers: " + configManager.getPowerIds().size()
                + ", configured rank entries: " + configManager.getRankIds().size());
    }

    @Override
    public void onDisable() {
        for (UUID playerId : new ArrayList<>(gravityTasks.keySet())) restoreGravity(playerId);
        for (BukkitTask task : fireyTasks.values()) task.cancel();
        fireyTasks.clear();
        for (BukkitTask task : arisenCleanupTasks.values()) task.cancel();
        arisenCleanupTasks.clear();
        for (UUID entityId : new ArrayList<>(arisenEntities)) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) entity.remove();
        }
        arisenEntities.clear();
        fireyActiveUntil.clear();
        fireyShotCooldowns.clear();
        savePlayerData();
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) return false;
        economy = registration.getProvider();
        return economy != null;
    }

    private boolean setupLuckPerms() {
        RegisteredServiceProvider<LuckPerms> registration = getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (registration == null) return false;
        luckPerms = registration.getProvider();
        return luckPerms != null;
    }

    private void setupDialogEngine() {
        if (!getServer().getPluginManager().isPluginEnabled("DialogEngine")) {
            getLogger().warning("DialogEngine is not installed. Command-based shops will remain available, but the G launcher and native dialogs will not.");
            return;
        }
        try {
            saveResource("dialogs/shop.yml", false);
            Class<?> engineClass = Class.forName("ai.resourcepack.dialogs.api.DialogEngine");
            dialogApiType = Class.forName("ai.resourcepack.dialogs.api.DialogEngineAPI");
            dialogApi = engineClass.getMethod("api").invoke(null);

            dialogApiType.getMethod("registerFolder", org.bukkit.plugin.Plugin.class, String.class, File.class)
                    .invoke(dialogApi, this, "superpower", new File(getDataFolder(), "dialogs"));
            registerDialogList("ListProvider", "superpower_ranks", this::getRankRows);
            registerDialogList("ListProvider", "superpower_powers", this::getPowerRows);
            registerDialogPlaceholder("PlaceholderResolver", "superpower_", this::resolveDialogPlaceholder);
            getLogger().info("DialogEngine menus registered in namespace superpower. G Quick Actions are available to supported clients after a server restart.");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            dialogApi = null;
            dialogApiType = null;
            getLogger().log(Level.WARNING, "Could not connect to DialogEngine 0.8.1 API. Commands still work; native menus are disabled.", exception);
        }
    }

    private void registerDialogList(String interfaceName, String listName, DialogRows rows) throws ReflectiveOperationException {
        Class<?> providerType = Class.forName("ai.resourcepack.dialogs.api." + interfaceName);
        Object provider = Proxy.newProxyInstance(providerType.getClassLoader(), new Class<?>[]{providerType},
                (proxy, method, args) -> method.getName().equals("rows")
                        ? rows.rows((Player) args[0]) : objectMethod(proxy, method, args));
        dialogApiType.getMethod("registerList", org.bukkit.plugin.Plugin.class, String.class, providerType)
                .invoke(dialogApi, this, listName, provider);
    }

    private void registerDialogPlaceholder(String interfaceName, String prefix, DialogPlaceholder resolver) throws ReflectiveOperationException {
        Class<?> resolverType = Class.forName("ai.resourcepack.dialogs.api." + interfaceName);
        Object provider = Proxy.newProxyInstance(resolverType.getClassLoader(), new Class<?>[]{resolverType},
                (proxy, method, args) -> method.getName().equals("resolve")
                        ? resolver.resolve((Player) args[0], args[1], (String) args[2]) : objectMethod(proxy, method, args));
        dialogApiType.getMethod("registerPlaceholders", org.bukkit.plugin.Plugin.class, String.class, resolverType)
                .invoke(dialogApi, this, prefix, provider);
    }

    private Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "SuperpowerPlugin DialogEngine provider";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            default -> null;
        };
    }

    private List<Map<String, String>> getRankRows(Player viewer) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String rankId : configManager.getRankIds()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", rankId);
            row.put("name", configManager.getRankName(rankId));
            row.put("text", configManager.getRankName(rankId));
            row.put("price", formatMoney(configManager.getRankPrice(rankId)));
            row.put("group", configManager.getRankGroup(rankId));
            row.put("duration", "+" + configManager.getRankDurationBonus(rankId) + " seconds");
            List<String> benefits = configManager.getRankBenefits(rankId);
            row.put("benefits", benefits.isEmpty() ? "Timed superpower effects receive the configured duration bonus." : String.join(", ", benefits));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, String>> getPowerRows(Player viewer) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String powerId : configManager.getPowerIds()) {
            if (!configManager.isPowerEnabled(powerId)) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", powerId);
            row.put("name", configManager.getPowerName(powerId));
            row.put("price", formatMoney(configManager.getPowerPrice(powerId)));
            row.put("description", configManager.getPowerDescription(powerId));
            rows.add(row);
        }
        return rows;
    }

    private String resolveDialogPlaceholder(Player viewer, Object context, String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        String rankId = dialogValue(context, "rank_id");
        String powerId = dialogValue(context, "power_id");
        return switch (normalized) {
            case "balance" -> formatMoney(economy.getBalance(viewer));
            case "rank" -> {
                String owned = playerRanks.get(viewer.getUniqueId());
                yield owned == null ? "None" : configManager.getRankName(owned);
            }
            case "power" -> {
                String owned = playerSuperpowers.get(viewer.getUniqueId());
                yield owned == null ? "None" : configManager.getPowerName(owned);
            }
            case "rank_name" -> rankId == null ? "Unknown rank" : configManager.getRankName(rankId);
            case "rank_price" -> rankId == null ? "0" : formatMoney(configManager.getRankPrice(rankId));
            case "rank_group" -> rankId == null ? "not configured" : configManager.getRankGroup(rankId);
            case "rank_duration" -> rankId == null ? "+0 seconds" : "+" + configManager.getRankDurationBonus(rankId) + " seconds";
            case "rank_benefits" -> rankId == null ? "No benefits configured" : String.join(", ", configManager.getRankBenefits(rankId));
            case "power_name" -> powerId == null ? "Unknown power" : configManager.getPowerName(powerId);
            case "power_price" -> powerId == null ? "0" : formatMoney(configManager.getPowerPrice(powerId));
            case "power_description" -> powerId == null ? "" : configManager.getPowerDescription(powerId);
            default -> null;
        };
    }

    private String dialogValue(Object context, String key) {
        if (context == null) return null;
        try {
            Class<?> contextType = Class.forName("ai.resourcepack.dialogs.api.DialogContext");
            Object value = contextType.getMethod("get", String.class).invoke(context, key);
            return value instanceof String string ? string : null;
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private boolean openDialog(Player player, String dialogId) {
        if (dialogApi == null || dialogApiType == null) return false;
        try {
            Object result = dialogApiType.getMethod("open", Player.class, String.class, Map.class)
                    .invoke(dialogApi, player, dialogId, Map.of());
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException exception) {
            getLogger().log(Level.WARNING, "Failed to open DialogEngine dialog " + dialogId, exception);
            return false;
        }
    }

    private void registerCommands() {
        new CommandAPICommand("superpower")
                .executesPlayer((Player player, CommandArguments args) -> {
                    if (!openDialog(player, "superpower:launcher")) showSuperpowerHelp(player);
                })
                .withSubcommand(new CommandAPICommand("shop")
                        .executesPlayer((Player player, CommandArguments args) -> {
                            if (!openDialog(player, "superpower:power_shop")) showPowerShop(player);
                        }))
                .withSubcommand(new CommandAPICommand("buy")
                        .withArguments(new StringArgument("power_id"))
                        .executesPlayer((Player player, CommandArguments args) -> buyPower(player, (String) args.get("power_id"))))
                .withSubcommand(new CommandAPICommand("gift")
                        .withArguments(new StringArgument("recipient"), new StringArgument("rank_id"))
                        .executesPlayer((Player player, CommandArguments args) -> {
                            Player recipient = Bukkit.getPlayerExact((String) args.get("recipient"));
                            if (recipient == null) {
                                player.sendMessage(color("&cThe recipient must be online to receive a rank gift right now."));
                                return;
                            }
                            rankService.gift(player, recipient, (String) args.get("rank_id"));
                        }))
                .withSubcommand(new CommandAPICommand("dismiss")
                        .executesPlayer((Player player, CommandArguments args) -> dismissPower(player)))
                .withSubcommand(new CommandAPICommand("reload")
                        .executesPlayer((Player player, CommandArguments args) -> reloadPluginConfig(player)))
                .withSubcommand(new CommandAPICommand("item")
                        .executesPlayer((Player player, CommandArguments args) -> giveActivator(player)))
                .withSubcommand(new CommandAPICommand("giveitem")
                        .withArguments(new StringArgument("recipient"))
                        .executesPlayer((Player player, CommandArguments args) -> {
                            if (!isAdmin(player)) {
                                player.sendMessage(color("&cYou do not have permission to give activators."));
                                return;
                            }
                            Player recipient = Bukkit.getPlayerExact((String) args.get("recipient"));
                            if (recipient == null) {
                                player.sendMessage(color("&cThat player must be online."));
                                return;
                            }
                            giveActivator(recipient);
                            player.sendMessage(color("&aActivator given to &e" + recipient.getName() + "&a."));
                        }))
                .register();

        new CommandAPICommand("rankshop")
                .executesPlayer((Player player, CommandArguments args) -> {
                    if (!openDialog(player, "superpower:rankshop")) showRankShop(player);
                })
                .withSubcommand(new CommandAPICommand("buy")
                        .withArguments(new StringArgument("rank_id"))
                        .executesPlayer((Player player, CommandArguments args) -> rankService.purchase(player, (String) args.get("rank_id"))))
                .withSubcommand(new CommandAPICommand("gift")
                        .withArguments(new StringArgument("recipient"), new StringArgument("rank_id"))
                        .executesPlayer((Player player, CommandArguments args) -> {
                            Player recipient = Bukkit.getPlayerExact((String) args.get("recipient"));
                            if (recipient == null) {
                                player.sendMessage(color("&cThe recipient must be online to receive a rank gift right now."));
                                return;
                            }
                            rankService.gift(player, recipient, (String) args.get("rank_id"));
                        }))
                .withSubcommand(new CommandAPICommand("benefits")
                        .executesPlayer((Player player, CommandArguments args) -> showRankBenefits(player)))
                .register();
    }

    private boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission("superpower.admin");
    }

    private void reloadPluginConfig(Player player) {
        if (!isAdmin(player)) {
            player.sendMessage(color("&cYou do not have permission to reload SuperpowerPlugin."));
            return;
        }

        reloadConfig();
        if (dialogApi != null && dialogApiType != null) {
            try {
                dialogApiType.getMethod("reload").invoke(dialogApi);
            } catch (ReflectiveOperationException exception) {
                getLogger().log(Level.WARNING, "Configuration was reloaded, but DialogEngine menus could not be refreshed.", exception);
            }
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            syncPlayerRank(online);
            normalizeActivatorItems(online);
        }
        player.sendMessage(color("&aSuperpowerPlugin configuration and DialogEngine menus reloaded."));
    }

    private void showSuperpowerHelp(Player player) {
        player.sendMessage(color("&6&lSuperpowers &7- &f/superpower shop &7| &f/superpower buy <power> &7| &f/superpower item &7| &f/superpower dismiss &7| &f/superpower gift &7| &f/superpower reload"));
        String current = playerSuperpowers.get(player.getUniqueId());
        player.sendMessage(color("&7Equipped power: &e" + (current == null ? "None" : configManager.getPowerName(current))));
    }

    private void showPowerShop(Player player) {
        player.sendMessage(color("&6&lSuperpower Shop &7- select a power with &f/superpower buy <power>"));
        for (String powerId : configManager.getPowerIds()) {
            if (!configManager.isPowerEnabled(powerId)) continue;
            player.sendMessage(color("&e" + powerId + " &7- " + configManager.getPowerName(powerId)
                    + " &8| &a" + formatMoney(configManager.getPowerPrice(powerId)) + " &8| &7" + configManager.getPowerDescription(powerId)));
        }
    }

    private void showRankShop(Player player) {
        player.sendMessage(color("&6&lRank Shop &7- balance: &a" + formatMoney(economy.getBalance(player))));
        player.sendMessage(color("&7Choose a rank with &f/rankshop buy <rank> &7or gift one with &f/rankshop gift <online-player> <rank>"));
        for (String rankId : configManager.getRankIds()) {
            player.sendMessage(color("&e" + rankId + " &7- " + configManager.getRankName(rankId)
                    + " &8| &a" + formatMoney(configManager.getRankPrice(rankId))
                    + " &8| &b+" + configManager.getRankDurationBonus(rankId) + "s to timed powers"));
        }
        player.sendMessage(color("&8Rank names, prices and LuckPerms groups are configurable examples until Dead Bush confirms the final rank ladder."));
    }

    private void showRankBenefits(Player player) {
        player.sendMessage(color("&6&lRank Benefits"));
        for (String rankId : configManager.getRankIds()) {
            player.sendMessage(color("&e" + configManager.getRankName(rankId) + "&7: timed power effects +" + configManager.getRankDurationBonus(rankId) + " seconds"));
            for (String benefit : configManager.getRankBenefits(rankId)) {
                player.sendMessage(color("&8 - &7" + benefit));
            }
        }
    }

    private void buyPower(Player player, String rawPowerId) {
        String powerId = rawPowerId == null ? "" : rawPowerId.toLowerCase(Locale.ROOT);
        if (!configManager.getPowerIds().contains(powerId) || !configManager.isPowerEnabled(powerId)) {
            player.sendMessage(color("&cUnknown or disabled power. Use /superpower shop."));
            return;
        }
        if (playerSuperpowers.containsKey(player.getUniqueId())) {
            player.sendMessage(color("&cYou already own a power. Use /superpower dismiss before purchasing another one."));
            return;
        }
        double price = configManager.getPowerPrice(powerId);
        if (!Double.isFinite(price) || price < 0.0D) {
            player.sendMessage(color("&cThis power has an invalid configured price. Contact an administrator."));
            return;
        }
        if (!economy.has(player, price)) {
            player.sendMessage(color("&cYou do not have enough money. &7Price: &e" + formatMoney(price)));
            return;
        }
        EconomyResponse response = economy.withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            player.sendMessage(color("&cPurchase failed: " + response.errorMessage));
            return;
        }
        playerSuperpowers.put(player.getUniqueId(), powerId);
        if (!savePlayerData()) {
            playerSuperpowers.remove(player.getUniqueId());
            EconomyResponse refund = economy.depositPlayer(player, price);
            if (!refund.transactionSuccess()) {
                getLogger().severe("Power purchase save failed and refund also failed for " + player.getUniqueId());
                player.sendMessage(color("&cYour purchase needs administrator attention. Please contact staff."));
            } else {
                player.sendMessage(color("&cCould not save your power. Your payment was refunded."));
            }
            return;
        }
        giveActivator(player);
        player.sendMessage(color("&aPurchased &e" + configManager.getPowerName(powerId) + "&a. Right-click your Superpower Activator to use it."));
    }

    private void dismissPower(Player player) {
        String removed = playerSuperpowers.remove(player.getUniqueId());
        if (removed == null) {
            player.sendMessage(color("&cYou do not have an equipped power."));
            return;
        }
        if (!savePlayerData()) {
            playerSuperpowers.put(player.getUniqueId(), removed);
            player.sendMessage(color("&cCould not save that change. Your power was not removed."));
            return;
        }
        player.sendMessage(color("&aRemoved your &e" + configManager.getPowerName(removed) + " &apower. You can purchase another from /superpower shop."));
    }

    private boolean normalizeActivatorItems(Player player) {
        boolean kept = false;
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int index = 0; index < storage.length; index++) {
            if (!isActivator(storage[index])) continue;
            if (!kept) {
                kept = true;
            } else {
                storage[index] = null;
            }
        }
        player.getInventory().setStorageContents(storage);

        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isActivator(offHand)) {
            if (kept) {
                player.getInventory().setItemInOffHand(null);
            } else {
                kept = true;
            }
        }
        return kept;
    }

    private void giveActivator(Player player) {
        if (normalizeActivatorItems(player)) return;
        ItemStack item = new ItemStack(Material.DIAMOND);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.setDisplayName(color("&b&lSuperpower Activator"));
        meta.setLore(List.of(color("&7Right-click to activate your equipped power."), color("&8This item is marked by SuperpowerPlugin.")));
        meta.getPersistentDataContainer().set(superpowerItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        leftovers.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
    }

    private boolean isActivator(ItemStack item) {
        if (item == null || item.getType() != Material.DIAMOND || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(superpowerItemKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() == null || event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (!isActivator(event.getItem())) return;
        event.setCancelled(true);
        activatePower(event.getPlayer());
    }

    private void activatePower(Player player) {
        UUID playerId = player.getUniqueId();
        String powerId = playerSuperpowers.get(playerId);
        if (powerId == null) {
            player.sendMessage(color("&cYou do not own a superpower. Visit /superpower shop."));
            return;
        }
        long now = System.currentTimeMillis();
        int duration = configManager.getPowerDurationSeconds(powerId) + configManager.getDurationBonusForRank(playerRanks.get(playerId));

        if (powerId.equals("firey")) {
            activateFirey(player, duration, now);
            return;
        }

        long nextUse = getCooldownUntil(playerId, powerId);
        if (nextUse > now) {
            player.sendMessage(color("&cPower cooldown: &e" + Math.max(1, (nextUse - now + 999) / 1000) + "s"));
            return;
        }

        boolean activated = switch (powerId) {
            case "magneto" -> activateMagneto(player, duration);
            case "frozen" -> activateFrozen(player);
            case "arise" -> activateArise(player, duration);
            case "flash" -> activateFlash(player, duration);
            case "hulk" -> activateHulk(player, duration);
            default -> false;
        };

        if (!activated) return;
        int cooldown = configManager.getPowerCooldownSeconds(powerId);
        setCooldownUntil(playerId, powerId, now + cooldown * 1000L);
        if (!savePlayerData()) {
            getLogger().warning("Could not persist cooldown for " + playerId + ". The current session can continue, but a restart may reset it.");
        }
        player.sendMessage(color("&aActivated &e" + configManager.getPowerName(powerId) + "&a for &e" + duration + " seconds&a."));
    }

    /** Firey is a timed mode: right-click launches a harmless particle-only fireball visual. */
    private long getCooldownUntil(UUID playerId, String powerId) {
        Map<String, Long> playerEntries = playerCooldowns.get(playerId);
        if (playerEntries == null) return 0L;
        return Math.max(
                playerEntries.getOrDefault(powerId, 0L),
                playerEntries.getOrDefault("_legacy", 0L)
        );
    }

    private void setCooldownUntil(UUID playerId, String powerId, long until) {
        playerCooldowns.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(powerId, until);
    }

    /** Firey mode duration/cooldown is separate from its five-second visual-shot interval. */
    private void activateFirey(Player player, int durationSeconds, long now) {
        UUID playerId = player.getUniqueId();
        long activeUntil = fireyActiveUntil.getOrDefault(playerId, 0L);
        boolean starting = activeUntil <= now;

        if (starting) {
            long nextUse = getCooldownUntil(playerId, "firey");
            if (nextUse > now) {
                player.sendMessage(color("&7Firey cooldown: &e" + Math.max(1, (nextUse - now + 999) / 1000) + "s"));
                return;
            }

            fireyActiveUntil.put(playerId, now + durationSeconds * 1000L);
            setCooldownUntil(playerId, "firey", now + configManager.getPowerCooldownSeconds("firey") * 1000L);
            if (!savePlayerData()) {
                getLogger().warning("Could not persist Firey cooldown for " + playerId + ".");
            }
            startFireyParticles(playerId);
            player.sendMessage(color("&6Firey mode activated for &e" + durationSeconds + " seconds&6. Right-click again to launch a fireball visual."));
        }

        long nextShot = fireyShotCooldowns.getOrDefault(playerId, 0L);
        if (nextShot > now) {
            player.sendMessage(color("&7Firey shot cooldown: &e" + Math.max(1, (nextShot - now + 999) / 1000) + "s"));
            return;
        }

        launchFireyVisual(player);
        fireyShotCooldowns.put(playerId, now + configManager.getFireyVisualCooldownSeconds() * 1000L);
        player.sendMessage(color("&6Firey launched a visual fireball. &7Next shot in " + configManager.getFireyVisualCooldownSeconds() + "s."));
    }

    private void launchFireyVisual(Player player) {
        Location start = player.getEyeLocation().add(player.getEyeLocation().getDirection().normalize());
        org.bukkit.util.Vector perTick = player.getEyeLocation().getDirection().normalize().multiply(0.65D);
        World world = player.getWorld();
        int[] step = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (step[0] >= 16) {
                task[0].cancel();
                return;
            }
            Location point = start.clone().add(perTick.clone().multiply(step[0]++));
            world.spawnParticle(Particle.FLAME, point, 14, 0.12, 0.12, 0.12, 0.005);
            world.spawnParticle(Particle.SMOKE, point, 3, 0.08, 0.08, 0.08, 0.002);
        }, 0L, 1L);
    }

    private void startFireyParticles(UUID playerId) {
        BukkitTask previous = fireyTasks.remove(playerId);
        if (previous != null) previous.cancel();
        BukkitTask visualTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            Player online = Bukkit.getPlayer(playerId);
            Long until = fireyActiveUntil.get(playerId);
            if (online == null || until == null || until <= System.currentTimeMillis()) {
                fireyActiveUntil.remove(playerId);
                BukkitTask task = fireyTasks.remove(playerId);
                if (task != null) task.cancel();
                return;
            }
            Location effectAt = online.getLocation().add(0, 1, 0);
            online.getWorld().spawnParticle(Particle.FLAME, effectAt, 8, 0.42, 0.55, 0.42, 0.005);
            online.getWorld().spawnParticle(Particle.SMOKE, effectAt, 2, 0.35, 0.45, 0.35, 0.002);
        }, 0L, 10L);
        fireyTasks.put(playerId, visualTask);
    }

    private boolean activateMagneto(Player player, int durationSeconds) {
        Location origin = player.getLocation();
        int height = configManager.getMagnetoHeight();
        int safeHeight = 0;
        for (int step = 1; step <= height; step++) {
            Location candidate = origin.clone().add(0, step, 0);
            if (!candidate.getBlock().isPassable() || !candidate.clone().add(0, 1, 0).getBlock().isPassable()) break;
            safeHeight = step;
        }
        if (safeHeight == 0) {
            player.sendMessage(color("&cThere is not enough open space above you to activate Magneto."));
            return false;
        }
        restoreGravity(player.getUniqueId());
        originalGravity.put(player.getUniqueId(), player.hasGravity());
        if (!player.teleport(origin.clone().add(0, safeHeight, 0))) {
            originalGravity.remove(player.getUniqueId());
            player.sendMessage(color("&cMagneto could not move you to a safe position."));
            return false;
        }
        player.setGravity(false);
        player.setVelocity(new Vector(0, 0, 0));
        UUID playerId = player.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> restoreGravity(playerId), durationSeconds * 20L);
        gravityTasks.put(playerId, task);
        return true;
    }

    private boolean activateFrozen(Player player) {
        int radius = configManager.getFrozenRadius();
        int freezeSeconds = configManager.getFrozenDurationSeconds()
                + configManager.getDurationBonusForRank(playerRanks.get(player.getUniqueId()));
        int freezeTicks = freezeSeconds * 20;
        int frozenCount = 0;
        double radiusSquared = (double) radius * radius;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof Player target) || target.equals(player)) continue;
            if (target.getLocation().distanceSquared(player.getLocation()) > radiusSquared) continue;
            target.setFreezeTicks(Math.max(target.getFreezeTicks(), freezeTicks));
            target.sendMessage(color("&bYou were frozen by &e" + player.getName() + "&b."));
            frozenCount++;
        }
        if (frozenCount == 0) {
            player.sendMessage(color("&7No other players are within &e" + radius + " blocks&7."));
            return false;
        }
        player.sendMessage(color("&bFrozen &e" + frozenCount + " &bplayer(s) for &e" + freezeSeconds + " seconds&b."));
        return true;
    }

    private boolean activateArise(Player player, int durationSeconds) {
        int count = configManager.getAriseMobCount();
        int enchantedCount = configManager.getAriseEnchantedCount();
        String tier = configManager.getAriseArmorTier();
        World world = player.getWorld();
        Location center = player.getLocation();
        long expiresAt = System.currentTimeMillis() + durationSeconds * 1000L;
        int spawned = 0;

        for (int index = 0; index < count; index++) {
            double angle = (Math.PI * 2.0D * index) / count;
            Location spawnAt = center.clone().add(Math.cos(angle) * 2.5D, 0, Math.sin(angle) * 2.5D);
            if (!spawnAt.getBlock().isPassable() || !spawnAt.clone().add(0, 1, 0).getBlock().isPassable()) continue;

            Entity entity = world.spawnEntity(spawnAt, EntityType.ZOMBIE);
            if (!(entity instanceof Zombie zombie)) {
                entity.remove();
                continue;
            }

            zombie.setCustomName(color("&5Arisen"));
            zombie.setCustomNameVisible(false);
            zombie.setCanPickupItems(false);
            zombie.setTarget(null);
            zombie.getPersistentDataContainer().set(arisenEntityKey, PersistentDataType.BYTE, (byte) 1);
            zombie.getPersistentDataContainer().set(arisenExpiresAtKey, PersistentDataType.LONG, expiresAt);

            if (index < enchantedCount) equipArisenArmor(zombie, tier);
            scheduleArisenCleanup(zombie, expiresAt);
            spawned++;
        }

        player.sendMessage(color("&5Arise summoned &e" + spawned + " &5undead ally/allies for &e" + durationSeconds + " seconds&5."));
        return spawned > 0;
    }

    private boolean isArisen(Entity entity) {
        Byte marker = entity.getPersistentDataContainer().get(arisenEntityKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private void scheduleArisenCleanup(Entity entity, long expiresAt) {
        UUID entityId = entity.getUniqueId();
        BukkitTask previous = arisenCleanupTasks.remove(entityId);
        if (previous != null) previous.cancel();
        arisenEntities.add(entityId);

        long remainingMillis = expiresAt - System.currentTimeMillis();
        if (remainingMillis <= 0L) {
            entity.remove();
            arisenEntities.remove(entityId);
            return;
        }

        long delayTicks = Math.max(1L, (remainingMillis + 49L) / 50L);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            arisenCleanupTasks.remove(entityId);
            Entity loadedEntity = Bukkit.getEntity(entityId);
            if (loadedEntity == null) {
                // Its persistent expiry marker remains on disk. ChunkLoadEvent
                // will schedule cleanup again or remove it immediately.
                return;
            }

            Long storedExpiry = loadedEntity.getPersistentDataContainer()
                    .get(arisenExpiresAtKey, PersistentDataType.LONG);
            if (storedExpiry == null || storedExpiry <= System.currentTimeMillis()) {
                loadedEntity.remove();
                arisenEntities.remove(entityId);
            } else {
                scheduleArisenCleanup(loadedEntity, storedExpiry);
            }
        }, delayTicks);
        arisenCleanupTasks.put(entityId, task);
    }

    private void cleanupArisenChunk(org.bukkit.Chunk chunk) {
        long now = System.currentTimeMillis();
        for (Entity entity : chunk.getEntities()) {
            if (!isArisen(entity)) continue;
            Long expiresAt = entity.getPersistentDataContainer().get(arisenExpiresAtKey, PersistentDataType.LONG);
            if (expiresAt == null || expiresAt <= now) {
                entity.remove();
                arisenEntities.remove(entity.getUniqueId());
            } else {
                scheduleArisenCleanup(entity, expiresAt);
            }
        }
    }

    @EventHandler
    public void onArisenTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie) || !(event.getTarget() instanceof Player)) return;
        if (isArisen(zombie)) event.setCancelled(true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        cleanupArisenChunk(event.getChunk());
    }

    private void equipArisenArmor(Zombie zombie, String tier) {
        EntityEquipment equipment = zombie.getEquipment();
        if (equipment == null) return;
        Material helmet = Material.matchMaterial(tier + "_HELMET");
        Material chest = Material.matchMaterial(tier + "_CHESTPLATE");
        Material legs = Material.matchMaterial(tier + "_LEGGINGS");
        Material boots = Material.matchMaterial(tier + "_BOOTS");
        equipment.setHelmet(new ItemStack(helmet == null ? Material.IRON_HELMET : helmet));
        equipment.setChestplate(new ItemStack(chest == null ? Material.IRON_CHESTPLATE : chest));
        equipment.setLeggings(new ItemStack(legs == null ? Material.IRON_LEGGINGS : legs));
        equipment.setBoots(new ItemStack(boots == null ? Material.IRON_BOOTS : boots));
        Enchantment protection = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("protection"));
        if (protection != null) {
            for (ItemStack armorPiece : List.of(equipment.getHelmet(), equipment.getChestplate(), equipment.getLeggings(), equipment.getBoots())) {
                if (armorPiece != null) armorPiece.addUnsafeEnchantment(protection, 3);
            }
        }
        equipment.setHelmetDropChance(0.0F);
        equipment.setChestplateDropChance(0.0F);
        equipment.setLeggingsDropChance(0.0F);
        equipment.setBootsDropChance(0.0F);
    }

    private boolean activateFlash(Player player, int durationSeconds) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationSeconds * 20,
                configManager.getFlashAmplifier(), true, true, true));
        return true;
    }

    private boolean activateHulk(Player player, int durationSeconds) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, durationSeconds * 20,
                configManager.getHulkJumpAmplifier(), true, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, durationSeconds * 20,
                configManager.getHulkStrengthAmplifier(), true, true, true));
        UUID playerId = player.getUniqueId();
        fallImmunityUntil.put(playerId, System.currentTimeMillis() + durationSeconds * 1000L);
        Bukkit.getScheduler().runTaskLater(this, () -> fallImmunityUntil.remove(playerId), durationSeconds * 20L);
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        syncPlayerRank(event.getPlayer());
        normalizeActivatorItems(event.getPlayer());
    }

    private void syncPlayerRank(Player player) {
        UUID playerId = player.getUniqueId();
        luckPerms.getUserManager().loadUser(playerId).whenComplete((user, error) -> {
            if (error != null) {
                getLogger().log(Level.WARNING, "Could not synchronize LuckPerms rank for " + playerId, error);
                return;
            }
            String matchedRank = findHighestConfiguredRank(user);
            Bukkit.getScheduler().runTask(this, () -> {
                String previous = playerRanks.get(playerId);
                if (matchedRank == null) playerRanks.remove(playerId);
                else playerRanks.put(playerId, matchedRank);
                if (!java.util.Objects.equals(previous, matchedRank) && !savePlayerData()) {
                    getLogger().warning("Could not persist the synchronized LuckPerms rank for " + playerId + ".");
                }
            });
        });
    }

    private String findHighestConfiguredRank(User user) {
        String matchedRank = null;
        int highestOrder = -1;
        String primaryGroup = user.getPrimaryGroup();
        Set<String> inheritedGroups = new java.util.HashSet<>();
        for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) inheritedGroups.add(node.getGroupName().toLowerCase(Locale.ROOT));
        if (primaryGroup != null) inheritedGroups.add(primaryGroup.toLowerCase(Locale.ROOT));

        for (String rankId : configManager.getRankIds()) {
            String groupName = configManager.getRankGroup(rankId);
            if (!inheritedGroups.contains(groupName.toLowerCase(Locale.ROOT))) continue;
            int order = configManager.getRankOrder(rankId);
            if (order > highestOrder) {
                highestOrder = order;
                matchedRank = rankId;
            }
        }
        return matchedRank;
    }

    @EventHandler
    public void onPlayerFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        Long immuneUntil = fallImmunityUntil.get(player.getUniqueId());
        if (immuneUntil != null && immuneUntil > System.currentTimeMillis()) event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        restoreGravity(playerId);
        fireyActiveUntil.remove(playerId);
        fireyShotCooldowns.remove(playerId);
        BukkitTask fireyTask = fireyTasks.remove(playerId);
        if (fireyTask != null) fireyTask.cancel();
        fallImmunityUntil.remove(playerId);
    }

    private void restoreGravity(UUID playerId) {
        BukkitTask task = gravityTasks.remove(playerId);
        if (task != null) task.cancel();
        Player player = Bukkit.getPlayer(playerId);
        Boolean restore = originalGravity.remove(playerId);
        if (player != null && restore != null) {
            player.setGravity(restore);
            player.setFallDistance(0.0F);
        }
    }

    public String getStoredRank(UUID playerId) {
        return playerRanks.get(playerId);
    }

    public boolean storeRank(UUID playerId, String rankId) {
        playerRanks.put(playerId, rankId);
        return savePlayerData();
    }

    public void removeStoredRank(UUID playerId) {
        playerRanks.remove(playerId);
        savePlayerData();
    }

    public boolean appendRankTransaction(String type, UUID payerId, UUID recipientId, String rankId, double amount) {
        File file = new File(getDataFolder(), "transactions.yml");
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        String transactionId = UUID.randomUUID().toString();
        String path = "transactions." + transactionId;
        data.set(path + ".type", type);
        data.set(path + ".payer", payerId.toString());
        data.set(path + ".recipient", recipientId.toString());
        data.set(path + ".rank", rankId);
        data.set(path + ".amount", amount);
        data.set(path + ".timestamp", System.currentTimeMillis());
        try {
            data.save(file);
            return true;
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not write rank transaction log.", exception);
            return false;
        }
    }

    private void loadPlayerData() {
        File file = new File(getDataFolder(), "players.yml");
        if (!file.isFile()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        loadUuidStringMap(data.getConfigurationSection("superpowers"), playerSuperpowers);
        loadUuidStringMap(data.getConfigurationSection("ranks"), playerRanks);

        ConfigurationSection cooldowns = data.getConfigurationSection("cooldowns");
        if (cooldowns == null) return;

        long now = System.currentTimeMillis();
        for (String playerKey : cooldowns.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(playerKey);
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Ignoring invalid UUID in cooldown storage: " + playerKey);
                continue;
            }

            ConfigurationSection perPower = cooldowns.getConfigurationSection(playerKey);
            if (perPower == null) {
                // Older builds stored one global timestamp per player. Preserve
                // any still-active cooldown as a temporary legacy floor.
                long legacyUntil = cooldowns.getLong(playerKey, 0L);
                if (legacyUntil > now) setCooldownUntil(playerId, "_legacy", legacyUntil);
                continue;
            }

            for (String powerId : perPower.getKeys(false)) {
                long until = perPower.getLong(powerId, 0L);
                if (until > now) setCooldownUntil(playerId, powerId, until);
            }
        }
    }

    private void loadUuidStringMap(ConfigurationSection section, Map<UUID, String> target) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String value = section.getString(key);
            if (value == null || value.isBlank()) continue;
            try {
                target.put(UUID.fromString(key), value);
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Ignoring invalid UUID in player data: " + key);
            }
        }
    }

    private boolean savePlayerData() {
        if (getDataFolder() != null && !getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("Could not create plugin data directory.");
            return false;
        }
        YamlConfiguration data = new YamlConfiguration();
        playerSuperpowers.forEach((uuid, power) -> data.set("superpowers." + uuid, power));
        playerRanks.forEach((uuid, rank) -> data.set("ranks." + uuid, rank));
        long now = System.currentTimeMillis();
        playerCooldowns.forEach((uuid, powerEntries) -> powerEntries.forEach((powerId, until) -> {
            if (until > now) data.set("cooldowns." + uuid + "." + powerId, until);
        }));
        try {
            data.save(new File(getDataFolder(), "players.yml"));
            return true;
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not save players.yml.", exception);
            return false;
        }
    }

    private String formatMoney(double amount) {
        return configManager.formatCurrency(amount);
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    @FunctionalInterface
    private interface DialogRows {
        List<Map<String, String>> rows(Player viewer);
    }

    @FunctionalInterface
    private interface DialogPlaceholder {
        String resolve(Player viewer, Object context, String key);
    }
}