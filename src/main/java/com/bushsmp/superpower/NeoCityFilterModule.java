package com.bushsmp.superpower;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.regex.Pattern;

/** Optional lightweight chat-name replacement; disabled unless explicitly configured. */
final class NeoCityFilterModule implements Listener {
    private final JavaPlugin plugin;
    private final Pattern targetPattern;
    private final String replacement;
    private final boolean enabled;

    NeoCityFilterModule(JavaPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("chat-filter.enabled", false);
        String target = plugin.getConfig().getString("chat-filter.target", "neocity");
        this.replacement = plugin.getConfig().getString("chat-filter.replacement", "neosh*tty");

        if (target == null || target.isBlank() || replacement == null || replacement.isEmpty()) {
            targetPattern = null;
            plugin.getLogger().warning("NeoCityFilter remains inactive because target or replacement is empty.");
            return;
        }
        targetPattern = Pattern.compile(Pattern.quote(target),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

        if (enabled) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            plugin.getLogger().info("NeoCityFilter chat replacement enabled.");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!enabled || targetPattern == null) return;

        Component filtered = event.message().replaceText(config -> config
                .match(targetPattern)
                .replacement((match, builder) -> builder.content(
                        matchCase(match.group(), replacement))));
        event.message(filtered);
    }

    private static String matchCase(String original, String replacement) {
        if (original.equals(original.toUpperCase(Locale.ROOT))) {
            return replacement.toUpperCase(Locale.ROOT);
        }
        if (original.equals(original.toLowerCase(Locale.ROOT))) {
            return replacement.toLowerCase(Locale.ROOT);
        }
        if (!original.isEmpty() && Character.isUpperCase(original.charAt(0))) {
            return Character.toUpperCase(replacement.charAt(0))
                    + replacement.substring(1).toLowerCase(Locale.ROOT);
        }
        return replacement.toLowerCase(Locale.ROOT);
    }
}
