package net.mineacle.core.auctionhouse.storage;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

public final class AuctionHouseStorage {

    private static final String EXTENSION =
            ".yml";
    private static final int MINIMUM_ITEM_BYTES = 16_384;
    private static final int MAXIMUM_ITEM_BYTES = 1_048_576;
    private static final long YAML_OVERHEAD_BYTES = 65_536L;
    private static final long MAXIMUM_RECEIPT_BYTES = 262_144L;

    private final Core core;
    private final File legacyFile;
    private final File rootFolder;
    private final File listingsFolder;
    private final File recoveryFolder;
    private final File receiptsFolder;

    private boolean initialized;
    private boolean directorySyncWarningLogged;
    private int maximumItemBytes = 262_144;

    public AuctionHouseStorage(
            Core core
    ) {
        this.core = core;
        this.legacyFile = new File(
                core.getDataFolder(),
                "auctionhouse-data.yml"
        );
        this.rootFolder = new File(
                core.getDataFolder(),
                "auctionhouse"
        );
        this.listingsFolder = new File(
                rootFolder,
                "listings"
        );
        this.recoveryFolder = new File(
                rootFolder,
                "recovery"
        );
        this.receiptsFolder = new File(
                rootFolder,
                "receipts"
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

        ensureDirectory(
                core.getDataFolder()
        );
        ensureDirectory(rootFolder);
        ensureDirectory(listingsFolder);
        ensureDirectory(recoveryFolder);
        ensureDirectory(receiptsFolder);
        migrateLegacy();
        initialized = true;
    }

    public ListingLoadResult loadListings() {
        try {
            initialize();
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Auction House listing storage is unavailable",
                    exception
            );
            return new ListingLoadResult(
                    List.of(),
                    List.of(
                            "listings directory is unavailable"
                    )
            );
        }

        File[] files =
                listingsFolder.listFiles(
                        (directory, name) ->
                                name.endsWith(
                                        EXTENSION
                                )
                );

        if (files == null) {
            return new ListingLoadResult(
                    List.of(),
                    List.of(
                            "listings directory could not be read"
                    )
            );
        }

        if (files.length == 0) {
            return new ListingLoadResult(
                    List.of(),
                    List.of()
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

        List<AuctionHouseListing> result =
                new ArrayList<>(
                        ordered.size()
                );
        List<String> problems =
                new ArrayList<>();

        for (File file : ordered) {
            AuctionHouseListing listing =
                    readListingFile(file);

            if (listing == null) {
                problems.add(
                        file.getName()
                                + " • unreadable or invalid listing file"
                );
                continue;
            }

            result.add(listing);
        }

        return new ListingLoadResult(
                result,
                problems
        );
    }

    public LegacyRecoveryLoadResult loadRecoveries() {
        initialize();

        File[] files =
                recoveryFolder.listFiles();

        if (files == null) {
            return new LegacyRecoveryLoadResult(
                    List.of(),
                    List.of(
                            "legacy recovery directory could not be read"
                    )
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

        List<PurchaseRecovery> result =
                new ArrayList<>();
        List<String> problems =
                new ArrayList<>();
        Map<UUID, UUID> listingTransactions =
                new LinkedHashMap<>();

        for (File file : ordered) {
            if (Files.isSymbolicLink(
                    file.toPath()
            )
                    || !file.isFile()) {
                problems.add(
                        file.getName()
                                + " • unexpected non-regular legacy recovery entry"
                );
                continue;
            }

            if (!file.getName()
                    .endsWith(
                            EXTENSION
                    )) {
                problems.add(
                        file.getName()
                                + " • unexpected file in legacy recovery storage"
                );
                continue;
            }

            try {
                PurchaseRecovery recovery =
                        readRecoveryStrict(
                                file
                        );
                String expectedName =
                        recovery.transactionId()
                                + EXTENSION;

                if (!file.getName()
                        .equals(
                                expectedName
                        )) {
                    problems.add(
                            file.getName()
                                    + " • filename does not match legacy transaction id "
                                    + recovery.transactionId()
                    );
                    continue;
                }

                UUID previousTransaction =
                        listingTransactions.putIfAbsent(
                                recovery.listing()
                                        .id(),
                                recovery.transactionId()
                        );

                if (previousTransaction != null
                        && !previousTransaction.equals(
                        recovery.transactionId()
                )) {
                    problems.add(
                            file.getName()
                                    + " • listing "
                                    + recovery.listing()
                                    .id()
                                    + " is referenced by multiple legacy recoveries"
                    );
                }

                result.add(recovery);
            } catch (
                    IOException
                    | InvalidConfigurationException
                    | RuntimeException exception
            ) {
                core.getLogger().log(
                        Level.SEVERE,
                        "Could not safely read auction legacy recovery "
                                + file.getName(),
                        exception
                );
                problems.add(
                        file.getName()
                                + " • unreadable or invalid legacy recovery"
                );
            }
        }

        result.sort(
                Comparator.comparingLong(
                        PurchaseRecovery::createdAt
                )
        );

        return new LegacyRecoveryLoadResult(
                result,
                problems
        );
    }

    public boolean listingExists(
            UUID listingId
    ) {
        return listingId != null
                && listingFile(
                listingId
        ).isFile();
    }

    public boolean listingSaveFailed(
            AuctionHouseListing listing
    ) {
        if (listing == null) {
            return true;
        }

        try {
            YamlConfiguration yaml =
                    new YamlConfiguration();

            writeListing(
                    yaml,
                    listing
            );

            return !atomicSave(
                    yaml,
                    listingFile(
                            listing.id()
                    ),
                    "auction listing "
                            + listing.id()
            );
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not serialize auction listing "
                            + listing.id(),
                    exception
            );
            return true;
        }
    }

    public boolean listingDeleteFailed(
            UUID listingId
    ) {
        if (listingId == null) {
            return true;
        }

        Path target =
                listingFile(
                        listingId
                ).toPath();

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

            return false;
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not delete auction listing "
                            + listingId,
                    exception
            );
            return true;
        }
    }

    public boolean saveRecovery(
            PurchaseRecovery recovery
    ) {
        if (recovery == null) {
            return false;
        }

        try {
            YamlConfiguration yaml =
                    new YamlConfiguration();

            yaml.set(
                    "transaction-id",
                    recovery.transactionId()
                            .toString()
            );
            yaml.set(
                    "state",
                    recovery.state().name()
            );
            yaml.set(
                    "buyer",
                    recovery.buyer()
                            .toString()
            );
            yaml.set(
                    "buyer-name",
                    recovery.buyerName()
            );
            yaml.set(
                    "created-at",
                    recovery.createdAt()
            );

            writeListing(
                    yaml,
                    recovery.listing()
            );

            return atomicSave(
                    yaml,
                    recoveryFile(
                            recovery.transactionId()
                    ),
                    "auction recovery "
                            + recovery.transactionId()
            );
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not serialize auction recovery "
                            + recovery.transactionId(),
                    exception
            );
            return false;
        }
    }

    public boolean recoveryDeleteFailed(
            UUID transactionId
    ) {
        if (transactionId == null) {
            return true;
        }

        Path target =
                recoveryFile(
                        transactionId
                ).toPath();

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

            return false;
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not delete auction recovery "
                            + transactionId,
                    exception
            );
            return true;
        }
    }

    public synchronized boolean recordSaleReceipt(
            UUID transactionId,
            UUID sellerId,
            String itemName,
            long priceCents
    ) {
        if (transactionId == null
                || sellerId == null
                || priceCents <= 0L) {
            return false;
        }

        File file =
                receiptFile(sellerId);

        if (file.isFile()
                && receiptFileTooLarge(file)) {
            core.getLogger().severe(
                    "Refused oversized Auction House sale receipt "
                            + file.getName()
            );
            return false;
        }

        YamlConfiguration yaml =
                new YamlConfiguration();

        if (file.isFile()) {
            try {
                yaml.load(file);
            } catch (
                    IOException
                    | InvalidConfigurationException
                    | RuntimeException exception
            ) {
                core.getLogger().log(
                        Level.SEVERE,
                        "Could not safely read Auction House sale receipt "
                                + file.getName(),
                        exception
                );
                return false;
            }
        }

        List<String> transactionIds =
                new ArrayList<>(
                        yaml.getStringList(
                                "transaction-ids"
                        )
                );
        String rawTransactionId =
                transactionId.toString();

        if (transactionIds.contains(
                rawTransactionId
        )) {
            return true;
        }

        int previousCount =
                Math.max(
                        0,
                        yaml.getInt(
                                "count",
                                0
                        )
                );
        long previousTotal =
                Math.max(
                        0L,
                        yaml.getLong(
                                "total-cents",
                                0L
                        )
                );

        int updatedCount =
                previousCount
                        == Integer.MAX_VALUE
                        ? Integer.MAX_VALUE
                        : previousCount + 1;
        long updatedTotal;

        try {
            updatedTotal =
                    Math.addExact(
                            previousTotal,
                            priceCents
                    );
        } catch (
                ArithmeticException exception
        ) {
            updatedTotal =
                    Long.MAX_VALUE;
        }

        transactionIds.add(
                rawTransactionId
        );

        yaml.set(
                "transaction-ids",
                List.copyOf(
                        transactionIds
                )
        );
        yaml.set(
                "count",
                updatedCount
        );
        yaml.set(
                "total-cents",
                updatedTotal
        );
        yaml.set(
                "last-item",
                boundedItemName(
                        itemName
                )
        );
        yaml.set(
                "last-price-cents",
                priceCents
        );

        return atomicSave(
                yaml,
                file,
                "auction sale receipt for "
                        + sellerId
        );
    }

    public synchronized SaleReceipt loadSaleReceipt(
            UUID sellerId
    ) {
        if (sellerId == null) {
            return null;
        }

        File file =
                receiptFile(sellerId);

        if (!file.isFile()) {
            return null;
        }

        if (receiptFileTooLarge(file)) {
            core.getLogger().severe(
                    "Skipped oversized Auction House sale receipt "
                            + file.getName()
            );
            return null;
        }

        YamlConfiguration yaml =
                new YamlConfiguration();

        try {
            yaml.load(file);
        } catch (
                IOException
                | InvalidConfigurationException
                | RuntimeException exception
        ) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not safely read Auction House sale receipt "
                            + file.getName(),
                    exception
            );
            return null;
        }

        int count =
                Math.max(
                        0,
                        yaml.getInt(
                                "count",
                                0
                        )
                );
        long totalCents =
                Math.max(
                        0L,
                        yaml.getLong(
                                "total-cents",
                                0L
                        )
                );
        String lastItem =
                boundedItemName(
                        yaml.getString(
                                "last-item",
                                "Item"
                        )
                );
        long lastPriceCents =
                Math.max(
                        0L,
                        yaml.getLong(
                                "last-price-cents",
                                0L
                        )
                );

        if (count <= 0
                || totalCents <= 0L) {
            deleteReceipt(file);
            return null;
        }

        return new SaleReceipt(
                count,
                totalCents,
                lastItem,
                lastPriceCents,
                List.copyOf(
                        yaml.getStringList(
                                "transaction-ids"
                        )
                )
        );
    }

    public synchronized boolean clearSaleReceiptIfUnchanged(
            UUID sellerId,
            List<String> expectedTransactionIds
    ) {
        if (sellerId == null
                || expectedTransactionIds == null) {
            return false;
        }

        File file =
                receiptFile(
                        sellerId
                );

        if (!file.exists()) {
            return true;
        }

        if (!file.isFile()
                || receiptFileTooLarge(
                file
        )) {
            return false;
        }

        YamlConfiguration yaml =
                new YamlConfiguration();

        try {
            yaml.load(
                    file
            );
        } catch (
                IOException
                | InvalidConfigurationException
                | RuntimeException exception
        ) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not safely verify Auction House sale receipt "
                            + file.getName(),
                    exception
            );
            return false;
        }

        List<String> currentTransactionIds =
                yaml.getStringList(
                        "transaction-ids"
                );

        if (!currentTransactionIds.equals(
                expectedTransactionIds
        )) {
            /*
             * A newer sale was recorded after this notice snapshot was read.
             * Leave the aggregate receipt intact. The next notice can include
             * the new sale; at worst an older aggregate notice repeats.
             */
            return true;
        }

        return deleteReceipt(
                file
        );
    }

    private void migrateLegacy() {
        if (!legacyFile.isFile()) {
            return;
        }

        YamlConfiguration legacy =
                new YamlConfiguration();

        try {
            legacy.load(
                    legacyFile
            );
        } catch (
                IOException
                | InvalidConfigurationException
                | RuntimeException exception
        ) {
            throw new IllegalStateException(
                    "Could not safely read legacy auctionhouse-data.yml",
                    exception
            );
        }
        ConfigurationSection section =
                legacy
                        .getConfigurationSection(
                                "listings"
                        );

        if (section == null) {
            archiveLegacy();
            return;
        }

        boolean complete = true;
        int migrated = 0;

        for (String rawId
                : section.getKeys(false)) {
            try {
                UUID id =
                        UUID.fromString(rawId);

                if (listingExists(id)) {
                    continue;
                }

                AuctionHouseListing listing =
                        readLegacyListing(
                                section,
                                rawId
                        );

                if (listingSaveFailed(
                        listing
                )) {
                    complete = false;
                    continue;
                }

                migrated++;
            } catch (RuntimeException exception) {
                complete = false;
                core.getLogger().log(
                        Level.WARNING,
                        "Could not migrate legacy auction listing "
                                + rawId,
                        exception
                );
            }
        }

        if (!complete) {
            core.getLogger().warning(
                    "Auction House legacy migration is incomplete; "
                            + "auctionhouse-data.yml was retained"
            );
            return;
        }

        if (migrated > 0) {
            core.getLogger().info(
                    "Migrated "
                            + migrated
                            + " Auction House listing(s) "
                            + "to per-listing storage"
            );
        }

        archiveLegacy();
    }

    private AuctionHouseListing
    readLegacyListing(
            ConfigurationSection section,
            String rawId
    ) {
        UUID id =
                UUID.fromString(rawId);
        UUID owner =
                UUID.fromString(
                        section.getString(
                                rawId + ".owner",
                                ""
                        )
                );
        String ownerName =
                section.getString(
                        rawId + ".owner-name",
                        "Unknown"
                );
        long priceCents =
                section.getLong(
                        rawId + ".price-cents",
                        0L
                );
        long createdAt =
                section.getLong(
                        rawId + ".created-at",
                        System.currentTimeMillis()
                );
        ItemStack item =
                section.getItemStack(
                        rawId + ".item"
                );

        if (invalidListing(
                item,
                priceCents,
                createdAt
        )) {
            core.getLogger().warning(
                    "Skipped invalid legacy auction listing "
                            + rawId
            );
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

    private AuctionHouseListing
    readListingFile(
            File file
    ) {
        if (Files.isSymbolicLink(
                file.toPath()
        )
                || !file.isFile()) {
            core.getLogger().severe(
                    "Skipped non-regular auction listing storage entry "
                            + file.getName()
            );
            return null;
        }

        if (storageFileTooLarge(file)) {
            core.getLogger().severe(
                    "Skipped oversized auction listing file "
                            + file.getName()
            );
            return null;
        }

        try {
            YamlConfiguration yaml =
                    new YamlConfiguration();
            yaml.load(file);
            requireListingFields(yaml);

            AuctionHouseListing listing =
                    readListing(yaml);

            if (listing == null) {
                core.getLogger().severe(
                        "Skipped invalid auction listing file "
                                + file.getName()
                );
                return null;
            }

            String expectedName =
                    listing.id()
                            + EXTENSION;

            if (!file.getName()
                    .equals(
                            expectedName
                    )) {
                core.getLogger().severe(
                        "Skipped auction listing file "
                                + file.getName()
                                + " because its filename does not match listing id "
                                + listing.id()
                );
                return null;
            }

            return listing;
        } catch (
                IOException
                | InvalidConfigurationException
                | RuntimeException exception
        ) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Skipped unreadable or invalid auction listing file "
                            + file.getName(),
                    exception
            );
            return null;
        }
    }

    private static void requireListingFields(
            YamlConfiguration yaml
    ) {
        for (String path : List.of(
                "listing.id",
                "listing.owner",
                "listing.price-cents",
                "listing.created-at",
                "listing.item-nbt"
        )) {
            if (!yaml.contains(path)) {
                throw new IllegalStateException(
                        "Missing required listing field "
                                + path
                );
            }
        }
    }

    private PurchaseRecovery readRecoveryStrict(
            File file
    ) throws IOException, InvalidConfigurationException {
        if (storageFileTooLarge(file)) {
            throw new IOException(
                    "Legacy recovery exceeds configured safety limit"
            );
        }

        YamlConfiguration yaml =
                new YamlConfiguration();
        yaml.load(file);

        requireRecoveryFields(yaml);

        UUID transactionId =
                UUID.fromString(
                        yaml.getString(
                                "transaction-id",
                                ""
                        )
                );
        PurchaseState state =
                PurchaseState.valueOf(
                        yaml.getString(
                                        "state",
                                        "PREPARED"
                                )
                                .trim()
                                .toUpperCase(
                                        Locale.ROOT
                                )
                );
        UUID buyer =
                UUID.fromString(
                        yaml.getString(
                                "buyer",
                                ""
                        )
                );
        String buyerName =
                yaml.getString(
                        "buyer-name",
                        "Unknown"
                );
        long createdAt =
                yaml.getLong(
                        "created-at"
                );

        if (createdAt <= 0L) {
            throw new IllegalStateException(
                    "Invalid legacy recovery created-at"
            );
        }

        AuctionHouseListing listing =
                readListing(yaml);

        if (listing == null) {
            throw new IllegalStateException(
                    "Missing recovery listing"
            );
        }

        return new PurchaseRecovery(
                transactionId,
                state,
                listing,
                buyer,
                buyerName,
                createdAt
        );
    }

    private static void requireRecoveryFields(
            YamlConfiguration yaml
    ) {
        for (String path : List.of(
                "transaction-id",
                "state",
                "buyer",
                "buyer-name",
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
                        "Missing required recovery field "
                                + path
                );
            }
        }
    }

    private AuctionHouseListing readListing(
            YamlConfiguration yaml
    ) {
        String path = "listing";
        UUID id =
                UUID.fromString(
                        yaml.getString(
                                path + ".id",
                                ""
                        )
                );
        UUID owner =
                UUID.fromString(
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

        if (invalidListing(
                item,
                priceCents,
                createdAt
        )) {
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
                    "Auction listing item exceeds configured storage limit"
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
                    "Could not inspect Auction House storage file size "
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

    private boolean invalidListing(
            ItemStack item,
            long priceCents,
            long createdAt
    ) {
        return item == null
                || item.getType().isAir()
                || item.getAmount() <= 0
                || priceCents <= 0L
                || createdAt <= 0L;
    }

    private boolean atomicSave(
            YamlConfiguration yaml,
            File target,
            String label
    ) {
        ensureDirectory(
                target.getParentFile()
        );

        Path targetPath =
                target.toPath();
        Path temporary =
                targetPath.resolveSibling(
                        target.getName()
                                + ".tmp"
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
                        StandardCopyOption
                                .REPLACE_EXISTING,
                        StandardCopyOption
                                .ATOMIC_MOVE
                );
            } catch (
                    AtomicMoveNotSupportedException ignored
            ) {
                Files.move(
                        temporary,
                        targetPath,
                        StandardCopyOption
                                .REPLACE_EXISTING
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
                Files.deleteIfExists(
                        temporary
                );
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
                    "Auction House directory sync is unavailable; file contents "
                            + "are forced but rename/delete durability depends on "
                            + "the filesystem",
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

    private void archiveLegacy() {
        if (!legacyFile.exists()) {
            return;
        }

        String suffix =
                ".migrated-"
                        + Instant.now()
                        .toEpochMilli();
        Path target =
                legacyFile.toPath()
                        .resolveSibling(
                                legacyFile.getName()
                                        + suffix
                        );

        try {
            Files.move(
                    legacyFile.toPath(),
                    target
            );
            forceDirectory(
                    target.getParent()
            );
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Migrated Auction House data, but could not "
                            + "archive auctionhouse-data.yml",
                    exception
            );
        }
    }

    private void ensureDirectory(
            File directory
    ) {
        if (directory.exists()) {
            if (!directory.isDirectory()) {
                throw new IllegalStateException(
                        directory
                                + " is not a directory"
                );
            }
            return;
        }

        if (!directory.mkdirs()
                && !directory.isDirectory()) {
            throw new IllegalStateException(
                    "Could not create "
                            + directory
            );
        }
    }

    private static String boundedItemName(
            String input
    ) {
        String value =
                input == null
                        || input.isBlank()
                        ? "Item"
                        : input
                        .replace(
                                '\n',
                                ' '
                        )
                        .replace(
                                '\r',
                                ' '
                        )
                        .trim();

        if (value.length()
                > 128) {
            return value.substring(
                    0,
                    128
            );
        }

        return value;
    }

    private boolean receiptFileTooLarge(
            File file
    ) {
        try {
            return Files.size(
                    file.toPath()
            ) > MAXIMUM_RECEIPT_BYTES;
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Could not inspect Auction House sale receipt size "
                            + file.getName(),
                    exception
            );
            return true;
        }
    }

    private boolean deleteReceipt(
            File file
    ) {
        Path target =
                file.toPath();

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
                    Level.WARNING,
                    "Could not delete Auction House sale receipt "
                            + file.getName(),
                    exception
            );
            return false;
        }
    }

    private File receiptFile(
            UUID sellerId
    ) {
        return new File(
                receiptsFolder,
                sellerId + EXTENSION
        );
    }

    private File listingFile(
            UUID listingId
    ) {
        return new File(
                listingsFolder,
                listingId
                        + EXTENSION
        );
    }

    private File recoveryFile(
            UUID transactionId
    ) {
        return new File(
                recoveryFolder,
                transactionId
                        + EXTENSION
        );
    }

    public record ListingLoadResult(
            List<AuctionHouseListing> listings,
            List<String> problems
    ) {
        public ListingLoadResult {
            listings =
                    listings == null
                            ? List.of()
                            : List.copyOf(
                            listings
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

    public record LegacyRecoveryLoadResult(
            List<PurchaseRecovery> recoveries,
            List<String> problems
    ) {
        public LegacyRecoveryLoadResult {
            recoveries =
                    recoveries == null
                            ? List.of()
                            : List.copyOf(
                            recoveries
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

    public record SaleReceipt(
            int count,
            long totalCents,
            String lastItem,
            long lastPriceCents,
            List<String> transactionIds
    ) {
        public SaleReceipt {
            count = Math.max(
                    0,
                    count
            );
            totalCents =
                    Math.max(
                            0L,
                            totalCents
                    );
            lastItem =
                    lastItem == null
                            || lastItem.isBlank()
                            ? "Item"
                            : lastItem;
            lastPriceCents =
                    Math.max(
                            0L,
                            lastPriceCents
                    );
            transactionIds =
                    transactionIds == null
                            ? List.of()
                            : List.copyOf(
                            transactionIds
                    );
        }
    }

    public enum PurchaseState {
        PREPARED,
        PAYMENT_STARTED,
        PAID,
        DELIVERED
    }

    public record PurchaseRecovery(
            UUID transactionId,
            PurchaseState state,
            AuctionHouseListing listing,
            UUID buyer,
            String buyerName,
            long createdAt
    ) {
        public PurchaseRecovery {
            if (transactionId == null
                    || state == null
                    || listing == null
                    || buyer == null) {
                throw new IllegalArgumentException(
                        "Auction recovery fields cannot be null"
                );
            }

            buyerName =
                    buyerName == null
                            || buyerName.isBlank()
                            ? "Unknown"
                            : buyerName;
        }

    }
}
