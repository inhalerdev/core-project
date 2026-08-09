package net.mineacle.core.webprofiles.storage;

import net.mineacle.core.Core;
import net.mineacle.core.webprofiles.model.WebTeamRecord;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.Locale;

@SuppressWarnings({"SqlNoDataSourceInspection", "SqlDialectInspection"})
public final class WebTeamRepository {

    private final Core core;
    private final FileConfiguration config;
    private final String table;

    public WebTeamRepository(Core core, FileConfiguration config) {
        this.core = core;
        this.config = config;
        this.table = safeTableName(config.getString("database.teams-table", "mineacle_web_teams"));
    }

    public void initialize() {
        if (!config.getBoolean("enabled", true) || !config.getBoolean("web-teams.enabled", true)) {
            return;
        }

        try {
            loadDriver();

            try (Connection connection = connection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS %s (
                            team_id VARCHAR(36) PRIMARY KEY,
                            team_name VARCHAR(32) NOT NULL,
                            founder_uuid CHAR(36) NOT NULL DEFAULT '',
                            member_count INT NOT NULL DEFAULT 0,
                            online_members INT NOT NULL DEFAULT 0,
                            total_balance_cents BIGINT NOT NULL DEFAULT 0,
                            total_balance_formatted VARCHAR(32) NOT NULL DEFAULT '$0',
                            total_kills BIGINT NOT NULL DEFAULT 0,
                            total_deaths BIGINT NOT NULL DEFAULT 0,
                            kd_ratio DOUBLE NOT NULL DEFAULT 0,
                            capital_rank INT NOT NULL DEFAULT 0,
                            kd_rank INT NOT NULL DEFAULT 0,
                            updated_at BIGINT NOT NULL DEFAULT 0,
                            INDEX idx_team_name (team_name),
                            INDEX idx_member_count (member_count),
                            INDEX idx_online_members (online_members),
                            INDEX idx_total_balance (total_balance_cents),
                            INDEX idx_kd_ratio (kd_ratio),
                            INDEX idx_capital_rank (capital_rank),
                            INDEX idx_kd_rank (kd_rank),
                            INDEX idx_updated (updated_at)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.formatted(table));
            }

            migrate();
        } catch (Exception exception) {
            core.getLogger().severe("Could not initialize web teams table: " + exception.getMessage());
        }
    }

    public void replaceAll(Collection<WebTeamRecord> teams) {
        if (!config.getBoolean("enabled", true) || !config.getBoolean("web-teams.enabled", true)) {
            return;
        }

        try {
            loadDriver();

            try (Connection connection = connection()) {
                boolean oldAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);

                try (Statement delete = connection.createStatement();
                     PreparedStatement insert = connection.prepareStatement("""
                             INSERT INTO %s (
                                team_id,
                                team_name,
                                founder_uuid,
                                member_count,
                                online_members,
                                total_balance_cents,
                                total_balance_formatted,
                                total_kills,
                                total_deaths,
                                kd_ratio,
                                capital_rank,
                                kd_rank,
                                updated_at
                             ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                             """.formatted(table))) {
                    delete.executeUpdate("DELETE FROM " + table);

                    for (WebTeamRecord team : teams) {
                        bind(insert, team);
                        insert.addBatch();
                    }

                    insert.executeBatch();
                    connection.commit();
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(oldAutoCommit);
                }
            }
        } catch (Exception exception) {
            core.getLogger().warning("Could not sync web teams: " + exception.getMessage());
        }
    }

    private void migrate() throws Exception {
        try (Connection connection = connection()) {
            ensureColumn(connection, "founder_uuid", "CHAR(36) NOT NULL DEFAULT ''");
            ensureColumn(connection, "member_count", "INT NOT NULL DEFAULT 0");
            ensureColumn(connection, "online_members", "INT NOT NULL DEFAULT 0");
            ensureColumn(connection, "total_balance_cents", "BIGINT NOT NULL DEFAULT 0");
            ensureColumn(connection, "total_balance_formatted", "VARCHAR(32) NOT NULL DEFAULT '$0'");
            ensureColumn(connection, "total_kills", "BIGINT NOT NULL DEFAULT 0");
            ensureColumn(connection, "total_deaths", "BIGINT NOT NULL DEFAULT 0");
            ensureColumn(connection, "kd_ratio", "DOUBLE NOT NULL DEFAULT 0");
            ensureColumn(connection, "capital_rank", "INT NOT NULL DEFAULT 0");
            ensureColumn(connection, "kd_rank", "INT NOT NULL DEFAULT 0");
            ensureColumn(connection, "updated_at", "BIGINT NOT NULL DEFAULT 0");

            ensureIndex(connection, "idx_team_name", "team_name");
            ensureIndex(connection, "idx_member_count", "member_count");
            ensureIndex(connection, "idx_online_members", "online_members");
            ensureIndex(connection, "idx_total_balance", "total_balance_cents");
            ensureIndex(connection, "idx_kd_ratio", "kd_ratio");
            ensureIndex(connection, "idx_capital_rank", "capital_rank");
            ensureIndex(connection, "idx_kd_rank", "kd_rank");
            ensureIndex(connection, "idx_updated", "updated_at");
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
        try (PreparedStatement statement = connection.prepareStatement("SHOW COLUMNS FROM " + table + " LIKE ?")) {
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
        try (PreparedStatement statement = connection.prepareStatement("SHOW INDEX FROM " + table + " WHERE Key_name = ?")) {
            statement.setString(1, index);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void bind(PreparedStatement statement, WebTeamRecord team) throws Exception {
        statement.setString(1, limit(team.teamId(), 36));
        statement.setString(2, limit(team.teamName(), 32));
        statement.setString(3, limit(team.founderUuid(), 36));
        statement.setInt(4, team.memberCount());
        statement.setInt(5, team.onlineMembers());
        statement.setLong(6, team.totalBalanceCents());
        statement.setString(7, limit(team.totalBalanceFormatted(), 32));
        statement.setLong(8, team.totalKills());
        statement.setLong(9, team.totalDeaths());
        statement.setDouble(10, team.kdRatio());
        statement.setInt(11, team.capitalRank());
        statement.setInt(12, team.kdRank());
        statement.setLong(13, team.updatedAt());
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
            core.getLogger().warning("Invalid web teams table name '" + configured + "', using mineacle_web_teams");
            return "mineacle_web_teams";
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