package com.bushsmp.superpower;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Optional backend half of family routing.
 *
 * It transfers direct Java connections during the initial configuration phase,
 * before the normal join/login flow, and stores the backend ID in a client cookie.
 * Proxy-origin connections must be explicitly allow-listed; without that list the
 * module fails closed and leaves the connection on this backend.
 */
final class FamilyRoutingModule implements Listener {
    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private final JavaPlugin plugin;
    private final PreferenceStore preferences;
    private final boolean enabled;
    private final String serverId;
    private final String targetHost;
    private final int targetPort;
    private final boolean logPeerAddresses;
    private final NamespacedKey cookieKey;
    private final List<AddressMatcher> trustedProxyPeers = new ArrayList<>();
    private final List<String> directBypassHosts = new ArrayList<>();

    FamilyRoutingModule(JavaPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("modules.family-routing.enabled", false);
        this.preferences = new PreferenceStore(plugin);

        String root = "family-routing.";
        this.serverId = normalize(plugin.getConfig().getString(root + "server-id", ""));
        this.targetHost = safe(plugin.getConfig().getString(root + "transfer-target.host", ""));
        this.targetPort = plugin.getConfig().getInt(root + "transfer-target.port", 25565);
        this.logPeerAddresses = plugin.getConfig().getBoolean(root + "debug.log-peer-addresses", false);

        String namespace = safe(plugin.getConfig().getString(root + "cookie.namespace", "network"));
        String key = safe(plugin.getConfig().getString(root + "cookie.key", "origin_server"));
        if (!namespace.matches("[a-z0-9._-]{1,64}") || !key.matches("[a-z0-9._/-]{1,64}")) {
            throw new IllegalArgumentException("Invalid family-routing cookie namespace or key.");
        }
        this.cookieKey = new NamespacedKey(namespace, key);

        for (String value : plugin.getConfig().getStringList(root + "trusted-proxy-peers")) {
            try {
                trustedProxyPeers.add(AddressMatcher.parse(value));
            } catch (Exception exception) {
                plugin.getLogger().warning("Ignoring invalid trusted proxy peer '" + value + "': " + exception.getMessage());
            }
        }
        for (String value : plugin.getConfig().getStringList(root + "direct-bypass-hosts")) {
            if (value != null && !value.isBlank()) directBypassHosts.add(normalizeHost(value));
        }

        if (!enabled) return;

        if (!SERVER_ID.matcher(serverId).matches()) {
            throw new IllegalArgumentException(
                    "family-routing.server-id must match [a-z0-9_-]{1,32} when family routing is enabled.");
        }
        if (targetHost.isBlank() || targetPort < 1 || targetPort > 65535) {
            throw new IllegalArgumentException(
                    "Set a valid family-routing.transfer-target.host and port.");
        }
        if (trustedProxyPeers.isEmpty()) {
            plugin.getLogger().warning(
                    "Family routing is enabled, but trusted-proxy-peers is empty. "
                    + "Automatic transfers are fail-closed to prevent proxy loops.");
        }

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registerCommand();
        plugin.getLogger().info("Family routing enabled for backend '" + serverId
                + "' -> " + targetHost + ":" + targetPort + ".");
    }

    boolean isEnabled() {
        return enabled;
    }

    private void registerCommand() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var command = Commands.literal("autotransfer")
                    .executes(context -> runPreferenceCommand(context.getSource().getSender(), "status"))
                    .then(Commands.literal("on")
                            .executes(context -> runPreferenceCommand(context.getSource().getSender(), "on")))
                    .then(Commands.literal("off")
                            .executes(context -> runPreferenceCommand(context.getSource().getSender(), "off")))
                    .then(Commands.literal("toggle")
                            .executes(context -> runPreferenceCommand(context.getSource().getSender(), "toggle")))
                    .then(Commands.literal("status")
                            .executes(context -> runPreferenceCommand(context.getSource().getSender(), "status")));
            event.registrar().register(command.build(),
                    "Enable, disable, or inspect automatic transfer to the family proxy.");
        });
    }

    private int runPreferenceCommand(CommandSender sender, String mode) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is player-only.");
            return Command.SINGLE_SUCCESS;
        }

        switch (mode) {
            case "on" -> setPreference(player, true);
            case "off" -> setPreference(player, false);
            case "toggle" -> setPreference(player, !preferences.isEnabled(player.getUniqueId()));
            default -> sendStatus(player);
        }
        return Command.SINGLE_SUCCESS;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInitialConfigure(PlayerConnectionInitialConfigureEvent event) {
        if (!enabled) return;

        PlayerConfigurationConnection connection = event.getConnection();
        String virtualHost = connection.getVirtualHost() == null
                ? ""
                : normalizeHost(connection.getVirtualHost().getHostString());

        if (!virtualHost.isEmpty() && directBypassHosts.contains(virtualHost)) return;

        UUID playerId = connection.getProfile().getId();
        if (playerId == null) return;

        // Floodgate players should remain on the independently accessible backend.
        // If Floodgate is present but its API cannot be queried, fail closed.
        if (isFloodgatePlayer(playerId)) return;

        InetAddress peer = rawPeerAddress(connection.getAddress());
        if (logPeerAddresses) {
            plugin.getLogger().info("Family routing peer for "
                    + connection.getProfile().getName() + " is "
                    + (peer == null ? "<unavailable>" : peer.getHostAddress())
                    + "; virtual host=" + virtualHost + ".");
        }

        if (trustedProxyPeers.isEmpty()) return;
        if (peer == null) {
            plugin.getLogger().warning("Could not identify the connection's raw TCP peer; leaving it direct.");
            return;
        }
        if (isTrustedProxy(peer)) return;
        if (!preferences.isEnabled(playerId)) return;

        try {
            connection.storeCookie(cookieKey, serverId.getBytes(StandardCharsets.UTF_8));
            connection.transfer(targetHost, targetPort);
            plugin.getLogger().info("Transferred direct connection for "
                    + connection.getProfile().getName() + " to the family proxy; origin='"
                    + serverId + "'.");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            plugin.getLogger().warning("Could not transfer "
                    + connection.getProfile().getName() + ": " + exception.getMessage());
        }
    }

    private boolean isFloodgatePlayer(UUID playerId) {
        if (plugin.getServer().getPluginManager().getPlugin("floodgate") == null
                && plugin.getServer().getPluginManager().getPlugin("Floodgate") == null) {
            return false;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Method method = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            return Boolean.TRUE.equals(method.invoke(api, playerId));
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().warning(
                    "Floodgate is installed but its API could not be queried; skipping auto-transfer for safety.");
            return true;
        }
    }

    private boolean isTrustedProxy(InetAddress peer) {
        for (AddressMatcher matcher : trustedProxyPeers) {
            if (matcher.matches(peer)) return true;
        }
        return false;
    }

    private void setPreference(Player player, boolean enabled) {
        try {
            preferences.setEnabled(player.getUniqueId(), enabled);
            player.sendMessage(enabled
                    ? "Automatic transfer to the family proxy is enabled on this backend."
                    : "Automatic transfer to the family proxy is disabled on this backend.");
        } catch (IllegalStateException exception) {
            player.sendMessage("Could not save your preference. Please contact staff.");
            plugin.getLogger().warning(exception.getMessage());
        }
    }

    private void sendStatus(Player player) {
        player.sendMessage("Automatic family-proxy transfer is "
                + (preferences.isEnabled(player.getUniqueId()) ? "enabled" : "disabled") + ".");
        player.sendMessage("Default for new players: "
                + (preferences.getDefaultEnabled() ? "enabled" : "disabled") + ".");
    }

    private static InetAddress rawPeerAddress(SocketAddress address) {
        if (!(address instanceof InetSocketAddress inet)) return null;
        return inet.getAddress();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT);
    }

    private static String normalizeHost(String value) {
        String host = normalize(value);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host;
    }

    /** Exact address, hostname, or IPv4/IPv6 CIDR matcher. */
    private static final class AddressMatcher {
        private final InetAddress network;
        private final int prefixBits;

        private AddressMatcher(InetAddress network, int prefixBits) {
            this.network = network;
            this.prefixBits = prefixBits;
        }

        static AddressMatcher parse(String raw) throws Exception {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("value is blank");
            String value = raw.trim();
            int slash = value.indexOf('/');
            if (slash >= 0) {
                InetAddress network = InetAddress.getByName(value.substring(0, slash).trim());
                int prefix = Integer.parseInt(value.substring(slash + 1).trim());
                int max = network.getAddress().length * 8;
                if (prefix < 0 || prefix > max) throw new IllegalArgumentException("prefix must be 0.." + max);
                return new AddressMatcher(network, prefix);
            }
            return new AddressMatcher(InetAddress.getAllByName(value)[0],
                    InetAddress.getAllByName(value)[0].getAddress().length * 8);
        }

        boolean matches(InetAddress candidate) {
            byte[] left = network.getAddress();
            byte[] right = candidate.getAddress();
            if (left.length != right.length) return false;
            int fullBytes = prefixBits / 8;
            int remainingBits = prefixBits % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (left[i] != right[i]) return false;
            }
            if (remainingBits == 0) return true;
            int mask = 0xFF << (8 - remainingBits);
            return (left[fullBytes] & mask) == (right[fullBytes] & mask);
        }
    }
}
