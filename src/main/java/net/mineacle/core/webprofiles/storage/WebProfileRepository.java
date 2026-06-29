package net.mineacle.core.webprofiles.storage;

import net.mineacle.core.Core;
import net.mineacle.core.webprofiles.model.WebProfileRecord;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Collection;

public final class WebProfileRepository {

    private final Core core;
    private final FileConfiguration config;
    private final String table;

    public WebProfileRepository(Core core, FileConfiguration config) {
        this.core = core;
        this.config = config;
        this.table = config.getString("database.table", "mineacle_web_profiles");
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
                            rank_name VARCHAR(32) NOT NULL DEFAULT 'Member',
                            rank_weight INT NOT NULL DEFAULT 0,
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
                            last_seen BIGINT NOT NULL DEFAULT 0,
                            online TINYINT(1) NOT NULL DEFAULT 0,
                            updated_at BIGINT NOT NULL DEFAULT 0,
                            INDEX idx_balance (balance_cents),
                            INDEX idx_playtime (playtime_seconds),
                            INDEX idx_kills (kills),
                            INDEX idx_rank (rank_weight),
                            INDEX idx_online (online),
                            INDEX idx_updated (updated_at)
                        )
                        """.formatted(table));
            }
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
                            rank_name,
                            rank_weight,
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
                            last_seen,
                            online,
                            updated_at
                         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         ON DUPLICATE KEY UPDATE
                            username = VALUES(username),
                            display_name = VALUES(display_name),
                            rank_name = VALUES(rank_name),
                            rank_weight = VALUES(rank_weight),
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

    private void bind(PreparedStatement statement, WebProfileRecord record) throws Exception {
        statement.setString(1, record.uuid().toString());
        statement.setString(2, limit(record.username(), 16));
        statement.setString(3, limit(record.displayName(), 32));
        statement.setString(4, limit(record.rankName(), 32));
        statement.setInt(5, record.rankWeight());
        statement.setLong(6, record.balanceCents());
        statement.setString(7, limit(record.balanceFormatted(), 32));
        statement.setLong(8, record.playtimeSeconds());
        statement.setString(9, limit(record.playtimeFormatted(), 32));
        statement.setLong(10, record.kills());
        statement.setLong(11, record.deaths());
        statement.setDouble(12, record.kdRatio());
        statement.setInt(13, record.moneyRank());
        statement.setInt(14, record.killsRank());
        statement.setInt(15, record.playtimeRank());
        statement.setLong(16, record.lastSeen());
        statement.setBoolean(17, record.online());
        statement.setLong(18, record.updatedAt());
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

        if (driver != null && !driver.isBlank()) {
            Class.forName(driver);
        }
    }

    private String limit(String value, int max) {
        if (value == null) {
            return "";
        }

        return value.length() <= max ? value : value.substring(0, max);
    }
}
