package net.mineacle.core.sell.storage;

import net.mineacle.core.Core;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dedicated v1.0.42 persistence for shared commodity markets.
 * <p>
 * Old Material-keyed market tables remain untouched. This avoids ambiguous
 * migration and guarantees that reversible forms cannot double-count legacy
 * supply rows.
 */
@SuppressWarnings("SqlNoDataSourceInspection")
public final class CommodityMarketStorage {

    public record MarketStateData(
            String marketKey,
            double marketMultiplier,
            double featuredMultiplier,
            long featuredUntil,
            long lastRepricedAt,
            long targetUnitsPerDay
    ) {
    }

    public record BucketData(
            String marketKey,
            long bucketStart,
            long unitsSold,
            long payoutCents
    ) {
    }

    public record Snapshot(
            List<MarketStateData> states,
            List<BucketData> buckets
    ) {
        public static Snapshot empty() {
            return new Snapshot(
                    List.of(),
                    List.of()
            );
        }
    }

    public record SaveBatch(
            List<MarketStateData> states,
            List<BucketData> buckets,
            long pruneBefore
    ) {
        public boolean empty() {
            return states.isEmpty()
                    && buckets.isEmpty();
        }
    }

    private final Core core;
    private final File yamlFile;

    private final boolean sqlConfigured;
    private final String driverClass;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String stateTable;
    private final String bucketTable;

    private YamlConfiguration yaml;

    public CommodityMarketStorage(
            Core core,
            FileConfiguration sellConfig
    ) {
        this.core = core;
        this.yamlFile = new File(
                core.getDataFolder(),
                "sell-commodity-market.yml"
        );

        String storage =
                nonBlank(
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

        String prefix = safeIdentifier(
                sellConfig.getString(
                        "market.table-prefix",
                        "mineacle_sell"
                )
        );
        stateTable =
                prefix + "_commodity_market";
        bucketTable =
                prefix + "_commodity_buckets";
    }

    public synchronized void initializeYaml() {
        yaml = YamlConfiguration
                .loadConfiguration(
                        yamlFile
                );
    }

    public synchronized Snapshot loadYaml(
            long bucketsSince
    ) {
        ensureYaml();

        List<MarketStateData> states =
                new ArrayList<>();
        List<BucketData> buckets =
                new ArrayList<>();

        var marketSection =
                yaml.getConfigurationSection(
                        "market"
                );

        if (marketSection != null) {
            for (String key
                    : marketSection.getKeys(false)) {
                String path =
                        "market." + key;

                states.add(
                        new MarketStateData(
                                normalizeKey(key),
                                yaml.getDouble(
                                        path
                                                + ".market-multiplier",
                                        1.0D
                                ),
                                yaml.getDouble(
                                        path
                                                + ".featured-multiplier",
                                        1.0D
                                ),
                                yaml.getLong(
                                        path
                                                + ".featured-until",
                                        0L
                                ),
                                yaml.getLong(
                                        path
                                                + ".last-repriced-at",
                                        0L
                                ),
                                yaml.getLong(
                                        path
                                                + ".target-units-per-day",
                                        1L
                                )
                        )
                );
            }
        }

        var bucketSection =
                yaml.getConfigurationSection(
                        "buckets"
                );

        if (bucketSection != null) {
            for (String key
                    : bucketSection.getKeys(false)) {
                var keySection =
                        yaml.getConfigurationSection(
                                "buckets." + key
                        );

                if (keySection == null) {
                    continue;
                }

                for (String rawStart
                        : keySection.getKeys(false)) {
                    try {
                        long start =
                                Long.parseLong(
                                        rawStart
                                );

                        if (start < bucketsSince) {
                            continue;
                        }

                        String path =
                                "buckets."
                                        + key
                                        + "."
                                        + rawStart;

                        buckets.add(
                                new BucketData(
                                        normalizeKey(key),
                                        start,
                                        yaml.getLong(
                                                path
                                                        + ".units-sold",
                                                0L
                                        ),
                                        yaml.getLong(
                                                path
                                                        + ".payout-cents",
                                                0L
                                        )
                                )
                        );
                    } catch (
                            NumberFormatException ignored
                    ) {
                        core.getLogger().warning(
                                "Skipped invalid commodity bucket "
                                        + key
                                        + "/"
                                        + rawStart
                        );
                    }
                }
            }
        }

        return new Snapshot(
                List.copyOf(states),
                List.copyOf(buckets)
        );
    }

    public synchronized void saveYaml(
            SaveBatch batch
    ) throws IOException {
        ensureYaml();

        for (MarketStateData state
                : batch.states()) {
            String path =
                    "market."
                            + state.marketKey();

            yaml.set(
                    path
                            + ".market-multiplier",
                    state.marketMultiplier()
            );
            yaml.set(
                    path
                            + ".featured-multiplier",
                    state.featuredMultiplier()
            );
            yaml.set(
                    path
                            + ".featured-until",
                    state.featuredUntil()
            );
            yaml.set(
                    path
                            + ".last-repriced-at",
                    state.lastRepricedAt()
            );
            yaml.set(
                    path
                            + ".target-units-per-day",
                    state.targetUnitsPerDay()
            );
        }

        for (BucketData bucket
                : batch.buckets()) {
            String path =
                    "buckets."
                            + bucket.marketKey()
                            + "."
                            + bucket.bucketStart();

            yaml.set(
                    path + ".units-sold",
                    bucket.unitsSold()
            );
            yaml.set(
                    path + ".payout-cents",
                    bucket.payoutCents()
            );
        }

        pruneYamlBuckets(
                batch.pruneBefore()
        );
        atomicSaveYaml();
    }

    public boolean sqlConfigured() {
        return sqlConfigured;
    }

    public void initializeSql()
            throws Exception {
        if (!sqlConfigured) {
            return;
        }

        loadDriver();

        try (Connection connection =
                     connection();
             Statement statement =
                     connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        market_key VARCHAR(64) PRIMARY KEY,
                        market_multiplier DECIMAL(10,4) NOT NULL,
                        featured_multiplier DECIMAL(10,4) NOT NULL,
                        featured_until BIGINT NOT NULL,
                        last_repriced_at BIGINT NOT NULL,
                        target_units_per_day BIGINT NOT NULL
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(stateTable));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        market_key VARCHAR(64) NOT NULL,
                        bucket_start BIGINT NOT NULL,
                        units_sold BIGINT NOT NULL,
                        payout_cents BIGINT NOT NULL,
                        PRIMARY KEY (market_key, bucket_start),
                        INDEX idx_commodity_bucket_start (bucket_start)
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(bucketTable));
        }
    }

    public Snapshot loadSql(
            long bucketsSince
    ) throws Exception {
        if (!sqlConfigured) {
            return Snapshot.empty();
        }

        loadDriver();

        List<MarketStateData> states =
                new ArrayList<>();
        List<BucketData> buckets =
                new ArrayList<>();

        try (Connection connection =
                     connection()) {
            try (PreparedStatement statement =
                         connection.prepareStatement("""
                                 SELECT market_key,
                                        market_multiplier,
                                        featured_multiplier,
                                        featured_until,
                                        last_repriced_at,
                                        target_units_per_day
                                   FROM %s
                                 """.formatted(
                                 stateTable
                         ));
                 ResultSet result =
                         statement.executeQuery()) {
                while (result.next()) {
                    states.add(
                            new MarketStateData(
                                    normalizeKey(
                                            result.getString(
                                                    "market_key"
                                            )
                                    ),
                                    result.getDouble(
                                            "market_multiplier"
                                    ),
                                    result.getDouble(
                                            "featured_multiplier"
                                    ),
                                    result.getLong(
                                            "featured_until"
                                    ),
                                    result.getLong(
                                            "last_repriced_at"
                                    ),
                                    result.getLong(
                                            "target_units_per_day"
                                    )
                            )
                    );
                }
            }

            try (PreparedStatement statement =
                         connection.prepareStatement("""
                                 SELECT market_key,
                                        bucket_start,
                                        units_sold,
                                        payout_cents
                                   FROM %s
                                  WHERE bucket_start >= ?
                                 """.formatted(
                                 bucketTable
                         ))) {
                statement.setLong(
                        1,
                        bucketsSince
                );

                try (ResultSet result =
                             statement.executeQuery()) {
                    while (result.next()) {
                        buckets.add(
                                new BucketData(
                                        normalizeKey(
                                                result.getString(
                                                        "market_key"
                                                )
                                        ),
                                        result.getLong(
                                                "bucket_start"
                                        ),
                                        result.getLong(
                                                "units_sold"
                                        ),
                                        result.getLong(
                                                "payout_cents"
                                        )
                                )
                        );
                    }
                }
            }
        }

        return new Snapshot(
                List.copyOf(states),
                List.copyOf(buckets)
        );
    }

    public void saveSql(
            SaveBatch batch
    ) throws Exception {
        if (!sqlConfigured) {
            return;
        }

        loadDriver();

        try (Connection connection =
                     connection()) {
            connection.setAutoCommit(false);

            try {
                saveSqlStates(
                        connection,
                        batch.states()
                );
                saveSqlBuckets(
                        connection,
                        batch.buckets()
                );
                pruneSqlBuckets(
                        connection,
                        batch.pruneBefore()
                );
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(
                        true
                );
            }
        }
    }

    private void saveSqlStates(
            Connection connection,
            List<MarketStateData> states
    ) throws Exception {
        if (states.isEmpty()) {
            return;
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 market_key,
                                 market_multiplier,
                                 featured_multiplier,
                                 featured_until,
                                 last_repriced_at,
                                 target_units_per_day
                             ) VALUES (?, ?, ?, ?, ?, ?)
                             ON DUPLICATE KEY UPDATE
                                 market_multiplier =
                                     VALUES(market_multiplier),
                                 featured_multiplier =
                                     VALUES(featured_multiplier),
                                 featured_until =
                                     VALUES(featured_until),
                                 last_repriced_at =
                                     VALUES(last_repriced_at),
                                 target_units_per_day =
                                     VALUES(target_units_per_day)
                             """.formatted(
                             stateTable
                     ))) {
            for (MarketStateData state
                    : states) {
                statement.setString(
                        1,
                        state.marketKey()
                );
                statement.setDouble(
                        2,
                        state.marketMultiplier()
                );
                statement.setDouble(
                        3,
                        state.featuredMultiplier()
                );
                statement.setLong(
                        4,
                        state.featuredUntil()
                );
                statement.setLong(
                        5,
                        state.lastRepricedAt()
                );
                statement.setLong(
                        6,
                        state.targetUnitsPerDay()
                );
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private void saveSqlBuckets(
            Connection connection,
            List<BucketData> buckets
    ) throws Exception {
        if (buckets.isEmpty()) {
            return;
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 market_key,
                                 bucket_start,
                                 units_sold,
                                 payout_cents
                             ) VALUES (?, ?, ?, ?)
                             ON DUPLICATE KEY UPDATE
                                 units_sold = VALUES(units_sold),
                                 payout_cents = VALUES(payout_cents)
                             """.formatted(
                             bucketTable
                     ))) {
            for (BucketData bucket
                    : buckets) {
                statement.setString(
                        1,
                        bucket.marketKey()
                );
                statement.setLong(
                        2,
                        bucket.bucketStart()
                );
                statement.setLong(
                        3,
                        bucket.unitsSold()
                );
                statement.setLong(
                        4,
                        bucket.payoutCents()
                );
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private void pruneSqlBuckets(
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
                             """.formatted(
                             bucketTable
                     ))) {
            statement.setLong(
                    1,
                    before
            );
            statement.executeUpdate();
        }
    }

    private void pruneYamlBuckets(
            long before
    ) {
        if (before <= 0L) {
            return;
        }

        var markets =
                yaml.getConfigurationSection(
                        "buckets"
                );

        if (markets == null) {
            return;
        }

        for (String key
                : markets.getKeys(false)) {
            var buckets =
                    yaml.getConfigurationSection(
                            "buckets." + key
                    );

            if (buckets == null) {
                continue;
            }

            for (String rawStart
                    : new ArrayList<>(
                    buckets.getKeys(false)
            )) {
                try {
                    if (Long.parseLong(
                            rawStart
                    ) < before) {
                        yaml.set(
                                "buckets."
                                        + key
                                        + "."
                                        + rawStart,
                                null
                        );
                    }
                } catch (
                        NumberFormatException exception
                ) {
                    yaml.set(
                            "buckets."
                                    + key
                                    + "."
                                    + rawStart,
                            null
                    );
                }
            }
        }
    }

    private void atomicSaveYaml()
            throws IOException {
        File folder =
                core.getDataFolder();

        if (!folder.exists()
                && !folder.mkdirs()
                && !folder.exists()) {
            throw new IOException(
                    "Could not create MineacleCore data folder"
            );
        }

        File temporary =
                new File(
                        folder,
                        yamlFile.getName()
                                + ".tmp"
                );

        yaml.save(temporary);

        try {
            Files.move(
                    temporary.toPath(),
                    yamlFile.toPath(),
                    StandardCopyOption
                            .ATOMIC_MOVE,
                    StandardCopyOption
                            .REPLACE_EXISTING
            );
        } catch (
                AtomicMoveNotSupportedException exception
        ) {
            Files.move(
                    temporary.toPath(),
                    yamlFile.toPath(),
                    StandardCopyOption
                            .REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(
                    temporary.toPath()
            );
        }
    }

    private void ensureYaml() {
        if (yaml == null) {
            initializeYaml();
        }
    }

    private Connection connection()
            throws Exception {
        return DriverManager.getConnection(
                jdbcUrl,
                username,
                password
        );
    }

    private void loadDriver()
            throws ClassNotFoundException {
        if (!driverClass.isBlank()) {
            Class.forName(
                    driverClass
            );
        }
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
            String configured
    ) {
        String fallback = "mineacle_sell";
        String value =
                configured == null
                        ? ""
                        : configured.trim();

        if (!value.matches(
                "[A-Za-z0-9_]{1,40}"
        )) {
            core.getLogger().warning(
                    "Invalid Sell market table prefix '"
                            + configured
                            + "' — using "
                            + fallback
            );
            return fallback;
        }

        return value.toLowerCase(
                Locale.ROOT
        );
    }

    private String normalizeKey(
            String raw
    ) {
        if (raw == null
                || raw.isBlank()) {
            return "";
        }

        return raw.trim()
                .toUpperCase(
                        Locale.ROOT
                );
    }
}
