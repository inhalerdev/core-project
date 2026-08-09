package net.mineacle.core.duels.storage;

import net.mineacle.core.Core;
import net.mineacle.core.duels.model.FightResultRecord;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Locale;
import java.util.logging.Level;

@SuppressWarnings({
        "SqlNoDataSourceInspection",
        "SqlDialectInspection"
})
public final class FightRepository {

    private final Core core;

    private volatile Settings settings;
    private volatile boolean ready;

    public FightRepository(
            Core core,
            FileConfiguration config
    ) {
        this.core = core;
        reload(config);
    }

    public synchronized void reload(
            FileConfiguration config
    ) {
        settings = Settings.from(core, config);
        ready = false;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public synchronized void initialize() {
        if (!enabled() || ready) {
            return;
        }

        try {
            loadDriver();

            try (Connection connection = connection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS %s (
                            fight_id CHAR(36) PRIMARY KEY,

                            winner_uuid CHAR(36) NOT NULL,
                            winner_username VARCHAR(16) NOT NULL,
                            winner_display_name VARCHAR(64) NOT NULL,

                            loser_uuid CHAR(36) NOT NULL,
                            loser_username VARCHAR(16) NOT NULL,
                            loser_display_name VARCHAR(64) NOT NULL,

                            world_key VARCHAR(64) NOT NULL,
                            world_name VARCHAR(32) NOT NULL,

                            winner_hearts DECIMAL(6,2) NOT NULL DEFAULT 0,
                            loser_hearts DECIMAL(6,2) NOT NULL DEFAULT 0,

                            started_at BIGINT NOT NULL,
                            ended_at BIGINT NOT NULL,
                            duration_seconds INT NOT NULL DEFAULT 0,

                            INDEX idx_fight_winner (winner_uuid),
                            INDEX idx_fight_loser (loser_uuid),
                            INDEX idx_fight_world (world_key),
                            INDEX idx_fight_ended (ended_at)
                        ) ENGINE=InnoDB
                        DEFAULT CHARSET=utf8mb4
                        COLLATE=utf8mb4_unicode_ci
                        """.formatted(settings.table()));
            }

            ready = true;
            core.getLogger().info(
                    "Web fight history table ready: "
                            + settings.table()
            );
        } catch (Exception exception) {
            ready = false;
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not initialize web fight history table",
                    exception
            );
        }
    }

    public void insert(FightResultRecord record) {
        if (!enabled() || record == null) {
            return;
        }

        if (!ready) {
            initialize();

            if (!ready) {
                return;
            }
        }

        try {
            loadDriver();

            try (Connection connection = connection();
                 PreparedStatement statement =
                         connection.prepareStatement("""
                                 INSERT INTO %s (
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
                                 ) VALUES (
                                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                                 )
                                 """.formatted(settings.table()))) {
                statement.setString(
                        1,
                        record.fightId().toString()
                );
                statement.setString(
                        2,
                        record.winnerUuid().toString()
                );
                statement.setString(
                        3,
                        limit(record.winnerUsername(), 16)
                );
                statement.setString(
                        4,
                        limit(record.winnerDisplayName(), 64)
                );
                statement.setString(
                        5,
                        record.loserUuid().toString()
                );
                statement.setString(
                        6,
                        limit(record.loserUsername(), 16)
                );
                statement.setString(
                        7,
                        limit(record.loserDisplayName(), 64)
                );
                statement.setString(
                        8,
                        limit(record.worldKey(), 64)
                );
                statement.setString(
                        9,
                        limit(record.worldName(), 32)
                );
                statement.setDouble(
                        10,
                        record.winnerHearts()
                );
                statement.setDouble(
                        11,
                        record.loserHearts()
                );
                statement.setLong(
                        12,
                        record.startedAt()
                );
                statement.setLong(
                        13,
                        record.endedAt()
                );
                statement.setInt(
                        14,
                        record.durationSeconds()
                );
                statement.executeUpdate();
            }
        } catch (Exception exception) {
            ready = false;
            core.getLogger().log(
                    Level.WARNING,
                    "Could not save web fight "
                            + record.fightId(),
                    exception
            );
        }
    }

    private Connection connection() throws Exception {
        Settings current = settings;

        return DriverManager.getConnection(
                current.jdbcUrl(),
                current.username(),
                current.password()
        );
    }

    private void loadDriver() throws ClassNotFoundException {
        String driver = settings.driverClass();

        if (!driver.isBlank()) {
            Class.forName(driver);
        }
    }

    private String limit(String value, int maximum) {
        if (value == null) {
            return "";
        }

        return value.length() <= maximum
                ? value
                : value.substring(0, maximum);
    }

    private record Settings(
            boolean enabled,
            String driverClass,
            String jdbcUrl,
            String username,
            String password,
            String table
    ) {

        private static Settings from(
                Core core,
                FileConfiguration config
        ) {
            String configuredTable = config.getString(
                    "database.fights-table",
                    "mineacle_web_fights"
            );
            String table = safeTableName(
                    core,
                    configuredTable
            );

            return new Settings(
                    config.getBoolean(
                            "web-fights.enabled",
                            true
                    ),
                    value(
                            config,
                            "database.driver-class",
                            "com.mysql.cj.jdbc.Driver"
                    ),
                    value(
                            config,
                            "database.jdbc-url",
                            "jdbc:mysql://127.0.0.1:3306/mineacle"
                    ),
                    value(
                            config,
                            "database.username",
                            "mineacle_core"
                    ),
                    value(
                            config,
                            "database.password",
                            ""
                    ),
                    table
            );
        }

        private static String value(
                FileConfiguration config,
                String path,
                String fallback
        ) {
            String configured = config.getString(path, fallback);
            return configured == null ? fallback : configured;
        }

        private static String safeTableName(
                Core core,
                String configured
        ) {
            String value = configured == null
                    ? ""
                    : configured.trim();

            if (!value.matches("[A-Za-z0-9_]{1,64}")) {
                core.getLogger().warning(
                        "Invalid web fight table name '"
                                + configured
                                + "', using mineacle_web_fights"
                );
                return "mineacle_web_fights";
            }

            return value.toLowerCase(Locale.ROOT);
        }
    }
}
