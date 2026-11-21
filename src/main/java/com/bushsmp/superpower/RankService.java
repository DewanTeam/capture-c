package com.bushsmp.superpower;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Handles paid rank grants. LuckPerms storage operations are asynchronous. */
public final class RankService {
    private final SuperpowerPlugin plugin;
    private final ConfigManager config;
    private final Economy economy;
    private final LuckPerms luckPerms;
    private final Set<UUID> lockedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, RankChange> pendingAppliedChanges = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown;

    public RankService(SuperpowerPlugin plugin, ConfigManager config, Economy economy, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.config = config;
        this.economy = economy;
        this.luckPerms = luckPerms;
    }

    public void purchase(Player buyer, String rankId) {
        transact(buyer, buyer, rankId, false);
    }

    public void gift(Player buyer, Player recipient, String rankId) {
        if (buyer.getUniqueId().equals(recipient.getUniqueId())) {
            buyer.sendMessage(color("&cYou cannot gift a rank to yourself. Use /rankshop buy " + rankId + " instead."));
            return;
        }
        transact(buyer, recipient, rankId, true);
    }

    private void transact(Player buyer, Player recipient, String rawRankId, boolean gift) {
        String rankId = rawRankId == null ? "" : rawRankId.toLowerCase(Locale.ROOT);
        if (!config.getRankIds().contains(rankId)) {
            buyer.sendMessage(color("&cUnknown or disabled rank. Use /rankshop to see available ranks."));
            return;
        }

        String groupName = config.getRankGroup(rankId);
        double price = config.getRankPrice(rankId);
        if (groupName.isBlank() || groupName.equalsIgnoreCase("CHANGE_ME")) {
            buyer.sendMessage(color("&cThis rank is not configured yet. An administrator must set its LuckPerms group in config.yml."));
            return;
        }
        if (!Double.isFinite(price) || price <= 0.0D) {
            buyer.sendMessage(color("&cThis rank has an invalid price configuration. Contact an administrator."));
            return;
        }
        if (!economy.has(buyer, price)) {
            buyer.sendMessage(color("&cYou do not have enough money. &7Price: &e" + format(price)));
            return;
        }

        UUID buyerId = buyer.getUniqueId();
        UUID recipientId = recipient.getUniqueId();
        if (!lockedPlayers.add(buyerId)) {
            buyer.sendMessage(color("&cYou already have a rank transaction in progress."));
            return;
        }
        // A purchase has the buyer and recipient as the same player, so acquire
        // that player's lock only once. A gift locks both distinct players.
        if (!buyerId.equals(recipientId) && !lockedPlayers.add(recipientId)) {
            lockedPlayers.remove(buyerId);
            buyer.sendMessage(color("&cThe recipient already has a rank transaction in progress."));
            return;
        }

        buyer.sendMessage(color("&7Checking LuckPerms and applying &e" + config.getRankName(rankId) + "&7..."));
        applyRank(recipientId, rankId, groupName).whenComplete((change, error) -> {
            if (error != null) {
                if (shuttingDown || !plugin.isEnabled()) {
                    release(buyerId, recipientId);
                    plugin.getLogger().log(Level.WARNING,
                            "Rank assignment completed with an error while the plugin was shutting down for " + recipientId,
                            unwrap(error));
                    return;
                }
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        release(buyerId, recipientId);
                        buyer.sendMessage(color("&cThe rank could not be applied. No money was taken. &7Check that the LuckPerms group '" + groupName + "' exists and that this is an upgrade."));
                        plugin.getLogger().log(Level.WARNING, "Failed to apply LuckPerms rank " + rankId + " to " + recipientId, unwrap(error));
                    });
                } catch (RuntimeException schedulingError) {
                    plugin.getLogger().log(Level.WARNING,
                            "Could not schedule rank-failure feedback because the plugin is shutting down.",
                            schedulingError);
                }
                return;
            }

            // Keep the saved LuckPerms mutation visible to onDisable until the
            // economy and transaction record are committed on the main thread.
            pendingAppliedChanges.put(recipientId, change);
            if (shuttingDown || !plugin.isEnabled()) {
                rollbackPendingAfterShutdown(recipientId, change);
                return;
            }

            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (shuttingDown || !plugin.isEnabled()) {
                        rollbackPendingAfterShutdown(recipientId, change);
                        return;
                    }
                    // If shutdown already claimed this mutation, never charge.
                    if (!pendingAppliedChanges.remove(recipientId, change)) return;

                    OfflinePlayer payer = Bukkit.getOfflinePlayer(buyerId);
                    EconomyResponse response = economy.withdrawPlayer(payer, price);
                    if (!response.transactionSuccess()) {
                        rollback(change).whenComplete((ignored, rollbackError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            release(buyerId, recipientId);
                            if (rollbackError != null) {
                                plugin.getLogger().log(Level.SEVERE, "Rank rollback failed after an unsuccessful payment for " + recipientId, unwrap(rollbackError));
                                buyer.sendMessage(color("&cPayment failed and automatic rank rollback needs administrator attention. Contact staff."));
                            } else {
                                buyer.sendMessage(color("&cPayment failed: " + response.errorMessage + ". No money was taken."));
                            }
                        }));
                        return;
                    }

                    String previousStoredRank = plugin.getStoredRank(recipientId);
                    boolean rankSaved = plugin.storeRank(recipientId, rankId);
                    boolean transactionSaved = rankSaved && plugin.appendRankTransaction(
                            gift ? "gift" : "purchase", buyerId, recipientId, rankId, price);

                    if (!transactionSaved) {
                        plugin.getLogger().severe("Could not persist rank transaction for payer " + buyerId + " and recipient " + recipientId + ". Starting rollback and refund.");
                        if (previousStoredRank == null) plugin.removeStoredRank(recipientId);
                        else plugin.storeRank(recipientId, previousStoredRank);
                        rollback(change).whenComplete((ignored, rollbackError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (rollbackError == null) {
                                EconomyResponse refund = economy.depositPlayer(payer, price);
                                if (!refund.transactionSuccess()) {
                                    plugin.getLogger().severe("Refund failed for " + buyerId + " after rank persistence failure: " + refund.errorMessage);
                                    buyer.sendMessage(color("&cThe rank transaction needs administrator attention. Please contact staff."));
                                } else {
                                    buyer.sendMessage(color("&cThe transaction could not be saved. Your payment was refunded and the rank was reverted."));
                                }
                            } else {
                                plugin.getLogger().log(Level.SEVERE, "Rank rollback failed after persistence failure; payment was retained and rank may already be applied.", unwrap(rollbackError));
                                buyer.sendMessage(color("&cYour rank was applied and payment processed, but transaction storage failed. Please contact an administrator."));
                            }
                            release(buyerId, recipientId);
                        }));
                        return;
                    }

                    release(buyerId, recipientId);
                    String rankName = color(config.getRankName(rankId));
                    String success = gift
                            ? "&aYou gifted " + rankName + " &ato &e" + recipient.getName() + "&a for &e" + format(price) + "."
                            : "&aYou purchased " + rankName + " &afor &e" + format(price) + ".";
                    buyer.sendMessage(color(success));
                    if (gift) {
                        recipient.sendMessage(color("&aYou received the " + rankName + " &arank from &e" + buyer.getName() + "&a!"));
                        recipient.sendMessage(color("&7This rank adds &e+" + config.getRankDurationBonus(rankId) + " seconds &7to timed superpower effects."));
                    } else {
                        buyer.sendMessage(color("&7This rank adds &e+" + config.getRankDurationBonus(rankId) + " seconds &7to timed superpower effects."));
                    }
                });
            } catch (RuntimeException schedulingError) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not schedule the economy step for rank " + rankId + "; rolling the LuckPerms change back.",
                        schedulingError);
                rollbackPendingAfterShutdown(recipientId, change);
            }
        });
    }

    /** Prevents a saved LuckPerms change from being left unpaid if shutdown interrupts the main-thread callback. */
    public void shutdown() {
        shuttingDown = true;
        for (Map.Entry<UUID, RankChange> entry : pendingAppliedChanges.entrySet()) {
            rollbackPendingAfterShutdown(entry.getKey(), entry.getValue());
        }
    }

    private void rollbackPendingAfterShutdown(UUID recipientId, RankChange change) {
        if (!pendingAppliedChanges.remove(recipientId, change)) return;
        rollback(change).whenComplete((ignored, rollbackError) -> {
            if (rollbackError != null) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not roll back an unpaid rank assignment during shutdown for " + recipientId,
                        unwrap(rollbackError));
            } else {
                plugin.getLogger().warning(
                        "Rolled back an unpaid rank assignment because SuperpowerPlugin was shutting down for " + recipientId);
            }
        });
    }

    private CompletableFuture<RankChange> applyRank(UUID userId, String rankId, String groupName) {
        return luckPerms.getGroupManager().loadGroup(groupName).thenCompose(group -> {
            if (group.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms group does not exist: " + groupName));
            }

            return luckPerms.getUserManager().loadUser(userId).thenCompose(user -> {
                String previousPrimary = user.getPrimaryGroup();
                Set<InheritanceNode> previousPaidNodes = new HashSet<>();
                for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) {
                    if (config.getRankOrderForGroup(node.getGroupName()) >= 0) {
                        previousPaidNodes.add(node);
                    }
                }

                int currentOrder = getHighestPaidRankOrder(user);
                int targetOrder = config.getRankOrder(rankId);
                if (hasPlayerRank(user) && currentOrder >= targetOrder) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "Player already has this rank or a higher configured rank"));
                }

                RankChange change = new RankChange(userId, groupName, previousPrimary, previousPaidNodes);
                try {
                    for (InheritanceNode node : previousPaidNodes) {
                        DataMutateResult removeResult = user.data().remove(node);
                        if (!removeResult.wasSuccessful()) {
                            throw new IllegalStateException("LuckPerms could not remove an existing paid-rank group: " + node.getGroupName() + " (" + removeResult + ")");
                        }
                    }

                    DataMutateResult addResult = user.data().add(InheritanceNode.builder(groupName).build());
                    if (!addResult.wasSuccessful()) {
                        throw new IllegalStateException("LuckPerms rejected the rank group assignment: " + addResult);
                    }

                    DataMutateResult primaryResult = user.setPrimaryGroup(groupName);
                    if (!primaryResult.wasSuccessful()) {
                        throw new IllegalStateException("LuckPerms rejected the primary group update: " + primaryResult);
                    }
                } catch (RuntimeException mutationError) {
                    return restoreThenFail(user, change, mutationError,
                            "Rank assignment failed and the in-memory LuckPerms state could not be restored.");
                }

                CompletableFuture<RankChange> savedResult = new CompletableFuture<>();
                luckPerms.getUserManager().saveUser(user).whenComplete((ignored, saveError) -> {
                    if (saveError == null) {
                        savedResult.complete(change);
                        return;
                    }
                    restoreUserState(user, change).whenComplete((restored, restoreError) -> {
                        if (restoreError != null) {
                            savedResult.completeExceptionally(new IllegalStateException(
                                    "LuckPerms save failed and restoring the previous user state also failed.", unwrap(restoreError)));
                        } else {
                            savedResult.completeExceptionally(unwrap(saveError));
                        }
                    });
                });
                return savedResult;
            });
        });
    }



    private boolean hasPlayerRank(User user) {
        return getHighestPaidRankOrder(user) >= 0;
    }

    private int getHighestPaidRankOrder(User user) {
        int highest = config.getRankOrderForGroup(user.getPrimaryGroup());
        for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) {
            highest = Math.max(highest, config.getRankOrderForGroup(node.getGroupName()));
        }
        return highest;
    }

    private CompletableFuture<RankChange> restoreThenFail(User user, RankChange change, Throwable failure, String rollbackMessage) {
        CompletableFuture<RankChange> result = new CompletableFuture<>();
        restoreUserState(user, change).whenComplete((ignored, restoreError) -> {
            if (restoreError != null) {
                result.completeExceptionally(new IllegalStateException(rollbackMessage, unwrap(restoreError)));
            } else {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private CompletableFuture<Void> restoreUserState(User user, RankChange change) {
        for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) {
            if (!node.getGroupName().equalsIgnoreCase(change.newGroup())) continue;
            DataMutateResult result = user.data().remove(node);
            if (!result.wasSuccessful() && result != DataMutateResult.FAIL_LACKS) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Could not remove temporary paid-rank group " + node.getGroupName() + " while rolling back."));
            }
        }

        for (InheritanceNode oldNode : change.previousPaidNodes()) {
            DataMutateResult result = user.data().add(oldNode);
            if (!result.wasSuccessful() && result != DataMutateResult.FAIL_ALREADY_HAS) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Could not restore previous LuckPerms group " + oldNode.getGroupName()));
            }
        }

        if (change.previousPrimary() != null && !change.previousPrimary().isBlank()) {
            DataMutateResult primaryResult = user.setPrimaryGroup(change.previousPrimary());
            if (!primaryResult.wasSuccessful()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Could not restore previous primary group " + change.previousPrimary()));
            }
        }
        return luckPerms.getUserManager().saveUser(user);
    }

    private CompletableFuture<Void> rollback(RankChange change) {
        return luckPerms.getUserManager().loadUser(change.userId())
                .thenCompose(user -> restoreUserState(user, change));
    }

    private void release(UUID buyer, UUID recipient) {
        lockedPlayers.remove(buyer);
        lockedPlayers.remove(recipient);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String format(double value) {
        return config.formatCurrency(value);
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private record RankChange(UUID userId, String newGroup, String previousPrimary, Set<InheritanceNode> previousPaidNodes) {
        private RankChange {
            previousPaidNodes = Set.copyOf(previousPaidNodes);
        }
    }
}