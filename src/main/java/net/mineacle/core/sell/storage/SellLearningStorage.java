package net.mineacle.core.sell.storage;

import net.mineacle.core.Core;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SQL persistence for Sell/Worth v10 shadow-learning telemetry.
 *
 * <p>This storage is intentionally aggregate-only. Live gameplay never waits on
 * this service. Completed server Sell payouts are imported asynchronously from
 * Mineacle's existing append-only transaction ledger, then coalesced into
 * hourly population/market/seller evidence for the shadow learner.</p>
 */
public final class SellLearningStorage {

    public record ActivityDelta(
            long bucketStart,
            long playerMillis,
            long sellTransactions,
            long sellPayoutCents,
            long auctionTransactions,
            long auctionTradeCents,
            long orderTransactions,
            long orderTradeCents
    ) {
    }

    public record MarketDelta(
            long bucketStart,
            String marketKey,
            long sellUnits,
            long sellPayoutCents,
            long sellTransactions,
            long auctionUnits,
            long auctionTradeCents,
            long auctionTransactions,
            long orderUnits,
            long orderTradeCents,
            long orderTransactions
    ) {
    }

    public record SellerDelta(
            long bucketStart,
            String marketKey,
            UUID playerId,
            String playerName,
            long sellUnits,
            long sellPayoutCents,
            long sellTransactions
    ) {
    }

    public record LearningRow(
            String marketKey,
            String referenceMaterial,
            BigDecimal shadowReferenceUnitCents,
            BigDecimal baselineUnitsPerPlayerHour,
            BigDecimal recentUnitsPerPlayerHour,
            double supplyRatio,
            double confidence,
            double recommendedMultiplier,
            String state,
            long metaStartedAt,
            long metaTargetUnits,
            long metaProgressUnits,
            long lastMetaAt,
            long lastEvaluatedAt,
            long lastAlertAt
    ) {
    }

    public record ActivityTotals(
            long playerMillis,
            long sellTransactions,
            long sellPayoutCents,
            long auctionTransactions,
            long auctionTradeCents,
            long orderTransactions,
            long orderTradeCents
    ) {
        public static ActivityTotals empty() {
            return new ActivityTotals(
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L
            );
        }
    }

    public record MarketTotals(
            String marketKey,
            long sellUnits,
            long sellPayoutCents,
            long sellTransactions,
            long auctionUnits,
            long auctionTradeCents,
            long auctionTransactions,
            long orderUnits,
            long orderTradeCents,
            long orderTransactions
    ) {
    }

    public record SellerTotals(
            String marketKey,
            UUID playerId,
            String playerName,
            long sellUnits,
            long sellPayoutCents,
            long sellTransactions
    ) {
    }

    public record WindowSnapshot(
            ActivityTotals activity,
            Map<String, MarketTotals> markets,
            List<SellerTotals> sellers
    ) {
        public static WindowSnapshot empty() {
            return new WindowSnapshot(
                    ActivityTotals.empty(),
                    Map.of(),
                    List.of()
            );
        }
    }

    public record WriteBatch(
            List<ActivityDelta> activity,
            List<MarketDelta> markets,
            List<SellerDelta> sellers,
            List<LearningRow> learningRows
    ) {
        public static WriteBatch empty() {
            return new WriteBatch(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        public boolean emptyBatch() {
            return activity.isEmpty()
                    && markets.isEmpty()
                    && sellers.isEmpty()
                    && learningRows.isEmpty();
        }
    }

    private final boolean sqlConfigured;
    private final String driverClass;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int queryTimeoutSeconds;

    private final String activityTable;
    private final String marketTable;
    private final String sellerTable;
    private final String learningTable;
    private final String syncTable;
    private final String ingestedSaleTable;
    private final String sourceTransactionTable;
    private final String sourceItemTable;

    public SellLearningStorage(
            Core core,
            FileConfiguration sellConfig
    ) {
        String storage = nonBlank(
                sellConfig.getString(
                        "market.storage",
                        "mysql"
                ),
                "mysql"
        );
        sqlConfigured =
                storage.equalsIgnoreCase("mysql")
                        || storage.equalsIgnoreCase("mariadb");

        File databaseFile = new File(
                core.getDataFolder(),
                nonBlank(
                        sellConfig.getString(
                                "market.database-config-file",
                                "webprofiles.yml"
                        ),
                        "webprofiles.yml"
                )
        );
        FileConfiguration database =
                YamlConfiguration.loadConfiguration(
                        databaseFile
                );

        driverClass = nonBlank(
                database.getString(
                        "database.driver-class",
                        "com.mysql.cj.jdbc.Driver"
                ),
                "com.mysql.cj.jdbc.Driver"
        );
        jdbcUrl = nonBlank(
                database.getString(
                        "database.jdbc-url",
                        "jdbc:mysql://127.0.0.1:3306/mineacle"
                ),
                "jdbc:mysql://127.0.0.1:3306/mineacle"
        );
        username = nonBlank(
                database.getString(
                        "database.username",
                        "mineacle_core"
                ),
                "mineacle_core"
        );
        password = database.getString(
                "database.password",
                ""
        );
        queryTimeoutSeconds = Math.clamp(
                database.getInt(
                        "database.query-timeout-seconds",
                        5
                ),
                1,
                30
        );

        String prefix = safeIdentifier(
                sellConfig.getString(
                        "market.table-prefix",
                        "mineacle_sell"
                )
        );
        activityTable = prefix + "_v10_activity_hourly";
        marketTable = prefix + "_v10_market_hourly";
        sellerTable = prefix + "_v10_seller_hourly";
        learningTable = prefix + "_v10_learning";
        syncTable = prefix + "_v10_sync";
        ingestedSaleTable = prefix + "_v10_ingested_sales";
        sourceTransactionTable = prefix + "_transactions";
        sourceItemTable = prefix + "_transaction_items";
    }

    public boolean sqlConfigured() {
        return sqlConfigured;
    }

    public void initialize()
            throws Exception {
        if (!sqlConfigured) {
            return;
        }

        loadDriver();

        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(queryTimeoutSeconds);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        bucket_start BIGINT PRIMARY KEY,
                        player_millis BIGINT NOT NULL,
                        sell_transactions BIGINT NOT NULL,
                        sell_payout_cents BIGINT NOT NULL,
                        auction_transactions BIGINT NOT NULL,
                        auction_trade_cents BIGINT NOT NULL,
                        order_transactions BIGINT NOT NULL,
                        order_trade_cents BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(activityTable));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        bucket_start BIGINT NOT NULL,
                        market_key VARCHAR(64) NOT NULL,
                        sell_units BIGINT NOT NULL,
                        sell_payout_cents BIGINT NOT NULL,
                        sell_transactions BIGINT NOT NULL,
                        auction_units BIGINT NOT NULL,
                        auction_trade_cents BIGINT NOT NULL,
                        auction_transactions BIGINT NOT NULL,
                        order_units BIGINT NOT NULL,
                        order_trade_cents BIGINT NOT NULL,
                        order_transactions BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (bucket_start, market_key),
                        INDEX idx_v10_market_key_time (market_key, bucket_start),
                        INDEX idx_v10_market_time (bucket_start)
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(marketTable));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        bucket_start BIGINT NOT NULL,
                        market_key VARCHAR(64) NOT NULL,
                        player_uuid CHAR(36) NOT NULL,
                        player_name VARCHAR(64) NOT NULL,
                        sell_units BIGINT NOT NULL,
                        sell_payout_cents BIGINT NOT NULL,
                        sell_transactions BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (bucket_start, market_key, player_uuid),
                        INDEX idx_v10_seller_market_time (market_key, bucket_start),
                        INDEX idx_v10_seller_player_time (player_uuid, bucket_start)
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(sellerTable));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        market_key VARCHAR(64) PRIMARY KEY,
                        reference_material VARCHAR(64) NOT NULL,
                        shadow_reference_unit_cents DECIMAL(24,8) NOT NULL,
                        baseline_units_per_player_hour DECIMAL(24,8) NOT NULL,
                        recent_units_per_player_hour DECIMAL(24,8) NOT NULL,
                        supply_ratio DECIMAL(12,6) NOT NULL,
                        confidence DECIMAL(10,6) NOT NULL,
                        recommended_multiplier DECIMAL(10,6) NOT NULL,
                        learning_state VARCHAR(24) NOT NULL,
                        meta_started_at BIGINT NOT NULL,
                        meta_target_units BIGINT NOT NULL,
                        meta_progress_units BIGINT NOT NULL,
                        last_meta_at BIGINT NOT NULL,
                        last_evaluated_at BIGINT NOT NULL,
                        last_alert_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        INDEX idx_v10_learning_state (learning_state),
                        INDEX idx_v10_learning_evaluated (last_evaluated_at)
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(learningTable));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        singleton_id TINYINT PRIMARY KEY,
                        tracking_started_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(syncTable));

            ensureTrackingStartedAtColumn(
                    connection,
                    syncTable
            );

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        sale_id CHAR(36) PRIMARY KEY,
                        created_at BIGINT NOT NULL,
                        ingested_at BIGINT NOT NULL,
                        INDEX idx_v10_ingested_created (created_at)
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(ingestedSaleTable));

            long trackingStartedAt = System.currentTimeMillis();
            statement.executeUpdate("""
                    INSERT IGNORE INTO %s (
                        singleton_id,
                        tracking_started_at,
                        updated_at
                    ) VALUES (1, %d, %d)
                    """.formatted(
                    syncTable,
                    trackingStartedAt,
                    trackingStartedAt
            ));

            statement.executeUpdate(
                    "UPDATE "
                            + syncTable
                            + " SET tracking_started_at = "
                            + trackingStartedAt
                            + " WHERE singleton_id = 1 "
                            + "AND tracking_started_at <= 0"
            );
        }
    }

    /**
     * Imports completed payouts from the existing durable Sell transaction
     * ledger. Hourly aggregates and the ingestion cursor advance in one SQL
     * transaction, so a crash can neither skip nor double-count a sale.
     *
     * @return number of completed Sell transactions imported
     */
    public int synchronizeSellLedger(
            long retentionCutoff,
            int maximumSales
    ) throws Exception {
        if (!sqlConfigured || maximumSales <= 0) {
            return 0;
        }

        loadDriver();
        int limit = Math.clamp(maximumSales, 1, 50_000);

        try (Connection connection = connection()) {
            connection.setAutoCommit(false);

            try {
                if (!tableExists(connection, sourceTransactionTable)
                        || !tableExists(connection, sourceItemTable)) {
                    connection.rollback();
                    return 0;
                }

                long trackingStartedAt =
                        loadTrackingStartForUpdate(connection);
                long effectiveCutoff = Math.max(
                        Math.max(0L, retentionCutoff),
                        trackingStartedAt
                );
                int imported = 0;

                while (imported < limit) {
                    int pageSize = Math.min(1_000, limit - imported);
                    List<LedgerSale> sales = loadLedgerSales(
                            connection,
                            effectiveCutoff,
                            pageSize
                    );

                    if (sales.isEmpty()) {
                        break;
                    }

                    Map<String, List<LedgerItem>> items =
                            loadLedgerItems(connection, sales);
                    LedgerAggregate aggregate =
                            aggregateLedgerSales(sales, items);
                    long now = System.currentTimeMillis();

                    saveActivity(connection, aggregate.activity(), now);
                    saveMarkets(connection, aggregate.markets(), now);
                    saveSellers(connection, aggregate.sellers(), now);
                    markLedgerSalesIngested(connection, sales, now);
                    imported += sales.size();

                    if (sales.size() < pageSize) {
                        break;
                    }
                }

                connection.commit();
                return imported;
            } catch (Exception exception) {
                try {
                    connection.rollback();
                } catch (Exception rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void saveBatch(
            WriteBatch batch
    ) throws Exception {
        if (!sqlConfigured
                || batch == null
                || batch.emptyBatch()) {
            return;
        }

        long now = System.currentTimeMillis();

        try (Connection connection = connection()) {
            connection.setAutoCommit(false);

            try {
                saveActivity(
                        connection,
                        batch.activity(),
                        now
                );
                saveMarkets(
                        connection,
                        batch.markets(),
                        now
                );
                saveSellers(
                        connection,
                        batch.sellers(),
                        now
                );
                saveLearning(
                        connection,
                        batch.learningRows(),
                        now
                );
                connection.commit();
            } catch (Exception exception) {
                try {
                    connection.rollback();
                } catch (Exception rollbackFailure) {
                    exception.addSuppressed(
                            rollbackFailure
                    );
                }
                throw exception;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public Map<String, LearningRow> loadLearning()
            throws Exception {
        if (!sqlConfigured) {
            return Map.of();
        }

        Map<String, LearningRow> result =
                new HashMap<>();

        try (Connection connection = connection();
             PreparedStatement statement =
                     connection.prepareStatement("""
                             SELECT market_key,
                                    reference_material,
                                    shadow_reference_unit_cents,
                                    baseline_units_per_player_hour,
                                    recent_units_per_player_hour,
                                    supply_ratio,
                                    confidence,
                                    recommended_multiplier,
                                    learning_state,
                                    meta_started_at,
                                    meta_target_units,
                                    meta_progress_units,
                                    last_meta_at,
                                    last_evaluated_at,
                                    last_alert_at
                               FROM %s
                             """.formatted(learningTable))) {
            statement.setQueryTimeout(queryTimeoutSeconds);

            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String key = normalizeKey(
                            rows.getString("market_key")
                    );

                    if (key.isBlank()) {
                        continue;
                    }

                    result.put(
                            key,
                            new LearningRow(
                                    key,
                                    nonBlank(
                                            rows.getString(
                                                    "reference_material"
                                            ),
                                            key
                                    ),
                                    positiveDecimal(
                                            rows.getBigDecimal(
                                                    "shadow_reference_unit_cents"
                                            )
                                    ),
                                    nonNegativeDecimal(
                                            rows.getBigDecimal(
                                                    "baseline_units_per_player_hour"
                                            )
                                    ),
                                    nonNegativeDecimal(
                                            rows.getBigDecimal(
                                                    "recent_units_per_player_hour"
                                            )
                                    ),
                                    finiteNonNegative(
                                            rows.getDouble(
                                                    "supply_ratio"
                                            )
                                    ),
                                    unitInterval(
                                            rows.getDouble(
                                                    "confidence"
                                            )
                                    ),
                                    positiveFiniteOrOne(
                                            rows.getDouble(
                                                    "recommended_multiplier"
                                            )
                                    ),
                                    nonBlank(
                                            rows.getString(
                                                    "learning_state"
                                            ),
                                            "LEARNING"
                                    ).toUpperCase(
                                            Locale.ROOT
                                    ),
                                    Math.max(
                                            0L,
                                            rows.getLong(
                                                    "meta_started_at"
                                            )
                                    ),
                                    Math.max(
                                            0L,
                                            rows.getLong(
                                                    "meta_target_units"
                                            )
                                    ),
                                    Math.max(
                                            0L,
                                            rows.getLong(
                                                    "meta_progress_units"
                                            )
                                    ),
                                    Math.max(
                                            0L,
                                            rows.getLong(
                                                    "last_meta_at"
                                            )
                                    ),
                                    Math.max(
                                            0L,
                                            rows.getLong(
                                                    "last_evaluated_at"
                                            )
                                    ),
                                    Math.max(
                                            0L,
                                            rows.getLong(
                                                    "last_alert_at"
                                            )
                                    )
                            )
                    );
                }
            }
        }

        return Map.copyOf(result);
    }

    public WindowSnapshot loadWindow(
            long fromInclusive,
            long toExclusive,
            boolean includeSellers
    ) throws Exception {
        if (!sqlConfigured
                || toExclusive <= fromInclusive) {
            return WindowSnapshot.empty();
        }

        try (Connection connection = connection()) {
            ActivityTotals activity =
                    loadActivity(
                            connection,
                            fromInclusive,
                            toExclusive
                    );
            Map<String, MarketTotals> markets =
                    loadMarkets(
                            connection,
                            fromInclusive,
                            toExclusive
                    );
            List<SellerTotals> sellers =
                    includeSellers
                            ? loadSellers(
                            connection,
                            fromInclusive,
                            toExclusive
                    )
                            : List.of();

            return new WindowSnapshot(
                    activity,
                    markets,
                    sellers
            );
        }
    }

    public long marketSellUnits(
            String marketKey,
            long fromInclusive,
            long toExclusive
    ) throws Exception {
        if (!sqlConfigured
                || marketKey == null
                || marketKey.isBlank()
                || toExclusive <= fromInclusive) {
            return 0L;
        }

        try (Connection connection = connection()) {
            if (tableExists(connection, sourceTransactionTable)
                    && tableExists(connection, sourceItemTable)) {
                try (PreparedStatement statement =
                             connection.prepareStatement("""
                                     SELECT COALESCE(SUM(i.market_units), 0)
                                                AS total_units
                                       FROM %s t
                                       JOIN %s i
                                         ON i.sale_id = t.sale_id
                                      WHERE i.market_key = ?
                                        AND t.created_at >= ?
                                        AND t.created_at < ?
                                     """.formatted(
                                     sourceTransactionTable,
                                     sourceItemTable
                             ))) {
                    statement.setQueryTimeout(queryTimeoutSeconds);
                    statement.setString(1, normalizeKey(marketKey));
                    statement.setLong(2, fromInclusive);
                    statement.setLong(3, toExclusive);

                    try (ResultSet result = statement.executeQuery()) {
                        return result.next()
                                ? Math.max(
                                0L,
                                result.getLong("total_units")
                        )
                                : 0L;
                    }
                }
            }

            /*
             * The ledger tables do not exist until the transaction ledger has
             * initialized. Falling back to hourly evidence keeps shadow state
             * readable during a brand-new/empty economy without blocking it.
             */
            try (PreparedStatement statement =
                         connection.prepareStatement("""
                                 SELECT COALESCE(SUM(sell_units), 0)
                                            AS total_units
                                   FROM %s
                                  WHERE market_key = ?
                                    AND bucket_start >= ?
                                    AND bucket_start < ?
                                 """.formatted(marketTable))) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                statement.setString(1, normalizeKey(marketKey));
                statement.setLong(2, fromInclusive);
                statement.setLong(3, toExclusive);

                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? Math.max(
                            0L,
                            result.getLong("total_units")
                    )
                            : 0L;
                }
            }
        }
    }

    public void pruneBefore(
            long cutoff
    ) throws Exception {
        if (!sqlConfigured
                || cutoff <= 0L) {
            return;
        }

        try (Connection connection = connection()) {
            connection.setAutoCommit(false);

            try {
                deleteBefore(
                        connection,
                        activityTable,
                        cutoff
                );
                deleteBefore(
                        connection,
                        marketTable,
                        cutoff
                );
                deleteBefore(
                        connection,
                        sellerTable,
                        cutoff
                );
                deleteIngestedBefore(
                        connection,
                        cutoff
                );
                connection.commit();
            } catch (Exception exception) {
                try {
                    connection.rollback();
                } catch (Exception rollbackFailure) {
                    exception.addSuppressed(
                            rollbackFailure
                    );
                }
                throw exception;
            }
        }
    }

    private void ensureTrackingStartedAtColumn(
            Connection connection,
            String table
    ) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();

        try (ResultSet result = metadata.getColumns(
                catalog,
                null,
                table,
                "tracking_started_at"
        )) {
            if (result.next()) {
                return;
            }
        }

        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.executeUpdate(
                    "ALTER TABLE "
                            + table
                            + " ADD COLUMN tracking_started_at "
                            + "BIGINT NOT NULL DEFAULT 0"
            );
        }
    }

    private boolean tableExists(
            Connection connection,
            String table
    ) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();

        for (String candidate : List.of(
                table,
                table.toLowerCase(Locale.ROOT),
                table.toUpperCase(Locale.ROOT)
        )) {
            try (ResultSet result = metadata.getTables(
                    catalog,
                    null,
                    candidate,
                    new String[]{"TABLE"}
            )) {
                if (result.next()) {
                    return true;
                }
            }
        }

        return false;
    }

    private long loadTrackingStartForUpdate(
            Connection connection
    ) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             SELECT tracking_started_at
                               FROM %s
                              WHERE singleton_id = 1
                              FOR UPDATE
                             """.formatted(syncTable))) {
            statement.setQueryTimeout(queryTimeoutSeconds);

            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return Math.max(
                            0L,
                            result.getLong("tracking_started_at")
                    );
                }
            }
        }

        return System.currentTimeMillis();
    }

    private List<LedgerSale> loadLedgerSales(
            Connection connection,
            long fromInclusive,
            int limit
    ) throws Exception {
        List<LedgerSale> result = new ArrayList<>();

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             SELECT t.sale_id,
                                    t.player_uuid,
                                    t.total_cents,
                                    t.created_at
                               FROM %s t
                               LEFT JOIN %s v10
                                 ON v10.sale_id = t.sale_id
                              WHERE t.created_at >= ?
                                AND v10.sale_id IS NULL
                              ORDER BY t.created_at, t.sale_id
                              LIMIT ?
                             """.formatted(
                             sourceTransactionTable,
                             ingestedSaleTable
                     ))) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setLong(1, Math.max(0L, fromInclusive));
            statement.setInt(2, Math.max(1, limit));

            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID playerId = uuid(rows.getString("player_uuid"));
                    String saleId = nonBlank(rows.getString("sale_id"), "");

                    if (playerId == null || saleId.isBlank()) {
                        continue;
                    }

                    result.add(new LedgerSale(
                            saleId,
                            playerId,
                            Math.max(0L, rows.getLong("total_cents")),
                            Math.max(0L, rows.getLong("created_at"))
                    ));
                }
            }
        }

        return List.copyOf(result);
    }

    private void markLedgerSalesIngested(
            Connection connection,
            List<LedgerSale> sales,
            long ingestedAt
    ) throws Exception {
        if (sales.isEmpty()) {
            return;
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 sale_id,
                                 created_at,
                                 ingested_at
                             ) VALUES (?, ?, ?)
                             """.formatted(ingestedSaleTable))) {
            statement.setQueryTimeout(queryTimeoutSeconds);

            for (LedgerSale sale : sales) {
                statement.setString(1, sale.saleId());
                statement.setLong(2, sale.createdAt());
                statement.setLong(3, ingestedAt);
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private Map<String, List<LedgerItem>> loadLedgerItems(
            Connection connection,
            List<LedgerSale> sales
    ) throws Exception {
        if (sales.isEmpty()) {
            return Map.of();
        }

        String placeholders = String.join(
                ",",
                java.util.Collections.nCopies(sales.size(), "?")
        );
        String sql = """
                SELECT sale_id,
                       line_index,
                       payout_cents,
                       market_key,
                       market_units
                  FROM %s
                 WHERE sale_id IN (%s)
                 ORDER BY sale_id, line_index
                """.formatted(sourceItemTable, placeholders);
        Map<String, List<LedgerItem>> result = new HashMap<>();

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);

            for (int index = 0; index < sales.size(); index++) {
                statement.setString(index + 1, sales.get(index).saleId());
            }

            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String saleId = nonBlank(rows.getString("sale_id"), "");
                    String marketKey = normalizeKey(rows.getString("market_key"));
                    long units = Math.max(0L, rows.getLong("market_units"));
                    long payout = Math.max(0L, rows.getLong("payout_cents"));

                    if (saleId.isBlank() || marketKey.isBlank() || units <= 0L) {
                        continue;
                    }

                    result.computeIfAbsent(
                            saleId,
                            ignored -> new ArrayList<>()
                    ).add(new LedgerItem(
                            marketKey,
                            units,
                            payout
                    ));
                }
            }
        }

        Map<String, List<LedgerItem>> immutable = new HashMap<>();
        for (Map.Entry<String, List<LedgerItem>> entry : result.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(immutable);
    }

    private LedgerAggregate aggregateLedgerSales(
            List<LedgerSale> sales,
            Map<String, List<LedgerItem>> itemsBySale
    ) {
        Map<Long, MutableActivityAggregate> activity = new LinkedHashMap<>();
        Map<MarketAggregateKey, MutableMarketAggregate> markets =
                new LinkedHashMap<>();
        Map<SellerAggregateKey, MutableSellerAggregate> sellers =
                new LinkedHashMap<>();

        for (LedgerSale sale : sales) {
            long bucket = hourBucket(sale.createdAt());
            MutableActivityAggregate activityRow = activity.computeIfAbsent(
                    bucket,
                    ignored -> new MutableActivityAggregate()
            );
            activityRow.sellTransactions = saturatingAdd(
                    activityRow.sellTransactions,
                    1L
            );
            activityRow.sellPayoutCents = saturatingAdd(
                    activityRow.sellPayoutCents,
                    sale.totalCents()
            );

            Set<String> saleMarkets = new HashSet<>();
            for (LedgerItem item : itemsBySale.getOrDefault(
                    sale.saleId(),
                    List.of()
            )) {
                MarketAggregateKey marketKey = new MarketAggregateKey(
                        bucket,
                        item.marketKey()
                );
                MutableMarketAggregate market = markets.computeIfAbsent(
                        marketKey,
                        ignored -> new MutableMarketAggregate()
                );
                market.sellUnits = saturatingAdd(
                        market.sellUnits,
                        item.marketUnits()
                );
                market.sellPayoutCents = saturatingAdd(
                        market.sellPayoutCents,
                        item.payoutCents()
                );

                SellerAggregateKey sellerKey = new SellerAggregateKey(
                        bucket,
                        item.marketKey(),
                        sale.playerId()
                );
                MutableSellerAggregate seller = sellers.computeIfAbsent(
                        sellerKey,
                        ignored -> new MutableSellerAggregate()
                );
                seller.sellUnits = saturatingAdd(
                        seller.sellUnits,
                        item.marketUnits()
                );
                seller.sellPayoutCents = saturatingAdd(
                        seller.sellPayoutCents,
                        item.payoutCents()
                );

                saleMarkets.add(item.marketKey());
            }

            for (String marketKey : saleMarkets) {
                MutableMarketAggregate market = markets.get(
                        new MarketAggregateKey(bucket, marketKey)
                );
                if (market != null) {
                    market.sellTransactions = saturatingAdd(
                            market.sellTransactions,
                            1L
                    );
                }

                MutableSellerAggregate seller = sellers.get(
                        new SellerAggregateKey(
                                bucket,
                                marketKey,
                                sale.playerId()
                        )
                );
                if (seller != null) {
                    seller.sellTransactions = saturatingAdd(
                            seller.sellTransactions,
                            1L
                    );
                }
            }
        }

        List<ActivityDelta> activityRows = new ArrayList<>(activity.size());
        for (Map.Entry<Long, MutableActivityAggregate> entry : activity.entrySet()) {
            activityRows.add(new ActivityDelta(
                    entry.getKey(),
                    0L,
                    entry.getValue().sellTransactions,
                    entry.getValue().sellPayoutCents,
                    0L,
                    0L,
                    0L,
                    0L
            ));
        }

        List<MarketDelta> marketRows = new ArrayList<>(markets.size());
        for (Map.Entry<MarketAggregateKey, MutableMarketAggregate> entry
                : markets.entrySet()) {
            MutableMarketAggregate value = entry.getValue();
            marketRows.add(new MarketDelta(
                    entry.getKey().bucketStart(),
                    entry.getKey().marketKey(),
                    value.sellUnits,
                    value.sellPayoutCents,
                    value.sellTransactions,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L
            ));
        }

        List<SellerDelta> sellerRows = new ArrayList<>(sellers.size());
        for (Map.Entry<SellerAggregateKey, MutableSellerAggregate> entry
                : sellers.entrySet()) {
            MutableSellerAggregate value = entry.getValue();
            sellerRows.add(new SellerDelta(
                    entry.getKey().bucketStart(),
                    entry.getKey().marketKey(),
                    entry.getKey().playerId(),
                    "",
                    value.sellUnits,
                    value.sellPayoutCents,
                    value.sellTransactions
            ));
        }

        return new LedgerAggregate(
                List.copyOf(activityRows),
                List.copyOf(marketRows),
                List.copyOf(sellerRows)
        );
    }

    private long hourBucket(long timestamp) {
        long safe = Math.max(0L, timestamp);
        return safe - Math.floorMod(safe, 3_600_000L);
    }

    private long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private void saveActivity(
            Connection connection,
            List<ActivityDelta> rows,
            long now
    ) throws Exception {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 bucket_start,
                                 player_millis,
                                 sell_transactions,
                                 sell_payout_cents,
                                 auction_transactions,
                                 auction_trade_cents,
                                 order_transactions,
                                 order_trade_cents,
                                 updated_at
                             ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                             ON DUPLICATE KEY UPDATE
                                 player_millis = player_millis + VALUES(player_millis),
                                 sell_transactions = sell_transactions + VALUES(sell_transactions),
                                 sell_payout_cents = sell_payout_cents + VALUES(sell_payout_cents),
                                 auction_transactions = auction_transactions + VALUES(auction_transactions),
                                 auction_trade_cents = auction_trade_cents + VALUES(auction_trade_cents),
                                 order_transactions = order_transactions + VALUES(order_transactions),
                                 order_trade_cents = order_trade_cents + VALUES(order_trade_cents),
                                 updated_at = VALUES(updated_at)
                             """.formatted(activityTable))) {
            statement.setQueryTimeout(queryTimeoutSeconds);

            for (ActivityDelta row : rows) {
                statement.setLong(1, row.bucketStart());
                statement.setLong(2, Math.max(0L, row.playerMillis()));
                statement.setLong(3, Math.max(0L, row.sellTransactions()));
                statement.setLong(4, Math.max(0L, row.sellPayoutCents()));
                statement.setLong(5, Math.max(0L, row.auctionTransactions()));
                statement.setLong(6, Math.max(0L, row.auctionTradeCents()));
                statement.setLong(7, Math.max(0L, row.orderTransactions()));
                statement.setLong(8, Math.max(0L, row.orderTradeCents()));
                statement.setLong(9, now);
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private void saveMarkets(
            Connection connection,
            List<MarketDelta> rows,
            long now
    ) throws Exception {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 bucket_start,
                                 market_key,
                                 sell_units,
                                 sell_payout_cents,
                                 sell_transactions,
                                 auction_units,
                                 auction_trade_cents,
                                 auction_transactions,
                                 order_units,
                                 order_trade_cents,
                                 order_transactions,
                                 updated_at
                             ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                             ON DUPLICATE KEY UPDATE
                                 sell_units = sell_units + VALUES(sell_units),
                                 sell_payout_cents = sell_payout_cents + VALUES(sell_payout_cents),
                                 sell_transactions = sell_transactions + VALUES(sell_transactions),
                                 auction_units = auction_units + VALUES(auction_units),
                                 auction_trade_cents = auction_trade_cents + VALUES(auction_trade_cents),
                                 auction_transactions = auction_transactions + VALUES(auction_transactions),
                                 order_units = order_units + VALUES(order_units),
                                 order_trade_cents = order_trade_cents + VALUES(order_trade_cents),
                                 order_transactions = order_transactions + VALUES(order_transactions),
                                 updated_at = VALUES(updated_at)
                             """.formatted(marketTable))) {
            statement.setQueryTimeout(queryTimeoutSeconds);

            for (MarketDelta row : rows) {
                statement.setLong(1, row.bucketStart());
                statement.setString(2, normalizeKey(row.marketKey()));
                statement.setLong(3, Math.max(0L, row.sellUnits()));
                statement.setLong(4, Math.max(0L, row.sellPayoutCents()));
                statement.setLong(5, Math.max(0L, row.sellTransactions()));
                statement.setLong(6, Math.max(0L, row.auctionUnits()));
                statement.setLong(7, Math.max(0L, row.auctionTradeCents()));
                statement.setLong(8, Math.max(0L, row.auctionTransactions()));
                statement.setLong(9, Math.max(0L, row.orderUnits()));
                statement.setLong(10, Math.max(0L, row.orderTradeCents()));
                statement.setLong(11, Math.max(0L, row.orderTransactions()));
                statement.setLong(12, now);
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private void saveSellers(
            Connection connection,
            List<SellerDelta> rows,
            long now
    ) throws Exception {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 bucket_start,
                                 market_key,
                                 player_uuid,
                                 player_name,
                                 sell_units,
                                 sell_payout_cents,
                                 sell_transactions,
                                 updated_at
                             ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                             ON DUPLICATE KEY UPDATE
                                 player_name = CASE
                                     WHEN VALUES(player_name) <> ''
                                     THEN VALUES(player_name)
                                     ELSE player_name
                                 END,
                                 sell_units = sell_units + VALUES(sell_units),
                                 sell_payout_cents = sell_payout_cents + VALUES(sell_payout_cents),
                                 sell_transactions = sell_transactions + VALUES(sell_transactions),
                                 updated_at = VALUES(updated_at)
                             """.formatted(sellerTable))) {
            statement.setQueryTimeout(queryTimeoutSeconds);

            for (SellerDelta row : rows) {
                if (row.playerId() == null) {
                    continue;
                }

                statement.setLong(1, row.bucketStart());
                statement.setString(2, normalizeKey(row.marketKey()));
                statement.setString(3, row.playerId().toString());
                statement.setString(
                        4,
                        safePlayerName(
                                row.playerName()
                        )
                );
                statement.setLong(5, Math.max(0L, row.sellUnits()));
                statement.setLong(6, Math.max(0L, row.sellPayoutCents()));
                statement.setLong(7, Math.max(0L, row.sellTransactions()));
                statement.setLong(8, now);
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private void saveLearning(
            Connection connection,
            List<LearningRow> rows,
            long now
    ) throws Exception {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 market_key,
                                 reference_material,
                                 shadow_reference_unit_cents,
                                 baseline_units_per_player_hour,
                                 recent_units_per_player_hour,
                                 supply_ratio,
                                 confidence,
                                 recommended_multiplier,
                                 learning_state,
                                 meta_started_at,
                                 meta_target_units,
                                 meta_progress_units,
                                 last_meta_at,
                                 last_evaluated_at,
                                 last_alert_at,
                                 updated_at
                             ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                             ON DUPLICATE KEY UPDATE
                                 reference_material = VALUES(reference_material),
                                 shadow_reference_unit_cents = VALUES(shadow_reference_unit_cents),
                                 baseline_units_per_player_hour = VALUES(baseline_units_per_player_hour),
                                 recent_units_per_player_hour = VALUES(recent_units_per_player_hour),
                                 supply_ratio = VALUES(supply_ratio),
                                 confidence = VALUES(confidence),
                                 recommended_multiplier = VALUES(recommended_multiplier),
                                 learning_state = VALUES(learning_state),
                                 meta_started_at = VALUES(meta_started_at),
                                 meta_target_units = VALUES(meta_target_units),
                                 meta_progress_units = VALUES(meta_progress_units),
                                 last_meta_at = VALUES(last_meta_at),
                                 last_evaluated_at = VALUES(last_evaluated_at),
                                 last_alert_at = VALUES(last_alert_at),
                                 updated_at = VALUES(updated_at)
                             """.formatted(learningTable))) {
            statement.setQueryTimeout(queryTimeoutSeconds);

            for (LearningRow row : rows) {
                statement.setString(1, normalizeKey(row.marketKey()));
                statement.setString(
                        2,
                        safeMaterialName(
                                row.referenceMaterial()
                        )
                );
                statement.setBigDecimal(
                        3,
                        positiveDecimal(
                                row.shadowReferenceUnitCents()
                        )
                );
                statement.setBigDecimal(
                        4,
                        nonNegativeDecimal(
                                row.baselineUnitsPerPlayerHour()
                        )
                );
                statement.setBigDecimal(
                        5,
                        nonNegativeDecimal(
                                row.recentUnitsPerPlayerHour()
                        )
                );
                statement.setDouble(
                        6,
                        finiteNonNegative(
                                row.supplyRatio()
                        )
                );
                statement.setDouble(
                        7,
                        unitInterval(
                                row.confidence()
                        )
                );
                statement.setDouble(
                        8,
                        positiveFiniteOrOne(
                                row.recommendedMultiplier()
                        )
                );
                statement.setString(
                        9,
                        nonBlank(
                                row.state(),
                                "LEARNING"
                        ).toUpperCase(
                                Locale.ROOT
                        )
                );
                statement.setLong(10, Math.max(0L, row.metaStartedAt()));
                statement.setLong(11, Math.max(0L, row.metaTargetUnits()));
                statement.setLong(12, Math.max(0L, row.metaProgressUnits()));
                statement.setLong(13, Math.max(0L, row.lastMetaAt()));
                statement.setLong(14, Math.max(0L, row.lastEvaluatedAt()));
                statement.setLong(15, Math.max(0L, row.lastAlertAt()));
                statement.setLong(16, now);
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private ActivityTotals loadActivity(
            Connection connection,
            long fromInclusive,
            long toExclusive
    ) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             SELECT COALESCE(SUM(player_millis), 0) AS player_millis,
                                    COALESCE(SUM(sell_transactions), 0) AS sell_transactions,
                                    COALESCE(SUM(sell_payout_cents), 0) AS sell_payout_cents,
                                    COALESCE(SUM(auction_transactions), 0) AS auction_transactions,
                                    COALESCE(SUM(auction_trade_cents), 0) AS auction_trade_cents,
                                    COALESCE(SUM(order_transactions), 0) AS order_transactions,
                                    COALESCE(SUM(order_trade_cents), 0) AS order_trade_cents
                               FROM %s
                              WHERE bucket_start >= ?
                                AND bucket_start < ?
                             """.formatted(activityTable))) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setLong(1, fromInclusive);
            statement.setLong(2, toExclusive);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return ActivityTotals.empty();
                }

                return new ActivityTotals(
                        Math.max(0L, result.getLong("player_millis")),
                        Math.max(0L, result.getLong("sell_transactions")),
                        Math.max(0L, result.getLong("sell_payout_cents")),
                        Math.max(0L, result.getLong("auction_transactions")),
                        Math.max(0L, result.getLong("auction_trade_cents")),
                        Math.max(0L, result.getLong("order_transactions")),
                        Math.max(0L, result.getLong("order_trade_cents"))
                );
            }
        }
    }

    private Map<String, MarketTotals> loadMarkets(
            Connection connection,
            long fromInclusive,
            long toExclusive
    ) throws Exception {
        Map<String, MarketTotals> result =
                new HashMap<>();

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             SELECT market_key,
                                    COALESCE(SUM(sell_units), 0) AS sell_units,
                                    COALESCE(SUM(sell_payout_cents), 0) AS sell_payout_cents,
                                    COALESCE(SUM(sell_transactions), 0) AS sell_transactions,
                                    COALESCE(SUM(auction_units), 0) AS auction_units,
                                    COALESCE(SUM(auction_trade_cents), 0) AS auction_trade_cents,
                                    COALESCE(SUM(auction_transactions), 0) AS auction_transactions,
                                    COALESCE(SUM(order_units), 0) AS order_units,
                                    COALESCE(SUM(order_trade_cents), 0) AS order_trade_cents,
                                    COALESCE(SUM(order_transactions), 0) AS order_transactions
                               FROM %s
                              WHERE bucket_start >= ?
                                AND bucket_start < ?
                              GROUP BY market_key
                             """.formatted(marketTable))) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setLong(1, fromInclusive);
            statement.setLong(2, toExclusive);

            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String key = normalizeKey(
                            rows.getString("market_key")
                    );

                    if (key.isBlank()) {
                        continue;
                    }

                    result.put(
                            key,
                            new MarketTotals(
                                    key,
                                    Math.max(0L, rows.getLong("sell_units")),
                                    Math.max(0L, rows.getLong("sell_payout_cents")),
                                    Math.max(0L, rows.getLong("sell_transactions")),
                                    Math.max(0L, rows.getLong("auction_units")),
                                    Math.max(0L, rows.getLong("auction_trade_cents")),
                                    Math.max(0L, rows.getLong("auction_transactions")),
                                    Math.max(0L, rows.getLong("order_units")),
                                    Math.max(0L, rows.getLong("order_trade_cents")),
                                    Math.max(0L, rows.getLong("order_transactions"))
                            )
                    );
                }
            }
        }

        return Map.copyOf(result);
    }

    private List<SellerTotals> loadSellers(
            Connection connection,
            long fromInclusive,
            long toExclusive
    ) throws Exception {
        List<SellerTotals> result =
                new ArrayList<>();

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             SELECT market_key,
                                    player_uuid,
                                    MAX(player_name) AS player_name,
                                    COALESCE(SUM(sell_units), 0) AS sell_units,
                                    COALESCE(SUM(sell_payout_cents), 0) AS sell_payout_cents,
                                    COALESCE(SUM(sell_transactions), 0) AS sell_transactions
                               FROM %s
                              WHERE bucket_start >= ?
                                AND bucket_start < ?
                              GROUP BY market_key, player_uuid
                             """.formatted(sellerTable))) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setLong(1, fromInclusive);
            statement.setLong(2, toExclusive);

            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String key = normalizeKey(
                            rows.getString("market_key")
                    );
                    UUID playerId = uuid(
                            rows.getString("player_uuid")
                    );

                    if (key.isBlank()
                            || playerId == null) {
                        continue;
                    }

                    result.add(
                            new SellerTotals(
                                    key,
                                    playerId,
                                    safePlayerName(
                                            rows.getString(
                                                    "player_name"
                                            )
                                    ),
                                    Math.max(0L, rows.getLong("sell_units")),
                                    Math.max(0L, rows.getLong("sell_payout_cents")),
                                    Math.max(0L, rows.getLong("sell_transactions"))
                            )
                    );
                }
            }
        }

        return List.copyOf(result);
    }

    private void deleteIngestedBefore(
            Connection connection,
            long cutoff
    ) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement(
                             "DELETE FROM "
                                     + ingestedSaleTable
                                     + " WHERE created_at < ?"
                     )) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setLong(1, cutoff);
            statement.executeUpdate();
        }
    }

    private void deleteBefore(
            Connection connection,
            String table,
            long cutoff
    ) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement(
                             "DELETE FROM "
                                     + table
                                     + " WHERE bucket_start < ?"
                     )) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setLong(1, cutoff);
            statement.executeUpdate();
        }
    }

    private record LedgerSale(
            String saleId,
            UUID playerId,
            long totalCents,
            long createdAt
    ) {
    }

    private record LedgerItem(
            String marketKey,
            long marketUnits,
            long payoutCents
    ) {
    }

    private record LedgerAggregate(
            List<ActivityDelta> activity,
            List<MarketDelta> markets,
            List<SellerDelta> sellers
    ) {
    }

    private record MarketAggregateKey(
            long bucketStart,
            String marketKey
    ) {
    }

    private record SellerAggregateKey(
            long bucketStart,
            String marketKey,
            UUID playerId
    ) {
    }

    private static final class MutableActivityAggregate {
        private long sellTransactions;
        private long sellPayoutCents;
    }

    private static final class MutableMarketAggregate {
        private long sellUnits;
        private long sellPayoutCents;
        private long sellTransactions;
    }

    private static final class MutableSellerAggregate {
        private long sellUnits;
        private long sellPayoutCents;
        private long sellTransactions;
    }

    private Connection connection()
            throws Exception {
        loadDriver();
        return DriverManager.getConnection(
                jdbcUrl,
                username,
                password
        );
    }

    private void loadDriver()
            throws ClassNotFoundException {
        if (driverClass != null
                && !driverClass.isBlank()) {
            Class.forName(driverClass);
        }
    }

    private String safeIdentifier(
            String value
    ) {
        String raw = nonBlank(
                value,
                "mineacle_sell"
        );
        StringBuilder builder =
                new StringBuilder();

        for (int index = 0;
             index < raw.length();
             index++) {
            char character = raw.charAt(index);

            if (Character.isLetterOrDigit(character)
                    || character == '_') {
                builder.append(character);
            }
        }

        if (builder.isEmpty()) {
            return "mineacle_sell";
        }

        return builder.substring(
                0,
                Math.min(
                        40,
                        builder.length()
                )
        );
    }

    private String normalizeKey(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return "";
        }

        return value.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
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

    private String safePlayerName(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return "";
        }

        String trimmed = value.trim();
        return trimmed.substring(
                0,
                Math.min(
                        64,
                        trimmed.length()
                )
        );
    }

    private String safeMaterialName(
            String value
    ) {
        String normalized = nonBlank(
                value,
                "UNKNOWN"
        ).toUpperCase(Locale.ROOT);

        return normalized.substring(
                0,
                Math.min(
                        64,
                        normalized.length()
                )
        );
    }

    private UUID uuid(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(
                    value.trim()
            );
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private BigDecimal positiveDecimal(
            BigDecimal value
    ) {
        if (value == null
                || value.signum() <= 0) {
            return BigDecimal.ONE;
        }

        return value;
    }

    private BigDecimal nonNegativeDecimal(
            BigDecimal value
    ) {
        if (value == null
                || value.signum() < 0) {
            return BigDecimal.ZERO;
        }

        return value;
    }

    private double finiteNonNegative(
            double value
    ) {
        return Double.isFinite(value)
                && value >= 0.0D
                ? value
                : 0.0D;
    }

    private double positiveFiniteOrOne(
            double value
    ) {
        return Double.isFinite(value)
                && value > 0.0D
                ? value
                : 1.0D;
    }

    private double unitInterval(
            double value
    ) {
        return Math.clamp(
                finiteNonNegative(value),
                0.0D,
                1.0D
        );
    }
}
