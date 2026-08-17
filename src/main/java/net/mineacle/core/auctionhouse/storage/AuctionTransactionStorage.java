package net.mineacle.core.auctionhouse.storage;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.common.player.DisplayNames;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Durable transaction journal for Auction House v2 operations.
 *
 * <p>This journal is intentionally separate from the legacy purchase recovery
 * folder. Existing v1 recovery records remain readable/manual-reviewable while
 * all new list, buy, cancel and reclaim operations use the deterministic v2
 * state machine.</p>
 */
public final class AuctionTransactionStorage {

    private static final String EXTENSION = ".yml";
    private static final int MINIMUM_ITEM_BYTES = 16_384;
    private static final int MAXIMUM_ITEM_BYTES = 1_048_576;
    private static final long YAML_OVERHEAD_BYTES = 65_536L;

    public enum TransactionType {
        LIST,
        BUY,
        RETURN
    }

    public enum TransactionState {
        PREPARED,
        LISTING_SAVED,
        SOURCE_REMOVED,
        LISTING_REMOVED,
        PAYMENT_STARTED,
        PAID,
        DELIVERY_STARTED,
        DELIVERED,
        ABORTED,
        QUARANTINED
    }

    public record AuctionTransaction(
            UUID transactionId,
            TransactionType type,
            TransactionState state,
            AuctionHouseListing listing,
            UUID actor,
            String actorName,
            int sourceSlot,
            long createdAt
    ) {
        public AuctionTransaction {
            if (transactionId == null
                    || type == null
                    || state == null
                    || listing == null
                    || actor == null) {
                throw new IllegalArgumentException(
                        "Auction transaction fields cannot be null"
                );
            }

            actorName = actorName == null || actorName.isBlank()
                    ? "Unknown"
                    : actorName;
        }

        public AuctionTransaction withState(
                TransactionState nextState
        ) {
            return new AuctionTransaction(
                    transactionId,
                    type,
                    nextState,
                    listing,
                    actor,
                    actorName,
                    sourceSlot,
                    createdAt
            );
        }
    }

    public record LoadResult(
            List<AuctionTransaction> transactions,
            List<String> problems
    ) {
        public LoadResult {
            transactions =
                    transactions == null
                            ? List.of()
                            : List.copyOf(
                            transactions
                    );
            problems =
                    problems == null
                            ? List.of()
                            : List.copyOf(
                            problems
                    );
        }

        public boolean healthy() {
            return problems.isEmpty();
        }
    }

    private final Core core;
    private final File folder;
    private boolean initialized;
    private boolean directorySyncWarningLogged;
    private int maximumItemBytes = 262_144;

    public AuctionTransactionStorage(Core core) {
        this.core = core;
        this.folder = new File(
                new File(core.getDataFolder(), "auctionhouse"),
                "transactions-v2"
        );
    }

    public void configureMaximumItemBytes(
            int maximumItemBytes
    ) {
        this.maximumItemBytes =
                Math.clamp(
                        maximumItemBytes,
                        MINIMUM_ITEM_BYTES,
                        MAXIMUM_ITEM_BYTES
                );
    }

    public void initialize() {
        if (initialized) {
            return;
        }

        ensureDirectory(folder);
        initialized = true;
    }

    public AuctionTransaction begin(
            TransactionType type,
            AuctionHouseListing listing,
            Player actor,
            int sourceSlot
    ) {
        if (type == null
                || listing == null
                || actor == null) {
            return null;
        }

        AuctionTransaction transaction =
                new AuctionTransaction(
                        UUID.randomUUID(),
                        type,
                        TransactionState.PREPARED,
                        listing,
                        actor.getUniqueId(),
                        DisplayNames.commandDisplayName(
                                actor
                        ),
                        sourceSlot,
                        System.currentTimeMillis()
                );

        return save(transaction)
                ? transaction
                : null;
    }

    public LoadResult load() {
        List<String> problems =
                new ArrayList<>();

        try {
            initialize();
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Auction House transaction storage is unavailable",
                    exception
            );
            problems.add(
                    "transactions-v2 directory is unavailable"
            );
            return new LoadResult(
                    List.of(),
                    problems
            );
        }

        File[] files =
                folder.listFiles();

        if (files == null) {
            problems.add(
                    "transactions-v2 directory could not be read"
            );
            return new LoadResult(
                    List.of(),
                    problems
            );
        }

        List<File> ordered =
                new ArrayList<>(
                        List.of(files)
                );
        ordered.sort(
                Comparator.comparing(
                        File::getName
                )
        );

        Map<UUID, AuctionTransaction> byId =
                new LinkedHashMap<>();
        Map<UUID, UUID> listingTransactions =
                new LinkedHashMap<>();

        for (File file : ordered) {
            if (Files.isSymbolicLink(
                    file.toPath()
            )
                    || !file.isFile()) {
                problems.add(
                        file.getName()
                                + " • unexpected non-regular entry in transaction journal"
                );
                continue;
            }

            if (!file.getName()
                    .endsWith(
                            EXTENSION
                    )) {
                problems.add(
                        file.getName()
                                + " • unexpected file in transaction journal"
                );
                continue;
            }

            try {
                AuctionTransaction transaction =
                        readStrict(file);
                String expectedName =
                        transaction.transactionId()
                                + EXTENSION;

                if (!file.getName()
                        .equals(expectedName)) {
                    problems.add(
                            file.getName()
                                    + " • filename does not match transaction id "
                                    + transaction.transactionId()
                    );
                    continue;
                }

                AuctionTransaction previous =
                        byId.putIfAbsent(
                                transaction.transactionId(),
                                transaction
                        );

                if (previous != null) {
                    problems.add(
                            file.getName()
                                    + " • duplicate transaction id "
                                    + transaction.transactionId()
                    );
                    continue;
                }

                UUID previousTransaction =
                        listingTransactions.putIfAbsent(
                                transaction.listing()
                                        .id(),
                                transaction.transactionId()
                        );

                if (previousTransaction != null
                        && !previousTransaction.equals(
                        transaction.transactionId()
                )) {
                    problems.add(
                            file.getName()
                                    + " • listing "
                                    + transaction.listing()
                                    .id()
                                    + " is referenced by multiple transaction journals"
                    );
                }
            } catch (
                    IOException
                    | InvalidConfigurationException
                    | RuntimeException exception
            ) {
                core.getLogger().log(
                        Level.SEVERE,
                        "Could not safely read Auction House transaction "
                                + file.getName(),
                        exception
                );
                problems.add(
                        file.getName()
                                + " • unreadable or invalid transaction journal"
                );
            }
        }

        List<AuctionTransaction> transactions =
                new ArrayList<>(
                        byId.values()
                );
        transactions.sort(
                Comparator.comparingLong(
                                AuctionTransaction::createdAt
                        )
                        .thenComparing(
                                transaction ->
                                        transaction.transactionId()
                                                .toString()
                        )
        );

        return new LoadResult(
                transactions,
                problems
        );
    }

    public boolean save(
            AuctionTransaction transaction
    ) {
        if (transaction == null) {
            return false;
        }

        initialize();

        try {
            YamlConfiguration yaml =
                    new YamlConfiguration();

            yaml.set(
                    "transaction-id",
                    transaction.transactionId().toString()
            );
            yaml.set(
                    "type",
                    transaction.type().name()
            );
            yaml.set(
                    "state",
                    transaction.state().name()
            );
            yaml.set(
                    "actor",
                    transaction.actor().toString()
            );
            yaml.set(
                    "actor-name",
                    transaction.actorName()
            );
            yaml.set(
                    "source-slot",
                    transaction.sourceSlot()
            );
            yaml.set(
                    "created-at",
                    transaction.createdAt()
            );

            writeListing(
                    yaml,
                    transaction.listing()
            );

            return atomicSave(
                    yaml,
                    file(transaction.transactionId()),
                    "Auction House transaction "
                            + transaction.transactionId()
            );
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not serialize Auction House transaction "
                            + transaction.transactionId(),
                    exception
            );
            return false;
        }
    }

    public boolean delete(UUID transactionId) {
        if (transactionId == null) {
            return false;
        }

        initialize();

        Path target =
                file(transactionId).toPath();

        try {
            boolean deleted =
                    Files.deleteIfExists(
                            target
                    );

            if (deleted) {
                forceDirectory(
                        target.getParent()
                );
            }

            return true;
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not delete Auction House transaction "
                            + transactionId,
                    exception
            );
            return false;
        }
    }

    private AuctionTransaction readStrict(
            File file
    ) throws IOException, InvalidConfigurationException {
        if (storageFileTooLarge(file)) {
            throw new IOException(
                    "Transaction file exceeds configured safety limit"
            );
        }

        YamlConfiguration yaml =
                new YamlConfiguration();
        yaml.load(file);

        requireTransactionFields(yaml);

        UUID transactionId =
                UUID.fromString(
                        yaml.getString(
                                "transaction-id",
                                ""
                        )
                );
        TransactionType type =
                TransactionType.valueOf(
                        yaml.getString(
                                        "type",
                                        ""
                                )
                                .trim()
                                .toUpperCase(
                                        Locale.ROOT
                                )
                );
        TransactionState state =
                TransactionState.valueOf(
                        yaml.getString(
                                        "state",
                                        "PREPARED"
                                )
                                .trim()
                                .toUpperCase(
                                        Locale.ROOT
                                )
                );
        UUID actor =
                UUID.fromString(
                        yaml.getString(
                                "actor",
                                ""
                        )
                );
        String actorName =
                yaml.getString(
                        "actor-name",
                        "Unknown"
                );
        int sourceSlot =
                yaml.getInt(
                        "source-slot",
                        -1
                );
        long createdAt =
                yaml.getLong(
                        "created-at"
                );

        if (createdAt <= 0L) {
            throw new IllegalStateException(
                    "Invalid transaction created-at"
            );
        }

        if (type == TransactionType.LIST) {
            if (sourceSlot < 0
                    || sourceSlot > 8) {
                throw new IllegalStateException(
                        "Invalid LIST source slot "
                                + sourceSlot
                );
            }
        } else if (sourceSlot != -1) {
            throw new IllegalStateException(
                    "Non-LIST transaction has source slot "
                            + sourceSlot
            );
        }

        AuctionHouseListing listing =
                readListing(yaml);

        if (listing == null) {
            throw new IllegalStateException(
                    "Missing transaction listing"
            );
        }

        return new AuctionTransaction(
                transactionId,
                type,
                state,
                listing,
                actor,
                actorName,
                sourceSlot,
                createdAt
        );
    }

    private static void requireTransactionFields(
            YamlConfiguration yaml
    ) {
        for (String path : List.of(
                "transaction-id",
                "type",
                "state",
                "actor",
                "source-slot",
                "created-at",
                "listing.id",
                "listing.owner",
                "listing.owner-name",
                "listing.price-cents",
                "listing.created-at",
                "listing.item-nbt"
        )) {
            if (!yaml.contains(path)) {
                throw new IllegalStateException(
                        "Missing required transaction field "
                                + path
                );
            }
        }
    }

    private AuctionHouseListing readListing(
            YamlConfiguration yaml
    ) {
        String path = "listing";

        UUID id = UUID.fromString(
                yaml.getString(
                        path + ".id",
                        ""
                )
        );
        UUID owner = UUID.fromString(
                yaml.getString(
                        path + ".owner",
                        ""
                )
        );
        String ownerName =
                yaml.getString(
                        path + ".owner-name",
                        "Unknown"
                );
        long priceCents =
                yaml.getLong(
                        path + ".price-cents",
                        0L
                );
        long createdAt =
                yaml.getLong(
                        path + ".created-at",
                        System.currentTimeMillis()
                );
        String encoded =
                yaml.getString(
                        path + ".item-nbt",
                        ""
                );

        if (encoded.isBlank()
                || encoded.length()
                > maximumEncodedItemCharacters()) {
            return null;
        }

        byte[] decoded =
                Base64.getDecoder()
                        .decode(encoded);

        if (decoded.length > maximumItemBytes) {
            return null;
        }

        ItemStack item =
                ItemStack.deserializeBytes(
                        decoded
                );

        if (item.getType().isAir()
                || item.getAmount() <= 0
                || priceCents <= 0L
                || createdAt <= 0L) {
            return null;
        }

        return new AuctionHouseListing(
                id,
                owner,
                ownerName,
                item,
                priceCents,
                createdAt
        );
    }

    private void writeListing(
            YamlConfiguration yaml,
            AuctionHouseListing listing
    ) {
        String path = "listing";

        yaml.set(
                path + ".id",
                listing.id().toString()
        );
        yaml.set(
                path + ".owner",
                listing.owner().toString()
        );
        yaml.set(
                path + ".owner-name",
                listing.ownerName()
        );
        yaml.set(
                path + ".price-cents",
                listing.priceCents()
        );
        yaml.set(
                path + ".created-at",
                listing.createdAt()
        );
        byte[] serialized =
                listing.serializedItemBytes();

        if (serialized.length > maximumItemBytes) {
            throw new IllegalArgumentException(
                    "Auction transaction item exceeds configured storage limit"
            );
        }

        yaml.set(
                path + ".item-nbt",
                Base64.getEncoder()
                        .encodeToString(
                                serialized
                        )
        );
    }

    private boolean storageFileTooLarge(
            File file
    ) {
        if (file == null || !file.isFile()) {
            return true;
        }

        try {
            return Files.size(
                    file.toPath()
            ) > maximumStorageFileBytes();
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Could not inspect Auction House transaction file size "
                            + file.getName(),
                    exception
            );
            return true;
        }
    }

    private long maximumStorageFileBytes() {
        return maximumEncodedItemCharacters()
                + YAML_OVERHEAD_BYTES;
    }

    private int maximumEncodedItemCharacters() {
        long groups =
                Math.ceilDiv(
                        maximumItemBytes,
                        3
                );
        long encoded =
                groups * 4L;

        return encoded >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) encoded;
    }

    private boolean atomicSave(
            YamlConfiguration yaml,
            File target,
            String label
    ) {
        ensureDirectory(target.getParentFile());

        Path targetPath = target.toPath();
        Path temporary =
                targetPath.resolveSibling(
                        target.getName() + ".tmp"
                );

        try {
            writeAndForce(
                    temporary,
                    yaml.saveToString()
            );

            try {
                Files.move(
                        temporary,
                        targetPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary,
                        targetPath,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            forceDirectory(
                    targetPath.getParent()
            );
            return true;
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not save " + label,
                    exception
            );

            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }

            return false;
        }
    }

    private static void writeAndForce(
            Path path,
            String value
    ) throws IOException {
        byte[] bytes =
                value.getBytes(
                        StandardCharsets.UTF_8
                );

        try (FileChannel channel =
                     FileChannel.open(
                             path,
                             StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING,
                             StandardOpenOption.WRITE
                     )) {
            ByteBuffer buffer =
                    ByteBuffer.wrap(
                            bytes
                    );

            while (buffer.hasRemaining()) {
                int written =
                        channel.write(
                                buffer
                        );

                if (written <= 0) {
                    throw new IOException(
                            "Could not make progress writing "
                                    + path.getFileName()
                    );
                }
            }

            channel.force(true);
        }
    }

    private void forceDirectory(
            Path directory
    ) {
        if (directory == null
                || windows()) {
            return;
        }

        try (FileChannel channel =
                     FileChannel.open(
                             directory,
                             StandardOpenOption.READ
                     )) {
            channel.force(true);
        } catch (
                IOException
                | UnsupportedOperationException
                | SecurityException exception
        ) {
            if (directorySyncWarningLogged) {
                return;
            }

            directorySyncWarningLogged = true;
            core.getLogger().log(
                    Level.WARNING,
                    "Auction House transaction directory sync is unavailable; "
                            + "file contents are forced but rename/delete durability "
                            + "depends on the filesystem",
                    exception
            );
        }
    }

    private static boolean windows() {
        return System.getProperty(
                        "os.name",
                        ""
                )
                .toLowerCase(
                        Locale.ROOT
                )
                .contains("win");
    }

    private File file(UUID transactionId) {
        return new File(
                folder,
                transactionId + EXTENSION
        );
    }

    private void ensureDirectory(File directory) {
        if (directory.exists()) {
            if (!directory.isDirectory()) {
                throw new IllegalStateException(
                        directory + " is not a directory"
                );
            }
            return;
        }

        if (!directory.mkdirs()
                && !directory.isDirectory()) {
            throw new IllegalStateException(
                    "Could not create " + directory
            );
        }
    }
}
