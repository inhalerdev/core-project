package net.mineacle.core.auctionhouse.storage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
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
public final class AuctionHouseDatabaseMirror {

    private static final long ERROR_LOG_INTERVAL_MILLIS =
            60_000L;

    private final Core core;
    private final boolean enabled;
    private final File settingsFile;
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

    public AuctionHouseDatabaseMirror(
            Core core,
            FileConfiguration auctionConfig
    ) {
        this.core = core;
        this.enabled =
                auctionConfig.getBoolean(
                        "database.mirror.enabled",
                        true
                );

        String settingsName =
                safeSettingsFileName(
                        auctionConfig.getString(
                                "database.mirror.settings-file",
                                "webprofiles.yml"
                        )
                );

        this.settingsFile =
                new File(
                        core.getDataFolder(),
                        settingsName
                );
        this.table =
                safeTableName(
                        auctionConfig.getString(
                                "database.mirror.table",
                                "mineacle_auction_listings"
                        )
                );
    }

    public boolean enabled() {
        return enabled;
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
                                                    "Mineacle-AuctionHouse-DB"
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
            AuctionHouseListing listing,
            long lifetimeMillis
    ) {
        if (!enabled
                || listing == null
                || closed.get()) {
            return;
        }

        MirrorListing snapshot =
                snapshot(
                        listing,
                        lifetimeMillis
                );

        submit(
                () -> upsertNow(snapshot)
        );
    }

    public void delete(
            UUID listingId
    ) {
        if (!enabled
                || listingId == null
                || closed.get()) {
            return;
        }

        submit(
                () -> deleteNow(listingId)
        );
    }

    public void reconcile(
            Collection<AuctionHouseListing> listings,
            long lifetimeMillis
    ) {
        if (!enabled
                || closed.get()) {
            return;
        }

        List<MirrorListing> snapshots =
                new ArrayList<>(
                        listings == null
                                ? 0
                                : listings.size()
                );

        if (listings != null) {
            for (AuctionHouseListing listing
                    : listings) {
                if (listing != null) {
                    snapshots.add(
                            snapshot(
                                    listing,
                                    lifetimeMillis
                            )
                    );
                }
            }
        }

        long syncGeneration =
                generation.incrementAndGet();

        submit(
                () -> reconcileNow(
                        List.copyOf(
                                snapshots
                        ),
                        syncGeneration
                )
        );
    }

    public void shutdown(
            Collection<AuctionHouseListing> finalListings,
            long lifetimeMillis
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

        List<MirrorListing> snapshots =
                new ArrayList<>(
                        finalListings == null
                                ? 0
                                : finalListings.size()
                );

        if (finalListings != null) {
            for (AuctionHouseListing listing
                    : finalListings) {
                if (listing != null) {
                    snapshots.add(
                            snapshot(
                                    listing,
                                    lifetimeMillis
                            )
                    );
                }
            }
        }

        long syncGeneration =
                generation.incrementAndGet();

        try {
            current.execute(
                    () -> reconcileNow(
                            List.copyOf(
                                    snapshots
                            ),
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
            MirrorListing listing
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
                                    listing,
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
            UUID listingId
    ) {
        try {
            withConnection(
                    connection -> {
                        try (PreparedStatement statement =
                                     connection.prepareStatement(
                                             "DELETE FROM "
                                                     + table
                                                     + " WHERE listing_id = ?"
                                     )) {
                            statement.setString(
                                    1,
                                    listingId.toString()
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
            List<MirrorListing> listings,
            long syncGeneration
    ) {
        try {
            withConnection(
                    connection -> {
                        boolean originalAutoCommit =
                                connection
                                        .getAutoCommit();

                        connection.setAutoCommit(false);

                        try {
                            try (PreparedStatement statement =
                                         connection.prepareStatement(
                                                 upsertSql()
                                         )) {
                                for (MirrorListing listing
                                        : listings) {
                                    bind(
                                            statement,
                                            listing,
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

        loadDriver(
                currentSettings.driverClass()
        );

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
                    settingsFile.getName()
                            + " is missing"
            );
        }

        FileConfiguration databaseConfig =
                YamlConfiguration
                        .loadConfiguration(
                                settingsFile
                        );

        String password =
                databaseConfig.getString(
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
                    settingsFile.getName()
                            + " database password is not configured"
            );
        }

        return new DatabaseSettings(
                databaseConfig.getString(
                        "database.driver-class",
                        "com.mysql.cj.jdbc.Driver"
                ),
                databaseConfig.getString(
                        "database.jdbc-url",
                        "jdbc:mysql://127.0.0.1:3306/mineacle_core"
                ),
                databaseConfig.getString(
                        "database.username",
                        "mineacle_core_user"
                ),
                password
        );
    }

    private void loadDriver(
            String driverClass
    ) throws ClassNotFoundException {
        if (!driverClass.isBlank()) {
            Class.forName(driverClass);
        }
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
                        listing_id CHAR(36) PRIMARY KEY,
                        seller_uuid CHAR(36) NOT NULL,
                        seller_name VARCHAR(16) NOT NULL,
                        item_material VARCHAR(64) NOT NULL,
                        item_name VARCHAR(128) NOT NULL,
                        item_amount INT NOT NULL,
                        item_nbt MEDIUMBLOB NOT NULL,
                        price_cents BIGINT NOT NULL,
                        created_at BIGINT NOT NULL,
                        expires_at BIGINT NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        updated_at BIGINT NOT NULL,
                        sync_generation BIGINT NOT NULL DEFAULT 0,
                        INDEX idx_auction_seller (seller_uuid),
                        INDEX idx_auction_status_price (status, price_cents),
                        INDEX idx_auction_expires (expires_at),
                        INDEX idx_auction_created (created_at),
                        INDEX idx_auction_updated (updated_at)
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
                    listing_id,
                    seller_uuid,
                    seller_name,
                    item_material,
                    item_name,
                    item_amount,
                    item_nbt,
                    price_cents,
                    created_at,
                    expires_at,
                    status,
                    updated_at,
                    sync_generation
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    status = VALUES(status),
                    updated_at = VALUES(updated_at),
                    sync_generation = VALUES(sync_generation)
                """.formatted(table);
    }

    private void bind(
            PreparedStatement statement,
            MirrorListing listing,
            long syncGeneration
    ) throws Exception {
        statement.setString(
                1,
                listing.id().toString()
        );
        statement.setString(
                2,
                listing.sellerId()
                        .toString()
        );
        statement.setString(
                3,
                limit(
                        listing.sellerName(),
                        16
                )
        );
        statement.setString(
                4,
                listing.material().name()
        );
        statement.setString(
                5,
                limit(
                        listing.itemName(),
                        128
                )
        );
        statement.setInt(
                6,
                listing.amount()
        );
        statement.setBytes(
                7,
                listing.itemNbt()
        );
        statement.setLong(
                8,
                listing.priceCents()
        );
        statement.setLong(
                9,
                listing.createdAt()
        );
        statement.setLong(
                10,
                listing.expiresAt()
        );
        statement.setString(
                11,
                listing.status()
        );
        statement.setLong(
                12,
                System.currentTimeMillis()
        );
        statement.setLong(
                13,
                syncGeneration
        );
    }

    private MirrorListing snapshot(
            AuctionHouseListing listing,
            long lifetimeMillis
    ) {
        ItemStack item =
                listing.item();
        long expiresAt =
                safeAdd(
                        listing.createdAt(),
                        Math.max(
                                0L,
                                lifetimeMillis
                        )
                );
        String status =
                System.currentTimeMillis()
                        >= expiresAt
                        ? "EXPIRED"
                        : "ACTIVE";

        return new MirrorListing(
                listing.id(),
                listing.owner(),
                listing.ownerName(),
                item.getType(),
                itemDisplayName(item),
                listing.amount(),
                item.serializeAsBytes(),
                listing.priceCents(),
                listing.createdAt(),
                expiresAt,
                status
        );
    }

    private String itemDisplayName(
            ItemStack item
    ) {
        ItemMeta meta =
                item.getItemMeta();

        if (meta != null
                && meta.hasDisplayName()) {
            Component displayName =
                    meta.displayName();

            if (displayName != null) {
                String plain =
                        PlainTextComponentSerializer
                                .plainText()
                                .serialize(
                                        displayName
                                )
                                .trim();

                if (!plain.isBlank()) {
                    return plain;
                }
            }
        }

        String raw =
                item.getType()
                        .name()
                        .toLowerCase(
                                Locale.ROOT
                        )
                        .replace(
                                '_',
                                ' '
                        );
        StringBuilder output =
                new StringBuilder(
                        raw.length()
                );
        boolean capitalize = true;

        for (char character
                : raw.toCharArray()) {
            if (character == ' ') {
                output.append(' ');
                capitalize = true;
                continue;
            }

            output.append(
                    capitalize
                            ? Character.toUpperCase(
                            character
                    )
                            : character
            );
            capitalize = false;
        }

        return output.toString();
    }

    private long safeAdd(
            long left,
            long right
    ) {
        try {
            return Math.addExact(
                    left,
                    right
            );
        } catch (
                ArithmeticException exception
        ) {
            return Long.MAX_VALUE;
        }
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
                : value.substring(
                0,
                maximum
        );
    }

    private String safeTableName(
            String configured
    ) {
        String fallback =
                "mineacle_auction_listings";
        String value =
                configured == null
                        ? ""
                        : configured.trim();

        if (!value.matches(
                "[A-Za-z0-9_]{1,64}"
        )) {
            core.getLogger().warning(
                    "[AuctionHouse] Invalid database mirror table '"
                            + configured
                            + "', using "
                            + fallback
            );
            return fallback;
        }

        return value.toLowerCase(
                Locale.ROOT
        );
    }

    private String safeSettingsFileName(
            String configured
    ) {
        String value =
                configured == null
                        ? ""
                        : configured.trim();

        if (!value.matches(
                "[A-Za-z0-9_.-]{1,64}"
        )
                || value.contains("..")) {
            core.getLogger().warning(
                    "[AuctionHouse] Invalid database settings file; "
                            + "using webprofiles.yml"
            );
            return "webprofiles.yml";
        }

        return value;
    }

    private void mirrorSuccess() {
        lastErrorLogAt.set(0L);

        if (readyLogged.compareAndSet(
                false,
                true
        )) {
            core.getLogger().info(
                    "[AuctionHouse] MariaDB mirror ready: "
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
                "[AuctionHouse] MariaDB mirror unavailable; "
                        + "player trading remains available from local storage: "
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
        private DatabaseSettings {
            driverClass =
                    driverClass == null
                            ? ""
                            : driverClass.trim();
            jdbcUrl =
                    jdbcUrl == null
                            ? ""
                            : jdbcUrl.trim();
            username =
                    username == null
                            ? ""
                            : username.trim();

            if (jdbcUrl.isBlank()) {
                throw new IllegalArgumentException(
                        "database.jdbc-url is blank"
                );
            }

            if (username.isBlank()) {
                throw new IllegalArgumentException(
                        "database.username is blank"
                );
            }
        }
    }

    private record MirrorListing(
            UUID id,
            UUID sellerId,
            String sellerName,
            Material material,
            String itemName,
            int amount,
            byte[] itemNbt,
            long priceCents,
            long createdAt,
            long expiresAt,
            String status
    ) {
        private MirrorListing {
            sellerName =
                    sellerName == null
                            ? ""
                            : sellerName;
            itemName =
                    itemName == null
                            ? ""
                            : itemName;
            itemNbt =
                    itemNbt == null
                            ? new byte[0]
                            : itemNbt.clone();
            amount =
                    Math.max(
                            1,
                            amount
                    );
        }

        @Override
        public byte[] itemNbt() {
            return itemNbt.clone();
        }
    }
}
