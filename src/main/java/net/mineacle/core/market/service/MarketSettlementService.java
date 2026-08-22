package net.mineacle.core.market.service;

import net.mineacle.core.Core;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.market.model.MarketTransaction;
import net.mineacle.core.market.storage.MarketTransactionStorage;
import net.mineacle.core.orders.service.OrderService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable settlement coordinator shared by Sell, Orders and Auction House.
 *
 * <p>R3C establishes the journal and idempotent payout bridge before automatic
 * item removal is enabled. The next pass adds the playerdata source marker and
 * begins creating PREPARED transactions from /sell.</p>
 */
public final class MarketSettlementService {

    private static final long PAYOUT_WAIT_MILLIS = 10_000L;
    private static final long RECOVERY_RETRY_TICKS = 20L;

    private final Core core;
    private final MarketTransactionStorage storage;
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

    /**
     * Execution additionally requires Orders to be bound. Keeping this
     * separate from storage health lets the Market module initialize before
     * the Orders module without reporting a false corruption state.
     */
    public synchronized boolean readyForExecution() {
        return healthy()
                && orderService != null
                && transactions.values().stream()
                .noneMatch(this::blocksNewSettlement);
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
     * Creates the durable PREPARED barrier. The caller must not remove player
     * items unless this succeeds.
     */
    @SuppressWarnings("unused")
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

    /** Durable state transition used by the source/order settlement phases. */
    @SuppressWarnings("unused")
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

    /**
     * After Order mutations are durable, materialize prepaid escrow plus the
     * true server-issuance portion into the zero-balance bridge and transfer
     * one exact combined payout to the seller.
     */
    @SuppressWarnings("unused")
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
                case PREPARED, SOURCE_REMOVED -> {
                    /*
                     * R3D adds the playerdata source marker that proves whether
                     * the item removal reached durable player state. Until that
                     * marker exists, never guess or mutate these phases.
                     */
                }
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

        /*
         * PENDING means durableMarketTransfer already applied the balance
         * mutation in memory and is only waiting for its checkpoint to reach
         * disk. Never fund the reserve again in this state; just retry the same
         * transaction id so EconomyService can finish the existing snapshot.
         */
        if (durability
                == EconomyService.MarketTransactionDurability.UNKNOWN) {
            long reserveBalance =
                    economy.getBalanceCents(
                            MarketAccounts.SETTLEMENT_RESERVE
                    );

            /*
             * New economy accounts inherit the configured starting balance.
             * The internal bridge must instead begin at exact zero.
             */
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

            /*
             * If the reserve is larger than this one transaction requires, do
             * not guess which journal owns the extra money.
             */
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
            case PERSISTENCE_PENDING, BUSY -> {
                scheduleRecovery();
                yield false;
            }
            case DISABLED -> {
                scheduleRecovery();
                yield false;
            }
            case INVALID,
                 INSUFFICIENT_FUNDS,
                 RECIPIENT_BALANCE_LIMIT -> {
                quarantine(
                        transaction,
                        "economy-" + status.name()
                                .toLowerCase(java.util.Locale.ROOT)
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
        return true;
    }

    private void quarantine(
            MarketTransaction transaction,
            String reason
    ) {
        MarketTransaction quarantined =
                transaction.quarantine(reason);

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
