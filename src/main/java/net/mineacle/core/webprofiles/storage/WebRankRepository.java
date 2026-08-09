package net.mineacle.core.webprofiles.storage;

import net.mineacle.core.Core;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings({
        "SqlNoDataSourceInspection",
        "SqlDialectInspection"
})
public final class WebRankRepository {

    private static final int READ_BATCH_SIZE = 500;

    private final Core core;
    private final boolean enabled;
    private final String driverClass;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String table;

    private boolean driverLoaded;

    public WebRankRepository(
            Core core,
            FileConfiguration config
    ) {
        this.core = core;
        this.enabled = config.getBoolean(
                "enabled",
                true
        );
        this.driverClass = config.getString(
                "database.driver-class",
                "com.mysql.cj.jdbc.Driver"
        );
        this.jdbcUrl = config.getString(
                "database.jdbc-url",
                "jdbc:mysql://127.0.0.1:3306/mineacle"
        );
        this.username = config.getString(
                "database.username",
                "mineacle_core"
        );
        this.password = config.getString(
                "database.password",
                ""
        );
        this.table = safeTableName(
                config.getString(
                        "database.table"
                )
        );
    }

    public void upsertRanks(
            Collection<RankUpdate> updates
    ) {
        if (!enabled
                || updates == null
                || updates.isEmpty()) {
            return;
        }

        List<RankUpdate> normalized =
                new ArrayList<>(updates.size());

        for (RankUpdate update : updates) {
            if (update != null
                    && update.uuid() != null) {
                normalized.add(update);
            }
        }

        if (normalized.isEmpty()) {
            return;
        }

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

                for (RankUpdate update : normalized) {
                    String safeUsername =
                            normalizeUsername(
                                    update.username()
                            );

                    statement.setString(
                            1,
                            update.uuid().toString()
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
                            limit(update.rankKey(), 32)
                    );
                    statement.setString(
                            5,
                            limit(update.rankName(), 32)
                    );
                    statement.setString(
                            6,
                            limit(update.rankPrefix(), 64)
                    );
                    statement.setString(
                            7,
                            limit(update.rankColor(), 7)
                    );
                    statement.setInt(
                            8,
                            update.rankWeight()
                    );
                    statement.setLong(
                            9,
                            updatedAt
                    );
                    statement.addBatch();
                }

                statement.executeBatch();
            }
        } catch (Exception exception) {
            core.getLogger().warning(
                    "Could not sync "
                            + normalized.size()
                            + " web rank update(s): "
                            + exception.getMessage()
            );
        }
    }

    public Map<UUID, StoredRank> findRanks(
            Collection<UUID> playerIds
    ) {
        if (!enabled
                || playerIds == null
                || playerIds.isEmpty()) {
            return Map.of();
        }

        LinkedHashSet<UUID> uniqueIds =
                new LinkedHashSet<>();

        for (UUID playerId : playerIds) {
            if (playerId != null) {
                uniqueIds.add(playerId);
            }
        }

        if (uniqueIds.isEmpty()) {
            return Map.of();
        }

        List<UUID> ids = List.copyOf(uniqueIds);
        Map<UUID, StoredRank> ranks =
                new HashMap<>(
                        Math.max(
                                16,
                                ids.size() * 2
                        )
                );

        try {
            loadDriver();

            try (Connection connection = connection()) {
                for (int start = 0;
                     start < ids.size();
                     start += READ_BATCH_SIZE) {
                    int end = Math.min(
                            ids.size(),
                            start + READ_BATCH_SIZE
                    );
                    readRankChunk(
                            connection,
                            ids.subList(start, end),
                            ranks
                    );
                }
            }

            return Map.copyOf(ranks);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not batch-read web profile ranks",
                    exception
            );
        }
    }

    private void readRankChunk(
            Connection connection,
            List<UUID> ids,
            Map<UUID, StoredRank> target
    ) throws Exception {
        String placeholders =
                String.join(
                        ",",
                        java.util.Collections.nCopies(
                                ids.size(),
                                "?"
                        )
                );
        String sql = """
                SELECT
                    uuid,
                    rank_key,
                    rank_name,
                    rank_prefix,
                    rank_color,
                    rank_weight
                FROM %s
                WHERE uuid IN (%s)
                """.formatted(
                table,
                placeholders
        );

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            for (int index = 0;
                 index < ids.size();
                 index++) {
                statement.setString(
                        index + 1,
                        ids.get(index).toString()
                );
            }

            try (ResultSet result =
                         statement.executeQuery()) {
                while (result.next()) {
                    String rawUuid =
                            result.getString("uuid");

                    if (rawUuid == null
                            || rawUuid.isBlank()) {
                        continue;
                    }

                    UUID uuid;

                    try {
                        uuid = UUID.fromString(rawUuid);
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }

                    target.put(
                            uuid,
                            new StoredRank(
                                    result.getString(
                                            "rank_key"
                                    ),
                                    result.getString(
                                            "rank_name"
                                    ),
                                    result.getString(
                                            "rank_prefix"
                                    ),
                                    result.getString(
                                            "rank_color"
                                    ),
                                    result.getInt(
                                            "rank_weight"
                                    )
                            )
                    );
                }
            }
        }
    }

    private Connection connection() throws Exception {
        validatePassword();

        return DriverManager.getConnection(
                jdbcUrl,
                username,
                password
        );
    }

    private void loadDriver()
            throws ClassNotFoundException {
        if (driverLoaded || driverClass.isBlank()) {
            return;
        }

        Class.forName(driverClass);
        driverLoaded = true;
    }

    private void validatePassword() {
        if (password.isBlank()
                || password.equalsIgnoreCase(
                "change-me"
        )
                || password
                .toUpperCase(Locale.ROOT)
                .startsWith("CHANGE-ME-")) {
            throw new IllegalStateException(
                    "The live webprofiles.yml database password "
                            + "is still a placeholder"
            );
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

    public record RankUpdate(
            UUID uuid,
            String username,
            String rankKey,
            String rankName,
            String rankPrefix,
            String rankColor,
            int rankWeight
    ) {
    }

    public record StoredRank(
            String key,
            String name,
            String prefix,
            String color,
            int weight
    ) {
    }
}
