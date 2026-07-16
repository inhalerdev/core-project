package net.mineacle.core.webprofiles.storage;

import net.mineacle.core.Core;
import net.mineacle.core.webprofiles.model.WebProfileRecord;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.Locale;

@SuppressWarnings({"SqlNoDataSourceInspection", "SqlDialectInspection"})
public final class WebProfileRepository {

    private final Core core;
    private final FileConfiguration config;
    private final String table;

    public WebProfileRepository(Core core, FileConfiguration config) {
        this.core = core;
        this.config = config;
        this.table = safeTableName(config.getString("database.table", "mineacle_web_profiles"));
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
                            INDEX idx_team_name (team_name),
                            INDEX idx_first_joined (first_joined_at),
                            INDEX idx_online (online),
                            INDEX idx_updated (updated_at)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.formatted(table));
            }

            migrate();
        } catch (Exception exception) {
            core.getLogger().severe("Could not initialize web profile table: " + exception.getMessage());
        }
    }

    public void upsertAll(Collection<WebProfileRecord> records) {
        if (!config.getBoolean("enabled", true) || records.isEmpty()) {
            return;
        }

        try {
            loadDriver();

            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO %s (
                            uuid,
                            username,
                            display_name,
                            rank_key,
                            rank_name,
                            rank_prefix,
                            rank_color,
                            rank_weight,
                            team_id,
                            team_name,
                            team_role,
                            team_joined_at,
                            balance_cents,
                            balance_formatted,
                            playtime_seconds,
                            playtime_formatted,
                            kills,
                            deaths,
                            kd_ratio,
                            money_rank,
                            kills_rank,
                            playtime_rank,
                            first_joined_at,
                            last_seen,
                            online,
                            updated_at
                         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         ON DUPLICATE KEY UPDATE
                            username = VALUES(username),
                            display_name = VALUES(display_name),
                            rank_key = IF(VALUES(online) = 1, VALUES(rank_key), rank_key),
                            rank_name = IF(VALUES(online) = 1, VALUES(rank_name), rank_name),
                            rank_prefix = IF(VALUES(online) = 1, VALUES(rank_prefix), rank_prefix),
                            rank_color = IF(VALUES(online) = 1, VALUES(rank_color), rank_color),
                            rank_weight = IF(VALUES(online) = 1, VALUES(rank_weight), rank_weight),
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
        } catch (Exception exception) {
            core.getLogger().warning("Could not sync web profiles: " + exception.getMessage());
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
                statement.executeUpdate("UPDATE " + table + " SET online = 0");
            }
        } catch (Exception exception) {
            core.getLogger().warning("Could not mark web profiles offline: " + exception.getMessage());
        }
    }

    private void migrate() throws Exception {
        try (Connection connection = connection()) {
            ensureColumn(connection, "rank_key", "VARCHAR(32) NOT NULL DEFAULT 'default'");
            ensureColumn(connection, "rank_name", "VARCHAR(32) NOT NULL DEFAULT 'Member'");
            ensureColumn(connection, "rank_prefix", "VARCHAR(64) NOT NULL DEFAULT ''");
            ensureColumn(connection, "rank_color", "CHAR(7) NOT NULL DEFAULT '#bbbbbb'");
            ensureColumn(connection, "rank_weight", "INT NOT NULL DEFAULT 0");
            ensureColumn(connection, "team_id", "VARCHAR(36) NOT NULL DEFAULT ''");
            ensureColumn(connection, "team_name", "VARCHAR(32) NOT NULL DEFAULT ''");
            ensureColumn(connection, "team_role", "VARCHAR(32) NOT NULL DEFAULT ''");
            ensureColumn(connection, "team_joined_at", "BIGINT NOT NULL DEFAULT 0");
            ensureColumn(connection, "first_joined_at", "BIGINT NOT NULL DEFAULT 0");

            ensureIndex(connection, "idx_rank", "rank_weight");
            ensureIndex(connection, "idx_rank_key", "rank_key");
            ensureIndex(connection, "idx_team_name", "team_name");
            ensureIndex(connection, "idx_first_joined", "first_joined_at");
        }
    }

    private void ensureColumn(Connection connection, String column, String definition) throws Exception {
        if (hasColumn(connection, column)) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean hasColumn(Connection connection, String column) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SHOW COLUMNS FROM " + table + " LIKE ?"
        )) {
            statement.setString(1, column);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void ensureIndex(Connection connection, String index, String column) throws Exception {
        if (hasIndex(connection, index)) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX " + index + " ON " + table + " (" + column + ")");
        }
    }

    private boolean hasIndex(Connection connection, String index) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SHOW INDEX FROM " + table + " WHERE Key_name = ?"
        )) {
            statement.setString(1, index);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void bind(PreparedStatement statement, WebProfileRecord record) throws Exception {
        statement.setString(1, record.uuid().toString());
        statement.setString(2, limit(record.username(), 16));
        statement.setString(3, limit(record.displayName(), 32));
        statement.setString(4, limit(record.rankKey(), 32));
        statement.setString(5, limit(record.rankName(), 32));
        statement.setString(6, limit(record.rankPrefix(), 64));
        statement.setString(7, limit(record.rankColor(), 7));
        statement.setInt(8, record.rankWeight());
        statement.setString(9, limit(record.teamId(), 36));
        statement.setString(10, limit(record.teamName(), 32));
        statement.setString(11, limit(record.teamRole(), 32));
        statement.setLong(12, record.teamJoinedAt());
        statement.setLong(13, record.balanceCents());
        statement.setString(14, limit(record.balanceFormatted(), 32));
        statement.setLong(15, record.playtimeSeconds());
        statement.setString(16, limit(record.playtimeFormatted(), 32));
        statement.setLong(17, record.kills());
        statement.setLong(18, record.deaths());
        statement.setDouble(19, record.kdRatio());
        statement.setInt(20, record.moneyRank());
        statement.setInt(21, record.killsRank());
        statement.setInt(22, record.playtimeRank());
        statement.setLong(23, record.firstJoinedAt());
        statement.setLong(24, record.lastSeen());
        statement.setBoolean(25, record.online());
        statement.setLong(26, record.updatedAt());
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                config.getString("database.jdbc-url", "jdbc:mysql://127.0.0.1:3306/mineacle"),
                config.getString("database.username", "mineacle_core"),
                config.getString("database.password", "change-me")
        );
    }

    private void loadDriver() throws ClassNotFoundException {
        String driver = config.getString("database.driver-class", "com.mysql.cj.jdbc.Driver");

        if (!driver.isBlank()) {
            Class.forName(driver);
        }
    }

    private String safeTableName(String configured) {
        String value = configured == null ? "" : configured.trim();

        if (!value.matches("[A-Za-z0-9_]{1,64}")) {
            core.getLogger().warning(
                    "Invalid web profile table name '" + configured + "', using mineacle_web_profiles"
            );
            return "mineacle_web_profiles";
        }

        return value.toLowerCase(Locale.ROOT);
    }

    private String limit(String value, int max) {
        if (value == null) {
            return "";
        }

        return value.length() <= max ? value : value.substring(0, max);
    }
}
