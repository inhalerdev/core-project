package net.mineacle.core.sell.storage;

import net.mineacle.core.Core;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@SuppressWarnings({
        "SqlNoDataSourceInspection",
        "SqlDialectInspection"
})
public final class SqlSellMarketRepository
        implements SellMarketRepository {

    private final Core core;
    private final String driverClass;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String marketTable;
    private final String bucketTable;
    private final String historyTable;

    public SqlSellMarketRepository(
            Core core,
            FileConfiguration databaseConfig,
            String configuredPrefix
    ) {
        this.core = core;
        this.driverClass = value(
                databaseConfig,
                "database.driver-class",
                "com.mysql.cj.jdbc.Driver"
        );
        this.jdbcUrl = value(
                databaseConfig,
                "database.jdbc-url",
                "jdbc:mysql://127.0.0.1:3306/mineacle"
        );
        this.username = value(
                databaseConfig,
                "database.username",
                "mineacle_core"
        );
        this.password = value(
                databaseConfig,
                "database.password",
                ""
        );

        String prefix = safeIdentifier(
                configuredPrefix,
                "mineacle_sell"
        );

        marketTable = prefix + "_market";
        bucketTable = prefix + "_market_buckets";
        historyTable = prefix + "_player_history";
    }

    @Override
    public void initialize() throws Exception {
        loadDriver();

        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        material VARCHAR(64) PRIMARY KEY,
                        market_multiplier DECIMAL(10,4) NOT NULL,
                        featured_multiplier DECIMAL(10,4) NOT NULL,
                        featured_until BIGINT NOT NULL,
                        last_repriced_at BIGINT NOT NULL,
                        target_units_per_day BIGINT NOT NULL
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(marketTable));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        material VARCHAR(64) NOT NULL,
                        bucket_start BIGINT NOT NULL,
                        units_sold BIGINT NOT NULL,
                        payout_cents BIGINT NOT NULL,
                        PRIMARY KEY (material, bucket_start),
                        INDEX idx_sell_bucket_start (bucket_start)
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(bucketTable));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        player_uuid CHAR(36) NOT NULL,
                        material VARCHAR(64) NOT NULL,
                        amount BIGINT NOT NULL,
                        total_cents BIGINT NOT NULL,
                        last_sold_at BIGINT NOT NULL,
                        PRIMARY KEY (player_uuid, material),
                        INDEX idx_sell_history_last (last_sold_at)
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(historyTable));
        }
    }

    @Override
    public Snapshot load(long bucketsSince) throws Exception {
        loadDriver();

        List<MarketStateData> states = new ArrayList<>();
        List<BucketData> buckets = new ArrayList<>();
        List<HistoryData> history = new ArrayList<>();

        try (Connection connection = connection()) {
            try (PreparedStatement statement =
                         connection.prepareStatement("""
                                 SELECT material,
                                        market_multiplier,
                                        featured_multiplier,
                                        featured_until,
                                        last_repriced_at,
                                        target_units_per_day
                                   FROM %s
                                 """.formatted(marketTable));
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    states.add(new MarketStateData(
                            result.getString("material"),
                            result.getDouble("market_multiplier"),
                            result.getDouble("featured_multiplier"),
                            result.getLong("featured_until"),
                            result.getLong("last_repriced_at"),
                            result.getLong("target_units_per_day")
                    ));
                }
            }

            try (PreparedStatement statement =
                         connection.prepareStatement("""
                                 SELECT material,
                                        bucket_start,
                                        units_sold,
                                        payout_cents
                                   FROM %s
                                  WHERE bucket_start >= ?
                                 """.formatted(bucketTable))) {
                statement.setLong(1, bucketsSince);

                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        buckets.add(new BucketData(
                                result.getString("material"),
                                result.getLong("bucket_start"),
                                result.getLong("units_sold"),
                                result.getLong("payout_cents")
                        ));
                    }
                }
            }

            try (PreparedStatement statement =
                         connection.prepareStatement("""
                                 SELECT player_uuid,
                                        material,
                                        amount,
                                        total_cents,
                                        last_sold_at
                                   FROM %s
                                 """.formatted(historyTable));
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    try {
                        history.add(new HistoryData(
                                UUID.fromString(
                                        result.getString("player_uuid")
                                ),
                                result.getString("material"),
                                result.getLong("amount"),
                                result.getLong("total_cents"),
                                result.getLong("last_sold_at")
                        ));
                    } catch (IllegalArgumentException ignored) {
                        core.getLogger().warning(
                                "Skipped malformed Sell history UUID "
                                        + result.getString("player_uuid")
                        );
                    }
                }
            }
        }

        return new Snapshot(
                List.copyOf(states),
                List.copyOf(buckets),
                List.copyOf(history)
        );
    }

    @Override
    public void save(SaveBatch batch) throws Exception {
        if (batch.empty() && batch.pruneBucketsBefore() <= 0L) {
            return;
        }

        loadDriver();

        try (Connection connection = connection()) {
            connection.setAutoCommit(false);

            try {
                saveStates(connection, batch.states());
                saveBuckets(connection, batch.buckets());
                saveHistory(connection, batch.history());
                pruneBuckets(
                        connection,
                        batch.pruneBucketsBefore()
                );
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    @Override
    public String name() {
        return "MySQL/MariaDB";
    }

    private void saveStates(
            Connection connection,
            List<MarketStateData> states
    ) throws Exception {
        if (states.isEmpty()) {
            return;
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 material,
                                 market_multiplier,
                                 featured_multiplier,
                                 featured_until,
                                 last_repriced_at,
                                 target_units_per_day
                             ) VALUES (?, ?, ?, ?, ?, ?)
                             ON DUPLICATE KEY UPDATE
                                 market_multiplier = VALUES(market_multiplier),
                                 featured_multiplier = VALUES(featured_multiplier),
                                 featured_until = VALUES(featured_until),
                                 last_repriced_at = VALUES(last_repriced_at),
                                 target_units_per_day = VALUES(target_units_per_day)
                             """.formatted(marketTable))) {
            for (MarketStateData state : states) {
                statement.setString(1, state.material());
                statement.setDouble(2, state.marketMultiplier());
                statement.setDouble(3, state.featuredMultiplier());
                statement.setLong(4, state.featuredUntil());
                statement.setLong(5, state.lastRepricedAt());
                statement.setLong(6, state.targetUnitsPerDay());
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private void saveBuckets(
            Connection connection,
            List<BucketData> buckets
    ) throws Exception {
        if (buckets.isEmpty()) {
            return;
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 material,
                                 bucket_start,
                                 units_sold,
                                 payout_cents
                             ) VALUES (?, ?, ?, ?)
                             ON DUPLICATE KEY UPDATE
                                 units_sold = VALUES(units_sold),
                                 payout_cents = VALUES(payout_cents)
                             """.formatted(bucketTable))) {
            for (BucketData bucket : buckets) {
                statement.setString(1, bucket.material());
                statement.setLong(2, bucket.bucketStart());
                statement.setLong(3, bucket.unitsSold());
                statement.setLong(4, bucket.payoutCents());
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private void saveHistory(
            Connection connection,
            List<HistoryData> history
    ) throws Exception {
        if (history.isEmpty()) {
            return;
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 player_uuid,
                                 material,
                                 amount,
                                 total_cents,
                                 last_sold_at
                             ) VALUES (?, ?, ?, ?, ?)
                             ON DUPLICATE KEY UPDATE
                                 amount = VALUES(amount),
                                 total_cents = VALUES(total_cents),
                                 last_sold_at = VALUES(last_sold_at)
                             """.formatted(historyTable))) {
            for (HistoryData entry : history) {
                statement.setString(
                        1,
                        entry.playerId().toString()
                );
                statement.setString(2, entry.material());
                statement.setLong(3, entry.amount());
                statement.setLong(4, entry.totalCents());
                statement.setLong(5, entry.lastSoldAt());
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private void pruneBuckets(
            Connection connection,
            long before
    ) throws Exception {
        if (before <= 0L) {
            return;
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             DELETE FROM %s
                              WHERE bucket_start < ?
                             """.formatted(bucketTable))) {
            statement.setLong(1, before);
            statement.executeUpdate();
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                jdbcUrl,
                username,
                password
        );
    }

    private void loadDriver() throws ClassNotFoundException {
        if (!driverClass.isBlank()) {
            Class.forName(driverClass);
        }
    }

    private String value(
            FileConfiguration configuration,
            String path,
            String fallback
    ) {
        String configured = configuration.getString(
                path,
                fallback
        );

        return configured == null ? fallback : configured;
    }

    private String safeIdentifier(
            String configured,
            String fallback
    ) {
        String value = configured == null
                ? ""
                : configured.trim();

        if (!value.matches("[A-Za-z0-9_]{1,48}")) {
            core.getLogger().warning(
                    "Invalid Sell market table prefix '"
                            + configured
                            + "' — using "
                            + fallback
            );
            return fallback;
        }

        return value.toLowerCase(Locale.ROOT);
    }
}
