package net.mineacle.core.webprofiles.auth;

import org.bukkit.configuration.file.FileConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@SuppressWarnings({
        "SqlNoDataSourceInspection",
        "SqlDialectInspection"
})
public final class WebVerificationRepository {

    private static final String DEFAULT_TABLE =
            "mineacle_web_verifications";
    private static final int USERNAME_MAX_LENGTH = 16;

    private final Core.Core core;
    private final FileConfiguration config;
    private final String table;

    public WebVerificationRepository(
            Core.Core core,
            FileConfiguration config
    ) {
        this.core = core;
        this.config = config;
        this.table = safeTableName(
                config.getString(
                        "website-auth.verification-table",
                        DEFAULT_TABLE
                )
        );
    }

    public void initialize() {
        if (!config.getBoolean(
                "website-auth.enabled",
                true
        )) {
            return;
        }

        try {
            loadDriver();

            try (Connection connection = connection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS %s (
                            challenge_id CHAR(32) NOT NULL,
                            uuid CHAR(36) NOT NULL,
                            username VARCHAR(16) NOT NULL,
                            username_lower VARCHAR(16) NOT NULL,
                            code_hash CHAR(64) NOT NULL,
                            created_at BIGINT UNSIGNED NOT NULL,
                            expires_at BIGINT UNSIGNED NOT NULL,
                            verified_at BIGINT UNSIGNED NULL,
                            verified_username VARCHAR(16) NULL,
                            consumed_at BIGINT UNSIGNED NULL,
                            PRIMARY KEY (challenge_id),
                            KEY idx_mineacle_verification_code (code_hash),
                            KEY idx_mineacle_verification_uuid (uuid),
                            KEY idx_mineacle_verification_expiry (expires_at),
                            KEY idx_mineacle_verification_state (
                                verified_at,
                                consumed_at
                            )
                        ) ENGINE=InnoDB
                        DEFAULT CHARSET=utf8mb4
                        COLLATE=utf8mb4_unicode_ci
                        """.formatted(table));
            }
        } catch (Exception exception) {
            core.getLogger().severe(
                    "Could not initialize website verification table: "
                            + exception.getMessage()
            );
        }
    }

    public VerificationResult verify(
            UUID playerUuid,
            String playerName,
            String rawCode
    ) {
        if (!config.getBoolean(
                "website-auth.enabled",
                true
        )) {
            return VerificationResult.DISABLED;
        }

        String code = normalizeCode(rawCode);

        if (playerUuid == null || code == null) {
            return VerificationResult.INVALID_CODE;
        }

        try {
            loadDriver();

            try (Connection connection = connection()) {
                connection.setAutoCommit(false);

                try {
                    String challengeId;
                    String expectedUuid;
                    long expiresAt;
                    long verifiedAt;
                    long consumedAt;

                    try (PreparedStatement statement =
                                 connection.prepareStatement(
                                         "SELECT challenge_id, uuid, "
                                                 + "expires_at, verified_at, "
                                                 + "consumed_at FROM "
                                                 + table
                                                 + " WHERE code_hash = ? "
                                                 + "ORDER BY created_at DESC "
                                                 + "LIMIT 1 FOR UPDATE"
                                 )) {
                        statement.setString(
                                1,
                                hashCode(code)
                        );

                        try (ResultSet result =
                                     statement.executeQuery()) {
                            if (!result.next()) {
                                connection.rollback();
                                return VerificationResult.INVALID_CODE;
                            }

                            challengeId = result.getString(
                                    "challenge_id"
                            );
                            expectedUuid = result.getString("uuid");
                            expiresAt = result.getLong("expires_at");
                            verifiedAt = result.getLong("verified_at");
                            consumedAt = result.getLong("consumed_at");
                        }
                    }

                    long now = System.currentTimeMillis() / 1000L;

                    if (consumedAt > 0L || verifiedAt > 0L) {
                        connection.rollback();
                        return VerificationResult.ALREADY_USED;
                    }

                    if (expiresAt <= now) {
                        connection.rollback();
                        return VerificationResult.EXPIRED;
                    }

                    if (!playerUuid.toString().equalsIgnoreCase(
                            expectedUuid
                    )) {
                        connection.rollback();
                        return VerificationResult.WRONG_PLAYER;
                    }

                    try (PreparedStatement statement =
                                 connection.prepareStatement(
                                         "UPDATE "
                                                 + table
                                                 + " SET verified_at = ?, "
                                                 + "verified_username = ? "
                                                 + "WHERE challenge_id = ? "
                                                 + "AND verified_at IS NULL "
                                                 + "AND consumed_at IS NULL"
                                 )) {
                        statement.setLong(1, now);
                        statement.setString(
                                2,
                                limitUsername(playerName)
                        );
                        statement.setString(3, challengeId);

                        if (statement.executeUpdate() != 1) {
                            connection.rollback();
                            return VerificationResult.ALREADY_USED;
                        }
                    }

                    connection.commit();
                    return VerificationResult.VERIFIED;
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (Exception exception) {
            core.getLogger().warning(
                    "Could not verify website account for "
                            + playerUuid
                            + ": "
                            + exception.getMessage()
            );
            return VerificationResult.ERROR;
        }
    }

    private Connection connection() throws Exception {
        String jdbcUrl = config.getString(
                "database.jdbc-url",
                ""
        ).trim();
        String username = config.getString(
                "database.username",
                ""
        );
        String password = config.getString(
                "database.password",
                ""
        );

        if (jdbcUrl.isBlank()) {
            throw new IllegalStateException(
                    "Missing database.jdbc-url"
            );
        }

        return DriverManager.getConnection(
                jdbcUrl,
                username,
                password
        );
    }

    private void loadDriver() throws Exception {
        String driverClass = config.getString(
                "database.driver-class",
                "com.mysql.cj.jdbc.Driver"
        ).trim();

        if (driverClass.isBlank()) {
            throw new IllegalStateException(
                    "Missing database.driver-class"
            );
        }

        Class.forName(driverClass);
    }

    private static String normalizeCode(String value) {
        if (value == null) {
            return null;
        }

        String code = value.trim().toUpperCase(Locale.ROOT);

        if (!code.matches("[A-HJ-NP-Z2-9]{6}")) {
            return null;
        }

        return code;
    }

    private static String hashCode(String code)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance(
                "SHA-256"
        );

        return HexFormat.of().formatHex(
                digest.digest(
                        code.getBytes(StandardCharsets.UTF_8)
                )
        );
    }

    private static String safeTableName(String value) {
        String candidate = value.trim();

        return candidate.matches("[A-Za-z0-9_]+")
                ? candidate
                : DEFAULT_TABLE;
    }

    private static String limitUsername(String value) {
        if (value == null) {
            return "";
        }

        String clean = value.trim();

        return clean.length() <= USERNAME_MAX_LENGTH
                ? clean
                : clean.substring(0, USERNAME_MAX_LENGTH);
    }

    public enum VerificationResult {
        VERIFIED,
        INVALID_CODE,
        WRONG_PLAYER,
        EXPIRED,
        ALREADY_USED,
        DISABLED,
        ERROR
    }
}
