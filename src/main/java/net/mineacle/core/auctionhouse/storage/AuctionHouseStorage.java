package net.mineacle.core.auctionhouse.storage;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

public final class AuctionHouseStorage {

    private static final String EXTENSION =
            ".yml";

    private final Core core;
    private final File legacyFile;
    private final File rootFolder;
    private final File listingsFolder;
    private final File recoveryFolder;
    private final File receiptsFolder;

    private boolean initialized;

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

    public List<AuctionHouseListing>
    loadListings() {
        initialize();

        File[] files =
                listingsFolder.listFiles(
                        (directory, name) ->
                                name.endsWith(
                                        EXTENSION
                                )
                );

        if (files == null
                || files.length == 0) {
            return List.of();
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

        for (File file : ordered) {
            AuctionHouseListing listing =
                    readListingFile(file);

            if (listing != null) {
                result.add(listing);
            }
        }

        return List.copyOf(result);
    }

    public List<PurchaseRecovery>
    loadRecoveries() {
        initialize();

        File[] files =
                recoveryFolder.listFiles(
                        (directory, name) ->
                                name.endsWith(
                                        EXTENSION
                                )
                );

        if (files == null
                || files.length == 0) {
            return List.of();
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
                new ArrayList<>(
                        ordered.size()
                );

        for (File file : ordered) {
            PurchaseRecovery recovery =
                    readRecovery(file);

            if (recovery != null) {
                result.add(recovery);
            }
        }

        return List.copyOf(result);
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
    }

    public boolean listingDeleteFailed(
            UUID listingId
    ) {
        if (listingId == null) {
            return true;
        }

        try {
            Files.deleteIfExists(
                    listingFile(
                            listingId
                    ).toPath()
            );
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

    public PurchaseRecovery beginRecovery(
            AuctionHouseListing listing,
            Player buyer
    ) {
        if (listing == null
                || buyer == null) {
            return null;
        }

        PurchaseRecovery recovery =
                new PurchaseRecovery(
                        UUID.randomUUID(),
                        PurchaseState.PREPARED,
                        listing,
                        buyer.getUniqueId(),
                        buyer.getName(),
                        System.currentTimeMillis()
                );

        return saveRecovery(recovery)
                ? recovery
                : null;
    }

    public boolean saveRecovery(
            PurchaseRecovery recovery
    ) {
        if (recovery == null) {
            return false;
        }

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
    }

    public boolean deleteRecovery(
            UUID transactionId
    ) {
        if (transactionId == null) {
            return false;
        }

        try {
            Files.deleteIfExists(
                    recoveryFile(
                            transactionId
                    ).toPath()
            );
            return true;
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not delete auction recovery "
                            + transactionId,
                    exception
            );
            return false;
        }
    }

    public boolean recordSaleReceipt(
            UUID sellerId,
            String itemName,
            long priceCents
    ) {
        if (sellerId == null
                || priceCents <= 0L) {
            return false;
        }

        File file =
                receiptFile(sellerId);
        YamlConfiguration yaml =
                file.isFile()
                        ? YamlConfiguration
                        .loadConfiguration(file)
                        : new YamlConfiguration();

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
                itemName == null
                        || itemName.isBlank()
                        ? "Item"
                        : itemName
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

    public SaleReceipt loadSaleReceipt(
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

        YamlConfiguration yaml =
                YamlConfiguration
                        .loadConfiguration(file);
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
                yaml.getString(
                        "last-item",
                        "Item"
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
                lastPriceCents
        );
    }

    public boolean clearSaleReceipt(
            UUID sellerId
    ) {
        return sellerId != null
                && deleteReceipt(
                receiptFile(sellerId)
        );
    }

    public File recoveryFolder() {
        return recoveryFolder;
    }

    private void migrateLegacy() {
        if (!legacyFile.isFile()) {
            return;
        }

        YamlConfiguration legacy =
                YamlConfiguration
                        .loadConfiguration(
                                legacyFile
                        );
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
                priceCents
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
        try {
            YamlConfiguration yaml =
                    YamlConfiguration
                            .loadConfiguration(
                                    file
                            );

            AuctionHouseListing listing =
                    readListing(yaml);

            if (listing == null) {
                core.getLogger().warning(
                        "Skipped invalid auction listing file "
                                + file.getName()
                );
            }

            return listing;
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Skipped broken auction listing file "
                            + file.getName(),
                    exception
            );
            return null;
        }
    }

    private PurchaseRecovery readRecovery(
            File file
    ) {
        try {
            YamlConfiguration yaml =
                    YamlConfiguration
                            .loadConfiguration(
                                    file
                            );

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
                            "created-at",
                            System.currentTimeMillis()
                    );
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
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not read auction recovery file "
                            + file.getName(),
                    exception
            );
            return null;
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

        if (encoded.isBlank()) {
            return null;
        }

        ItemStack item =
                ItemStack.deserializeBytes(
                        Base64.getDecoder()
                                .decode(encoded)
                );

        if (invalidListing(
                item,
                priceCents
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
        yaml.set(
                path + ".item-nbt",
                Base64.getEncoder()
                        .encodeToString(
                                listing.item()
                                        .serializeAsBytes()
                        )
        );
    }

    private boolean invalidListing(
            ItemStack item,
            long priceCents
    ) {
        return item == null
                || item.getType().isAir()
                || item.getAmount() <= 0
                || priceCents <= 0L;
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
            Files.writeString(
                    temporary,
                    yaml.saveToString(),
                    StandardCharsets.UTF_8
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

    private boolean deleteReceipt(
            File file
    ) {
        try {
            Files.deleteIfExists(
                    file.toPath()
            );
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

    public record SaleReceipt(
            int count,
            long totalCents,
            String lastItem,
            long lastPriceCents
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

        public PurchaseRecovery withState(
                PurchaseState nextState
        ) {
            return new PurchaseRecovery(
                    transactionId,
                    nextState,
                    listing,
                    buyer,
                    buyerName,
                    createdAt
            );
        }
    }
}
