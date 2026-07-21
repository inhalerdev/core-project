package net.mineacle.core.webprofiles.storage;

import net.mineacle.core.Core;
import net.mineacle.core.webprofiles.model.WebFightRecord;
import net.mineacle.core.webprofiles.model.WebProfileRecord;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@SuppressWarnings({
        "SqlNoDataSourceInspection",
        "SqlDialectInspection"
})
public final class WebProfileRepository {

    private final Core core;
    private final FileConfiguration config;
    private final String table;
    private final String fightsTable;
    private final String fightsBackupTable;

    private volatile FightIdMode fightIdMode;

    public WebProfileRepository(
            Core core,
            FileConfiguration config
    ) {
        this.core = core;
        this.config = config;
        this.table = safeTableName(
                config.getString(
                        "database.table",
                        "mineacle_web_profiles"
                ),
                "mineacle_web_profiles"
        );
        this.fightsTable = safeTableName(
                config.getString(
                        "database.fights-table",
                        "mineacle_web_fights"
                ),
                "mineacle_web_fights"
        );
        this.fightsBackupTable = safeTableName(
                config.getString(
                        "database.fights-reset-backup-table",
                        "mineacle_web_fights_pre_reset"
                ),
                "mineacle_web_fights_pre_reset"
        );
    }

    public void initialize() {
        if (!config.getBoolean("enabled", true)) {
            return;
        }

        try {
            loadDriver();

            try (Connection connection = connection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS %s (
                            uuid CHAR(36) PRIMARY KEY,
                            username VARCHAR(16) NOT NULL,
                            display_name VARCHAR(32) NOT NULL,
                            rank_key VARCHAR(32) NOT NULL DEFAULT 'default',
                            rank_name VARCHAR(32) NOT NULL DEFAULT 'Member',
                            rank_prefix VARCHAR(64) NOT NULL DEFAULT '',
                            rank_color CHAR(7) NOT NULL DEFAULT '#bbbbbb',
                            rank_weight INT NOT NULL DEFAULT 0,
                            world_key VARCHAR(64) NOT NULL DEFAULT '',
                            world_name VARCHAR(32) NOT NULL DEFAULT '',
                            world_group VARCHAR(24) NOT NULL DEFAULT '',
                            team_id VARCHAR(36) NOT NULL DEFAULT '',
                            team_name VARCHAR(32) NOT NULL DEFAULT '',
                            team_role VARCHAR(32) NOT NULL DEFAULT '',
                            team_joined_at BIGINT NOT NULL DEFAULT 0,
                            balance_cents BIGINT NOT NULL DEFAULT 0,
                            balance_formatted VARCHAR(32) NOT NULL DEFAULT '$0',
                            playtime_seconds BIGINT NOT NULL DEFAULT 0,
                            playtime_formatted VARCHAR(32) NOT NULL DEFAULT '0m',
                            kills BIGINT NOT NULL DEFAULT 0,
                            deaths BIGINT NOT NULL DEFAULT 0,
                            kd_ratio DOUBLE NOT NULL DEFAULT 0,
                            money_rank INT NOT NULL DEFAULT 0,
                            kills_rank INT NOT NULL DEFAULT 0,
                            playtime_rank INT NOT NULL DEFAULT 0,
                            first_joined_at BIGINT NOT NULL DEFAULT 0,
                            last_seen BIGINT NOT NULL DEFAULT 0,
                            online TINYINT(1) NOT NULL DEFAULT 0,
                            updated_at BIGINT NOT NULL DEFAULT 0,
                            INDEX idx_balance (balance_cents),
                            INDEX idx_playtime (playtime_seconds),
                            INDEX idx_kills (kills),
                            INDEX idx_rank (rank_weight),
                            INDEX idx_rank_key (rank_key),
                            INDEX idx_world_key (world_key),
                            INDEX idx_world_group (world_group),
                            INDEX idx_team_name (team_name),
                            INDEX idx_first_joined (first_joined_at),
                            INDEX idx_online (online),
                            INDEX idx_updated (updated_at)
                        ) ENGINE=InnoDB
                        DEFAULT CHARSET=utf8mb4
                        COLLATE=utf8mb4_unicode_ci
                        """.formatted(table));
            }

            migrate();
            initializeFightTable();
            applyConfiguredFightCutoff();
        } catch (Exception exception) {
            core.getLogger().severe(
                    "Could not initialize web profile table: "
                            + exception.getMessage()
            );
        }
    }


    public long fightHistoryCount() throws Exception {
        if (!config.getBoolean(
                "web-fights.enabled",
                true
        )) {
            return 0L;
        }

        loadDriver();

        try (Connection connection = connection()) {
            if (!tableExists(
                    connection,
                    fightsTable
            )) {
                return 0L;
            }

            try (Statement statement =
                         connection.createStatement();
                 ResultSet result =
                         statement.executeQuery(
                                 "SELECT COUNT(*) FROM "
                                         + fightsTable
                         )) {
                return result.next()
                        ? Math.max(
                        0L,
                        result.getLong(1)
                )
                        : 0L;
            }
        }
    }

    /**
     * Archives every live fight row and then clears the website fight table.
     *
     * The operation is transactional. A failed backup or delete rolls back
     * both steps, leaving live fight history unchanged.
     */
    public FightClearResult clearFightHistoryWithBackup()
            throws Exception {
        if (!config.getBoolean(
                "web-fights.enabled",
                true
        )) {
            return new FightClearResult(
                    0L,
                    0L,
                    fightsBackupTable
            );
        }

        loadDriver();

        try (Connection connection = connection()) {
            connection.setAutoCommit(false);

            try {
                if (!tableExists(
                        connection,
                        fightsTable
                )) {
                    connection.commit();

                    return new FightClearResult(
                            0L,
                            0L,
                            fightsBackupTable
                    );
                }

                ensureFightBackupTable(connection);

                long liveRows = countRows(
                        connection,
                        fightsTable
                );
                long backupRowsBefore = countRows(
                        connection,
                        fightsBackupTable
                );

                try (Statement statement =
                             connection.createStatement()) {
                    statement.executeUpdate(
                            "INSERT IGNORE INTO "
                                    + fightsBackupTable
                                    + " SELECT * FROM "
                                    + fightsTable
                    );
                }

                long backupRowsAfter = countRows(
                        connection,
                        fightsBackupTable
                );
                long archived = Math.max(
                        0L,
                        backupRowsAfter
                                - backupRowsBefore
                );
                long removed;

                try (Statement statement =
                             connection.createStatement()) {
                    removed = Math.max(
                            0L,
                            statement.executeUpdate(
                                    "DELETE FROM "
                                            + fightsTable
                            )
                    );
                }

                connection.commit();

                return new FightClearResult(
                        Math.min(
                                liveRows,
                                removed
                        ),
                        archived,
                        fightsBackupTable
                );
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void upsertAll(
            Collection<WebProfileRecord> records
    ) {
        if (!config.getBoolean("enabled", true)
                || records == null
                || records.isEmpty()) {
            return;
        }

        try {
            loadDriver();

            try (Connection connection = connection()) {
                connection.setAutoCommit(false);

                try {
                    detectAndPurgeResetFightHistory(
                            connection,
                            records
                    );

                    try (PreparedStatement statement =
                                 connection.prepareStatement("""
                            INSERT INTO %s (
                               uuid, username, display_name,
                               rank_key, rank_name, rank_prefix,
                               rank_color, rank_weight,
                               world_key, world_name, world_group,
                               team_id, team_name, team_role,
                               team_joined_at,
                               balance_cents, balance_formatted,
                               playtime_seconds, playtime_formatted,
                               kills, deaths, kd_ratio,
                               money_rank, kills_rank, playtime_rank,
                               first_joined_at, last_seen,
                               online, updated_at
                            ) VALUES (
                               ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                               ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                            )
                            ON DUPLICATE KEY UPDATE
                               username = VALUES(username),
                               display_name = VALUES(display_name),
                               rank_key = IF(VALUES(online) = 1, VALUES(rank_key), rank_key),
                               rank_name = IF(VALUES(online) = 1, VALUES(rank_name), rank_name),
                               rank_prefix = IF(VALUES(online) = 1, VALUES(rank_prefix), rank_prefix),
                               rank_color = IF(VALUES(online) = 1, VALUES(rank_color), rank_color),
                               rank_weight = IF(VALUES(online) = 1, VALUES(rank_weight), rank_weight),
                               world_key = IF(VALUES(world_key) <> '', VALUES(world_key), world_key),
                               world_name = IF(VALUES(world_name) <> '', VALUES(world_name), world_name),
                               world_group = IF(VALUES(world_group) <> '', VALUES(world_group), world_group),
                               team_id = VALUES(team_id),
                               team_name = VALUES(team_name),
                               team_role = VALUES(team_role),
                               team_joined_at = VALUES(team_joined_at),
                               balance_cents = VALUES(balance_cents),
                               balance_formatted = VALUES(balance_formatted),
                               playtime_seconds = VALUES(playtime_seconds),
                               playtime_formatted = VALUES(playtime_formatted),
                               kills = VALUES(kills),
                               deaths = VALUES(deaths),
                               kd_ratio = VALUES(kd_ratio),
                               money_rank = VALUES(money_rank),
                               kills_rank = VALUES(kills_rank),
                               playtime_rank = VALUES(playtime_rank),
                               first_joined_at = VALUES(first_joined_at),
                               last_seen = VALUES(last_seen),
                               online = VALUES(online),
                               updated_at = VALUES(updated_at)
                            """.formatted(table))) {
                        for (WebProfileRecord record : records) {
                            bind(statement, record);
                            statement.addBatch();
                        }

                        statement.executeBatch();
                    }

                    connection.commit();
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (Exception exception) {
            core.getLogger().warning(
                    "Could not sync web profiles: "
                            + exception.getMessage()
            );
        }
    }

    public boolean insertFight(
            WebFightRecord record
    ) {
        if (!config.getBoolean("enabled", true)
                || !config.getBoolean(
                "web-fights.enabled",
                true
        )
                || record == null) {
            return false;
        }

        try {
            loadDriver();

            try (Connection connection = connection()) {
                FightIdMode idMode =
                        resolveFightIdMode(connection);
                String columns = idMode
                        == FightIdMode.AUTO_INCREMENT
                        ? """
                           winner_uuid,
                           winner_username,
                           winner_display_name,
                           loser_uuid,
                           loser_username,
                           loser_display_name,
                           world_key,
                           world_name,
                           winner_hearts,
                           loser_hearts,
                           started_at,
                           ended_at,
                           duration_seconds
                           """
                        : """
                           fight_id,
                           winner_uuid,
                           winner_username,
                           winner_display_name,
                           loser_uuid,
                           loser_username,
                           loser_display_name,
                           world_key,
                           world_name,
                           winner_hearts,
                           loser_hearts,
                           started_at,
                           ended_at,
                           duration_seconds
                           """;
                String placeholders = idMode
                        == FightIdMode.AUTO_INCREMENT
                        ? "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?"
                        : "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?";

                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     "INSERT INTO "
                                             + fightsTable
                                             + " ("
                                             + columns
                                             + ") VALUES ("
                                             + placeholders
                                             + ")"
                             )) {
                    int index = 1;

                    if (idMode
                            == FightIdMode.EXPLICIT_UUID) {
                        statement.setString(
                                index++,
                                record.fightId().toString()
                        );
                    } else if (idMode
                            == FightIdMode.EXPLICIT_LONG) {
                        statement.setLong(
                                index++,
                                numericFightId(
                                        record.fightId()
                                )
                        );
                    }

                    statement.setString(
                            index++,
                            record.winnerUuid().toString()
                    );
                    statement.setString(
                            index++,
                            limit(
                                    record.winnerUsername(),
                                    16
                            )
                    );
                    statement.setString(
                            index++,
                            limit(
                                    record.winnerDisplayName(),
                                    32
                            )
                    );
                    statement.setString(
                            index++,
                            record.loserUuid().toString()
                    );
                    statement.setString(
                            index++,
                            limit(
                                    record.loserUsername(),
                                    16
                            )
                    );
                    statement.setString(
                            index++,
                            limit(
                                    record.loserDisplayName(),
                                    32
                            )
                    );
                    statement.setString(
                            index++,
                            limit(
                                    record.worldKey(),
                                    64
                            )
                    );
                    statement.setString(
                            index++,
                            limit(
                                    record.worldName(),
                                    32
                            )
                    );
                    statement.setDouble(
                            index++,
                            nonNegative(
                                    record.winnerHearts()
                            )
                    );
                    statement.setDouble(
                            index++,
                            nonNegative(
                                    record.loserHearts()
                            )
                    );
                    statement.setLong(
                            index++,
                            Math.max(
                                    0L,
                                    record.startedAt()
                            )
                    );
                    statement.setLong(
                            index++,
                            Math.max(
                                    0L,
                                    record.endedAt()
                            )
                    );
                    statement.setLong(
                            index,
                            Math.max(
                                    1L,
                                    record.durationSeconds()
                            )
                    );

                    return statement.executeUpdate() == 1;
                }
            }
        } catch (Exception exception) {
            core.getLogger().warning(
                    "Could not post web fight "
                            + record.fightId()
                            + ": "
                            + exception.getMessage()
            );
            return false;
        }
    }

    public Optional<StoredRank> findRank(UUID uuid) {
        if (!config.getBoolean("enabled", true)
                || uuid == null) {
            return Optional.empty();
        }

        try {
            loadDriver();

            try (Connection connection = connection();
                 PreparedStatement statement =
                         connection.prepareStatement(
                                 "SELECT rank_key, rank_name, "
                                         + "rank_prefix, rank_color, "
                                         + "rank_weight FROM "
                                         + table
                                         + " WHERE uuid = ? LIMIT 1"
                         )) {
                statement.setString(1, uuid.toString());

                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new StoredRank(
                                    result.getString("rank_key"),
                                    result.getString("rank_name"),
                                    result.getString("rank_prefix"),
                                    result.getString("rank_color"),
                                    result.getInt("rank_weight")
                            )
                    );
                }
            }
        } catch (Exception exception) {
            core.getLogger().warning(
                    "Could not read stored web profile rank for "
                            + uuid
                            + ": "
                            + exception.getMessage()
            );
            return Optional.empty();
        }
    }

    public void markOffline() {
        if (!config.getBoolean("enabled", true)) {
            return;
        }

        try {
            loadDriver();

            try (Connection connection = connection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "UPDATE " + table + " SET online = 0"
                );
            }
        } catch (Exception exception) {
            core.getLogger().warning(
                    "Could not mark web profiles offline: "
                            + exception.getMessage()
            );
        }
    }

    private FightIdMode resolveFightIdMode(
            Connection connection
    ) throws Exception {
        FightIdMode cached = fightIdMode;

        if (cached != null) {
            return cached;
        }

        try (PreparedStatement statement =
                     connection.prepareStatement(
                             "SHOW COLUMNS FROM "
                                     + fightsTable
                                     + " LIKE 'fight_id'"
                     );
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException(
                        "The web fights table is missing fight_id"
                );
            }

            String type = result.getString("Type");
            String extra = result.getString("Extra");
            String normalizedType = type == null
                    ? ""
                    : type.toLowerCase(Locale.ROOT);
            String normalizedExtra = extra == null
                    ? ""
                    : extra.toLowerCase(Locale.ROOT);

            if (normalizedExtra.contains(
                    "auto_increment"
            )) {
                cached = FightIdMode.AUTO_INCREMENT;
            } else if (normalizedType.contains("int")) {
                cached = FightIdMode.EXPLICIT_LONG;
            } else {
                cached = FightIdMode.EXPLICIT_UUID;
            }
        }

        fightIdMode = cached;
        return cached;
    }

    private long numericFightId(UUID uuid) {
        long value = uuid.getMostSignificantBits()
                ^ uuid.getLeastSignificantBits();

        if (value == Long.MIN_VALUE) {
            return 1L;
        }

        value = Math.abs(value);
        return value == 0L ? 1L : value;
    }

    private void initializeFightTable()
            throws Exception {
        if (!config.getBoolean(
                "web-fights.enabled",
                true
        )) {
            return;
        }

        try (Connection connection = connection();
             Statement statement =
                     connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        fight_id CHAR(36) PRIMARY KEY,
                        winner_uuid CHAR(36) NOT NULL,
                        winner_username VARCHAR(16) NOT NULL,
                        winner_display_name VARCHAR(32) NOT NULL,
                        loser_uuid CHAR(36) NOT NULL,
                        loser_username VARCHAR(16) NOT NULL,
                        loser_display_name VARCHAR(32) NOT NULL,
                        world_key VARCHAR(64) NOT NULL DEFAULT '',
                        world_name VARCHAR(32) NOT NULL DEFAULT '',
                        winner_hearts DECIMAL(8,2) NOT NULL DEFAULT 0,
                        loser_hearts DECIMAL(8,2) NOT NULL DEFAULT 0,
                        started_at BIGINT NOT NULL DEFAULT 0,
                        ended_at BIGINT NOT NULL DEFAULT 0,
                        duration_seconds BIGINT NOT NULL DEFAULT 0,
                        INDEX idx_fights_winner_ended (
                            winner_uuid,
                            ended_at
                        ),
                        INDEX idx_fights_loser_ended (
                            loser_uuid,
                            ended_at
                        ),
                        INDEX idx_fights_ended (
                            ended_at
                        ),
                        INDEX idx_fights_world (
                            world_key
                        )
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(fightsTable));
        }
    }

    private void detectAndPurgeResetFightHistory(
            Connection connection,
            Collection<WebProfileRecord> records
    ) throws Exception {
        if (!config.getBoolean(
                "web-fights.reset-detection.enabled",
                true
        )) {
            return;
        }

        ExistingTotals previous = existingTotals(connection);

        if (previous.players() <= 0L) {
            return;
        }

        double coverage = records.size()
                / (double) previous.players();
        double requiredCoverage = clamp(
                config.getDouble(
                        "web-fights.reset-detection.minimum-profile-coverage",
                        0.80D
                ),
                0.10D,
                1.0D
        );

        if (coverage < requiredCoverage) {
            return;
        }

        long currentKills = 0L;
        long currentDeaths = 0L;

        for (WebProfileRecord record : records) {
            currentKills = safeAdd(
                    currentKills,
                    record.kills()
            );
            currentDeaths = safeAdd(
                    currentDeaths,
                    record.deaths()
            );
        }

        /*
         * Duel history is combat data, so reset detection compares combat
         * counters only. Playtime can be retained during a combat-stat reset
         * and must not prevent stale fights from being removed.
         */
        long previousCombatEvents = safeAdd(
                previous.kills(),
                previous.deaths()
        );
        long currentCombatEvents = safeAdd(
                currentKills,
                currentDeaths
        );
        long minimumPreviousCombatEvents = Math.max(
                1L,
                config.getLong(
                        "web-fights.reset-detection."
                                + "minimum-previous-combat-events",
                        10L
                )
        );
        double maximumRemainingRatio = clamp(
                config.getDouble(
                        "web-fights.reset-detection.maximum-remaining-ratio",
                        0.25D
                ),
                0.0D,
                0.95D
        );

        if (previousCombatEvents
                < minimumPreviousCombatEvents
                || currentCombatEvents
                > previousCombatEvents
                * maximumRemainingRatio) {
            return;
        }

        if (!tableExists(connection, fightsTable)) {
            return;
        }

        backupAllFightRows(connection);

        try (Statement statement = connection.createStatement()) {
            int removed = statement.executeUpdate(
                    "DELETE FROM " + fightsTable
            );
            core.getLogger().warning(
                    "Detected a global Mineacle stats reset — moved "
                            + removed
                            + " stale web fight records to "
                            + fightsBackupTable
            );
        }
    }

    private ExistingTotals existingTotals(
            Connection connection
    ) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) AS players, "
                             + "COALESCE(SUM(kills), 0) AS kills, "
                             + "COALESCE(SUM(deaths), 0) AS deaths, "
                             + "COALESCE(SUM(playtime_seconds), 0) "
                             + "AS playtime FROM "
                             + table
             )) {
            if (!result.next()) {
                return ExistingTotals.empty();
            }

            return new ExistingTotals(
                    result.getLong("players"),
                    result.getLong("kills"),
                    result.getLong("deaths"),
                    result.getLong("playtime")
            );
        }
    }

    private void applyConfiguredFightCutoff() throws Exception {
        long cutoff = Math.max(
                0L,
                config.getLong(
                        "web-fights.delete-ended-before-epoch-millis",
                        0L
                )
        );

        if (cutoff <= 0L) {
            return;
        }

        try (Connection connection = connection()) {
            if (!tableExists(connection, fightsTable)) {
                return;
            }

            backupFightRowsBefore(
                    connection,
                    cutoff
            );

            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 "DELETE FROM "
                                         + fightsTable
                                         + " WHERE ended_at < ?"
                         )) {
                statement.setLong(1, cutoff);
                int removed = statement.executeUpdate();

                if (removed > 0) {
                    core.getLogger().info(
                            "Moved "
                                    + removed
                                    + " pre-reset web fight records to "
                                    + fightsBackupTable
                    );
                }
            }
        }
    }

    private void backupAllFightRows(
            Connection connection
    ) throws Exception {
        ensureFightBackupTable(connection);

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT IGNORE INTO "
                            + fightsBackupTable
                            + " SELECT * FROM "
                            + fightsTable
            );
        }
    }

    private void backupFightRowsBefore(
            Connection connection,
            long cutoff
    ) throws Exception {
        ensureFightBackupTable(connection);

        try (PreparedStatement statement =
                     connection.prepareStatement(
                             "INSERT IGNORE INTO "
                                     + fightsBackupTable
                                     + " SELECT * FROM "
                                     + fightsTable
                                     + " WHERE ended_at < ?"
                     )) {
            statement.setLong(1, cutoff);
            statement.executeUpdate();
        }
    }

    private void ensureFightBackupTable(
            Connection connection
    ) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS "
                            + fightsBackupTable
                            + " LIKE "
                            + fightsTable
            );
        }
    }

    private void migrate() throws Exception {
        try (Connection connection = connection()) {
            ensureColumn(connection, "rank_key", "VARCHAR(32) NOT NULL DEFAULT 'default'");
            ensureColumn(connection, "rank_name", "VARCHAR(32) NOT NULL DEFAULT 'Member'");
            ensureColumn(connection, "rank_prefix", "VARCHAR(64) NOT NULL DEFAULT ''");
            ensureColumn(connection, "rank_color", "CHAR(7) NOT NULL DEFAULT '#bbbbbb'");
            ensureColumn(connection, "rank_weight", "INT NOT NULL DEFAULT 0");
            ensureColumn(connection, "world_key", "VARCHAR(64) NOT NULL DEFAULT ''");
            ensureColumn(connection, "world_name", "VARCHAR(32) NOT NULL DEFAULT ''");
            ensureColumn(connection, "world_group", "VARCHAR(24) NOT NULL DEFAULT ''");
            ensureColumn(connection, "team_id", "VARCHAR(36) NOT NULL DEFAULT ''");
            ensureColumn(connection, "team_name", "VARCHAR(32) NOT NULL DEFAULT ''");
            ensureColumn(connection, "team_role", "VARCHAR(32) NOT NULL DEFAULT ''");
            ensureColumn(connection, "team_joined_at", "BIGINT NOT NULL DEFAULT 0");
            ensureColumn(connection, "first_joined_at", "BIGINT NOT NULL DEFAULT 0");

            ensureIndex(connection, "idx_rank", "rank_weight");
            ensureIndex(connection, "idx_rank_key", "rank_key");
            ensureIndex(connection, "idx_world_key", "world_key");
            ensureIndex(connection, "idx_world_group", "world_group");
            ensureIndex(connection, "idx_team_name", "team_name");
            ensureIndex(connection, "idx_first_joined", "first_joined_at");
        }
    }

    private void ensureColumn(
            Connection connection,
            String column,
            String definition
    ) throws Exception {
        if (hasColumn(connection, column)) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "ALTER TABLE "
                            + table
                            + " ADD COLUMN "
                            + column
                            + " "
                            + definition
            );
        }
    }

    private boolean hasColumn(
            Connection connection,
            String column
    ) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement(
                             "SHOW COLUMNS FROM "
                                     + table
                                     + " LIKE ?"
                     )) {
            statement.setString(1, column);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void ensureIndex(
            Connection connection,
            String index,
            String column
    ) throws Exception {
        if (hasIndex(connection, index)) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE INDEX "
                            + index
                            + " ON "
                            + table
                            + " ("
                            + column
                            + ")"
            );
        }
    }

    private boolean hasIndex(
            Connection connection,
            String index
    ) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement(
                             "SHOW INDEX FROM "
                                     + table
                                     + " WHERE Key_name = ?"
                     )) {
            statement.setString(1, index);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }


    private long countRows(
            Connection connection,
            String tableName
    ) throws Exception {
        try (Statement statement =
                     connection.createStatement();
             ResultSet result =
                     statement.executeQuery(
                             "SELECT COUNT(*) FROM "
                                     + tableName
                     )) {
            return result.next()
                    ? Math.max(
                    0L,
                    result.getLong(1)
            )
                    : 0L;
        }
    }

    private boolean tableExists(
            Connection connection,
            String tableName
    ) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement(
                             "SHOW TABLES LIKE ?"
                     )) {
            statement.setString(1, tableName);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void bind(
            PreparedStatement statement,
            WebProfileRecord record
    ) throws Exception {
        statement.setString(1, record.uuid().toString());
        statement.setString(2, limit(record.username(), 16));
        statement.setString(3, limit(record.displayName(), 32));
        statement.setString(4, limit(record.rankKey(), 32));
        statement.setString(5, limit(record.rankName(), 32));
        statement.setString(6, limit(record.rankPrefix(), 64));
        statement.setString(7, limit(record.rankColor(), 7));
        statement.setInt(8, record.rankWeight());
        statement.setString(9, limit(record.worldKey(), 64));
        statement.setString(10, limit(record.worldName(), 32));
        statement.setString(11, limit(record.worldGroup(), 24));
        statement.setString(12, limit(record.teamId(), 36));
        statement.setString(13, limit(record.teamName(), 32));
        statement.setString(14, limit(record.teamRole(), 32));
        statement.setLong(15, record.teamJoinedAt());
        statement.setLong(16, record.balanceCents());
        statement.setString(17, limit(record.balanceFormatted(), 32));
        statement.setLong(18, record.playtimeSeconds());
        statement.setString(19, limit(record.playtimeFormatted(), 32));
        statement.setLong(20, record.kills());
        statement.setLong(21, record.deaths());
        statement.setDouble(22, record.kdRatio());
        statement.setInt(23, record.moneyRank());
        statement.setInt(24, record.killsRank());
        statement.setInt(25, record.playtimeRank());
        statement.setLong(26, record.firstJoinedAt());
        statement.setLong(27, record.lastSeen());
        statement.setBoolean(28, record.online());
        statement.setLong(29, record.updatedAt());
    }

    private Connection connection() throws Exception {
        String password = config.getString(
                "database.password",
                "change-me"
        );

        if (password == null
                || password.isBlank()
                || password.equalsIgnoreCase("change-me")
                || password.toUpperCase(Locale.ROOT)
                .startsWith("CHANGE-ME-")) {
            throw new IllegalStateException(
                    "The live webprofiles.yml database password "
                            + "is still a placeholder"
            );
        }

        return DriverManager.getConnection(
                config.getString(
                        "database.jdbc-url",
                        "jdbc:mysql://127.0.0.1:3306/mineacle"
                ),
                config.getString(
                        "database.username",
                        "mineacle_core"
                ),
                password
        );
    }

    private void loadDriver() throws ClassNotFoundException {
        String driver = config.getString(
                "database.driver-class",
                "com.mysql.cj.jdbc.Driver"
        );

        if (driver != null && !driver.isBlank()) {
            Class.forName(driver);
        }
    }

    private String safeTableName(
            String configured,
            String fallback
    ) {
        String value = configured == null
                ? ""
                : configured.trim();

        if (!value.matches("[A-Za-z0-9_]{1,64}")) {
            core.getLogger().warning(
                    "Invalid web table name '"
                            + configured
                            + "', using "
                            + fallback
            );
            return fallback;
        }

        return value.toLowerCase(Locale.ROOT);
    }

    private String limit(String value, int maximum) {
        if (value == null) {
            return "";
        }

        return value.length() <= maximum
                ? value
                : value.substring(0, maximum);
    }

    private long safeAdd(long left, long right) {
        try {
            return Math.addExact(
                    Math.max(0L, left),
                    Math.max(0L, right)
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private double nonNegative(double value) {
        if (!Double.isFinite(value)
                || value < 0.0D) {
            return 0.0D;
        }

        return value;
    }

    private double clamp(
            double value,
            double minimum,
            double maximum
    ) {
        if (!Double.isFinite(value)) {
            return minimum;
        }

        return Math.max(
                minimum,
                Math.min(maximum, value)
        );
    }

    private enum FightIdMode {
        AUTO_INCREMENT,
        EXPLICIT_UUID,
        EXPLICIT_LONG
    }

    public record StoredRank(
            String key,
            String name,
            String prefix,
            String color,
            int weight
    ) {
    }

    private record ExistingTotals(
            long players,
            long kills,
            long deaths,
            long playtimeSeconds
    ) {
        private static ExistingTotals empty() {
            return new ExistingTotals(0L, 0L, 0L, 0L);
        }
    }

    public record FightClearResult(
            long removedRows,
            long archivedRows,
            String backupTable
    ) {
    }

}
