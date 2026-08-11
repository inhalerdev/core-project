package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

@SuppressWarnings({
        "SqlNoDataSourceInspection",
        "SqlDialectInspection"
})
public final class BountyDatabaseMirror {

    private static final long ERROR_LOG_INTERVAL_MILLIS =
            60_000L;
    private static final String DEFAULT_TABLE =
            "mineacle_web_bounties";

    private final Core core;
    private final File settingsFile;
    private final boolean enabled;
    private final long syncSeconds;
    private final String table;

    private final AtomicBoolean closed =
            new AtomicBoolean(false);
    private final AtomicBoolean readyLogged =
            new AtomicBoolean(false);
    private final AtomicLong lastErrorLogAt =
            new AtomicLong(0L);
    private final AtomicLong generation =
            new AtomicLong(
                    System.currentTimeMillis()
            );

    private ExecutorService worker;
    private volatile DatabaseSettings settings;
    private volatile boolean schemaReady;

    public BountyDatabaseMirror(
            Core core
    ) {
        this.core = core;
        this.settingsFile =
                new File(
                        core.getDataFolder(),
                        "webprofiles.yml"
                );

        FileConfiguration configuration =
                settingsFile.isFile()
                        ? YamlConfiguration
                        .loadConfiguration(
                                settingsFile
                        )
                        : new YamlConfiguration();

        this.enabled =
                configuration.getBoolean(
                        "web-bounties.enabled",
                        true
                );
        this.syncSeconds =
                Math.clamp(
                        configuration.getLong(
                                "web-bounties.interval-seconds",
                                30L
                        ),
                        15L,
                        900L
                );
        this.table =
                safeTableName(
                        configuration.getString(
                                "database.bounties-table",
                                DEFAULT_TABLE
                        )
                );
    }

    public boolean enabled() {
        return enabled;
    }

    public long syncSeconds() {
        return syncSeconds;
    }

    public void start() {
        if (!enabled
                || closed.get()
                || worker != null) {
            return;
        }

        worker =
                Executors
                        .newSingleThreadExecutor(
                                runnable -> {
                                    Thread thread =
                                            new Thread(
                                                    runnable,
                                                    "Mineacle-Bounty-DB"
                                            );
                                    thread.setDaemon(true);
                                    return thread;
                                }
                        );

        submit(
                this::initializeSchema
        );
    }

    public void upsert(
            Snapshot snapshot
    ) {
        if (!enabled
                || snapshot == null
                || closed.get()) {
            return;
        }

        submit(
                () -> upsertNow(snapshot)
        );
    }

    public void delete(
            UUID targetId
    ) {
        if (!enabled
                || targetId == null
                || closed.get()) {
            return;
        }

        submit(
                () -> deleteNow(targetId)
        );
    }

    public void reconcile(
            Collection<Snapshot> snapshots
    ) {
        if (!enabled
                || closed.get()) {
            return;
        }

        List<Snapshot> copy =
                snapshots == null
                        ? List.of()
                        : List.copyOf(
                                snapshots
                        );
        long syncGeneration =
                generation.incrementAndGet();

        submit(
                () -> reconcileNow(
                        copy,
                        syncGeneration
                )
        );
    }

    public void shutdown(
            Collection<Snapshot> finalSnapshots
    ) {
        if (!enabled
                || !closed.compareAndSet(
                false,
                true
        )) {
            return;
        }

        ExecutorService current =
                worker;

        if (current == null) {
            return;
        }

        List<Snapshot> copy =
                finalSnapshots == null
                        ? List.of()
                        : List.copyOf(
                                finalSnapshots
                        );
        long syncGeneration =
                generation.incrementAndGet();

        try {
            current.execute(
                    () -> reconcileNow(
                            copy,
                            syncGeneration
                    )
            );
        } catch (
                RejectedExecutionException ignored
        ) {
        }

        current.shutdown();

        try {
            if (!current.awaitTermination(
                    250L,
                    TimeUnit.MILLISECONDS
            )) {
                current.shutdownNow();
            }
        } catch (
                InterruptedException exception
        ) {
            Thread.currentThread()
                    .interrupt();
            current.shutdownNow();
        }

        worker = null;
    }

    private void submit(
            Runnable task
    ) {
        ExecutorService current =
                worker;

        if (current == null
                || current.isShutdown()
                || closed.get()) {
            return;
        }

        try {
            current.execute(task);
        } catch (
                RejectedExecutionException ignored
        ) {
        }
    }

    private void initializeSchema() {
        try {
            withConnection(
                    connection -> {
                    }
            );
        } catch (Exception exception) {
            mirrorFailure(exception);
        }
    }

    private void upsertNow(
            Snapshot snapshot
    ) {
        try {
            withConnection(
                    connection -> {
                        try (PreparedStatement statement =
                                     connection.prepareStatement(
                                             upsertSql()
                                     )) {
                            bind(
                                    statement,
                                    snapshot,
                                    0L
                            );
                            statement.executeUpdate();
                        }
                    }
            );
        } catch (Exception exception) {
            mirrorFailure(exception);
        }
    }

    private void deleteNow(
            UUID targetId
    ) {
        try {
            withConnection(
                    connection -> {
                        try (PreparedStatement statement =
                                     connection.prepareStatement(
                                             "DELETE FROM "
                                                     + table
                                                     + " WHERE target_uuid = ?"
                                     )) {
                            statement.setString(
                                    1,
                                    targetId.toString()
                            );
                            statement.executeUpdate();
                        }
                    }
            );
        } catch (Exception exception) {
            mirrorFailure(exception);
        }
    }

    private void reconcileNow(
            List<Snapshot> snapshots,
            long syncGeneration
    ) {
        try {
            withConnection(
                    connection -> {
                        boolean originalAutoCommit =
                                connection.getAutoCommit();

                        connection.setAutoCommit(false);

                        try {
                            try (PreparedStatement statement =
                                         connection.prepareStatement(
                                                 upsertSql()
                                         )) {
                                for (Snapshot snapshot
                                        : snapshots) {
                                    bind(
                                            statement,
                                            snapshot,
                                            syncGeneration
                                    );
                                    statement.addBatch();
                                }

                                statement.executeBatch();
                            }

                            try (PreparedStatement statement =
                                         connection.prepareStatement(
                                                 "DELETE FROM "
                                                         + table
                                                         + " WHERE sync_generation <> ?"
                                         )) {
                                statement.setLong(
                                        1,
                                        syncGeneration
                                );
                                statement.executeUpdate();
                            }

                            connection.commit();
                        } catch (Exception exception) {
                            connection.rollback();
                            throw exception;
                        } finally {
                            connection.setAutoCommit(
                                    originalAutoCommit
                            );
                        }
                    }
            );
        } catch (Exception exception) {
            mirrorFailure(exception);
        }
    }

    private void withConnection(
            SqlWork work
    ) throws Exception {
        DatabaseSettings currentSettings =
                settings;

        if (currentSettings == null) {
            currentSettings =
                    loadSettings();
            settings = currentSettings;
        }

        if (!currentSettings.driverClass()
                .isBlank()) {
            Class.forName(
                    currentSettings.driverClass()
            );
        }

        try (Connection connection =
                     DriverManager.getConnection(
                             currentSettings.jdbcUrl(),
                             currentSettings.username(),
                             currentSettings.password()
                     )) {
            ensureSchema(connection);
            work.run(connection);
            mirrorSuccess();
        }
    }

    private DatabaseSettings loadSettings() {
        if (!settingsFile.isFile()) {
            throw new IllegalStateException(
                    "webprofiles.yml is missing"
            );
        }

        FileConfiguration configuration =
                YamlConfiguration
                        .loadConfiguration(
                                settingsFile
                        );
        String password =
                configuration.getString(
                        "database.password",
                        ""
                );

        if (password.isBlank()
                || password.equalsIgnoreCase(
                "change-me"
        )
                || password
                .toUpperCase(
                        Locale.ROOT
                )
                .startsWith(
                        "CHANGE-ME-"
                )) {
            throw new IllegalStateException(
                    "webprofiles.yml database password is not configured"
            );
        }

        return new DatabaseSettings(
                configuration.getString(
                        "database.driver-class",
                        "com.mysql.cj.jdbc.Driver"
                ),
                configuration.getString(
                        "database.jdbc-url",
                        "jdbc:mysql://127.0.0.1:3306/mineacle_core"
                ),
                configuration.getString(
                        "database.username",
                        "mineacle_core_user"
                ),
                password
        );
    }

    private void ensureSchema(
            Connection connection
    ) throws Exception {
        if (schemaReady) {
            return;
        }

        try (Statement statement =
                     connection.createStatement()) {
            statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS %s (
                        target_uuid CHAR(36) PRIMARY KEY,
                        target_username VARCHAR(16) NOT NULL,
                        target_display_name VARCHAR(64) NOT NULL,
                        amount_cents BIGINT NOT NULL,
                        payout_cents BIGINT NOT NULL,
                        online TINYINT(1) NOT NULL,
                        last_updated BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        sync_generation BIGINT NOT NULL DEFAULT 0,
                        INDEX idx_bounty_amount (amount_cents),
                        INDEX idx_bounty_online_amount (online, amount_cents),
                        INDEX idx_bounty_last_updated (last_updated),
                        INDEX idx_bounty_updated_at (updated_at)
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(table)
            );
        }

        schemaReady = true;
    }

    private String upsertSql() {
        return """
                INSERT INTO %s (
                    target_uuid,
                    target_username,
                    target_display_name,
                    amount_cents,
                    payout_cents,
                    online,
                    last_updated,
                    updated_at,
                    sync_generation
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    target_username = VALUES(target_username),
                    target_display_name = VALUES(target_display_name),
                    amount_cents = VALUES(amount_cents),
                    payout_cents = VALUES(payout_cents),
                    online = VALUES(online),
                    last_updated = VALUES(last_updated),
                    updated_at = VALUES(updated_at),
                    sync_generation = VALUES(sync_generation)
                """.formatted(table);
    }

    private void bind(
            PreparedStatement statement,
            Snapshot snapshot,
            long syncGeneration
    ) throws Exception {
        statement.setString(
                1,
                snapshot.targetId()
                        .toString()
        );
        statement.setString(
                2,
                limit(
                        snapshot.targetUsername(),
                        16
                )
        );
        statement.setString(
                3,
                limit(
                        snapshot.targetDisplayName(),
                        64
                )
        );
        statement.setLong(
                4,
                snapshot.amountCents()
        );
        statement.setLong(
                5,
                snapshot.payoutCents()
        );
        statement.setBoolean(
                6,
                snapshot.online()
        );
        statement.setLong(
                7,
                snapshot.lastUpdated()
        );
        statement.setLong(
                8,
                System.currentTimeMillis()
        );
        statement.setLong(
                9,
                syncGeneration
        );
    }

    private String safeTableName(
            String configured
    ) {
        String value =
                configured.trim();

        if (!value.matches(
                "[A-Za-z0-9_]{1,64}"
        )) {
            core.getLogger().warning(
                    "[Bounty] Invalid web bounty table '"
                            + configured
                            + "', using "
                            + DEFAULT_TABLE
            );
            return DEFAULT_TABLE;
        }

        return value.toLowerCase(
                Locale.ROOT
        );
    }

    private String limit(
            String value,
            int maximum
    ) {
        if (value == null) {
            return "";
        }

        return value.length()
                <= maximum
                ? value
                : value.substring(
                0,
                maximum
        );
    }

    private void mirrorSuccess() {
        lastErrorLogAt.set(0L);

        if (readyLogged.compareAndSet(
                false,
                true
        )) {
            core.getLogger().info(
                    "[Bounty] MariaDB mirror ready: "
                            + table
            );
        }
    }

    private void mirrorFailure(
            Exception exception
    ) {
        schemaReady = false;
        settings = null;

        long now =
                System.currentTimeMillis();
        long previous =
                lastErrorLogAt.get();

        if (previous > 0L
                && now - previous
                < ERROR_LOG_INTERVAL_MILLIS) {
            return;
        }

        if (!lastErrorLogAt.compareAndSet(
                previous,
                now
        )) {
            return;
        }

        core.getLogger().log(
                Level.WARNING,
                "[Bounty] MariaDB mirror unavailable; "
                        + "bounty gameplay remains available from local storage: "
                        + safeMessage(exception),
                exception
        );
    }

    private String safeMessage(
            Exception exception
    ) {
        String message =
                exception.getMessage();

        if (message == null
                || message.isBlank()) {
            return exception
                    .getClass()
                    .getSimpleName();
        }

        return message
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
    }

    @FunctionalInterface
    private interface SqlWork {

        void run(
                Connection connection
        ) throws Exception;
    }

    private record DatabaseSettings(
            String driverClass,
            String jdbcUrl,
            String username,
            String password
    ) {
    }

    public record Snapshot(
            UUID targetId,
            String targetUsername,
            String targetDisplayName,
            long amountCents,
            long payoutCents,
            boolean online,
            long lastUpdated
    ) {
        public Snapshot {
            if (targetId == null) {
                throw new IllegalArgumentException(
                        "targetId cannot be null"
                );
            }

            targetUsername =
                    targetUsername == null
                            || targetUsername.isBlank()
                            ? targetId.toString()
                            : targetUsername;
            targetDisplayName =
                    targetDisplayName == null
                            || targetDisplayName.isBlank()
                            ? targetUsername
                            : targetDisplayName;
            amountCents =
                    Math.max(
                            0L,
                            amountCents
                    );
            payoutCents =
                    Math.max(
                            0L,
                            payoutCents
                    );
            lastUpdated =
                    Math.max(
                            0L,
                            lastUpdated
                    );
        }
    }
}
