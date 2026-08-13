package net.mineacle.core.sell.storage;

import net.mineacle.core.Core;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Asynchronous append-only audit ledger for completed server Sell payouts.
 *
 * <p>The payout hot path never performs JDBC work. A sale reserves one bounded
 * in-memory audit slot before EconomyService is credited, then commits the
 * immutable audit record to this queue after the credit succeeds. SQL writes
 * are batched asynchronously in a single database transaction.</p>
 *
 * <p>If the database is unavailable, completed audits remain queued. Once the
 * configured backlog limit is reached, new server sales are rejected before
 * payout rather than creating untracked money.</p>
 */
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlSourceToSinkFlow"})
public final class SellTransactionLedger {

    private static final long DEFAULT_FLUSH_SECONDS = 2L;
    private static final int DEFAULT_MAX_BATCH_SALES = 500;
    private static final int DEFAULT_MAX_PENDING_SALES = 20_000;
    private static final long SHUTDOWN_FLUSH_BUDGET_MILLIS = 5_000L;
    private static final long FAILURE_WARNING_INTERVAL_NANOS =
            30_000_000_000L;

    private final Core core;
    private final Queue<SaleAudit> pending =
            new ConcurrentLinkedQueue<>();
    private final AtomicInteger reservedSlots =
            new AtomicInteger();
    private final AtomicBoolean flushInFlight =
            new AtomicBoolean();

    private volatile LedgerConfig configuration =
            LedgerConfig.defaults();
    private volatile LedgerConfig schemaReadyFor;
    private volatile boolean accepting = true;
    private volatile boolean started;
    private volatile long lastFailureWarningNanos;

    private BukkitTask flushTask;

    public SellTransactionLedger(Core core) {
        this.core = core;
        reloadConfiguration();
    }

    public synchronized void start() {
        if (started) {
            return;
        }

        started = true;
        accepting = true;
        reschedule();

        core.getServer().getScheduler().runTaskAsynchronously(
                core,
                this::flushSafely
        );
    }

    public synchronized void reloadConfiguration() {
        File sellFile =
                new File(
                        core.getDataFolder(),
                        "sell.yml"
                );
        FileConfiguration sellConfig =
                YamlConfiguration.loadConfiguration(
                        sellFile
                );

        String storage =
                nonBlank(
                        sellConfig.getString(
                                "market.storage",
                                "mysql"
                        ),
                        "mysql"
                );
        boolean databaseConfigured =
                storage.equalsIgnoreCase("mysql")
                        || storage.equalsIgnoreCase("mariadb");

        String databaseConfigName =
                nonBlank(
                        sellConfig.getString(
                                "market.database-config-file",
                                "webprofiles.yml"
                        ),
                        "webprofiles.yml"
                );
        FileConfiguration databaseConfig =
                YamlConfiguration.loadConfiguration(
                        new File(
                                core.getDataFolder(),
                                databaseConfigName
                        )
                );

        String prefix =
                safeIdentifier(
                        sellConfig.getString(
                                "market.table-prefix",
                                "mineacle_sell"
                        )
                );
        long flushSeconds =
                Math.max(
                        1L,
                        sellConfig.getLong(
                                "transactions.flush-seconds",
                                DEFAULT_FLUSH_SECONDS
                        )
                );
        int maxBatch =
                Math.max(
                        1,
                        sellConfig.getInt(
                                "transactions.max-batch-sales",
                                DEFAULT_MAX_BATCH_SALES
                        )
                );
        int maxPending =
                Math.max(
                        maxBatch,
                        sellConfig.getInt(
                                "transactions.max-pending-sales",
                                DEFAULT_MAX_PENDING_SALES
                        )
                );

        configuration =
                new LedgerConfig(
                        nonBlank(
                                databaseConfig.getString(
                                        "database.driver-class",
                                        "com.mysql.cj.jdbc.Driver"
                                ),
                                "com.mysql.cj.jdbc.Driver"
                        ),
                        nonBlank(
                                databaseConfig.getString(
                                        "database.jdbc-url",
                                        "jdbc:mysql://127.0.0.1:3306/mineacle"
                                ),
                                "jdbc:mysql://127.0.0.1:3306/mineacle"
                        ),
                        nonBlank(
                                databaseConfig.getString(
                                        "database.username",
                                        "mineacle_core"
                                ),
                                "mineacle_core"
                        ),
                        databaseConfig.getString(
                                "database.password",
                                ""
                        ),
                        prefix + "_transactions",
                        prefix + "_transaction_items",
                        databaseConfigured,
                        maxBatch,
                        maxPending,
                        Math.max(
                                20L,
                                flushSeconds * 20L
                        )
                );
        schemaReadyFor = null;

        if (started) {
            reschedule();
        }
    }

    /**
     * Reserves audit capacity before any economy credit occurs.
     */
    public Reservation reserve() {
        if (!accepting) {
            return null;
        }

        while (true) {
            int current =
                    reservedSlots.get();

            if (current >= configuration.maxPendingSales()) {
                return null;
            }

            if (reservedSlots.compareAndSet(
                    current,
                    current + 1
            )) {
                return new Reservation(this);
            }
        }
    }

    @SuppressWarnings("unused")
    public int pendingSales() {
        return reservedSlots.get();
    }

    @SuppressWarnings("unused")
    public int maxPendingSales() {
        return configuration.maxPendingSales();
    }

    public synchronized void shutdown() {
        accepting = false;
        started = false;

        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }

        long deadline =
                System.currentTimeMillis()
                        + SHUTDOWN_FLUSH_BUDGET_MILLIS;

        while (!pending.isEmpty()
                && System.currentTimeMillis() < deadline) {
            if (!flushBatchBlocking()) {
                break;
            }
        }

        if (!pending.isEmpty()) {
            core.getLogger().severe(
                    "Sell audit ledger shutdown with "
                            + pending.size()
                            + " completed sale audits still queued"
            );
        }
    }

    private synchronized void reschedule() {
        if (!started) {
            return;
        }

        if (flushTask != null) {
            flushTask.cancel();
        }

        flushTask =
                core.getServer()
                        .getScheduler()
                        .runTaskTimerAsynchronously(
                                core,
                                this::flushSafely,
                                configuration.flushTicks(),
                                configuration.flushTicks()
                        );
    }

    private void flushSafely() {
        if (!flushInFlight.compareAndSet(
                false,
                true
        )) {
            return;
        }

        try {
            flushOneBatch();
        } finally {
            flushInFlight.set(false);
        }
    }

    private void flushOneBatch() {
        List<SaleAudit> batch =
                drainBatch();

        if (batch.isEmpty()) {
            return;
        }

        if (!configuration.databaseConfigured()) {
            requeue(batch);
            return;
        }

        try {
            writeBatch(batch);
            reservedSlots.addAndGet(
                    -batch.size()
            );
        } catch (Exception exception) {
            schemaReadyFor = null;
            requeue(batch);

            warnFlushFailure(
                    "Could not flush Sell transaction audit batch — "
                            + batch.size()
                            + " completed sales remain queued",
                    exception
            );
        }
    }

    private boolean flushBatchBlocking() {
        List<SaleAudit> batch =
                drainBatch();

        if (batch.isEmpty()) {
            return true;
        }

        if (!configuration.databaseConfigured()) {
            requeue(batch);
            return false;
        }

        try {
            writeBatch(batch);
            reservedSlots.addAndGet(
                    -batch.size()
            );
            return true;
        } catch (Exception exception) {
            schemaReadyFor = null;
            requeue(batch);

            core.getLogger().log(
                    Level.WARNING,
                    "Could not flush Sell transaction audits during shutdown",
                    exception
            );
            return false;
        }
    }

    private List<SaleAudit> drainBatch() {
        List<SaleAudit> batch =
                new ArrayList<>(
                        configuration.maxBatchSales()
                );

        for (int index = 0;
             index < configuration.maxBatchSales();
             index++) {
            SaleAudit audit =
                    pending.poll();

            if (audit == null) {
                break;
            }

            batch.add(audit);
        }

        return batch;
    }

    private void requeue(
            List<SaleAudit> batch
    ) {
        pending.addAll(batch);
    }

    private void writeBatch(
            List<SaleAudit> batch
    ) throws Exception {
        LedgerConfig current = configuration;
        Class.forName(current.driverClass());

        try (Connection connection =
                     DriverManager.getConnection(
                             current.jdbcUrl(),
                             current.username(),
                             current.password()
                     )) {
            connection.setAutoCommit(false);

            try {
                if (schemaReadyFor != current) {
                    initialize(
                            connection,
                            current
                    );
                    schemaReadyFor = current;
                }
                insertSales(
                        connection,
                        current,
                        batch
                );
                insertItems(
                        connection,
                        current,
                        batch
                );
                connection.commit();
            } catch (Exception exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    private void initialize(
            Connection connection,
            LedgerConfig configuration
    ) throws Exception {
        try (Statement statement =
                     connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        sale_id CHAR(36) PRIMARY KEY,
                        player_uuid CHAR(36) NOT NULL,
                        catalog_revision INT NOT NULL,
                        catalog_generation BIGINT NOT NULL,
                        total_cents BIGINT NOT NULL,
                        total_amount BIGINT NOT NULL,
                        created_at BIGINT NOT NULL,
                        INDEX idx_sell_transactions_player_created (
                            player_uuid,
                            created_at
                        ),
                        INDEX idx_sell_transactions_created (
                            created_at
                        )
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(
                    configuration.transactionTable()
            ));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        sale_id CHAR(36) NOT NULL,
                        line_index SMALLINT NOT NULL,
                        material VARCHAR(64) NOT NULL,
                        amount BIGINT NOT NULL,
                        payout_cents BIGINT NOT NULL,
                        market_key VARCHAR(64) NOT NULL,
                        market_units BIGINT NOT NULL,
                        PRIMARY KEY (
                            sale_id,
                            line_index
                        ),
                        INDEX idx_sell_transaction_items_material (
                            material
                        ),
                        INDEX idx_sell_transaction_items_market_key (
                            market_key
                        )
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(
                    configuration.transactionItemTable()
            ));
        }
    }

    private void insertSales(
            Connection connection,
            LedgerConfig configuration,
            List<SaleAudit> batch
    ) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT IGNORE INTO %s (
                                 sale_id,
                                 player_uuid,
                                 catalog_revision,
                                 catalog_generation,
                                 total_cents,
                                 total_amount,
                                 created_at
                             ) VALUES (?, ?, ?, ?, ?, ?, ?)
                             """.formatted(
                             configuration.transactionTable()
                     ))) {
            for (SaleAudit audit : batch) {
                statement.setString(
                        1,
                        audit.saleId()
                );
                statement.setString(
                        2,
                        audit.playerId()
                                .toString()
                );
                statement.setInt(
                        3,
                        audit.catalogRevision()
                );
                statement.setLong(
                        4,
                        audit.catalogGeneration()
                );
                statement.setLong(
                        5,
                        audit.totalCents()
                );
                statement.setLong(
                        6,
                        audit.totalAmount()
                );
                statement.setLong(
                        7,
                        audit.createdAt()
                );
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private void insertItems(
            Connection connection,
            LedgerConfig configuration,
            List<SaleAudit> batch
    ) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT IGNORE INTO %s (
                                 sale_id,
                                 line_index,
                                 material,
                                 amount,
                                 payout_cents,
                                 market_key,
                                 market_units
                             ) VALUES (?, ?, ?, ?, ?, ?, ?)
                             """.formatted(
                             configuration.transactionItemTable()
                     ))) {
            for (SaleAudit audit : batch) {
                int lineIndex = 0;

                for (SaleAuditItem item
                        : audit.items()) {
                    statement.setString(
                            1,
                            audit.saleId()
                    );
                    statement.setInt(
                            2,
                            lineIndex++
                    );
                    statement.setString(
                            3,
                            item.material()
                    );
                    statement.setLong(
                            4,
                            item.amount()
                    );
                    statement.setLong(
                            5,
                            item.payoutCents()
                    );
                    statement.setString(
                            6,
                            item.marketKey()
                    );
                    statement.setLong(
                            7,
                            item.marketUnits()
                    );
                    statement.addBatch();
                }
            }

            statement.executeBatch();
        }
    }

    private void rollbackQuietly(
            Connection connection
    ) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void commit(
            Reservation reservation,
            SaleAudit audit
    ) {
        if (reservation == null
                || audit == null
                || !reservation.finish()) {
            throw new IllegalStateException(
                    "Invalid Sell audit reservation commit"
            );
        }

        pending.add(audit);

    }

    private void cancel(
            Reservation reservation
    ) {
        if (reservation != null
                && reservation.finish()) {
            reservedSlots.decrementAndGet();
        }
    }

    private void warnFlushFailure(
            String message,
            Exception exception
    ) {
        long now =
                System.nanoTime();

        if (now - lastFailureWarningNanos
                < FAILURE_WARNING_INTERVAL_NANOS) {
            return;
        }

        lastFailureWarningNanos = now;
        core.getLogger().log(
                Level.WARNING,
                message,
                exception
        );
    }

    private String nonBlank(
            String value,
            String fallback
    ) {
        return value == null
                || value.isBlank()
                ? fallback
                : value.trim();
    }

    private String safeIdentifier(
            String raw
    ) {
        String fallback = "mineacle_sell";
        String normalized =
                nonBlank(
                        raw,
                        fallback
                ).toLowerCase(
                        Locale.ROOT
                );

        return normalized.matches(
                "[a-z0-9_]{1,40}"
        )
                ? normalized
                : fallback;
    }

    private record LedgerConfig(
            String driverClass,
            String jdbcUrl,
            String username,
            String password,
            String transactionTable,
            String transactionItemTable,
            boolean databaseConfigured,
            int maxBatchSales,
            int maxPendingSales,
            long flushTicks
    ) {

        private static LedgerConfig defaults() {
            return new LedgerConfig(
                    "com.mysql.cj.jdbc.Driver",
                    "jdbc:mysql://127.0.0.1:3306/mineacle",
                    "mineacle_core",
                    "",
                    "mineacle_sell_transactions",
                    "mineacle_sell_transaction_items",
                    true,
                    DEFAULT_MAX_BATCH_SALES,
                    DEFAULT_MAX_PENDING_SALES,
                    DEFAULT_FLUSH_SECONDS * 20L
            );
        }
    }

    public static final class Reservation {

        private final SellTransactionLedger owner;
        private final AtomicBoolean finished =
                new AtomicBoolean();

        private Reservation(
                SellTransactionLedger owner
        ) {
            this.owner = owner;
        }

        public void commit(
                SaleAudit audit
        ) {
            owner.commit(
                    this,
                    audit
            );
        }

        public void cancel() {
            owner.cancel(this);
        }

        private boolean finish() {
            return finished.compareAndSet(
                    false,
                    true
            );
        }
    }

    public record SaleAudit(
            String saleId,
            UUID playerId,
            int catalogRevision,
            long catalogGeneration,
            long totalCents,
            long totalAmount,
            long createdAt,
            List<SaleAuditItem> items
    ) {

        public SaleAudit {
            items = items == null
                    ? List.of()
                    : List.copyOf(items);
        }
    }

    public record SaleAuditItem(
            String material,
            long amount,
            long payoutCents,
            String marketKey,
            long marketUnits
    ) {
    }
}
