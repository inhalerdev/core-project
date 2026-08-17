package net.mineacle.core.auctionhouse.storage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

@SuppressWarnings({
        "SqlNoDataSourceInspection",
        "SqlDialectInspection"
})
public final class AuctionHouseDatabaseMirror {

    private static final long ERROR_LOG_INTERVAL_MILLIS = 60_000L;
    private static final int DEFAULT_QUEUE_CAPACITY = 128;
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3_000;
    private static final int DEFAULT_SOCKET_TIMEOUT_MILLIS = 5_000;
    private static final long DEFAULT_FAILURE_BACKOFF_MILLIS = 15_000L;
    private static final long DEFAULT_PAYLOAD_CACHE_MAX_BYTES = 33_554_432L;
    private static final long SHUTDOWN_WAIT_MILLIS = 100L;

    private final Core core;
    private final boolean enabled;
    private final File settingsFile;
    private final String table;
    private final int queueCapacity;
    private final int connectTimeoutMillis;
    private final int socketTimeoutMillis;
    private final long failureBackoffMillis;
    private final long payloadCacheMaxBytes;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean readyLogged = new AtomicBoolean(false);
    private final AtomicLong lastErrorLogAt = new AtomicLong(0L);
    private final AtomicLong nextConnectionAttemptAt = new AtomicLong(0L);
    private final AtomicLong generation = new AtomicLong(System.currentTimeMillis());

    /*
     * Listing item payloads are immutable for the life of a listing. Caching
     * the serialized item avoids rebuilding every ItemStack NBT blob during
     * each 60-second website reconciliation pass.
     *
     * Seller display identity is deliberately NOT trusted from this cache.
     * Nicknames may change while a listing is active, so each mirror snapshot
     * resolves the current public Mineacle identity from the seller UUID.
     */
    private final Object payloadCacheLock = new Object();
    private final LinkedHashMap<UUID, MirrorPayload> payloadCache =
            new LinkedHashMap<>(16, 0.75F, true);
    private long payloadCacheBytes;

    private ThreadPoolExecutor worker;
    private volatile DatabaseSettings settings;
    private volatile boolean schemaReady;

    public AuctionHouseDatabaseMirror(
            Core core,
            FileConfiguration auctionConfig
    ) {
        this.core = core;
        this.enabled = auctionConfig.getBoolean(
                "database.mirror.enabled",
                true
        );
        this.queueCapacity = Math.clamp(
                auctionConfig.getInt(
                        "database.mirror.queue-capacity",
                        DEFAULT_QUEUE_CAPACITY
                ),
                16,
                2_048
        );
        this.connectTimeoutMillis = Math.clamp(
                auctionConfig.getInt(
                        "database.mirror.connect-timeout-millis",
                        DEFAULT_CONNECT_TIMEOUT_MILLIS
                ),
                500,
                30_000
        );
        this.socketTimeoutMillis = Math.clamp(
                auctionConfig.getInt(
                        "database.mirror.socket-timeout-millis",
                        DEFAULT_SOCKET_TIMEOUT_MILLIS
                ),
                1_000,
                60_000
        );
        long backoffSeconds = Math.clamp(
                auctionConfig.getLong(
                        "database.mirror.failure-backoff-seconds",
                        TimeUnit.MILLISECONDS.toSeconds(
                                DEFAULT_FAILURE_BACKOFF_MILLIS
                        )
                ),
                5L,
                300L
        );
        this.failureBackoffMillis = TimeUnit.SECONDS.toMillis(backoffSeconds);
        this.payloadCacheMaxBytes = Math.clamp(
                auctionConfig.getLong(
                        "database.mirror.payload-cache-max-bytes",
                        DEFAULT_PAYLOAD_CACHE_MAX_BYTES
                ),
                1_048_576L,
                268_435_456L
        );

        String settingsName = safeSettingsFileName(
                auctionConfig.getString(
                        "database.mirror.settings-file",
                        "webprofiles.yml"
                )
        );
        this.settingsFile = new File(core.getDataFolder(), settingsName);
        this.table = safeTableName(
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
        if (!enabled || closed.get() || worker != null) {
            return;
        }

        worker = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "Mineacle-AuctionHouse-DB"
                    );
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
        worker.prestartCoreThread();
        submit(this::initializeSchema);
    }

    public void upsert(
            AuctionHouseListing listing,
            long lifetimeMillis
    ) {
        if (!enabled || listing == null || closed.get()) {
            return;
        }

        try {
            MirrorListing snapshot = snapshot(listing, lifetimeMillis);
            submit(() -> upsertNow(snapshot));
        } catch (RuntimeException exception) {
            snapshotFailure(
                    "Could not snapshot auction listing for MariaDB mirror",
                    exception
            );
        }
    }

    public void delete(UUID listingId) {
        if (!enabled || listingId == null || closed.get()) {
            return;
        }

        removeCachedPayload(listingId);
        submit(() -> deleteNow(listingId));
    }

    public void reconcile(
            Collection<AuctionHouseListing> listings,
            long lifetimeMillis
    ) {
        if (!enabled || closed.get()) {
            return;
        }

        try {
            List<MirrorListing> snapshots = snapshots(
                    listings,
                    lifetimeMillis
            );
            long syncGeneration = generation.incrementAndGet();
            submit(() -> reconcileNow(snapshots, syncGeneration));
        } catch (RuntimeException exception) {
            snapshotFailure(
                    "Could not snapshot Auction House for MariaDB reconciliation",
                    exception
            );
        }
    }

    public void shutdown() {
        if (!enabled || !closed.compareAndSet(false, true)) {
            return;
        }

        ThreadPoolExecutor current = worker;
        worker = null;
        clearPayloadCache();

        if (current == null) {
            return;
        }

        /*
         * The MariaDB copy is explicitly non-authoritative. Do not serialize a
         * full market snapshot or wait on a remote database during plugin
         * shutdown/reload. The next startup reconciliation repairs the mirror.
         */
        current.getQueue().clear();
        current.shutdownNow();

        try {
            if (!current.awaitTermination(
                    SHUTDOWN_WAIT_MILLIS,
                    TimeUnit.MILLISECONDS
            )) {
                core.getLogger().fine(
                        "[AuctionHouse] MariaDB mirror worker still stopping after shutdown wait"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private List<MirrorListing> snapshots(
            Collection<AuctionHouseListing> listings,
            long lifetimeMillis
    ) {
        if (listings == null || listings.isEmpty()) {
            return List.of();
        }

        List<MirrorListing> snapshots = new ArrayList<>(listings.size());
        Set<UUID> liveIds = new HashSet<>(listings.size());

        for (AuctionHouseListing listing : listings) {
            if (listing == null) {
                continue;
            }
            liveIds.add(listing.id());
            snapshots.add(snapshot(listing, lifetimeMillis));
        }

        retainCachedPayloads(liveIds);
        return List.copyOf(snapshots);
    }

    private MirrorListing snapshot(
            AuctionHouseListing listing,
            long lifetimeMillis
    ) {
        MirrorPayload payload = cachedPayload(listing);
        String sellerName = publicSellerName(listing);
        long expiresAt = safeAdd(
                listing.createdAt(),
                Math.max(0L, lifetimeMillis)
        );
        String status = System.currentTimeMillis() >= expiresAt
                ? "EXPIRED"
                : "ACTIVE";

        return new MirrorListing(
                payload,
                sellerName,
                expiresAt,
                status
        );
    }

    private MirrorPayload cachedPayload(AuctionHouseListing listing) {
        UUID listingId = listing.id();

        synchronized (payloadCacheLock) {
            MirrorPayload cached = payloadCache.get(listingId);
            if (cached != null) {
                return cached;
            }
        }

        MirrorPayload created = createPayload(listing);
        long createdBytes = created.itemNbtLength();

        if (createdBytes > payloadCacheMaxBytes) {
            return created;
        }

        synchronized (payloadCacheLock) {
            MirrorPayload cached = payloadCache.get(listingId);
            if (cached != null) {
                return cached;
            }

            payloadCache.put(listingId, created);
            payloadCacheBytes = safeCacheAdd(
                    payloadCacheBytes,
                    createdBytes
            );
            trimPayloadCache();
        }

        return created;
    }

    private void removeCachedPayload(UUID listingId) {
        synchronized (payloadCacheLock) {
            MirrorPayload removed = payloadCache.remove(listingId);
            if (removed != null) {
                payloadCacheBytes = Math.max(
                        0L,
                        payloadCacheBytes - removed.itemNbtLength()
                );
            }
        }
    }

    private void retainCachedPayloads(Set<UUID> liveIds) {
        synchronized (payloadCacheLock) {
            var iterator = payloadCache.entrySet().iterator();

            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (liveIds.contains(entry.getKey())) {
                    continue;
                }

                payloadCacheBytes = Math.max(
                        0L,
                        payloadCacheBytes
                                - entry.getValue().itemNbtLength()
                );
                iterator.remove();
            }
        }
    }

    private void trimPayloadCache() {
        var iterator = payloadCache.entrySet().iterator();

        while (payloadCacheBytes > payloadCacheMaxBytes
                && iterator.hasNext()) {
            var entry = iterator.next();
            payloadCacheBytes = Math.max(
                    0L,
                    payloadCacheBytes
                            - entry.getValue().itemNbtLength()
            );
            iterator.remove();
        }

        if (payloadCache.isEmpty()) {
            payloadCacheBytes = 0L;
        }
    }

    private void clearPayloadCache() {
        synchronized (payloadCacheLock) {
            payloadCache.clear();
            payloadCacheBytes = 0L;
        }
    }

    private long safeCacheAdd(long current, long added) {
        try {
            return Math.addExact(current, added);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private MirrorPayload createPayload(AuctionHouseListing listing) {
        ItemStack item = listing.item();
        return new MirrorPayload(
                listing.id(),
                listing.owner(),
                item.getType(),
                itemDisplayName(item),
                listing.amount(),
                item.serializeAsBytes(),
                listing.priceCents(),
                listing.createdAt()
        );
    }

    /**
     * Public website identity only. UUID remains the stable seller authority;
     * the mirrored name follows Mineacle's nickname-or-username rule and is
     * resolved for every mirror snapshot so nickname changes repair active rows.
     */
    private String publicSellerName(AuctionHouseListing listing) {
        String display = TextColor.strip(
                DisplayNames.commandDisplayName(
                        Bukkit.getOfflinePlayer(listing.owner())
                )
        );

        if (display.isBlank()) {
            display = TextColor.strip(listing.ownerName());
        }

        display = display
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();

        return display.isBlank()
                ? "Unknown"
                : display;
    }

    private void submit(Runnable task) {
        ThreadPoolExecutor current = worker;
        if (current == null
                || current.isShutdown()
                || closed.get()) {
            return;
        }

        try {
            current.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void initializeSchema() {
        try {
            withConnection(connection -> {
            });
        } catch (Exception exception) {
            mirrorFailure(exception);
        }
    }

    private void upsertNow(MirrorListing listing) {
        try {
            withConnection(
                    connection -> {
                        try (PreparedStatement statement =
                                     connection.prepareStatement(upsertSql())) {
                            bind(statement, listing, 0L);
                            statement.executeUpdate();
                        }
                    }
            );
        } catch (Exception exception) {
            mirrorFailure(exception);
        }
    }

    private void deleteNow(UUID listingId) {
        try {
            withConnection(
                    connection -> {
                        try (PreparedStatement statement = connection.prepareStatement(
                                "DELETE FROM " + table + " WHERE listing_id = ?"
                        )) {
                            statement.setString(1, listingId.toString());
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
                        boolean originalAutoCommit = connection.getAutoCommit();
                        connection.setAutoCommit(false);

                        try {
                            try (PreparedStatement statement =
                                         connection.prepareStatement(upsertSql())) {
                                for (MirrorListing listing : listings) {
                                    bind(statement, listing, syncGeneration);
                                    statement.addBatch();
                                }
                                statement.executeBatch();
                            }

                            try (PreparedStatement statement = connection.prepareStatement(
                                    "DELETE FROM " + table
                                            + " WHERE sync_generation <> ?"
                            )) {
                                statement.setLong(1, syncGeneration);
                                statement.executeUpdate();
                            }

                            connection.commit();
                        } catch (Exception exception) {
                            connection.rollback();
                            throw exception;
                        } finally {
                            connection.setAutoCommit(originalAutoCommit);
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
        long now = System.currentTimeMillis();
        if (now < nextConnectionAttemptAt.get()) {
            return;
        }

        DatabaseSettings currentSettings = settings;
        if (currentSettings == null) {
            currentSettings = loadSettings();
            settings = currentSettings;
        }

        loadDriver(currentSettings.driverClass());

        Properties properties = new Properties();
        properties.setProperty("user", currentSettings.username());
        properties.setProperty("password", currentSettings.password());
        properties.setProperty(
                "connectTimeout",
                String.valueOf(connectTimeoutMillis)
        );
        properties.setProperty(
                "socketTimeout",
                String.valueOf(socketTimeoutMillis)
        );

        try (Connection connection = DriverManager.getConnection(
                currentSettings.jdbcUrl(),
                properties
        )) {
            ensureSchema(connection);
            work.run(connection);
            mirrorSuccess();
        }
    }

    private DatabaseSettings loadSettings() {
        if (!settingsFile.isFile()) {
            throw new IllegalStateException(settingsFile.getName() + " is missing");
        }

        FileConfiguration databaseConfig =
                YamlConfiguration.loadConfiguration(settingsFile);
        String password = databaseConfig.getString("database.password", "");

        if (password.isBlank()
                || password.equalsIgnoreCase("change-me")
                || password.toUpperCase(Locale.ROOT).startsWith("CHANGE-ME-")) {
            throw new IllegalStateException(
                    settingsFile.getName() + " database password is not configured"
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

    private void loadDriver(String driverClass) throws ClassNotFoundException {
        if (!driverClass.isBlank()) {
            Class.forName(driverClass);
        }
    }

    private void ensureSchema(Connection connection) throws Exception {
        if (schemaReady) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
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
                    seller_name = VALUES(seller_name),
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
        MirrorPayload payload = listing.payload();
        statement.setString(1, payload.id().toString());
        statement.setString(2, payload.sellerId().toString());
        statement.setString(3, limit(listing.sellerName(), 16));
        statement.setString(4, payload.material().name());
        statement.setString(5, limit(payload.itemName(), 128));
        statement.setInt(6, payload.amount());
        statement.setBytes(7, payload.itemNbt());
        statement.setLong(8, payload.priceCents());
        statement.setLong(9, payload.createdAt());
        statement.setLong(10, listing.expiresAt());
        statement.setString(11, listing.status());
        statement.setLong(12, System.currentTimeMillis());
        statement.setLong(13, syncGeneration);
    }

    private String itemDisplayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component displayName = meta.displayName();
            if (displayName != null) {
                String plain = PlainTextComponentSerializer.plainText()
                        .serialize(displayName)
                        .replace('\n', ' ')
                        .replace('\r', ' ')
                        .trim();
                if (!plain.isBlank()) {
                    return plain;
                }
            }
        }

        String raw = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder output = new StringBuilder(raw.length());
        boolean capitalize = true;
        for (char character : raw.toCharArray()) {
            if (character == ' ') {
                output.append(' ');
                capitalize = true;
                continue;
            }
            output.append(capitalize ? Character.toUpperCase(character) : character);
            capitalize = false;
        }
        return output.toString();
    }

    private long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
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

    private String safeTableName(String configured) {
        String fallback = "mineacle_auction_listings";
        String value = configured == null ? "" : configured.trim();
        if (!value.matches("[A-Za-z0-9_]{1,64}")) {
            core.getLogger().warning(
                    "[AuctionHouse] Invalid database mirror table '"
                            + configured + "', using " + fallback
            );
            return fallback;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String safeSettingsFileName(String configured) {
        String value = configured == null ? "" : configured.trim();
        if (!value.matches("[A-Za-z0-9_.-]{1,64}") || value.contains("..")) {
            core.getLogger().warning(
                    "[AuctionHouse] Invalid database settings file; using webprofiles.yml"
            );
            return "webprofiles.yml";
        }
        return value;
    }

    private void mirrorSuccess() {
        nextConnectionAttemptAt.set(0L);
        lastErrorLogAt.set(0L);

        if (readyLogged.compareAndSet(false, true)) {
            core.getLogger().info(
                    "[AuctionHouse] MariaDB mirror ready: " + table
            );
        }
    }

    private void snapshotFailure(
            String message,
            RuntimeException exception
    ) {
        long now = System.currentTimeMillis();
        long previous = lastErrorLogAt.get();

        if (previous > 0L
                && now - previous < ERROR_LOG_INTERVAL_MILLIS) {
            return;
        }
        if (!lastErrorLogAt.compareAndSet(previous, now)) {
            return;
        }

        core.getLogger().log(
                Level.WARNING,
                "[AuctionHouse] " + message
                        + "; player trading is unaffected",
                exception
        );
    }

    private void mirrorFailure(Exception exception) {
        schemaReady = false;
        settings = null;

        long now = System.currentTimeMillis();
        nextConnectionAttemptAt.accumulateAndGet(
                safeAdd(now, failureBackoffMillis),
                Math::max
        );

        long previous = lastErrorLogAt.get();
        if (previous > 0L && now - previous < ERROR_LOG_INTERVAL_MILLIS) {
            return;
        }
        if (!lastErrorLogAt.compareAndSet(previous, now)) {
            return;
        }

        core.getLogger().log(
                Level.WARNING,
                "[AuctionHouse] MariaDB mirror unavailable; player trading "
                        + "remains available from local storage: "
                        + safeMessage(exception),
                exception
        );
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws Exception;
    }

    private record DatabaseSettings(
            String driverClass,
            String jdbcUrl,
            String username,
            String password
    ) {
        private DatabaseSettings {
            driverClass = driverClass == null ? "" : driverClass.trim();
            jdbcUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
            username = username == null ? "" : username.trim();

            if (jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("database.jdbc-url is blank");
            }
            if (username.isBlank()) {
                throw new IllegalArgumentException("database.username is blank");
            }
        }
    }

    private record MirrorPayload(
            UUID id,
            UUID sellerId,
            Material material,
            String itemName,
            int amount,
            byte[] itemNbt,
            long priceCents,
            long createdAt
    ) {
        private MirrorPayload {
            itemName = itemName == null ? "" : itemName;
            itemNbt = itemNbt == null ? new byte[0] : itemNbt.clone();
            amount = Math.max(1, amount);
        }

        /* Internal JDBC binding only; PreparedStatement does not mutate it. */
        @Override
        public byte[] itemNbt() {
            return itemNbt;
        }

        private int itemNbtLength() {
            return itemNbt.length;
        }
    }

    private record MirrorListing(
            MirrorPayload payload,
            String sellerName,
            long expiresAt,
            String status
    ) {
        private MirrorListing {
            sellerName = sellerName == null || sellerName.isBlank()
                    ? "Unknown"
                    : sellerName;
        }
    }
}
