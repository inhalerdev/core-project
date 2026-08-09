package net.mineacle.core.webprofiles.storage;

import net.mineacle.core.Core;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Locale;
import java.util.UUID;

@SuppressWarnings({
        "SqlNoDataSourceInspection",
        "SqlDialectInspection"
})
public final class WebRankRepository {

    private final Core core;
    private final FileConfiguration config;
    private final String table;

    public WebRankRepository(
            Core core,
            FileConfiguration config
    ) {
        this.core = core;
        this.config = config;
        this.table = safeTableName(
                config.getString(
                        "database.table",
                        "mineacle_web_profiles"
                )
        );
    }

    // Updates only the public rank fields for LuckPerms recalculations.
    // Never overwrite money, statistics, team data, last seen, or online
    // state just to forward an offline rank change to the website.
    public void upsertRank(
            UUID uuid,
            String username,
            String rankKey,
            String rankName,
            String rankPrefix,
            String rankColor,
            int rankWeight
    ) {
        if (!config.getBoolean("enabled", true)
                || uuid == null) {
            return;
        }

        String safeUsername = normalizeUsername(username);
        long updatedAt = System.currentTimeMillis();

        try {
            loadDriver();

            try (Connection connection = connection();
                 PreparedStatement statement =
                         connection.prepareStatement("""
                                 INSERT INTO %s (
                                     uuid,
                                     username,
                                     display_name,
                                     rank_key,
                                     rank_name,
                                     rank_prefix,
                                     rank_color,
                                     rank_weight,
                                     online,
                                     updated_at
                                 ) VALUES (
                                     ?, ?, ?, ?, ?, ?, ?, ?, 0, ?
                                 )
                                 ON DUPLICATE KEY UPDATE
                                     rank_key = VALUES(rank_key),
                                     rank_name = VALUES(rank_name),
                                     rank_prefix = VALUES(rank_prefix),
                                     rank_color = VALUES(rank_color),
                                     rank_weight = VALUES(rank_weight),
                                     updated_at = GREATEST(
                                         updated_at,
                                         VALUES(updated_at)
                                     )
                                 """.formatted(table))) {
                statement.setString(
                        1,
                        uuid.toString()
                );
                statement.setString(
                        2,
                        limit(safeUsername, 16)
                );
                statement.setString(
                        3,
                        limit(safeUsername, 32)
                );
                statement.setString(
                        4,
                        limit(rankKey, 32)
                );
                statement.setString(
                        5,
                        limit(rankName, 32)
                );
                statement.setString(
                        6,
                        limit(rankPrefix, 64)
                );
                statement.setString(
                        7,
                        limit(rankColor, 7)
                );
                statement.setInt(
                        8,
                        rankWeight
                );
                statement.setLong(
                        9,
                        updatedAt
                );
                statement.executeUpdate();
            }
        } catch (Exception exception) {
            core.getLogger().warning(
                    "Could not sync offline web rank for "
                            + uuid
                            + ": "
                            + exception.getMessage()
            );
        }
    }

    private Connection connection() throws Exception {
        String password = config.getString(
                "database.password",
                "change-me"
        );

        if (password.isBlank()
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

        if (!driver.isBlank()) {
            Class.forName(driver);
        }
    }

    private String safeTableName(String configured) {
        String fallback = "mineacle_web_profiles";
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

    private String normalizeUsername(String username) {
        if (username != null && !username.isBlank()) {
            return username.trim();
        }

        return "Player";
    }

    private String limit(
            String value,
            int maximum
    ) {
        if (value == null) {
            return "";
        }

        return value.length() <= maximum
                ? value
                : value.substring(0, maximum);
    }
}
