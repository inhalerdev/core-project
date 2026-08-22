package net.mineacle.core.market.storage;

import net.mineacle.core.Core;
import net.mineacle.core.market.model.MarketTransaction;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;

/**
 * One-file-per-transaction durable journal for Mineacle's shared market.
 * Completed records are deleted; unresolved records are replayed on startup.
 */
public final class MarketTransactionStorage {

    private static final int SCHEMA_VERSION = 1;

    public record LoadResult(
            boolean healthy,
            List<MarketTransaction> transactions,
            List<String> problems
    ) {
    }

    private final Core core;
    private final File directory;
    private boolean directorySyncWarningLogged;

    public MarketTransactionStorage(Core core) {
        this.core = core;
        this.directory = new File(
                core.getDataFolder(),
                "market-transactions"
        );
    }

    public synchronized LoadResult load() {
        List<MarketTransaction> transactions =
                new ArrayList<>();
        List<String> problems =
                new ArrayList<>();

        if (directoryUnavailable()) {
            problems.add(
                    "could not create market-transactions directory"
            );
            return new LoadResult(
                    false,
                    List.of(),
                    List.copyOf(problems)
            );
        }

        File[] files = directory.listFiles(
                (ignored, name) ->
                        name.toLowerCase(Locale.ROOT)
                                .endsWith(".yml")
        );

        if (files == null) {
            problems.add(
                    "could not list market-transactions directory"
            );
            return new LoadResult(
                    false,
                    List.of(),
                    List.copyOf(problems)
            );
        }

        java.util.Arrays.sort(
                files,
                Comparator.comparing(File::getName)
        );

        for (File file : files) {
            try {
                MarketTransaction transaction =
                        read(file);
                validate(transaction);
                transactions.add(transaction);
            } catch (Exception exception) {
                problems.add(
                        file.getName()
                                + ": "
                                + exception.getClass().getSimpleName()
                                + " — "
                                + safeMessage(exception)
                );
            }
        }

        return new LoadResult(
                problems.isEmpty(),
                List.copyOf(transactions),
                List.copyOf(problems)
        );
    }

    public synchronized boolean save(
            MarketTransaction transaction
    ) {
        if (transaction == null
                || directoryUnavailable()) {
            return false;
        }

        try {
            validate(transaction);
            YamlConfiguration configuration =
                    write(transaction);
            atomicSave(
                    configuration,
                    file(transaction.transactionId())
            );
            return true;
        } catch (Exception exception) {
            core.getLogger().severe(
                    "[Market] Could not save transaction "
                            + transaction.transactionId()
                            + ": "
                            + safeMessage(exception)
            );
            return false;
        }
    }

    public synchronized boolean delete(UUID transactionId) {
        if (transactionId == null
                || directoryUnavailable()) {
            return false;
        }

        Path path = file(transactionId).toPath();

        try {
            Files.deleteIfExists(path);
            forceDirectory(directory.toPath());
            return true;
        } catch (IOException exception) {
            core.getLogger().severe(
                    "[Market] Could not delete committed transaction "
                            + transactionId
                            + ": "
                            + safeMessage(exception)
            );
            return false;
        }
    }

    private MarketTransaction read(File file) {
        YamlConfiguration configuration =
                YamlConfiguration.loadConfiguration(file);
        int schema = configuration.getInt(
                "schema-version",
                0
        );

        if (schema != SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "unsupported schema " + schema
            );
        }

        UUID transactionId = UUID.fromString(
                required(
                        configuration.getString("transaction-id"),
                        "transaction-id"
                )
        );
        MarketTransaction.State state =
                MarketTransaction.State.valueOf(
                        required(
                                configuration.getString("state"),
                                "state"
                        ).toUpperCase(Locale.ROOT)
                );
        UUID sellerId = UUID.fromString(
                required(
                        configuration.getString("seller-id"),
                        "seller-id"
                )
        );

        List<MarketTransaction.SourceItem> sourceItems =
                readSourceItems(configuration);
        List<MarketTransaction.SellLeg> sellLegs =
                readSellLegs(configuration);

        return new MarketTransaction(
                transactionId,
                state,
                sellerId,
                sourceItems,
                sellLegs,
                configuration.getLong(
                        "payout.order-cents",
                        0L
                ),
                configuration.getLong(
                        "payout.server-cents",
                        0L
                ),
                configuration.getLong(
                        "payout.total-cents",
                        0L
                ),
                configuration.getLong(
                        "created-at-millis",
                        0L
                ),
                configuration.getString(
                        "quarantine-reason",
                        ""
                )
        );
    }

    private List<MarketTransaction.SourceItem> readSourceItems(
            YamlConfiguration configuration
    ) {
        ConfigurationSection section =
                configuration.getConfigurationSection(
                        "source-items"
                );

        if (section == null) {
            return List.of();
        }

        List<String> keys = new ArrayList<>(
                section.getKeys(false)
        );
        keys.sort(this::numericKeyCompare);
        List<MarketTransaction.SourceItem> result =
                new ArrayList<>(keys.size());

        for (String key : keys) {
            String path = "source-items." + key;
            int slot = configuration.getInt(
                    path + ".slot",
                    -1
            );
            ItemStack item = configuration.getItemStack(
                    path + ".item"
            );

            if (slot < 0
                    || item == null
                    || item.getType().isAir()
                    || item.getAmount() <= 0) {
                throw new IllegalStateException(
                        "invalid source item " + key
                );
            }

            result.add(
                    new MarketTransaction.SourceItem(
                            slot,
                            item
                    )
            );
        }

        return List.copyOf(result);
    }

    private List<MarketTransaction.SellLeg> readSellLegs(
            YamlConfiguration configuration
    ) {
        ConfigurationSection section =
                configuration.getConfigurationSection(
                        "sell-legs"
                );

        if (section == null) {
            return List.of();
        }

        List<String> keys = new ArrayList<>(
                section.getKeys(false)
        );
        keys.sort(this::numericKeyCompare);
        List<MarketTransaction.SellLeg> result =
                new ArrayList<>(keys.size());

        for (String key : keys) {
            String path = "sell-legs." + key;
            Material material = Material.matchMaterial(
                    required(
                            configuration.getString(
                                    path + ".material"
                            ),
                            path + ".material"
                    )
            );

            if (material == null
                    || material == Material.AIR
                    || !material.isItem()) {
                throw new IllegalStateException(
                        "invalid material in " + path
                );
            }

            result.add(
                    new MarketTransaction.SellLeg(
                            material,
                            configuration.getInt(
                                    path + ".requested-amount",
                                    0
                            ),
                            configuration.getLong(
                                    path + ".server-unit-cents",
                                    0L
                            ),
                            readOrderLegs(
                                    configuration,
                                    path + ".orders"
                            ),
                            configuration.getInt(
                                    path + ".order-amount",
                                    0
                            ),
                            configuration.getLong(
                                    path + ".order-payout-cents",
                                    0L
                            ),
                            configuration.getInt(
                                    path + ".server-amount",
                                    0
                            ),
                            configuration.getLong(
                                    path + ".server-payout-cents",
                                    0L
                            )
                    )
            );
        }

        return List.copyOf(result);
    }

    private List<MarketTransaction.OrderLeg> readOrderLegs(
            YamlConfiguration configuration,
            String basePath
    ) {
        ConfigurationSection section =
                configuration.getConfigurationSection(basePath);

        if (section == null) {
            return List.of();
        }

        List<String> keys = new ArrayList<>(
                section.getKeys(false)
        );
        keys.sort(this::numericKeyCompare);
        List<MarketTransaction.OrderLeg> result =
                new ArrayList<>(keys.size());

        for (String key : keys) {
            String path = basePath + "." + key;
            result.add(
                    new MarketTransaction.OrderLeg(
                            UUID.fromString(
                                    required(
                                            configuration.getString(
                                                    path + ".order-id"
                                            ),
                                            path + ".order-id"
                                    )
                            ),
                            UUID.fromString(
                                    required(
                                            configuration.getString(
                                                    path + ".buyer-id"
                                            ),
                                            path + ".buyer-id"
                                    )
                            ),
                            configuration.getInt(
                                    path + ".amount",
                                    0
                            ),
                            configuration.getLong(
                                    path + ".unit-price-cents",
                                    0L
                            ),
                            configuration.getLong(
                                    path + ".payout-cents",
                                    0L
                            ),
                            configuration.getLong(
                                    path + ".created-at-millis",
                                    0L
                            )
                    )
            );
        }

        return List.copyOf(result);
    }

    private YamlConfiguration write(
            MarketTransaction transaction
    ) {
        YamlConfiguration configuration =
                new YamlConfiguration();
        configuration.set(
                "schema-version",
                SCHEMA_VERSION
        );
        configuration.set(
                "transaction-id",
                transaction.transactionId().toString()
        );
        configuration.set(
                "state",
                transaction.state().name()
        );
        configuration.set(
                "seller-id",
                transaction.sellerId().toString()
        );
        configuration.set(
                "payout.order-cents",
                transaction.orderPayoutCents()
        );
        configuration.set(
                "payout.server-cents",
                transaction.serverPayoutCents()
        );
        configuration.set(
                "payout.total-cents",
                transaction.totalPayoutCents()
        );
        configuration.set(
                "created-at-millis",
                transaction.createdAtMillis()
        );

        if (!transaction.quarantineReason().isBlank()) {
            configuration.set(
                    "quarantine-reason",
                    transaction.quarantineReason()
            );
        }

        int sourceIndex = 0;
        for (MarketTransaction.SourceItem sourceItem
                : transaction.sourceItems()) {
            String path =
                    "source-items." + sourceIndex++;
            configuration.set(
                    path + ".slot",
                    sourceItem.slot()
            );
            configuration.set(
                    path + ".item",
                    sourceItem.item()
            );
        }

        int legIndex = 0;
        for (MarketTransaction.SellLeg sellLeg
                : transaction.sellLegs()) {
            String path =
                    "sell-legs." + legIndex++;
            configuration.set(
                    path + ".material",
                    sellLeg.material().name()
            );
            configuration.set(
                    path + ".requested-amount",
                    sellLeg.requestedAmount()
            );
            configuration.set(
                    path + ".server-unit-cents",
                    sellLeg.serverUnitCents()
            );
            configuration.set(
                    path + ".order-amount",
                    sellLeg.orderAmount()
            );
            configuration.set(
                    path + ".order-payout-cents",
                    sellLeg.orderPayoutCents()
            );
            configuration.set(
                    path + ".server-amount",
                    sellLeg.serverAmount()
            );
            configuration.set(
                    path + ".server-payout-cents",
                    sellLeg.serverPayoutCents()
            );

            int orderIndex = 0;
            for (MarketTransaction.OrderLeg orderLeg
                    : sellLeg.orderLegs()) {
                String orderPath =
                        path + ".orders." + orderIndex++;
                configuration.set(
                        orderPath + ".order-id",
                        orderLeg.orderId().toString()
                );
                configuration.set(
                        orderPath + ".buyer-id",
                        orderLeg.buyerId().toString()
                );
                configuration.set(
                        orderPath + ".amount",
                        orderLeg.amount()
                );
                configuration.set(
                        orderPath + ".unit-price-cents",
                        orderLeg.unitPriceCents()
                );
                configuration.set(
                        orderPath + ".payout-cents",
                        orderLeg.payoutCents()
                );
                configuration.set(
                        orderPath + ".created-at-millis",
                        orderLeg.createdAtMillis()
                );
            }
        }

        return configuration;
    }

    private void validate(MarketTransaction transaction) {
        if (transaction == null
                || transaction.transactionId() == null
                || transaction.sellerId() == null
                || MarketAccountsGuard.internalSeller(
                transaction.sellerId()
        )) {
            throw new IllegalStateException(
                    "invalid transaction identity"
            );
        }

        if (!transaction.payoutInvariant()) {
            throw new IllegalStateException(
                    "payout invariant failed"
            );
        }

        if (transaction.totalPayoutCents() <= 0L
                || transaction.sourceItems().isEmpty()
                || transaction.sellLegs().isEmpty()) {
            throw new IllegalStateException(
                    "transaction has no executable source or payout"
            );
        }

        long orderPayout = 0L;
        long serverPayout = 0L;
        Map<Material, Long> soldAmounts =
                new HashMap<>();

        try {
            for (MarketTransaction.SellLeg leg
                    : transaction.sellLegs()) {
                if (leg == null
                        || !leg.amountInvariant()
                        || !leg.payoutInvariant()) {
                    throw new IllegalStateException(
                            "sell-leg invariant failed"
                    );
                }

                for (MarketTransaction.OrderLeg orderLeg
                        : leg.orderLegs()) {
                    if (transaction.sellerId().equals(
                            orderLeg.buyerId()
                    )) {
                        throw new IllegalStateException(
                                "seller cannot fill own order"
                        );
                    }
                }

                orderPayout = Math.addExact(
                        orderPayout,
                        leg.orderPayoutCents()
                );
                serverPayout = Math.addExact(
                        serverPayout,
                        leg.serverPayoutCents()
                );
                soldAmounts.merge(
                        leg.material(),
                        (long) leg.requestedAmount(),
                        Math::addExact
                );
            }
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "sell-leg totals overflow",
                    exception
            );
        }

        if (orderPayout != transaction.orderPayoutCents()
                || serverPayout != transaction.serverPayoutCents()) {
            throw new IllegalStateException(
                    "transaction leg payout totals do not reconcile"
            );
        }

        Map<Material, Long> sourceAmounts =
                new HashMap<>();
        Set<Integer> sourceSlots =
                new HashSet<>();

        try {
            for (MarketTransaction.SourceItem source
                    : transaction.sourceItems()) {
                if (source == null
                        || !sourceSlots.add(source.slot())) {
                    throw new IllegalStateException(
                            "duplicate or invalid source slot"
                    );
                }

                ItemStack sourceStack = source.item();
                sourceAmounts.merge(
                        sourceStack.getType(),
                        (long) sourceStack.getAmount(),
                        Math::addExact
                );
            }
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "source item totals overflow",
                    exception
            );
        }

        if (!sourceAmounts.equals(soldAmounts)) {
            throw new IllegalStateException(
                    "source item totals do not reconcile with sell legs"
            );
        }

    }

    private boolean directoryUnavailable() {
        if (directory.isDirectory()) {
            return false;
        }

        if (directory.exists()) {
            return true;
        }

        return !directory.mkdirs();
    }

    private File file(UUID transactionId) {
        return new File(
                directory,
                transactionId + ".yml"
        );
    }

    private void atomicSave(
            YamlConfiguration configuration,
            File destination
    ) throws IOException {
        Path target = destination.toPath();
        Path temporary = new File(
                directory,
                destination.getName() + ".tmp"
        ).toPath();

        try {
            writeAndForce(
                    temporary,
                    configuration.saveToString()
            );

            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            forceDirectory(directory.toPath());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeAndForce(
            Path path,
            String value
    ) throws IOException {
        byte[] bytes = value.getBytes(
                StandardCharsets.UTF_8
        );

        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);

            while (buffer.hasRemaining()) {
                int written = channel.write(buffer);

                if (written <= 0) {
                    throw new IOException(
                            "could not make progress writing journal"
                    );
                }
            }

            channel.force(true);
        }
    }

    private void forceDirectory(Path path) {
        if (path == null || windows()) {
            return;
        }

        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.READ
        )) {
            channel.force(true);
        } catch (IOException
                 | UnsupportedOperationException
                 | SecurityException exception) {
            if (directorySyncWarningLogged) {
                return;
            }

            directorySyncWarningLogged = true;
            core.getLogger().warning(
                    "[Market] Directory sync unavailable; journal file contents "
                            + "are forced but rename durability depends on the filesystem: "
                            + exception.getClass().getSimpleName()
            );
        }
    }

    private int numericKeyCompare(
            String left,
            String right
    ) {
        try {
            return Integer.compare(
                    Integer.parseInt(left),
                    Integer.parseInt(right)
            );
        } catch (NumberFormatException exception) {
            return left.compareTo(right);
        }
    }

    private static String required(
            String value,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "missing " + field
            );
        }

        return value.trim();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "no detail"
                : message.replace('\n', ' ')
                .replace('\r', ' ');
    }

    private static boolean windows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    /** Avoids a storage->service package dependency for one identity check. */
    private static final class MarketAccountsGuard {
        private static boolean internalSeller(UUID sellerId) {
            return sellerId.equals(
                    UUID.fromString(
                            "0e655d25-b0a6-50a0-a319-14251d732485"
                    )
            );
        }
    }
}
