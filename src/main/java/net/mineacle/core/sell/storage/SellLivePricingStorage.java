package net.mineacle.core.sell.storage;

import net.mineacle.core.Core;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Durable price-book storage for the revision-10 live Sell governor.
 *
 * <p>Generation publication is two-phase. A complete candidate and all market
 * rows are committed as STAGED first. Only after the runtime catalog swaps
 * atomically does the service promote that generation to ACTIVE and supersede
 * the previous one. A restart therefore ignores partial/staged work.</p>
 */
@SuppressWarnings("SqlNoDataSourceInspection")
public final class SellLivePricingStorage {

    public record PricePoint(
            String marketKey,
            BigDecimal referenceUnitCents,
            double liveMultiplier,
            double effectiveMultiplier,
            double targetMultiplier,
            double confidence,
            String learningState
    ) {
        public PricePoint {
            marketKey =
                    normalizeKey(marketKey);
            referenceUnitCents =
                    referenceUnitCents == null
                            ? BigDecimal.ZERO
                            : referenceUnitCents;
            learningState =
                    learningState == null
                            ? ""
                            : learningState;
        }
    }

    public record GenerationDraft(
            int catalogRevision,
            String referenceFingerprint,
            double macroMultiplier,
            BigDecimal recentIssuanceCentsPerPlayerHour,
            BigDecimal baselineIssuanceCentsPerPlayerHour,
            long evaluatedAt,
            Map<String, PricePoint> prices
    ) {
        public GenerationDraft {
            referenceFingerprint =
                    referenceFingerprint == null
                            ? ""
                            : referenceFingerprint;
            recentIssuanceCentsPerPlayerHour =
                    recentIssuanceCentsPerPlayerHour == null
                            ? BigDecimal.ZERO
                            : recentIssuanceCentsPerPlayerHour;
            baselineIssuanceCentsPerPlayerHour =
                    baselineIssuanceCentsPerPlayerHour == null
                            ? BigDecimal.ZERO
                            : baselineIssuanceCentsPerPlayerHour;
            prices =
                    prices == null
                            ? Map.of()
                            : Map.copyOf(prices);
        }
    }

    public record Generation(
            long generationId,
            int catalogRevision,
            String referenceFingerprint,
            double macroMultiplier,
            BigDecimal recentIssuanceCentsPerPlayerHour,
            BigDecimal baselineIssuanceCentsPerPlayerHour,
            long evaluatedAt,
            long createdAt,
            long activatedAt,
            Map<String, PricePoint> prices
    ) {
        public Generation {
            referenceFingerprint =
                    referenceFingerprint == null
                            ? ""
                            : referenceFingerprint;
            recentIssuanceCentsPerPlayerHour =
                    recentIssuanceCentsPerPlayerHour == null
                            ? BigDecimal.ZERO
                            : recentIssuanceCentsPerPlayerHour;
            baselineIssuanceCentsPerPlayerHour =
                    baselineIssuanceCentsPerPlayerHour == null
                            ? BigDecimal.ZERO
                            : baselineIssuanceCentsPerPlayerHour;
            prices =
                    prices == null
                            ? Map.of()
                            : Map.copyOf(prices);
        }

        public Map<String, Double> liveMultipliers() {
            Map<String, Double> result =
                    new LinkedHashMap<>();

            for (PricePoint point : prices.values()) {
                if (point.marketKey().isBlank()
                        || !Double.isFinite(
                        point.liveMultiplier()
                )
                        || point.liveMultiplier()
                        <= 0.0D) {
                    continue;
                }

                result.put(
                        point.marketKey(),
                        point.liveMultiplier()
                );
            }

            return Map.copyOf(result);
        }
    }

    private final boolean sqlConfigured;
    private final String driverClass;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int queryTimeoutSeconds;

    private final String generationTable;
    private final String priceTable;

    public SellLivePricingStorage(
            Core core,
            FileConfiguration sellConfig
    ) {
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
                        || storage.equalsIgnoreCase(
                        "mariadb"
                );

        File databaseFile =
                new File(
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

        driverClass =
                nonBlank(
                        database.getString(
                                "database.driver-class",
                                "com.mysql.cj.jdbc.Driver"
                        ),
                        "com.mysql.cj.jdbc.Driver"
                );
        jdbcUrl =
                nonBlank(
                        database.getString(
                                "database.jdbc-url",
                                "jdbc:mysql://127.0.0.1:3306/mineacle"
                        ),
                        "jdbc:mysql://127.0.0.1:3306/mineacle"
                );
        username =
                nonBlank(
                        database.getString(
                                "database.username",
                                "mineacle_core"
                        ),
                        "mineacle_core"
                );
        password =
                database.getString(
                        "database.password",
                        ""
                );
        queryTimeoutSeconds =
                Math.clamp(
                        database.getInt(
                                "database.query-timeout-seconds",
                                5
                        ),
                        1,
                        30
                );

        String prefix =
                safeIdentifier(
                        sellConfig.getString(
                                "market.table-prefix",
                                "mineacle_sell"
                        )
                );
        generationTable =
                prefix
                        + "_v10_price_generations";
        priceTable =
                prefix
                        + "_v10_price_book";
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

        try (Connection connection =
                     connection();
             Statement statement =
                     connection.createStatement()) {
            statement.setQueryTimeout(
                    queryTimeoutSeconds
            );

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        generation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        status VARCHAR(16) NOT NULL,
                        catalog_revision INT NOT NULL,
                        reference_fingerprint CHAR(64) NOT NULL,
                        macro_multiplier DECIMAL(10,6) NOT NULL,
                        recent_issuance_cents_per_player_hour DECIMAL(24,8) NOT NULL,
                        baseline_issuance_cents_per_player_hour DECIMAL(24,8) NOT NULL,
                        evaluated_at BIGINT NOT NULL,
                        created_at BIGINT NOT NULL,
                        activated_at BIGINT NOT NULL,
                        INDEX idx_v10_price_generation_status (status, generation_id),
                        INDEX idx_v10_price_generation_fingerprint (reference_fingerprint)
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(
                    generationTable
            ));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        generation_id BIGINT NOT NULL,
                        market_key VARCHAR(64) NOT NULL,
                        reference_unit_cents DECIMAL(24,8) NOT NULL,
                        live_multiplier DECIMAL(10,6) NOT NULL,
                        effective_multiplier DECIMAL(10,6) NOT NULL,
                        target_multiplier DECIMAL(10,6) NOT NULL,
                        confidence DECIMAL(10,6) NOT NULL,
                        learning_state VARCHAR(24) NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (generation_id, market_key),
                        INDEX idx_v10_price_book_market (market_key, generation_id)
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(
                    priceTable
            ));

            long abandonedBefore =
                    System.currentTimeMillis()
                            - 24L * 60L * 60L * 1_000L;
            statement.executeUpdate(
                    "UPDATE "
                            + generationTable
                            + " SET status = 'ABANDONED' "
                            + "WHERE status = 'STAGED' "
                            + "AND created_at < "
                            + abandonedBefore
            );
        }
    }

    public Generation loadLatestActive()
            throws Exception {
        if (!sqlConfigured) {
            return null;
        }

        loadDriver();

        try (Connection connection =
                     connection();
             PreparedStatement statement =
                     connection.prepareStatement("""
                             SELECT generation_id,
                                    catalog_revision,
                                    reference_fingerprint,
                                    macro_multiplier,
                                    recent_issuance_cents_per_player_hour,
                                    baseline_issuance_cents_per_player_hour,
                                    evaluated_at,
                                    created_at,
                                    activated_at
                             FROM %s
                             WHERE status = 'ACTIVE'
                             ORDER BY generation_id DESC
                             LIMIT 1
                             """.formatted(
                             generationTable
                     ))) {
            statement.setQueryTimeout(
                    queryTimeoutSeconds
            );

            try (ResultSet result =
                         statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }

                long generationId =
                        result.getLong(
                                "generation_id"
                        );

                return new Generation(
                        generationId,
                        result.getInt(
                                "catalog_revision"
                        ),
                        result.getString(
                                "reference_fingerprint"
                        ),
                        result.getDouble(
                                "macro_multiplier"
                        ),
                        result.getBigDecimal(
                                "recent_issuance_cents_per_player_hour"
                        ),
                        result.getBigDecimal(
                                "baseline_issuance_cents_per_player_hour"
                        ),
                        result.getLong(
                                "evaluated_at"
                        ),
                        result.getLong(
                                "created_at"
                        ),
                        result.getLong(
                                "activated_at"
                        ),
                        loadPrices(
                                connection,
                                generationId
                        )
                );
            }
        }
    }

    public long stageGeneration(
            GenerationDraft draft
    ) throws Exception {
        if (!sqlConfigured
                || draft == null
                || draft.catalogRevision() <= 0
                || draft.referenceFingerprint()
                .isBlank()) {
            throw new IllegalStateException(
                    "Live Sell price storage is not ready"
            );
        }

        loadDriver();
        long now =
                System.currentTimeMillis();

        try (Connection connection =
                     connection()) {
            connection.setAutoCommit(false);

            try {
                long generationId;

                try (PreparedStatement statement =
                             connection.prepareStatement("""
                                     INSERT INTO %s (
                                         status,
                                         catalog_revision,
                                         reference_fingerprint,
                                         macro_multiplier,
                                         recent_issuance_cents_per_player_hour,
                                         baseline_issuance_cents_per_player_hour,
                                         evaluated_at,
                                         created_at,
                                         activated_at
                                     ) VALUES (
                                         'STAGED', ?, ?, ?, ?, ?, ?, ?, 0
                                     )
                                     """.formatted(
                                     generationTable
                             ),
                                     Statement
                                             .RETURN_GENERATED_KEYS)) {
                    statement.setQueryTimeout(
                            queryTimeoutSeconds
                    );
                    statement.setInt(
                            1,
                            draft.catalogRevision()
                    );
                    statement.setString(
                            2,
                            draft.referenceFingerprint()
                    );
                    statement.setDouble(
                            3,
                            draft.macroMultiplier()
                    );
                    statement.setBigDecimal(
                            4,
                            draft.recentIssuanceCentsPerPlayerHour()
                    );
                    statement.setBigDecimal(
                            5,
                            draft.baselineIssuanceCentsPerPlayerHour()
                    );
                    statement.setLong(
                            6,
                            draft.evaluatedAt()
                    );
                    statement.setLong(
                            7,
                            now
                    );
                    statement.executeUpdate();

                    try (ResultSet keys =
                                 statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new IllegalStateException(
                                    "Could not allocate Sell live generation"
                            );
                        }

                        generationId =
                                keys.getLong(1);
                    }
                }

                savePrices(
                        connection,
                        generationId,
                        draft.prices(),
                        now
                );
                connection.commit();
                return generationId;
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
                    connection.setAutoCommit(
                            true
                    );
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void activateGeneration(
            long generationId
    ) throws Exception {
        if (!sqlConfigured
                || generationId <= 0L) {
            throw new IllegalArgumentException(
                    "generationId"
            );
        }

        loadDriver();

        try (Connection connection =
                     connection()) {
            connection.setAutoCommit(false);

            try {
                String status;

                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     "SELECT status FROM "
                                             + generationTable
                                             + " WHERE generation_id = ? FOR UPDATE"
                             )) {
                    statement.setQueryTimeout(
                            queryTimeoutSeconds
                    );
                    statement.setLong(
                            1,
                            generationId
                    );

                    try (ResultSet result =
                                 statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalStateException(
                                    "Missing staged Sell live generation "
                                            + generationId
                            );
                        }

                        status =
                                result.getString(
                                        "status"
                                );
                    }
                }

                if ("ACTIVE".equalsIgnoreCase(
                        status
                )) {
                    connection.commit();
                    return;
                }

                if (!"STAGED".equalsIgnoreCase(
                        status
                )) {
                    throw new IllegalStateException(
                            "Sell live generation "
                                    + generationId
                                    + " is "
                                    + status
                    );
                }

                try (PreparedStatement supersede =
                             connection.prepareStatement(
                                     "UPDATE "
                                             + generationTable
                                             + " SET status = 'SUPERSEDED' "
                                             + "WHERE status = 'ACTIVE' "
                                             + "AND generation_id <> ?"
                             );
                     PreparedStatement activate =
                             connection.prepareStatement(
                                     "UPDATE "
                                             + generationTable
                                             + " SET status = 'ACTIVE', "
                                             + "activated_at = ? "
                                             + "WHERE generation_id = ? "
                                             + "AND status = 'STAGED'"
                             )) {
                    supersede.setQueryTimeout(
                            queryTimeoutSeconds
                    );
                    supersede.setLong(
                            1,
                            generationId
                    );
                    supersede.executeUpdate();

                    activate.setQueryTimeout(
                            queryTimeoutSeconds
                    );
                    activate.setLong(
                            1,
                            System.currentTimeMillis()
                    );
                    activate.setLong(
                            2,
                            generationId
                    );

                    if (activate.executeUpdate() != 1) {
                        throw new IllegalStateException(
                                "Could not activate Sell live generation "
                                        + generationId
                        );
                    }
                }

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
                    connection.setAutoCommit(
                            true
                    );
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void rejectGeneration(
            long generationId
    ) {
        if (!sqlConfigured
                || generationId <= 0L) {
            return;
        }

        try {
            loadDriver();

            try (Connection connection =
                         connection();
                 PreparedStatement statement =
                         connection.prepareStatement(
                                 "UPDATE "
                                         + generationTable
                                         + " SET status = 'REJECTED' "
                                         + "WHERE generation_id = ? "
                                         + "AND status = 'STAGED'"
                         )) {
                statement.setQueryTimeout(
                        queryTimeoutSeconds
                );
                statement.setLong(
                        1,
                        generationId
                );
                statement.executeUpdate();
            }
        } catch (Exception ignored) {
        }
    }

    public void pruneBefore(
            long cutoff
    ) throws Exception {
        if (!sqlConfigured
                || cutoff <= 0L) {
            return;
        }

        loadDriver();

        try (Connection connection =
                     connection()) {
            connection.setAutoCommit(false);

            try {
                try (PreparedStatement deletePrices =
                             connection.prepareStatement(
                                     "DELETE p FROM "
                                             + priceTable
                                             + " p INNER JOIN "
                                             + generationTable
                                             + " g ON g.generation_id = p.generation_id "
                                             + "WHERE g.status IN ('SUPERSEDED','REJECTED','ABANDONED') "
                                             + "AND g.created_at < ?"
                             );
                     PreparedStatement deleteGenerations =
                             connection.prepareStatement(
                                     "DELETE FROM "
                                             + generationTable
                                             + " WHERE status IN ('SUPERSEDED','REJECTED','ABANDONED') "
                                             + "AND created_at < ?"
                             )) {
                    deletePrices.setQueryTimeout(
                            queryTimeoutSeconds
                    );
                    deletePrices.setLong(
                            1,
                            cutoff
                    );
                    deletePrices.executeUpdate();

                    deleteGenerations.setQueryTimeout(
                            queryTimeoutSeconds
                    );
                    deleteGenerations.setLong(
                            1,
                            cutoff
                    );
                    deleteGenerations.executeUpdate();
                }

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
                    connection.setAutoCommit(
                            true
                    );
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Map<String, PricePoint> loadPrices(
            Connection connection,
            long generationId
    ) throws Exception {
        Map<String, PricePoint> result =
                new LinkedHashMap<>();

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             SELECT market_key,
                                    reference_unit_cents,
                                    live_multiplier,
                                    effective_multiplier,
                                    target_multiplier,
                                    confidence,
                                    learning_state
                             FROM %s
                             WHERE generation_id = ?
                             ORDER BY market_key
                             """.formatted(
                             priceTable
                     ))) {
            statement.setQueryTimeout(
                    queryTimeoutSeconds
            );
            statement.setLong(
                    1,
                    generationId
            );

            try (ResultSet rows =
                         statement.executeQuery()) {
                while (rows.next()) {
                    PricePoint point =
                            new PricePoint(
                                    rows.getString(
                                            "market_key"
                                    ),
                                    rows.getBigDecimal(
                                            "reference_unit_cents"
                                    ),
                                    rows.getDouble(
                                            "live_multiplier"
                                    ),
                                    rows.getDouble(
                                            "effective_multiplier"
                                    ),
                                    rows.getDouble(
                                            "target_multiplier"
                                    ),
                                    rows.getDouble(
                                            "confidence"
                                    ),
                                    rows.getString(
                                            "learning_state"
                                    )
                            );
                    result.put(
                            point.marketKey(),
                            point
                    );
                }
            }
        }

        return Map.copyOf(result);
    }

    private void savePrices(
            Connection connection,
            long generationId,
            Map<String, PricePoint> prices,
            long now
    ) throws Exception {
        if (prices == null
                || prices.isEmpty()) {
            return;
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 generation_id,
                                 market_key,
                                 reference_unit_cents,
                                 live_multiplier,
                                 effective_multiplier,
                                 target_multiplier,
                                 confidence,
                                 learning_state,
                                 updated_at
                             ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                             """.formatted(
                             priceTable
                     ))) {
            statement.setQueryTimeout(
                    queryTimeoutSeconds
            );

            for (PricePoint point
                    : prices.values()) {
                statement.setLong(
                        1,
                        generationId
                );
                statement.setString(
                        2,
                        point.marketKey()
                );
                statement.setBigDecimal(
                        3,
                        point.referenceUnitCents()
                );
                statement.setDouble(
                        4,
                        point.liveMultiplier()
                );
                statement.setDouble(
                        5,
                        point.effectiveMultiplier()
                );
                statement.setDouble(
                        6,
                        point.targetMultiplier()
                );
                statement.setDouble(
                        7,
                        point.confidence()
                );
                statement.setString(
                        8,
                        point.learningState()
                );
                statement.setLong(
                        9,
                        now
                );
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private void loadDriver()
            throws Exception {
        Class.forName(driverClass);
    }

    private Connection connection()
            throws Exception {
        return DriverManager.getConnection(
                jdbcUrl,
                username,
                password
        );
    }

    private static String normalizeKey(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return "";
        }

        return value.trim()
                .toUpperCase(
                        Locale.ROOT
                )
                .replace('-', '_')
                .replace(' ', '_');
    }

    private static String nonBlank(
            String value,
            String fallback
    ) {
        return value == null
                || value.isBlank()
                ? fallback
                : value.trim();
    }

    private static String safeIdentifier(
            String raw
    ) {
        String value =
                nonBlank(
                        raw,
                        "mineacle_sell"
                )
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (!value.matches(
                "[a-z0-9_]+"
        )) {
            return "mineacle_sell";
        }

        return value;
    }
}
