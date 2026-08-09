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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class YamlOrdersRepository
        implements OrdersRepository {

    private static final int SCHEMA_VERSION = 2;

    private final Core core;
    private final Map<UUID, OrderRecord> orders =
            new LinkedHashMap<>();

    private final File file;

    public YamlOrdersRepository(Core core) {
        this.core = core;
        this.file = new File(
                core.getDataFolder(),
                "orders.yml"
        );
        load();
    }

    @Override
    public synchronized void load() {
        ensureFile();

        YamlConfiguration configuration =
                YamlConfiguration.loadConfiguration(file);
        int loadedSchema = configuration.getInt(
                "schema-version",
                1
        );
        ConfigurationSection section =
                configuration.getConfigurationSection("orders");
        Map<UUID, OrderRecord> loaded =
                new LinkedHashMap<>();

        if (section != null) {
            for (String key : section.getKeys(false)) {
                OrderRecord order = read(
                        configuration,
                        key
                );

                if (order != null) {
                    loaded.put(order.id(), order);
                }
            }
        }

        orders.clear();
        orders.putAll(loaded);

        if (loadedSchema < SCHEMA_VERSION
                && !orders.isEmpty()) {
            backupBeforeMigration();
            save();
        }
    }

    @Override
    public synchronized boolean save() {
        ensureFile();

        YamlConfiguration snapshot =
                new YamlConfiguration();
        snapshot.set(
                "schema-version",
                SCHEMA_VERSION
        );

        for (OrderRecord order : orders.values()) {
            write(snapshot, order);
        }

        File temporary = new File(
                file.getParentFile(),
                file.getName() + ".tmp"
        );

        try {
            snapshot.save(temporary);

            try {
                Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            return true;
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not save orders.yml",
                    exception
            );

            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (IOException ignored) {
            }

            return false;
        }
    }

    @Override
    public synchronized Collection<OrderRecord> all() {
        List<OrderRecord> copy = new ArrayList<>();

        for (OrderRecord order : orders.values()) {
            copy.add(order.copy());
        }

        return List.copyOf(copy);
    }

    @Override
    public synchronized OrderRecord get(UUID id) {
        OrderRecord order = orders.get(id);

        return order == null
                ? null
                : order.copy();
    }

    @Override
    public synchronized boolean put(OrderRecord order) {
        if (order == null) {
            return false;
        }

        UUID id = order.id();
        OrderRecord previous = orders.put(
                id,
                order.copy()
        );

        if (save()) {
            return true;
        }

        if (previous == null) {
            orders.remove(id);
        } else {
            orders.put(id, previous);
        }

        return false;
    }

    @Override
    public synchronized boolean remove(UUID id) {
        if (id == null) {
            return false;
        }

        OrderRecord previous = orders.remove(id);

        if (previous == null) {
            return true;
        }

        if (save()) {
            return true;
        }

        orders.put(id, previous);
        return false;
    }

    private OrderRecord read(
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
            String ownerName = configuration.getString(
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
            int deliveredAmount = clamp(
                    configuration.getInt(
                            path + ".delivered-amount",
                            0
                    ),
                    0,
                    requestedAmount
            );
            int collectedAmount = clamp(
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
            long escrowRemaining = Math.max(
                    0L,
                    configuration.getLong(
                            path + ".escrow-remaining-cents",
                            totalEscrow
                    )
            );
            long createdAt = Math.max(
                    0L,
                    configuration.getLong(
                            path + ".created-at-millis",
                            System.currentTimeMillis()
                    )
            );
            boolean active = configuration.getBoolean(
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
        } catch (Exception exception) {
            core.getLogger().warning(
                    "Could not load order "
                            + key
                            + ": "
                            + exception.getMessage()
            );
            return null;
        }
    }

    private void write(
            YamlConfiguration snapshot,
            OrderRecord order
    ) {
        String path = "orders." + order.id();

        snapshot.set(
                path + ".owner-id",
                order.ownerId().toString()
        );
        snapshot.set(
                path + ".owner-name",
                order.ownerName()
        );
        snapshot.set(
                path + ".material",
                order.material().name()
        );
        snapshot.set(
                path + ".requested-amount",
                order.requestedAmount()
        );
        snapshot.set(
                path + ".delivered-amount",
                order.deliveredAmount()
        );
        snapshot.set(
                path + ".collected-amount",
                order.collectedAmount()
        );
        snapshot.set(
                path + ".price-per-item-cents",
                order.pricePerItemCents()
        );
        snapshot.set(
                path + ".total-escrow-cents",
                order.totalEscrowCents()
        );
        snapshot.set(
                path + ".escrow-remaining-cents",
                order.escrowRemainingCents()
        );
        snapshot.set(
                path + ".created-at-millis",
                order.createdAtMillis()
        );
        snapshot.set(
                path + ".active",
                order.active()
        );
    }

    private void backupBeforeMigration() {
        File backup = new File(
                file.getParentFile(),
                "orders.yml.pre-v2-migration.bak"
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

    private int clamp(
            int value,
            int minimum,
            int maximum
    ) {
        return Math.max(
                minimum,
                Math.min(maximum, value)
        );
    }

    private long safeMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
