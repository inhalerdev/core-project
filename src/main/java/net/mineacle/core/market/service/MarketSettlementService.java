package net.mineacle.core.market.service;

import net.mineacle.core.Core;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.market.model.MarketTransaction;
import net.mineacle.core.market.storage.MarketTransactionStorage;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable settlement coordinator shared by Sell, Orders and Auction House.
 */
public final class MarketSettlementService {

    public enum ExecutionStatus {
        COMPLETED,
        PENDING,
        SAFE_FAILURE
    }

    private static final long PAYOUT_WAIT_MILLIS = 10_000L;
    private static final long RECOVERY_RETRY_TICKS = 20L;

    private final Core core;
    private final MarketTransactionStorage storage;
    private final MarketSourceMarker sourceMarker;
    private final Map<UUID, MarketTransaction> transactions =
            new LinkedHashMap<>();
    private final List<String> storageProblems =
            new ArrayList<>();

    private OrderService orderService;
    private boolean storageHealthy;
    private boolean retryScheduled;

    public MarketSettlementService(Core core) {
        this.core = core;
        this.storage = new MarketTransactionStorage(core);
        this.sourceMarker = new MarketSourceMarker(core);
        load();
    }

    public synchronized void bindOrders(OrderService service) {
        orderService = service;
        recoverAll();
    }

    public synchronized void unbindOrders(OrderService service) {
        if (orderService == service) {
            orderService = null;
        }
    }

    public synchronized boolean healthy() {
        if (!storageHealthy) {
            return false;
        }

        for (MarketTransaction transaction : transactions.values()) {
            if (transaction.state()
                    == MarketTransaction.State.QUARANTINED) {
                return false;
            }
        }

        return true;
    }

    public synchronized boolean executionBlocked() {
        return !healthy()
                || orderService == null
                || transactions.values().stream()
                .anyMatch(this::blocksNewSettlement);
    }

    public synchronized List<String> recoverySummaries() {
        List<String> result =
                new ArrayList<>();

        for (String problem : storageProblems) {
            result.add(
                    "STORAGE BLOCKED • " + problem
            );
        }

        for (MarketTransaction transaction
                : transactions.values()) {
            result.add(
                    shortId(transaction.transactionId())
                            + " "
                            + transaction.state().name()
                            + " • seller "
                            + shortId(transaction.sellerId())
                            + " • orders "
                            + transaction.orderPayoutCents()
                            + "c • server "
                            + transaction.serverPayoutCents()
                            + "c • total "
                            + transaction.totalPayoutCents()
                            + "c"
                            + (transaction.quarantineReason().isBlank()
                            ? ""
                            : " • " + transaction.quarantineReason())
            );
        }

        return List.copyOf(result);
    }

    /**
     * Owns a direct-player source transaction from PREPARED through payout.
     *
     * <p>PREPARED is durable before the player's live inventory is mutated.
     * SOURCE_REMOVED is written into playerdata before the shared journal may
     * advance, providing restart proof that the source stacks cannot return.</p>
     */
    public synchronized ExecutionStatus executePlayerSource(
            Player player,
            MarketTransaction transaction
    ) {
        if (player == null
                || transaction == null
                || !player.getUniqueId().equals(
                transaction.sellerId()
        )
                || executionBlocked()) {
            return ExecutionStatus.SAFE_FAILURE;
        }

        if (!begin(transaction)) {
            return ExecutionStatus.SAFE_FAILURE;
        }

        if (!sourceStillMatches(
                player,
                transaction
        )) {
            abortPrepared(transaction);
            return ExecutionStatus.SAFE_FAILURE;
        }

        removeSource(
                player,
                transaction
        );

        if (!sourceMarker.persistSourceRemoved(
                player,
                transaction.transactionId()
        )) {
            restoreSource(
                    player,
                    transaction
            );
            sourceMarker.clearInMemory(player);

            try {
                player.saveData();
            } catch (RuntimeException exception) {
                quarantine(
                        transaction,
                        "playerdata-source-rollback-uncertain"
                );
                return ExecutionStatus.PENDING;
            }

            abortPrepared(transaction);
            return ExecutionStatus.SAFE_FAILURE;
        }

        MarketTransaction sourceRemoved =
                advance(
                        transaction,
                        MarketTransaction.State.SOURCE_REMOVED
                );

        if (sourceRemoved == null) {
            return ExecutionStatus.PENDING;
        }

        if (!commitOrdersAndContinue(
                sourceRemoved
        )) {
            return ExecutionStatus.PENDING;
        }

        return transactions.containsKey(
                transaction.transactionId()
        )
                ? ExecutionStatus.PENDING
                : ExecutionStatus.COMPLETED;
    }

    public synchronized void recoverPlayer(
            Player player
    ) {
        if (player == null) {
            return;
        }

        MarketSourceMarker.Marker marker =
                sourceMarker.read(player);
        MarketTransaction sellerTransaction =
                unresolvedForSeller(
                        player.getUniqueId()
                );

        if (sellerTransaction == null) {
            if (marker != null) {
                sourceMarker.clearAndPersist(
                        player,
                        marker.transactionId()
                );
            }
            return;
        }

        if (sellerTransaction.state()
                != MarketTransaction.State.PREPARED) {
            recoverAll();

            if (!transactions.containsKey(
                    sellerTransaction.transactionId()
            )) {
                sourceMarker.clearAndPersist(
                        player,
                        sellerTransaction.transactionId()
                );
            }
            return;
        }

        if (marker == null
                || !marker.transactionId().equals(
                sellerTransaction.transactionId()
        )
                || marker.phase()
                != MarketSourceMarker.Phase.SOURCE_REMOVED) {
            /*
             * No durable SOURCE_REMOVED proof means playerdata still owns the
             * source stacks. PREPARED has not touched Orders or economy, so the
             * journal can be safely abandoned.
             */
            abortPrepared(sellerTransaction);

            if (marker != null) {
                sourceMarker.clearAndPersist(
                        player,
                        marker.transactionId()
                );
            }
            return;
        }

        MarketTransaction sourceRemoved =
                advance(
                        sellerTransaction,
                        MarketTransaction.State.SOURCE_REMOVED
                );

        if (sourceRemoved != null) {
            commitOrdersAndContinue(
                    sourceRemoved
            );
        }

        if (!transactions.containsKey(
                sellerTransaction.transactionId()
        )) {
            sourceMarker.clearAndPersist(
                    player,
                    sellerTransaction.transactionId()
            );
        }
    }

    public synchronized boolean begin(
            MarketTransaction transaction
    ) {
        if (transaction == null
                || transaction.state()
                != MarketTransaction.State.PREPARED
                || !healthy()
                || transactions.containsKey(
                transaction.transactionId()
        )
                || transactions.values().stream()
                .anyMatch(this::blocksNewSettlement)) {
            return false;
        }

        if (!storage.save(transaction)) {
            storageHealthy = false;
            storageProblems.add(
                    "could not durably begin transaction "
                            + transaction.transactionId()
            );
            return false;
        }

        transactions.put(
                transaction.transactionId(),
                transaction
        );
        return true;
    }

    public synchronized MarketTransaction advance(
            MarketTransaction transaction,
            MarketTransaction.State next
    ) {
        if (transaction == null
                || next == null) {
            return null;
        }

        MarketTransaction current =
                transactions.get(
                        transaction.transactionId()
                );

        if (current == null
                || current.state()
                != transaction.state()) {
            return null;
        }

        MarketTransaction updated =
                current.withState(next);

        if (!storage.save(updated)) {
            storageHealthy = false;
            storageProblems.add(
                    "could not advance transaction "
                            + transaction.transactionId()
                            + " to "
                            + next
            );
            return null;
        }

        transactions.put(
                updated.transactionId(),
                updated
        );
        return updated;
    }

    public synchronized boolean settlePayout(
            MarketTransaction transaction
    ) {
        if (transaction == null
                || transaction.state()
                != MarketTransaction.State.ORDERS_COMMITTED) {
            return false;
        }

        MarketTransaction payoutStarted =
                advance(
                        transaction,
                        MarketTransaction.State.PAYOUT_STARTED
                );

        if (payoutStarted == null) {
            return false;
        }

        return recoverPayout(payoutStarted);
    }

    public synchronized void recoverAll() {
        if (!storageHealthy
                || orderService == null) {
            return;
        }

        for (MarketTransaction transaction
                : List.copyOf(transactions.values())) {
            switch (transaction.state()) {
                case PREPARED -> {
                    Player online =
                            Bukkit.getPlayer(
                                    transaction.sellerId()
                            );

                    if (online != null
                            && online.isOnline()) {
                        recoverPlayer(online);
                    }
                }
                case SOURCE_REMOVED ->
                        commitOrdersAndContinue(
                                transaction
                        );
                case ORDERS_COMMITTED -> {
                    MarketTransaction payoutStarted =
                            advance(
                                    transaction,
                                    MarketTransaction.State.PAYOUT_STARTED
                            );

                    if (payoutStarted != null) {
                        recoverPayout(payoutStarted);
                    }
                }
                case PAYOUT_STARTED ->
                        recoverPayout(transaction);
                case PAID ->
                        finalizePaid(transaction);
                case COMMITTED ->
                        deleteCommitted(transaction);
                case QUARANTINED -> {
                    // Requires explicit operator review.
                }
            }
        }
    }

    public synchronized void shutdown() {
        retryScheduled = false;
    }

    private boolean commitOrdersAndContinue(
            MarketTransaction transaction
    ) {
        if (transaction == null
                || transaction.state()
                != MarketTransaction.State.SOURCE_REMOVED
                || orderService == null) {
            return false;
        }

        if (!orderService.commitAutomaticFills(
                transaction
        )) {
            scheduleRecovery();
            return false;
        }

        MarketTransaction ordersCommitted =
                advance(
                        transaction,
                        MarketTransaction.State.ORDERS_COMMITTED
                );

        if (ordersCommitted == null) {
            scheduleRecovery();
            return false;
        }

        return settlePayout(
                ordersCommitted
        );
    }

    private void load() {
        MarketTransactionStorage.LoadResult result =
                storage.load();
        storageHealthy = result.healthy();
        storageProblems.clear();
        storageProblems.addAll(result.problems());
        transactions.clear();

        for (MarketTransaction transaction
                : result.transactions()) {
            transactions.put(
                    transaction.transactionId(),
                    transaction
            );
        }

        if (!storageHealthy) {
            core.getLogger().severe(
                    "[Market] Settlement journal health check failed; "
                            + "automatic cross-market execution is disabled"
            );
        } else if (!transactions.isEmpty()) {
            core.getLogger().warning(
                    "[Market] Loaded "
                            + transactions.size()
                            + " unresolved settlement transaction(s)"
            );
        }
    }

    private boolean recoverPayout(
            MarketTransaction transaction
    ) {
        EconomyService economy =
                EconomyModule.economyService();

        if (economy == null
                || !economy.enabled()
                || transaction.totalPayoutCents() <= 0L) {
            scheduleRecovery();
            return false;
        }

        EconomyService.MarketTransactionDurability durability =
                economy.marketTransactionDurability(
                        transaction.transactionId()
                );

        if (durability
                == EconomyService.MarketTransactionDurability.COMMITTED) {
            return markPaid(transaction);
        }

        long required =
                transaction.totalPayoutCents();

        if (durability
                == EconomyService.MarketTransactionDurability.UNKNOWN) {
            long reserveBalance =
                    economy.getBalanceCents(
                            MarketAccounts.SETTLEMENT_RESERVE
                    );

            if (!economy.hasAccount(
                    MarketAccounts.SETTLEMENT_RESERVE
            ) && reserveBalance > 0L) {
                if (!economy.take(
                        MarketAccounts.SETTLEMENT_RESERVE,
                        reserveBalance
                )) {
                    quarantine(
                            transaction,
                            "could-not-zero-new-settlement-reserve"
                    );
                    return false;
                }
                reserveBalance = 0L;
            }

            if (reserveBalance > required) {
                quarantine(
                        transaction,
                        "settlement-reserve-exceeds-transaction"
                );
                return false;
            }

            long deficit = required - reserveBalance;

            if (deficit > 0L
                    && !economy.tryGive(
                    MarketAccounts.SETTLEMENT_RESERVE,
                    deficit
            )) {
                quarantine(
                        transaction,
                        "could-not-materialize-settlement-reserve"
                );
                return false;
            }
        }

        EconomyService.MarketTransferStatus status =
                economy.durableMarketTransfer(
                        transaction.transactionId(),
                        MarketAccounts.SETTLEMENT_RESERVE,
                        transaction.sellerId(),
                        required,
                        PAYOUT_WAIT_MILLIS
                );

        return switch (status) {
            case SUCCESS, ALREADY_COMMITTED ->
                    markPaid(transaction);
            case PERSISTENCE_PENDING, BUSY, DISABLED -> {
                scheduleRecovery();
                yield false;
            }
            case INVALID,
                 INSUFFICIENT_FUNDS,
                 RECIPIENT_BALANCE_LIMIT -> {
                quarantine(
                        transaction,
                        "economy-" + status.name()
                                .toLowerCase(
                                        java.util.Locale.ROOT
                                )
                );
                yield false;
            }
        };
    }

    private boolean markPaid(
            MarketTransaction transaction
    ) {
        MarketTransaction current =
                transactions.get(
                        transaction.transactionId()
                );

        if (current == null) {
            return false;
        }

        if (current.state()
                == MarketTransaction.State.PAID) {
            return finalizePaid(current);
        }

        if (current.state()
                != MarketTransaction.State.PAYOUT_STARTED
                && current.state()
                != MarketTransaction.State.ORDERS_COMMITTED) {
            return false;
        }

        MarketTransaction paid =
                current.withState(
                        MarketTransaction.State.PAID
                );

        if (!storage.save(paid)) {
            storageHealthy = false;
            storageProblems.add(
                    "seller was paid but PAID state could not persist for "
                            + paid.transactionId()
            );
            return false;
        }

        transactions.put(
                paid.transactionId(),
                paid
        );
        return finalizePaid(paid);
    }

    private boolean finalizePaid(
            MarketTransaction transaction
    ) {
        MarketTransaction committed =
                transaction.withState(
                        MarketTransaction.State.COMMITTED
                );

        if (!storage.save(committed)) {
            storageHealthy = false;
            storageProblems.add(
                    "could not commit paid transaction "
                            + transaction.transactionId()
            );
            return false;
        }

        transactions.put(
                committed.transactionId(),
                committed
        );
        return deleteCommitted(committed);
    }

    private boolean deleteCommitted(
            MarketTransaction transaction
    ) {
        if (!storage.delete(
                transaction.transactionId()
        )) {
            return false;
        }

        transactions.remove(
                transaction.transactionId()
        );

        Player online =
                Bukkit.getPlayer(
                        transaction.sellerId()
                );

        if (online != null
                && online.isOnline()) {
            sourceMarker.clearAndPersist(
                    online,
                    transaction.transactionId()
            );
        }

        return true;
    }

    private void abortPrepared(
            MarketTransaction transaction
    ) {
        if (transaction == null
                || transaction.state()
                != MarketTransaction.State.PREPARED) {
            return;
        }

        if (!storage.delete(
                transaction.transactionId()
        )) {
            storageHealthy = false;
            storageProblems.add(
                    "could not abort PREPARED transaction "
                            + transaction.transactionId()
            );
            return;
        }

        transactions.remove(
                transaction.transactionId()
        );
    }

    private MarketTransaction unresolvedForSeller(
            UUID sellerId
    ) {
        if (sellerId == null) {
            return null;
        }

        for (MarketTransaction transaction
                : transactions.values()) {
            if (sellerId.equals(
                    transaction.sellerId()
            )
                    && blocksNewSettlement(
                    transaction
            )) {
                return transaction;
            }
        }

        return null;
    }

    private boolean sourceStillMatches(
            Player player,
            MarketTransaction transaction
    ) {
        PlayerInventory inventory =
                player.getInventory();

        for (MarketTransaction.SourceItem source
                : transaction.sourceItems()) {
            ItemStack current =
                    inventory.getItem(
                            source.slot()
                    );
            ItemStack expected =
                    source.item();

            if (current == null
                    || current.getType().isAir()
                    || current.getAmount()
                    != expected.getAmount()
                    || !current.isSimilar(expected)) {
                return false;
            }
        }

        return true;
    }

    private void removeSource(
            Player player,
            MarketTransaction transaction
    ) {
        PlayerInventory inventory =
                player.getInventory();

        for (MarketTransaction.SourceItem source
                : transaction.sourceItems()) {
            inventory.setItem(
                    source.slot(),
                    null
            );
        }
    }

    private void restoreSource(
            Player player,
            MarketTransaction transaction
    ) {
        PlayerInventory inventory =
                player.getInventory();

        for (MarketTransaction.SourceItem source
                : transaction.sourceItems()) {
            inventory.setItem(
                    source.slot(),
                    source.item()
            );
        }
    }

    private void quarantine(
            MarketTransaction transaction,
            String reason
    ) {
        MarketTransaction current =
                transactions.get(
                        transaction.transactionId()
                );
        MarketTransaction quarantined =
                (current == null
                        ? transaction
                        : current)
                        .quarantine(reason);

        if (!storage.save(quarantined)) {
            storageHealthy = false;
            storageProblems.add(
                    "could not persist quarantine for "
                            + transaction.transactionId()
            );
            return;
        }

        transactions.put(
                quarantined.transactionId(),
                quarantined
        );
        core.getLogger().severe(
                "[Market] Quarantined settlement "
                        + transaction.transactionId()
                        + " — "
                        + reason
        );
    }

    private void scheduleRecovery() {
        if (retryScheduled) {
            return;
        }

        retryScheduled = true;
        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    synchronized (MarketSettlementService.this) {
                        retryScheduled = false;
                    }
                    recoverAll();
                },
                RECOVERY_RETRY_TICKS
        );
    }

    private boolean blocksNewSettlement(
            MarketTransaction transaction
    ) {
        return transaction.state()
                != MarketTransaction.State.COMMITTED;
    }

    private String shortId(UUID id) {
        String value = id.toString();
        return value.substring(0, 8);
    }
}
