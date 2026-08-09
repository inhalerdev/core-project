package net.mineacle.core.orders.storage;

import net.mineacle.core.Core;
import net.mineacle.core.orders.model.OrderRecord;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class YamlOrdersRepository
        implements OrdersRepository {

    private static final int SCHEMA_VERSION = 3;
    private static final long SAVE_DEBOUNCE_MILLIS = 250L;
    private static final long SAVE_RETRY_MILLIS = 30_000L;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private static final Comparator<OrderKey> NEWEST_FIRST =
            Comparator.comparingLong(
                            OrderKey::createdAtMillis
                    )
                    .reversed()
                    .thenComparing(
                            key -> key.id().toString()
                    );

    private final Core core;
    private final File file;
    private final Map<UUID, OrderRecord> orders =
            new LinkedHashMap<>();
    private final NavigableSet<OrderKey> activeIndex =
            new TreeSet<>(NEWEST_FIRST);
    private final Map<UUID, NavigableSet<OrderKey>> ownerIndex =
            new HashMap<>();
    private final Map<UUID, Integer> activeCountByOwner =
            new HashMap<>();
    private final ScheduledThreadPoolExecutor persistenceExecutor;

    private boolean dirty;
    private boolean closed;
    private long generation;
    private long persistedGeneration;
    private ScheduledFuture<?> pendingSave;

    public YamlOrdersRepository(Core core) {
        this.core = core;
        this.file = new File(
                core.getDataFolder(),
                "orders.yml"
        );
        this.persistenceExecutor =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> {
                            Thread thread = new Thread(
                                    runnable,
                                    "Mineacle-OrdersSave"
                            );
                            thread.setDaemon(true);
                            return thread;
                        }
                );
        persistenceExecutor.setRemoveOnCancelPolicy(
                true
        );
        persistenceExecutor
                .setExecuteExistingDelayedTasksAfterShutdownPolicy(
                        false
                );
        persistenceExecutor
                .setContinueExistingPeriodicTasksAfterShutdownPolicy(
                        false
                );

        loadFromDisk();
    }

    @Override
    public synchronized boolean save() {
        if (closed) {
            return false;
        }

        if (!dirty) {
            return true;
        }

        return scheduleSaveLocked(0L);
    }

    @Override
    public void shutdown() {
        Snapshot finalSnapshot;
        long finalGeneration;

        synchronized (this) {
            if (closed) {
                return;
            }

            closed = true;

            if (pendingSave != null) {
                pendingSave.cancel(false);
                pendingSave = null;
            }

            finalSnapshot = snapshotLocked();
            finalGeneration = generation;

            if (dirty
                    || persistedGeneration
                    < finalGeneration) {
                try {
                    persistenceExecutor.execute(
                            () -> persistFinal(
                                    finalSnapshot,
                                    finalGeneration
                            )
                    );
                } catch (RejectedExecutionException exception) {
                    logFailure(
                            "Could not queue final orders save",
                            exception
                    );
                }
            }
        }

        persistenceExecutor.shutdown();

        try {
            if (!persistenceExecutor.awaitTermination(
                    SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            )) {
                persistenceExecutor.shutdownNow();
                core.getLogger().severe(
                        "Orders persistence did not finish within "
                                + SHUTDOWN_TIMEOUT_SECONDS
                                + " seconds"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            persistenceExecutor.shutdownNow();
            core.getLogger().log(
                    Level.SEVERE,
                    "Interrupted while flushing orders persistence",
                    exception
            );
        }
    }

    @Override
    public synchronized Collection<OrderRecord> active() {
        return recordsFor(activeIndex);
    }

    @Override
    public synchronized Collection<OrderRecord> byOwner(
            UUID ownerId
    ) {
        if (ownerId == null) {
            return List.of();
        }

        NavigableSet<OrderKey> keys =
                ownerIndex.get(ownerId);

        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        return recordsFor(keys);
    }

    @Override
    public synchronized int activeCountByOwner(
            UUID ownerId
    ) {
        if (ownerId == null) {
            return 0;
        }

        return activeCountByOwner.getOrDefault(
                ownerId,
                0
        );
    }

    @Override
    public synchronized OrderRecord get(UUID id) {
        if (id == null) {
            return null;
        }

        OrderRecord order = orders.get(id);

        return order == null
                ? null
                : order.copy();
    }

    @Override
    public synchronized boolean put(
            OrderRecord order
    ) {
        if (order == null || closed) {
            return false;
        }

        OrderRecord stored = order.copy();
        OrderRecord previous = orders.put(
                stored.id(),
                stored
        );

        if (previous != null) {
            deindex(previous);
        }

        index(stored);
        changedLocked();
        return true;
    }

    @Override
    public synchronized boolean remove(UUID id) {
        if (id == null || closed) {
            return false;
        }

        OrderRecord removed = orders.remove(id);

        if (removed == null) {
            return true;
        }

        deindex(removed);
        changedLocked();
        return true;
    }

    private void loadFromDisk() {
        ensureFile();

        YamlConfiguration configuration =
                YamlConfiguration.loadConfiguration(file);
        int loadedSchema = configuration.getInt(
                "schema-version",
                1
        );
        ConfigurationSection section =
                configuration.getConfigurationSection(
                        "orders"
                );
        Map<UUID, OrderRecord> loaded =
                new LinkedHashMap<>();

        if (section != null) {
            for (String key : section.getKeys(false)) {
                OrderRecord order = readOrder(
                        configuration,
                        key
                );

                if (order != null) {
                    loaded.put(
                            order.id(),
                            order
                    );
                }
            }
        }

        synchronized (this) {
            orders.clear();
            activeIndex.clear();
            ownerIndex.clear();
            activeCountByOwner.clear();

            for (OrderRecord order : loaded.values()) {
                orders.put(
                        order.id(),
                        order.copy()
                );
                index(order);
            }

            dirty = loadedSchema < SCHEMA_VERSION;
            generation = dirty ? 1L : 0L;
            persistedGeneration = 0L;

            if (dirty && !orders.isEmpty()) {
                backupBeforeMigration();
                scheduleSaveLocked(0L);
            }
        }
    }

    private OrderRecord readOrder(
            YamlConfiguration configuration,
            String key
    ) {
        String path = "orders." + key;

        try {
            UUID id = UUID.fromString(key);
            UUID ownerId = UUID.fromString(
                    configuration.getString(
                            path + ".owner-id",
                            ""
                    )
            );
            String ownerName =
                    configuration.getString(
                            path + ".owner-name",
                            ""
                    );
            Material material = Material.matchMaterial(
                    configuration.getString(
                            path + ".material",
                            ""
                    )
            );

            if (material == null
                    || material == Material.AIR
                    || !material.isItem()) {
                throw new IllegalArgumentException(
                        "Invalid order material"
                );
            }

            int requestedAmount = Math.max(
                    1,
                    configuration.getInt(
                            path + ".requested-amount",
                            1
                    )
            );
            int deliveredAmount = Math.clamp(
                    configuration.getInt(
                            path + ".delivered-amount",
                            0
                    ),
                    0,
                    requestedAmount
            );
            int collectedAmount = Math.clamp(
                    configuration.getInt(
                            path + ".collected-amount",
                            0
                    ),
                    0,
                    deliveredAmount
            );
            long legacyPricePerItem = Math.max(
                    1L,
                    configuration.getLong(
                            path + ".price-per-item-cents",
                            1L
                    )
            );
            long legacyTotal = safeMultiply(
                    legacyPricePerItem,
                    requestedAmount
            );
            long totalEscrow = Math.max(
                    0L,
                    configuration.getLong(
                            path + ".total-escrow-cents",
                            legacyTotal
                    )
            );
            long escrowRemaining = Math.clamp(
                    configuration.getLong(
                            path + ".escrow-remaining-cents",
                            totalEscrow
                    ),
                    0L,
                    totalEscrow
            );
            long createdAt = Math.max(
                    0L,
                    configuration.getLong(
                            path + ".created-at-millis",
                            System.currentTimeMillis()
                    )
            );
            boolean active =
                    configuration.getBoolean(
                            path + ".active",
                            true
                    );

            return new OrderRecord(
                    id,
                    ownerId,
                    ownerName,
                    material,
                    requestedAmount,
                    deliveredAmount,
                    collectedAmount,
                    totalEscrow,
                    escrowRemaining,
                    createdAt,
                    active
            );
        } catch (IllegalArgumentException exception) {
            core.getLogger().warning(
                    "Skipped invalid order "
                            + key
                            + ": "
                            + exception.getMessage()
            );
            return null;
        }
    }

    private void index(OrderRecord order) {
        OrderKey key = new OrderKey(
                order.id(),
                order.createdAtMillis()
        );

        ownerIndex.computeIfAbsent(
                order.ownerId(),
                ignored -> new TreeSet<>(
                        NEWEST_FIRST
                )
        ).add(key);

        if (order.active()
                && order.remainingAmount() > 0) {
            activeIndex.add(key);
            activeCountByOwner.merge(
                    order.ownerId(),
                    1,
                    Integer::sum
            );
        }
    }

    private void deindex(OrderRecord order) {
        OrderKey key = new OrderKey(
                order.id(),
                order.createdAtMillis()
        );

        activeIndex.remove(key);

        NavigableSet<OrderKey> ownerOrders =
                ownerIndex.get(order.ownerId());

        if (ownerOrders != null) {
            ownerOrders.remove(key);

            if (ownerOrders.isEmpty()) {
                ownerIndex.remove(order.ownerId());
            }
        }

        if (order.active()
                && order.remainingAmount() > 0) {
            int updated = activeCountByOwner
                    .getOrDefault(
                            order.ownerId(),
                            1
                    ) - 1;

            if (updated <= 0) {
                activeCountByOwner.remove(
                        order.ownerId()
                );
            } else {
                activeCountByOwner.put(
                        order.ownerId(),
                        updated
                );
            }
        }
    }

    private Collection<OrderRecord> recordsFor(
            Collection<OrderKey> keys
    ) {
        List<OrderRecord> result =
                new ArrayList<>(keys.size());

        for (OrderKey key : keys) {
            OrderRecord order = orders.get(
                    key.id()
            );

            if (order != null) {
                result.add(order.copy());
            }
        }

        return List.copyOf(result);
    }

    private void changedLocked() {
        dirty = true;
        generation++;
        scheduleSaveLocked(
                SAVE_DEBOUNCE_MILLIS
        );
    }

    private boolean scheduleSaveLocked(
            long delayMillis
    ) {
        if (closed || !dirty) {
            return !closed;
        }

        if (pendingSave != null
                && !pendingSave.isDone()) {
            if (delayMillis > 0L) {
                return true;
            }

            pendingSave.cancel(false);
            pendingSave = null;
        }

        try {
            pendingSave =
                    persistenceExecutor.schedule(
                            this::persistLatest,
                            Math.max(
                                    0L,
                                    delayMillis
                            ),
                            TimeUnit.MILLISECONDS
                    );
            return true;
        } catch (RejectedExecutionException exception) {
            logFailure(
                    "Could not queue orders persistence",
                    exception
            );
            return false;
        }
    }

    private void persistLatest() {
        Snapshot snapshot;
        long snapshotGeneration;

        synchronized (this) {
            pendingSave = null;

            if (closed || !dirty) {
                return;
            }

            snapshot = snapshotLocked();
            snapshotGeneration = generation;
        }

        try {
            writeSnapshot(snapshot);
        } catch (IOException exception) {
            synchronized (this) {
                dirty = true;

                if (!closed) {
                    scheduleSaveLocked(
                            SAVE_RETRY_MILLIS
                    );
                }
            }

            logFailure(
                    "Could not save orders.yml — the latest "
                            + "snapshot remains in memory and "
                            + "will be retried",
                    exception
            );
            return;
        }

        synchronized (this) {
            persistedGeneration = Math.max(
                    persistedGeneration,
                    snapshotGeneration
            );
            dirty = generation > persistedGeneration;

            if (dirty && !closed) {
                scheduleSaveLocked(0L);
            }
        }
    }

    private void persistFinal(
            Snapshot snapshot,
            long snapshotGeneration
    ) {
        try {
            writeSnapshot(snapshot);

            synchronized (this) {
                persistedGeneration = Math.max(
                        persistedGeneration,
                        snapshotGeneration
                );
                dirty = generation
                        > persistedGeneration;
            }
        } catch (IOException exception) {
            logFailure(
                    "Could not save final orders.yml snapshot",
                    exception
            );
        }
    }

    private Snapshot snapshotLocked() {
        List<OrderSnapshot> snapshots =
                new ArrayList<>(orders.size());

        for (OrderRecord order : orders.values()) {
            snapshots.add(
                    new OrderSnapshot(
                            order.id(),
                            order.ownerId(),
                            order.ownerName(),
                            order.material(),
                            order.requestedAmount(),
                            order.deliveredAmount(),
                            order.collectedAmount(),
                            order.totalEscrowCents(),
                            order.escrowRemainingCents(),
                            order.createdAtMillis(),
                            order.active()
                    )
            );
        }

        snapshots.sort(
                Comparator.comparing(
                        snapshot ->
                                snapshot.id().toString()
                )
        );

        return new Snapshot(
                List.copyOf(snapshots)
        );
    }

    private void writeSnapshot(
            Snapshot snapshot
    ) throws IOException {
        YamlConfiguration configuration =
                new YamlConfiguration();
        configuration.set(
                "schema-version",
                SCHEMA_VERSION
        );

        for (OrderSnapshot order :
                snapshot.orders()) {
            String path =
                    "orders." + order.id();

            configuration.set(
                    path + ".owner-id",
                    order.ownerId().toString()
            );
            configuration.set(
                    path + ".owner-name",
                    order.ownerName()
            );
            configuration.set(
                    path + ".material",
                    order.material().name()
            );
            configuration.set(
                    path + ".requested-amount",
                    order.requestedAmount()
            );
            configuration.set(
                    path + ".delivered-amount",
                    order.deliveredAmount()
            );
            configuration.set(
                    path + ".collected-amount",
                    order.collectedAmount()
            );

            long averagePrice =
                    order.requestedAmount() <= 0
                            ? 0L
                            : Math.max(
                                    1L,
                                    order.totalEscrowCents()
                                            / order.requestedAmount()
                            );

            configuration.set(
                    path + ".price-per-item-cents",
                    averagePrice
            );
            configuration.set(
                    path + ".total-escrow-cents",
                    order.totalEscrowCents()
            );
            configuration.set(
                    path + ".escrow-remaining-cents",
                    order.escrowRemainingCents()
            );
            configuration.set(
                    path + ".created-at-millis",
                    order.createdAtMillis()
            );
            configuration.set(
                    path + ".active",
                    order.active()
            );
        }

        atomicSave(configuration);
    }

    private void atomicSave(
            YamlConfiguration configuration
    ) throws IOException {
        File folder = core.getDataFolder();

        if (!folder.exists()
                && !folder.mkdirs()
                && !folder.exists()) {
            throw new IOException(
                    "Could not create MineacleCore data folder"
            );
        }

        File temporary = new File(
                folder,
                file.getName() + ".tmp"
        );

        configuration.save(temporary);

        try {
            Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(
                    temporary.toPath()
            );
        }
    }

    private void backupBeforeMigration() {
        File backup = new File(
                file.getParentFile(),
                "orders.yml.pre-v3-migration.bak"
        );

        if (backup.exists() || !file.isFile()) {
            return;
        }

        try {
            Files.copy(
                    file.toPath(),
                    backup.toPath()
            );
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Could not create Orders migration backup",
                    exception
            );
        }
    }

    private void ensureFile() {
        File folder = core.getDataFolder();

        if (!folder.exists()
                && !folder.mkdirs()
                && !folder.exists()) {
            throw new IllegalStateException(
                    "Could not create MineacleCore data folder"
            );
        }

        if (file.exists()) {
            return;
        }

        try {
            if (!file.createNewFile()
                    && !file.isFile()) {
                throw new IOException(
                        "Could not create orders.yml"
                );
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not initialize orders.yml",
                    exception
            );
        }
    }

    private long safeMultiply(
            long left,
            long right
    ) {
        try {
            return Math.multiplyExact(
                    left,
                    right
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private void logFailure(
            String message,
            Exception exception
    ) {
        core.getLogger().log(
                Level.SEVERE,
                message,
                exception
        );
    }

    private record OrderKey(
            UUID id,
            long createdAtMillis
    ) {
    }

    private record OrderSnapshot(
            UUID id,
            UUID ownerId,
            String ownerName,
            Material material,
            int requestedAmount,
            int deliveredAmount,
            int collectedAmount,
            long totalEscrowCents,
            long escrowRemainingCents,
            long createdAtMillis,
            boolean active
    ) {
    }

    private record Snapshot(
            List<OrderSnapshot> orders
    ) {
    }
}
