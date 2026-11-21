package com.bushsmp.superpower.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.CookieReceiveEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.key.Key;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Proxy half of SuperpowerPlugin. The transfer cookie is used only as an
 * allow-listed routing hint; it is never treated as an authentication token.
 */
@Plugin(
        id = "superpowerplugin",
        name = "SuperpowerPlugin",
        version = "1.1.0",
        description = "Universal family-network routing companion.",
        authors = {"DewanTeam"}
)
public final class FamilyOriginProxyPlugin {
    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final byte[] EMPTY_COOKIE = new byte[0];

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<UUID, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile boolean logDecisions;
    private volatile Key cookieKey;
    private volatile long requestTimeoutMillis;
    private volatile String fallbackServerName;
    private volatile Map<String, RegisteredServer> allowedServers = Map.of();
    private ScheduledExecutorService timeoutExecutor;

    @Inject
    public FamilyOriginProxyPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        Properties config;
        try {
            config = loadConfig();
        } catch (IOException exception) {
            logger.error("Could not load SuperpowerPlugin proxy configuration; origin routing is disabled.", exception);
            enabled = false;
            return;
        }

        enabled = Boolean.parseBoolean(config.getProperty("origin-router.enabled", "false").trim());
        logDecisions = Boolean.parseBoolean(config.getProperty("log.cookie-decisions", "true").trim());
        if (!enabled) {
            logger.info("Family origin routing is disabled in plugins/SuperpowerPlugin/config.properties.");
            return;
        }

        String namespace = config.getProperty("cookie.namespace", "network").trim();
        String key = config.getProperty("cookie.key", "origin_server").trim();
        if (!namespace.matches("[a-z0-9._-]{1,64}") || !key.matches("[a-z0-9._/-]{1,64}")) {
            disable("cookie.namespace or cookie.key has an invalid value.");
            return;
        }
        cookieKey = Key.key(namespace, key);

        try {
            requestTimeoutMillis = Long.parseLong(
                    config.getProperty("cookie.request-timeout-ms", "1500").trim());
        } catch (NumberFormatException exception) {
            disable("cookie.request-timeout-ms must be an integer.");
            return;
        }
        if (requestTimeoutMillis < 250 || requestTimeoutMillis > 10000) {
            disable("cookie.request-timeout-ms must be between 250 and 10000.");
            return;
        }

        fallbackServerName = normalize(config.getProperty("fallback-server", "bushsmp"));
        Map<String, RegisteredServer> destinations = new HashMap<>();
        for (String configuredName : splitNames(
                config.getProperty("allowed-servers", "bushsmp,ucahub,daybreak"))) {
            String name = normalize(configuredName);
            if (!SERVER_ID.matcher(name).matches()) {
                logger.warn("Ignoring invalid allowed backend ID '{}'.", configuredName);
                continue;
            }
            Optional<RegisteredServer> registered = proxy.getServer(name);
            if (registered.isPresent()) {
                destinations.put(name, registered.get());
            } else {
                logger.warn("Allowed backend '{}' is not present in velocity.toml [servers].", name);
            }
        }
        if (destinations.isEmpty()) {
            disable("None of allowed-servers exist in velocity.toml [servers].");
            return;
        }
        allowedServers = Map.copyOf(destinations);

        if (!fallbackServerName.isEmpty() && proxy.getServer(fallbackServerName).isEmpty()) {
            logger.warn("Fallback backend '{}' is not configured; the proxy's own initial-server choice will be preserved.",
                    fallbackServerName);
        }

        timeoutExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SuperpowerPlugin-OriginCookieTimeout");
            thread.setDaemon(true);
            return thread;
        });

        logger.info("Family origin routing enabled. Cookie '{}:{}'; accepted backend IDs: {}.",
                namespace, key, allowedServers.keySet());
    }

    @Subscribe
    public EventTask onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        if (!enabled || timeoutExecutor == null) {
            return EventTask.withContinuation(Continuation::resume);
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        return EventTask.withContinuation(continuation -> {
            PendingRequest pending = new PendingRequest(event, player, continuation);
            PendingRequest existing = pendingRequests.putIfAbsent(playerId, pending);
            if (existing != null) {
                logger.warn("A family origin-cookie request is already pending for {}; preserving normal routing.",
                        player.getUsername());
                continuation.resume();
                return;
            }

            pending.timeout = timeoutExecutor.schedule(
                    () -> finishWithoutCookie(playerId, pending, "cookie request timed out"),
                    requestTimeoutMillis,
                    TimeUnit.MILLISECONDS
            );

            try {
                player.requestCookie(cookieKey);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                finishWithoutCookie(playerId, pending,
                        "cookie request unavailable: " + exception.getMessage());
            }
        });
    }

    @Subscribe
    public void onCookieReceive(CookieReceiveEvent event) {
        if (!enabled || cookieKey == null || !cookieKey.equals(event.getOriginalKey())) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        PendingRequest pending = pendingRequests.get(playerId);

        // Consume this private routing cookie even if the initial-server selection
        // already timed out. A stale cookie must not be forwarded to a backend.
        event.setResult(CookieReceiveEvent.ForwardResult.handled());

        String backendId = decodeServerId(event.getOriginalData());
        RegisteredServer destination = backendId == null ? null : allowedServers.get(backendId);

        // Cookies have no delete operation; overwrite with an empty payload.
        try {
            player.storeCookie(cookieKey, EMPTY_COOKIE);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            if (logDecisions) {
                logger.debug("Could not clear consumed origin cookie for {}: {}",
                        player.getUsername(), exception.getMessage());
            }
        }

        if (pending == null) return;

        if (destination != null) {
            finishWithDestination(playerId, pending, destination, backendId);
        } else {
            finishWithoutCookie(playerId, pending,
                    backendId == null
                            ? "no valid origin cookie"
                            : "cookie names an unapproved or unavailable backend");
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        PendingRequest pending = pendingRequests.remove(event.getPlayer().getUniqueId());
        if (pending != null && pending.finished.compareAndSet(false, true)) {
            cancelTimeout(pending);
            resumeOnce(pending);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        enabled = false;
        for (Map.Entry<UUID, PendingRequest> entry : new ArrayList<>(pendingRequests.entrySet())) {
            PendingRequest pending = entry.getValue();
            if (pendingRequests.remove(entry.getKey(), pending)
                    && pending.finished.compareAndSet(false, true)) {
                cancelTimeout(pending);
                resumeOnce(pending);
            }
        }
        if (timeoutExecutor != null) {
            timeoutExecutor.shutdownNow();
            timeoutExecutor = null;
        }
    }

    private void finishWithDestination(UUID playerId, PendingRequest pending,
                                       RegisteredServer destination, String backendId) {
        if (!pending.finished.compareAndSet(false, true)) return;
        pendingRequests.remove(playerId, pending);
        cancelTimeout(pending);
        pending.event.setInitialServer(destination);
        if (logDecisions) {
            logger.info("Routing {} to original backend '{}' using the transfer cookie.",
                    pending.player.getUsername(), backendId);
        }
        resumeOnce(pending);
    }

    private void finishWithoutCookie(UUID playerId, PendingRequest pending, String reason) {
        if (!pending.finished.compareAndSet(false, true)) return;
        pendingRequests.remove(playerId, pending);
        cancelTimeout(pending);
        preserveFallback(pending.event);
        if (logDecisions) {
            logger.debug("No approved origin route for {} ({}); preserving normal proxy routing.",
                    pending.player.getUsername(), reason);
        }
        resumeOnce(pending);
    }

    private void preserveFallback(PlayerChooseInitialServerEvent event) {
        if (event.getInitialServer().isPresent()
                || fallbackServerName == null || fallbackServerName.isEmpty()) return;
        proxy.getServer(fallbackServerName).ifPresent(event::setInitialServer);
    }

    private void resumeOnce(PendingRequest pending) {
        try {
            pending.continuation.resume();
        } catch (IllegalStateException ignored) {
            // A disconnect, shutdown, timeout, and cookie response can race.
        }
    }

    private void cancelTimeout(PendingRequest pending) {
        ScheduledFuture<?> timeout = pending.timeout;
        if (timeout != null) timeout.cancel(false);
    }

    private Properties loadConfig() throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.properties");
        if (Files.notExists(file)) {
            try (InputStream input = getClass().getResourceAsStream("/config.properties")) {
                if (input == null) throw new IOException("Bundled config.properties is missing.");
                Files.copy(input, file);
            }
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private void disable(String reason) {
        enabled = false;
        logger.error("Family origin routing disabled: {}", reason);
    }

    private static List<String> splitNames(String input) {
        List<String> result = new ArrayList<>();
        if (input == null || input.isBlank()) return result;
        for (String part : input.split(",")) if (!part.isBlank()) result.add(part.trim());
        return result;
    }

    private static String normalize(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }

    private static String decodeServerId(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 32) return null;
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            String normalized = normalize(decoded);
            return SERVER_ID.matcher(normalized).matches() ? normalized : null;
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private static final class PendingRequest {
        private final PlayerChooseInitialServerEvent event;
        private final Player player;
        private final Continuation continuation;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile ScheduledFuture<?> timeout;

        private PendingRequest(PlayerChooseInitialServerEvent event, Player player,
                               Continuation continuation) {
            this.event = event;
            this.player = player;
            this.continuation = continuation;
        }
    }
}
