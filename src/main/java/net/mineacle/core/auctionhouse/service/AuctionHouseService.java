package net.mineacle.core.auctionhouse.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.auctionhouse.storage.AuctionHouseDatabaseMirror;
import net.mineacle.core.auctionhouse.storage.AuctionHouseStorage;
import net.mineacle.core.auctionhouse.storage.AuctionHouseStorage.PurchaseRecovery;
import net.mineacle.core.auctionhouse.storage.AuctionHouseStorage.PurchaseState;
import net.mineacle.core.auctionhouse.storage.AuctionHouseStorage.SaleReceipt;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.RankDisplayResolver;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.SellModule;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class AuctionHouseService {

    public static final int PAGE_SIZE = 45;

    private static final long DEFAULT_WORTH_CACHE_MILLIS =
            3_000L;
    private static final long LISTING_LIFETIME_MILLIS =
            TimeUnit.HOURS.toMillis(24L);

    public enum SortMode {
        LOWEST_PRICE("Lowest Price"),
        LOWEST_UNIT_PRICE("Lowest Each"),
        HIGHEST_PRICE("Highest Price"),
        RECENTLY_LISTED("Recently Listed");

        private final String label;

        SortMode(
                String label
        ) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public SortMode next() {
            SortMode[] modes = values();

            return modes[
                    (ordinal() + 1)
                            % modes.length
                    ];
        }

        public SortMode previous() {
            SortMode[] modes = values();

            return modes[
                    (ordinal()
                            + modes.length
                            - 1)
                            % modes.length
                    ];
        }
    }

    public enum FilterMode {
        ALL("All"),
        BLOCKS("Blocks"),
        TOOLS("Tools"),
        FOOD("Food"),
        COMBAT("Combat"),
        POTIONS("Potions"),
        BOOKS("Books"),
        INGREDIENTS("Materials"),
        UTILITIES("Utilities");

        private final String label;

        FilterMode(
                String label
        ) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public FilterMode next() {
            FilterMode[] modes = values();

            return modes[
                    (ordinal() + 1)
                            % modes.length
                    ];
        }

        public FilterMode previous() {
            FilterMode[] modes = values();

            return modes[
                    (ordinal()
                            + modes.length
                            - 1)
                            % modes.length
                    ];
        }

        public boolean matches(
                Material material
        ) {
            return this == ALL
                    || this == classify(
                    material
            );
        }

        private static FilterMode classify(
                Material material
        ) {
            if (material == null
                    || material.isAir()) {
                return UTILITIES;
            }

            String name =
                    material.name();

            if (name.contains("POTION")
                    || name.equals(
                    "DRAGON_BREATH"
            )) {
                return POTIONS;
            }

            if (name.contains("BOOK")
                    || name.equals("PAPER")
                    || name.equals("MAP")
                    || name.endsWith("_MAP")) {
                return BOOKS;
            }

            if (material.isEdible()) {
                return FOOD;
            }

            if (isCombat(name)) {
                return COMBAT;
            }

            if (isTool(name)) {
                return TOOLS;
            }

            if (isIngredient(
                    material,
                    name
            )) {
                return INGREDIENTS;
            }

            if (material.isBlock()) {
                return BLOCKS;
            }

            return UTILITIES;
        }

        private static boolean isTool(
                String name
        ) {
            return name.endsWith(
                    "_PICKAXE"
            )
                    || name.endsWith(
                    "_AXE"
            )
                    || name.endsWith(
                    "_SHOVEL"
            )
                    || name.endsWith(
                    "_HOE"
            )
                    || name.equals("SHEARS")
                    || name.equals(
                    "FISHING_ROD"
            )
                    || name.equals("BRUSH")
                    || name.equals(
                    "FLINT_AND_STEEL"
            );
        }

        private static boolean isCombat(
                String name
        ) {
            return name.endsWith(
                    "_SWORD"
            )
                    || name.endsWith(
                    "_HELMET"
            )
                    || name.endsWith(
                    "_CHESTPLATE"
            )
                    || name.endsWith(
                    "_LEGGINGS"
            )
                    || name.endsWith(
                    "_BOOTS"
            )
                    || name.equals("BOW")
                    || name.equals("CROSSBOW")
                    || name.equals("SHIELD")
                    || name.equals("TRIDENT")
                    || name.equals("MACE")
                    || name.equals("ARROW")
                    || name.endsWith(
                    "_ARROW"
            );
        }

        private static boolean isIngredient(
                Material material,
                String name
        ) {
            return Tag.ITEMS_COALS
                    .isTagged(material)
                    || name.contains("INGOT")
                    || name.contains("NUGGET")
                    || name.contains("DUST")
                    || name.contains("GEM")
                    || name.contains("SHARD")
                    || name.contains("SCRAP")
                    || name.startsWith("RAW_");
        }
    }

    public enum CreateResult {
        SUCCESS,
        DISABLED,
        NO_PERMISSION,
        NO_ITEM,
        ITEM_CHANGED,
        INVALID_AMOUNT,
        NO_SLOT,
        INVALID_PRICE,
        BELOW_MINIMUM,
        ABOVE_MAXIMUM,
        BLOCKED_ITEM,
        FILLED_CONTAINER,
        OVERSIZED_ITEM,
        STORAGE_ERROR
    }

    public enum CancelResult {
        SUCCESS,
        NOT_FOUND,
        NOT_OWNER,
        INVENTORY_FULL,
        STORAGE_ERROR
    }

    public enum BuyResult {
        SUCCESS,
        NOT_FOUND,
        EXPIRED,
        BUSY,
        OWN_ITEM,
        BELOW_SERVER_WORTH,
        NOT_ENOUGH_MONEY,
        INVENTORY_FULL,
        ECONOMY_MISSING,
        PAYMENT_FAILED,
        STORAGE_ERROR
    }

    public record CreateOutcome(
            CreateResult result,
            AuctionHouseListing listing
    ) {
    }

    public record BuyOutcome(
            BuyResult result,
            AuctionHouseListing listing
    ) {
    }

    private final Core core;
    private final File configFile;
    private final AuctionHouseStorage storage;

    private AuctionHouseDatabaseMirror databaseMirror;
    private BukkitTask databaseMirrorTask;

    private final Map<UUID, AuctionHouseListing>
            listings =
            new LinkedHashMap<>();
    private final Map<UUID, LinkedHashSet<UUID>>
            ownerIndex =
            new HashMap<>();
    private final Map<UUID, SearchDocument>
            searchIndex =
            new HashMap<>();
    private final Map<UUID, WorthSnapshot>
            worthCache =
            new HashMap<>();
    private final Set<UUID> processingListings =
            new HashSet<>();
    private final Map<UUID, PurchaseRecovery>
            quarantinedRecoveries =
            new LinkedHashMap<>();

    private YamlConfiguration config =
            new YamlConfiguration();
    private Set<Material> blockedMaterials =
            Set.of();
    private Set<String> legacyElevatedGroups =
            Set.of();
    private Map<String, List<String>>
            searchAliases =
            Map.of();

    private SellService sellService;

    public AuctionHouseService(
            Core core
    ) {
        this.core = core;
        this.configFile = new File(
                core.getDataFolder(),
                "auctionhouse.yml"
        );
        this.storage =
                new AuctionHouseStorage(core);
    }

    public synchronized void load() {
        stopDatabaseMirror();
        ensureConfigFile();

        config =
                YamlConfiguration
                        .loadConfiguration(
                                configFile
                        );

        sellService =
                SellModule.sellService();
        blockedMaterials =
                loadBlockedMaterials();
        legacyElevatedGroups =
                loadLegacyElevatedGroups();
        searchAliases =
                loadSearchAliases();

        listings.clear();
        ownerIndex.clear();
        searchIndex.clear();
        worthCache.clear();
        processingListings.clear();
        quarantinedRecoveries.clear();

        storage.initialize();
        recoverInterruptedPurchases();

        for (AuctionHouseListing stored
                : storage.loadListings()) {
            if (isQuarantined(
                    stored.id()
            )) {
                core.getLogger().severe(
                        "[AuctionHouse] Listing "
                                + stored.id()
                                + " is hidden because a purchase "
                                + "recovery is unresolved"
                );
                continue;
            }

            addInMemory(
                    normalizedLoadedListing(
                            stored
                    )
            );
        }

        core.getLogger().info(
                "Auction House loaded "
                        + listings.size()
                        + " listing(s)"
                        + (
                        quarantinedRecoveries
                                .isEmpty()
                                ? ""
                                : " with "
                                + quarantinedRecoveries
                                .size()
                                + " quarantined purchase(s)"
                )
        );

        startDatabaseMirror();
    }

    public synchronized void shutdown() {
        stopDatabaseMirror();
        listings.clear();
        ownerIndex.clear();
        searchIndex.clear();
        worthCache.clear();
        processingListings.clear();
        quarantinedRecoveries.clear();
    }

    public boolean enabled() {
        return config.getBoolean(
                "enabled",
                true
        );
    }

    public int pageSize() {
        return PAGE_SIZE;
    }

    public SortMode defaultSort() {
        String configured =
                config.getString(
                        "browse.default-sort",
                        "LOWEST_PRICE"
                );

        if (configured.isBlank()) {
            return SortMode.LOWEST_PRICE;
        }

        try {
            return SortMode.valueOf(
                    configured
                            .trim()
                            .toUpperCase(
                                    Locale.ROOT
                            )
            );
        } catch (
                IllegalArgumentException ignored
        ) {
            return SortMode.LOWEST_PRICE;
        }
    }

    public boolean quickBuyEnabled() {
        return config.getBoolean(
                "browse.shift-click-quick-buy",
                true
        );
    }

    public long minPriceCents() {
        return Math.max(
                1L,
                configuredPriceCents(
                        "listing.min-price",
                        "1"
                )
        );
    }

    public long maxPriceCents() {
        return Math.max(
                minPriceCents(),
                configuredPriceCents(
                        "listing.max-price",
                        "1B"
                )
        );
    }

    public int maxSearchLength() {
        return Math.clamp(
                config.getInt(
                        "search.max-length",
                        48
                ),
                8,
                128
        );
    }

    public long promptTimeoutTicks() {
        long seconds =
                Math.clamp(
                        config.getLong(
                                "search.prompt-timeout-seconds",
                                60L
                        ),
                        5L,
                        300L
                );

        return seconds * 20L;
    }

    public int listingLimit(
            Player player
    ) {
        if (player == null) {
            return 0;
        }

        int defaultLimit =
                Math.clamp(
                        config.getInt(
                                "listing.default-slots",
                                18
                        ),
                        1,
                        999
                );

        if (player.hasPermission(
                "mineacleauctionhouse.admin"
        )) {
            return Math.max(
                    defaultLimit,
                    Math.clamp(
                            config.getInt(
                                    "listing.admin-slots",
                                    999
                            ),
                            1,
                            999
                    )
            );
        }

        if (hasElevatedListingTier(
                player
        )) {
            return Math.max(
                    defaultLimit,
                    Math.clamp(
                            config.getInt(
                                    "listing.elevated-slots",
                                    config.getInt(
                                            "listing.plus-slots",
                                            27
                                    )
                            ),
                            1,
                            999
                    )
            );
        }

        return defaultLimit;
    }

    public boolean canList(
            Player player
    ) {
        if (player == null
                || !player.hasPermission(
                "mineacleauctionhouse.use"
        )) {
            return false;
        }

        if (player.hasPermission(
                "mineacleauctionhouse.admin"
        )
                || player.hasPermission(
                "mineacleauctionhouse.sell"
        )) {
            return true;
        }

        if (config.getBoolean(
                "listing.allow-default",
                true
        )) {
            return true;
        }

        return hasElevatedListingTier(
                player
        );
    }

    public boolean hasElevatedListingTier(
            Player player
    ) {
        if (player == null) {
            return false;
        }

        if (player.hasPermission(
                "mineacleauctionhouse.admin"
        )) {
            return true;
        }

        String permission =
                config.getString(
                        "listing.elevated-permission",
                        config.getString(
                                "listing.plus-permission",
                                "mineacle.plus"
                        )
                );

        if (!permission.isBlank()
                && player.hasPermission(
                permission.trim()
        )) {
            return true;
        }

        /*
         * Temporary compatibility bridge while LuckPerms groups still only
         * have weights/prefixes. The actual group names live in configuration,
         * not feature code, and this path should disappear after the dedicated
         * capability-permission migration.
         */
        if (!config.getBoolean(
                "listing.legacy-primary-group-fallback",
                true
        )
                || legacyElevatedGroups
                .isEmpty()) {
            return false;
        }

        String primary =
                normalizeGroup(
                        RankDisplayResolver
                                .resolve(player)
                                .key()
                );

        return !primary.isBlank()
                && legacyElevatedGroups
                .contains(primary);
    }

    public synchronized int activeListingCount(
            UUID owner
    ) {
        if (owner == null) {
            return 0;
        }

        Set<UUID> ids =
                ownerIndex.get(owner);

        if (ids == null
                || ids.isEmpty()) {
            return 0;
        }

        int count = 0;

        for (UUID id : ids) {
            AuctionHouseListing listing =
                    listings.get(id);

            if (listing != null
                    && !isExpired(listing)) {
                count++;
            }
        }

        return count;
    }

    public synchronized int expiredListingCount(
            UUID owner
    ) {
        if (owner == null) {
            return 0;
        }

        Set<UUID> ids =
                ownerIndex.get(owner);

        if (ids == null
                || ids.isEmpty()) {
            return 0;
        }

        int count = 0;

        for (UUID id : ids) {
            AuctionHouseListing listing =
                    listings.get(id);

            if (listing != null
                    && isExpired(listing)) {
                count++;
            }
        }

        return count;
    }

    public synchronized int occupiedListingCount(
            UUID owner
    ) {
        if (owner == null) {
            return 0;
        }

        Set<UUID> ids =
                ownerIndex.get(owner);

        return ids == null
                ? 0
                : ids.size();
    }

    public boolean listingSlotsFull(
            Player player
    ) {
        return player == null
                || occupiedListingCount(
                player.getUniqueId()
        ) >= listingLimit(player);
    }

    public synchronized List<AuctionHouseListing>
    search(
            String query,
            SortMode sortMode,
            FilterMode filterMode
    ) {
        List<String> tokens =
                searchTokens(query);
        SortMode effectiveSort =
                sortMode == null
                        ? defaultSort()
                        : sortMode;
        FilterMode effectiveFilter =
                filterMode == null
                        ? FilterMode.ALL
                        : filterMode;

        List<AuctionHouseListing> result =
                new ArrayList<>();

        for (AuctionHouseListing listing
                : listings.values()) {
            if (isExpired(listing)) {
                continue;
            }

            SearchDocument document =
                    searchIndex.get(
                            listing.id()
                    );

            if (document == null
                    || !effectiveFilter
                    .matches(
                            document.material()
                    )
                    || !document.matches(
                            tokens
                    )) {
                continue;
            }

            result.add(listing);
        }

        result.sort(
                comparator(
                        effectiveSort
                )
        );

        return List.copyOf(result);
    }

    public synchronized List<AuctionHouseListing>
    ownerListings(
            UUID owner
    ) {
        if (owner == null) {
            return List.of();
        }

        Set<UUID> ids =
                ownerIndex.get(owner);

        if (ids == null
                || ids.isEmpty()) {
            return List.of();
        }

        List<AuctionHouseListing> result =
                new ArrayList<>(
                        ids.size()
                );

        for (UUID id : ids) {
            AuctionHouseListing listing =
                    listings.get(id);

            if (listing != null) {
                result.add(listing);
            }
        }

        result.sort(
                Comparator
                        .comparing(
                                this::isExpired
                        )
                        .thenComparing(
                                Comparator
                                        .comparingLong(
                                                AuctionHouseListing
                                                        ::createdAt
                                        )
                                        .reversed()
                        )
        );

        return List.copyOf(result);
    }

    public synchronized AuctionHouseListing listing(
            UUID id
    ) {
        return id == null
                ? null
                : listings.get(id);
    }

    public synchronized CreateOutcome createListing(
            Player player,
            long priceCents
    ) {
        ItemStack held =
                cleanedHeldItem(
                        player
                );
        int amount =
                held == null
                        ? 0
                        : held.getAmount();

        return createListing(
                player,
                priceCents,
                amount,
                null
        );
    }

    public synchronized CreateOutcome createListing(
            Player player,
            long priceCents,
            int amount,
            ItemStack expectedItem
    ) {
        if (!enabled()) {
            return outcome(
                    CreateResult.DISABLED
            );
        }

        if (!canList(player)) {
            return outcome(
                    CreateResult.NO_PERMISSION
            );
        }

        if (priceCents <= 0L) {
            return outcome(
                    CreateResult.INVALID_PRICE
            );
        }

        if (priceCents
                > maxPriceCents()) {
            return outcome(
                    CreateResult.ABOVE_MAXIMUM
            );
        }

        ItemStack original =
                player.getInventory()
                        .getItemInMainHand();

        if (original.getType().isAir()
                || original.getAmount() <= 0) {
            return outcome(
                    CreateResult.NO_ITEM
            );
        }

        ItemStack held =
                cleanItem(original);

        if (expectedItem != null
                && !similarIgnoringAmount(
                held,
                expectedItem
        )) {
            return outcome(
                    CreateResult.ITEM_CHANGED
            );
        }

        if (amount <= 0
                || amount
                > held.getAmount()) {
            return outcome(
                    CreateResult.INVALID_AMOUNT
            );
        }

        if (listingSlotsFull(player)) {
            return outcome(
                    CreateResult.NO_SLOT
            );
        }

        ItemStack saleItem =
                held.clone();
        saleItem.setAmount(amount);

        CreateResult itemCheck =
                validateListingItem(
                        saleItem
                );

        if (itemCheck
                != CreateResult.SUCCESS) {
            return outcome(itemCheck);
        }

        long minimumPrice =
                minimumListingPriceCents(
                        player,
                        saleItem
                );

        if (priceCents < minimumPrice) {
            return outcome(
                    CreateResult.BELOW_MINIMUM
            );
        }

        AuctionHouseListing listing =
                new AuctionHouseListing(
                        UUID.randomUUID(),
                        player.getUniqueId(),
                        DisplayNames.username(
                                player
                        ),
                        saleItem,
                        priceCents,
                        System.currentTimeMillis()
                );

        ItemStack replacement;

        if (held.getAmount() == amount) {
            replacement =
                    new ItemStack(
                            Material.AIR
                    );
        } else {
            replacement = held.clone();
            replacement.setAmount(
                    held.getAmount()
                            - amount
            );
        }

        /*
         * Fail closed against duplication: remove the item from the live
         * inventory before activating durable storage.
         */
        player.getInventory()
                .setItemInMainHand(
                        replacement
                );

        if (storage.listingSaveFailed(
                listing
        )) {
            player.getInventory()
                    .setItemInMainHand(
                            original
                    );
            return outcome(
                    CreateResult.STORAGE_ERROR
            );
        }

        addInMemory(listing);
        auditList(
                player,
                listing
        );

        return new CreateOutcome(
                CreateResult.SUCCESS,
                listing
        );
    }

    public synchronized CancelResult cancelListing(
            Player player,
            UUID id
    ) {
        AuctionHouseListing listing =
                listings.get(id);

        if (listing == null) {
            return CancelResult.NOT_FOUND;
        }

        /*
         * Cancellation always returns the item to its real owner. Admin
         * confiscation/removal must be a separate explicit moderation action.
         */
        if (!listing.owner()
                .equals(
                        player.getUniqueId()
                )) {
            return CancelResult.NOT_OWNER;
        }

        ItemStack item =
                listing.item();

        if (inventoryFullFor(
                player.getInventory(),
                item
        )) {
            return CancelResult
                    .INVENTORY_FULL;
        }

        if (storage.listingDeleteFailed(
                id
        )) {
            return CancelResult
                    .STORAGE_ERROR;
        }

        removeInMemory(listing);

        ItemStack[] before =
                cloneStorageContents(
                        player.getInventory()
                );
        Map<Integer, ItemStack> leftovers =
                player.getInventory()
                        .addItem(item);

        if (!leftovers.isEmpty()) {
            player.getInventory()
                    .setStorageContents(
                            before
                    );

            if (storage.listingSaveFailed(
                    listing
            )) {
                core.getLogger().severe(
                        "[AuctionHouse] Could not restore cancelled "
                                + "listing "
                                + id
                                + " after an inventory delivery failure"
                );
                return CancelResult
                        .STORAGE_ERROR;
            }

            addInMemory(listing);
            return CancelResult
                    .INVENTORY_FULL;
        }

        auditCancel(
                player,
                listing
        );

        return CancelResult.SUCCESS;
    }

    public synchronized BuyOutcome buy(
            Player buyer,
            UUID id
    ) {
        AuctionHouseListing listing =
                listings.get(id);

        if (listing == null) {
            return new BuyOutcome(
                    BuyResult.NOT_FOUND,
                    null
            );
        }

        if (isExpired(listing)) {
            return new BuyOutcome(
                    BuyResult.EXPIRED,
                    listing
            );
        }

        if (!processingListings.add(id)) {
            return new BuyOutcome(
                    BuyResult.BUSY,
                    listing
            );
        }

        try {
            return buyLocked(
                    buyer,
                    listing
            );
        } finally {
            processingListings.remove(id);
        }
    }

    private BuyOutcome buyLocked(
            Player buyer,
            AuctionHouseListing listing
    ) {
        if (listing.owner()
                .equals(
                        buyer.getUniqueId()
                )) {
            return new BuyOutcome(
                    BuyResult.OWN_ITEM,
                    listing
            );
        }

        ItemStack item =
                listing.item();

        long currentMinimum =
                minimumListingPriceCents(
                        buyer,
                        item
                );

        if (listing.priceCents()
                < currentMinimum) {
            return new BuyOutcome(
                    BuyResult.BELOW_SERVER_WORTH,
                    listing
            );
        }

        if (inventoryFullFor(
                buyer.getInventory(),
                item
        )) {
            return new BuyOutcome(
                    BuyResult.INVENTORY_FULL,
                    listing
            );
        }

        Economy economy =
                economy();

        if (economy == null) {
            return new BuyOutcome(
                    BuyResult.ECONOMY_MISSING,
                    listing
            );
        }

        double price =
                listing.priceCents()
                        / 100.0D;
        OfflinePlayer seller =
                Bukkit.getOfflinePlayer(
                        listing.owner()
                );

        try {
            if (!economy.has(
                    buyer,
                    price
            )) {
                return new BuyOutcome(
                        BuyResult.NOT_ENOUGH_MONEY,
                        listing
                );
            }
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Could not check auction buyer balance",
                    exception
            );
            return new BuyOutcome(
                    BuyResult.PAYMENT_FAILED,
                    listing
            );
        }

        PurchaseRecovery recovery =
                storage.beginRecovery(
                        listing,
                        buyer
                );

        if (recovery == null) {
            return new BuyOutcome(
                    BuyResult.STORAGE_ERROR,
                    listing
            );
        }

        if (storage.listingDeleteFailed(
                listing.id()
        )) {
            storage.deleteRecovery(
                    recovery.transactionId()
            );
            return new BuyOutcome(
                    BuyResult.STORAGE_ERROR,
                    listing
            );
        }

        removeInMemory(listing);

        PurchaseRecovery paymentStarted =
                updateRecovery(
                        recovery,
                        PurchaseState
                                .PAYMENT_STARTED
                );

        if (paymentStarted == null) {
            restorePreparedPurchase(
                    listing,
                    recovery
            );
            return new BuyOutcome(
                    BuyResult.STORAGE_ERROR,
                    listing
            );
        }

        EconomyResponse withdrawal;

        try {
            withdrawal =
                    economy.withdrawPlayer(
                            buyer,
                            price
                    );
        } catch (RuntimeException exception) {
            /*
             * An exception is transaction-ambiguous: the provider may have
             * changed the balance before throwing. Never guess. Quarantine.
             */
            quarantine(paymentStarted);

            core.getLogger().log(
                    Level.SEVERE,
                    "[AuctionHouse] Buyer withdrawal became ambiguous for "
                            + paymentStarted
                            .transactionId(),
                    exception
            );

            return new BuyOutcome(
                    BuyResult.PAYMENT_FAILED,
                    listing
            );
        }

        if (withdrawal == null
                || !withdrawal
                .transactionSuccess()) {
            restorePreparedPurchase(
                    listing,
                    paymentStarted
            );
            return new BuyOutcome(
                    BuyResult.NOT_ENOUGH_MONEY,
                    listing
            );
        }

        EconomyResponse deposit;

        try {
            deposit =
                    economy.depositPlayer(
                            seller,
                            price
                    );
        } catch (RuntimeException exception) {
            /*
             * Seller credit is also ambiguous on exception. Refunding here
             * could mint money if the provider credited before throwing.
             */
            quarantine(paymentStarted);

            core.getLogger().log(
                    Level.SEVERE,
                    "[AuctionHouse] Seller deposit became ambiguous for "
                            + paymentStarted
                            .transactionId(),
                    exception
            );

            return new BuyOutcome(
                    BuyResult.PAYMENT_FAILED,
                    listing
            );
        }

        if (deposit == null
                || !deposit
                .transactionSuccess()) {
            boolean refunded =
                    refundBuyerOnly(
                            economy,
                            buyer,
                            price,
                            paymentStarted
                    );

            if (refunded) {
                restorePreparedPurchase(
                        listing,
                        paymentStarted
                );
            } else {
                quarantine(
                        paymentStarted
                );
            }

            return new BuyOutcome(
                    BuyResult.PAYMENT_FAILED,
                    listing
            );
        }

        PurchaseRecovery paid =
                updateRecovery(
                        paymentStarted,
                        PurchaseState.PAID
                );

        if (paid == null) {
            boolean rolledBack =
                    rollbackCompletedPayment(
                            economy,
                            buyer,
                            seller,
                            price,
                            paymentStarted
                    );

            if (rolledBack) {
                restorePreparedPurchase(
                        listing,
                        paymentStarted
                );
            } else {
                quarantine(
                        paymentStarted
                );
            }

            return new BuyOutcome(
                    BuyResult.STORAGE_ERROR,
                    listing
            );
        }

        ItemStack[] before =
                cloneStorageContents(
                        buyer.getInventory()
                );
        Map<Integer, ItemStack> leftovers =
                buyer.getInventory()
                        .addItem(item);

        if (!leftovers.isEmpty()) {
            buyer.getInventory()
                    .setStorageContents(
                            before
                    );

            boolean rolledBack =
                    rollbackCompletedPayment(
                            economy,
                            buyer,
                            seller,
                            price,
                            paid
                    );

            if (rolledBack) {
                restorePreparedPurchase(
                        listing,
                        paid
                );
            } else {
                quarantine(paid);
            }

            return new BuyOutcome(
                    BuyResult.INVENTORY_FULL,
                    listing
            );
        }

        PurchaseRecovery delivered =
                updateRecovery(
                        paid,
                        PurchaseState.DELIVERED
                );

        if (delivered != null) {
            if (!storage.deleteRecovery(
                    delivered.transactionId()
            )) {
                quarantine(delivered);
            }
        } else {
            /*
             * Item and payment are already complete. Retain the PAID record
             * for manual audit rather than replaying anything automatically.
             */
            quarantine(paid);
        }

        notifySeller(listing);
        auditPurchase(
                buyer,
                listing,
                paid.transactionId()
        );

        return new BuyOutcome(
                BuyResult.SUCCESS,
                listing
        );
    }

    public synchronized List<String>
    recoverySummaries() {
        if (quarantinedRecoveries
                .isEmpty()) {
            return List.of();
        }

        List<String> lines =
                new ArrayList<>();

        for (PurchaseRecovery recovery
                : quarantinedRecoveries
                .values()) {
            AuctionHouseListing listing =
                    recovery.listing();

            lines.add(
                    recovery.transactionId()
                            .toString()
                            .substring(
                                    0,
                                    8
                            )
                            .toUpperCase(
                                    Locale.ROOT
                            )
                            + " "
                            + recovery.state().name()
                            + " listing="
                            + listing.id()
                            + " buyer="
                            + recovery.buyer()
                            + " seller="
                            + listing.owner()
                            + " price="
                            + format(
                            listing.priceCents()
                    )
            );
        }

        return List.copyOf(lines);
    }

    public String recoveryPath() {
        return storage
                .recoveryFolder()
                .getAbsolutePath();
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy>
                provider =
                core.getServer()
                        .getServicesManager()
                        .getRegistration(
                                Economy.class
                        );

        return provider == null
                ? null
                : provider.getProvider();
    }

    private long configuredPriceCents(
            String path,
            String fallback
    ) {
        Object raw =
                config.get(path);

        if (raw instanceof Number number) {
            double value =
                    number.doubleValue();

            if (Double.isFinite(value)
                    && value > 0.0D) {
                long parsed =
                        MoneyFormatter
                                .parsePositiveCents(
                                        BigDecimal
                                                .valueOf(
                                                        value
                                                )
                                                .stripTrailingZeros()
                                                .toPlainString()
                                );

                if (parsed > 0L) {
                    return parsed;
                }
            }
        } else if (raw != null) {
            long parsed =
                    MoneyFormatter
                            .parsePositiveCents(
                                    String.valueOf(raw)
                            );

            if (parsed > 0L) {
                return parsed;
            }
        }

        long fallbackCents =
                MoneyFormatter
                        .parsePositiveCents(
                                fallback
                        );

        return Math.max(
                1L,
                fallbackCents
        );
    }

    public long parsePriceCents(
            String raw
    ) {
        return MoneyFormatter
                .parsePositiveCents(raw);
    }

    public String format(
            long cents
    ) {
        return MoneyFormatter
                .moneyFromCents(cents);
    }

    public ItemStack previewHeldItem(
            Player player
    ) {
        ItemStack held =
                cleanedHeldItem(
                        player
                );

        return held == null
                ? null
                : held.clone();
    }

    public String itemName(
            ItemStack item
    ) {
        if (item == null
                || item.getType().isAir()) {
            return "Item";
        }

        ItemMeta meta =
                item.getItemMeta();

        if (meta != null
                && meta.hasDisplayName()) {
            Component displayName =
                    meta.displayName();

            if (displayName != null) {
                String display =
                        PlainTextComponentSerializer
                                .plainText()
                                .serialize(
                                        displayName
                                )
                                .trim();

                if (!display.isBlank()) {
                    return display;
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
                            ? Character
                            .toUpperCase(
                                    character
                            )
                            : character
            );
            capitalize = false;
        }

        return output.toString();
    }

    public String sellerDisplayName(
            AuctionHouseListing listing
    ) {
        if (listing == null) {
            return "Unknown";
        }

        OfflinePlayer seller =
                Bukkit.getOfflinePlayer(
                        listing.owner()
                );
        String display =
                TextColor.strip(
                        DisplayNames
                                .displayName(
                                        seller
                                )
                );

        if (display.isBlank()) {
            display =
                    TextColor.strip(
                            listing.ownerName()
                    );
        }

        return display.isBlank()
                ? "Unknown"
                : display;
    }

    public long unitPriceCents(
            AuctionHouseListing listing
    ) {
        if (listing == null
                || listing.priceCents() <= 0L) {
            return 0L;
        }

        return Math.max(
                1L,
                Math.ceilDiv(
                        listing.priceCents(),
                        Math.max(
                                1,
                                listing.amount()
                        )
                )
        );
    }

    public long worthCents(
            ItemStack item
    ) {
        if (item == null
                || item.getType().isAir()
                || !config.getBoolean(
                "worth.show-reference",
                true
        )) {
            return 0L;
        }

        SellService current =
                currentSellService();

        if (current == null) {
            return 0L;
        }

        try {
            return Math.max(
                    0L,
                    current.visualWorthCents(
                            null,
                            cleanItem(item)
                    )
            );
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.FINE,
                    "Could not resolve Auction House worth reference",
                    exception
            );
            return 0L;
        }
    }

    /**
     * Exact amount the server Sell system would currently pay for this exact
     * stack. Player-market-only and otherwise unsellable items return zero and
     * therefore fall back to the normal Auction House minimum.
     */
    public long serverSellCents(
            Player player,
            ItemStack item
    ) {
        if (item == null
                || item.getType().isAir()) {
            return 0L;
        }

        SellService current =
                currentSellService();

        if (current == null) {
            return 0L;
        }

        try {
            return Math.max(
                    0L,
                    current.stackWorthCents(
                            player,
                            cleanItem(item)
                    )
            );
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.FINE,
                    "Could not resolve Auction House server-sell floor",
                    exception
            );
            return 0L;
        }
    }

    public long minimumListingPriceCents(
            Player player,
            ItemStack item
    ) {
        long configuredMinimum =
                minPriceCents();

        if (!config.getBoolean(
                "listing.enforce-server-sell-floor",
                true
        )) {
            return configuredMinimum;
        }

        return Math.max(
                configuredMinimum,
                serverSellCents(
                        player,
                        item
                )
        );
    }

    public long worthCents(
            AuctionHouseListing listing
    ) {
        if (listing == null
                || !config.getBoolean(
                "worth.show-reference",
                true
        )) {
            return 0L;
        }

        long now =
                System.currentTimeMillis();
        long cacheMillis =
                Math.clamp(
                        config.getLong(
                                "worth.cache-millis",
                                DEFAULT_WORTH_CACHE_MILLIS
                        ),
                        250L,
                        60_000L
                );

        WorthSnapshot cached =
                worthCache.get(
                        listing.id()
                );

        if (cached != null
                && now - cached.createdAt()
                <= cacheMillis) {
            return cached.cents();
        }

        long cents =
                worthCents(
                        listing.item()
                );

        worthCache.put(
                listing.id(),
                new WorthSnapshot(
                        cents,
                        now
                )
        );

        return cents;
    }

    public boolean isExpired(
            AuctionHouseListing listing
    ) {
        if (listing == null) {
            return true;
        }

        long lifetime =
                listingLifetimeMillis();

        return lifetime > 0L
                && System.currentTimeMillis()
                >= safeAdd(
                listing.createdAt(),
                lifetime
        );
    }

    public String expiryText(
            AuctionHouseListing listing
    ) {
        if (listing == null) {
            return "Expired";
        }

        long lifetime =
                listingLifetimeMillis();

        if (lifetime <= 0L) {
            return "No Expiry";
        }

        long remaining =
                safeAdd(
                        listing.createdAt(),
                        lifetime
                )
                        - System
                        .currentTimeMillis();

        if (remaining <= 0L) {
            return "Expired";
        }

        long days =
                TimeUnit.MILLISECONDS
                        .toDays(remaining);
        remaining -=
                TimeUnit.DAYS
                        .toMillis(days);

        long hours =
                TimeUnit.MILLISECONDS
                        .toHours(remaining);
        remaining -=
                TimeUnit.HOURS
                        .toMillis(hours);

        long minutes =
                TimeUnit.MILLISECONDS
                        .toMinutes(remaining);

        if (days > 0L) {
            return days
                    + "d "
                    + hours
                    + "h";
        }

        if (hours > 0L) {
            return hours
                    + "h "
                    + minutes
                    + "m";
        }

        return Math.max(
                1L,
                minutes
        ) + "m";
    }

    public String sanitizeSearchQuery(
            String query
    ) {
        if (query == null
                || query.isBlank()) {
            return "";
        }

        String plain =
                TextColor.strip(query);

        StringBuilder output =
                new StringBuilder(
                        Math.min(
                                plain.length(),
                                maxSearchLength()
                        )
                );
        boolean pendingSpace = false;

        for (int index = 0;
             index < plain.length();
             index++) {
            char character =
                    plain.charAt(index);

            if (Character
                    .isISOControl(
                            character
                    )
                    || Character
                    .isWhitespace(
                            character
                    )) {
                pendingSpace = true;
                continue;
            }

            if (pendingSpace
                    && !output.isEmpty()) {
                output.append(' ');
            }

            output.append(character);
            pendingSpace = false;

            if (output.length()
                    >= maxSearchLength()) {
                break;
            }
        }

        return output.toString()
                .trim();
    }

    public boolean searchQueryTooLong(
            String query
    ) {
        if (query == null) {
            return false;
        }

        String plain =
                TextColor.strip(query);

        int visible = 0;
        boolean pendingSpace = false;

        for (int index = 0;
             index < plain.length();
             index++) {
            char character =
                    plain.charAt(index);

            if (Character
                    .isISOControl(
                            character
                    )
                    || Character
                    .isWhitespace(
                            character
                    )) {
                pendingSpace = true;
                continue;
            }

            if (pendingSpace
                    && visible > 0) {
                visible++;
            }

            visible++;
            pendingSpace = false;

            if (visible
                    > maxSearchLength()) {
                return true;
            }
        }

        return false;
    }

    public String text(
            String path,
            String fallback,
            String... replacements
    ) {
        String value =
                config.getString(
                        path,
                        fallback
                );

        if (replacements == null) {
            return value;
        }

        for (int index = 0;
             index + 1
                     < replacements.length;
             index += 2) {
            String key =
                    replacements[index];
            String replacement =
                    replacements[index + 1];

            if (key != null
                    && replacement != null) {
                value =
                        value.replace(
                                key,
                                replacement
                        );
            }
        }

        return value;
    }

    private CreateOutcome outcome(
            CreateResult result
    ) {
        return new CreateOutcome(
                result,
                null
        );
    }

    private CreateResult validateListingItem(
            ItemStack item
    ) {
        if (item == null
                || item.getType().isAir()
                || !item.getType().isItem()) {
            return CreateResult.NO_ITEM;
        }

        if (blockedMaterials
                .contains(
                        item.getType()
                )) {
            return CreateResult.BLOCKED_ITEM;
        }

        if (config.getBoolean(
                "listing.reject-overstacked",
                true
        )
                && item.getAmount()
                > item.getMaxStackSize()) {
            return CreateResult.BLOCKED_ITEM;
        }

        if (config.getBoolean(
                "listing.block-filled-containers",
                true
        )
                && hasContainerContents(
                item
        )) {
            return CreateResult
                    .FILLED_CONTAINER;
        }

        int maximumBytes =
                Math.clamp(
                        config.getInt(
                                "listing.max-item-nbt-bytes",
                                262_144
                        ),
                        16_384,
                        4_194_304
                );

        try {
            if (item.serializeAsBytes()
                    .length
                    > maximumBytes) {
                return CreateResult
                        .OVERSIZED_ITEM;
            }
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Could not serialize a proposed auction item",
                    exception
            );
            return CreateResult
                    .OVERSIZED_ITEM;
        }

        return CreateResult.SUCCESS;
    }

    private boolean hasContainerContents(
            ItemStack item
    ) {
        ItemMeta meta =
                item.getItemMeta();

        if (meta instanceof BundleMeta bundle
                && !bundle.getItems()
                .isEmpty()) {
            return true;
        }

        if (meta instanceof BlockStateMeta state
                && state.getBlockState()
                instanceof ShulkerBox shulker) {
            for (ItemStack content
                    : shulker
                    .getInventory()
                    .getContents()) {
                if (content != null
                        && !content
                        .getType()
                        .isAir()) {
                    return true;
                }
            }
        }

        return false;
    }

    private AuctionHouseListing
    normalizedLoadedListing(
            AuctionHouseListing listing
    ) {
        ItemStack cleaned =
                cleanItem(
                        listing.item()
                );

        return new AuctionHouseListing(
                listing.id(),
                listing.owner(),
                listing.ownerName(),
                cleaned,
                listing.priceCents(),
                listing.createdAt()
        );
    }

    private ItemStack cleanedHeldItem(
            Player player
    ) {
        if (player == null) {
            return null;
        }

        return cleanItem(
                player.getInventory()
                        .getItemInMainHand()
        );
    }

    private SellService currentSellService() {
        SellService current =
                sellService;

        if (current == null) {
            current =
                    SellModule.sellService();
            sellService = current;
        }

        return current;
    }

    private ItemStack cleanItem(
            ItemStack raw
    ) {
        if (raw == null
                || raw.getType().isAir()) {
            return raw;
        }

        SellService current =
                currentSellService();

        return current == null
                ? raw.clone()
                : current.stripWorthLore(
                        raw
                );
    }

    private boolean similarIgnoringAmount(
            ItemStack left,
            ItemStack right
    ) {
        if (left == null
                || right == null) {
            return left == right;
        }

        ItemStack a = left.clone();
        ItemStack b = right.clone();

        a.setAmount(1);
        b.setAmount(1);

        return a.isSimilar(b);
    }

    private long listingLifetimeMillis() {
        return LISTING_LIFETIME_MILLIS;
    }

    private Comparator<AuctionHouseListing>
    comparator(
            SortMode mode
    ) {
        return switch (mode) {
            case LOWEST_PRICE ->
                    Comparator
                            .comparingLong(
                                    AuctionHouseListing
                                            ::priceCents
                            )
                            .thenComparingLong(
                                    AuctionHouseListing
                                            ::createdAt
                            )
                            .thenComparing(
                                    listing ->
                                            listing.id()
                                                    .toString()
                            );
            case LOWEST_UNIT_PRICE ->
                    Comparator
                            .comparingLong(
                                    this::unitPriceCents
                            )
                            .thenComparingLong(
                                    AuctionHouseListing
                                            ::priceCents
                            )
                            .thenComparingLong(
                                    AuctionHouseListing
                                            ::createdAt
                            )
                            .thenComparing(
                                    listing ->
                                            listing.id()
                                                    .toString()
                            );
            case HIGHEST_PRICE ->
                    Comparator
                            .comparingLong(
                                    AuctionHouseListing
                                            ::priceCents
                            )
                            .reversed()
                            .thenComparingLong(
                                    AuctionHouseListing
                                            ::createdAt
                            )
                            .thenComparing(
                                    listing ->
                                            listing.id()
                                                    .toString()
                            );
            case RECENTLY_LISTED ->
                    Comparator
                            .comparingLong(
                                    AuctionHouseListing
                                            ::createdAt
                            )
                            .reversed()
                            .thenComparing(
                                    listing ->
                                            listing.id()
                                                    .toString()
                            );
        };
    }

    private void addInMemory(
            AuctionHouseListing listing
    ) {
        listings.put(
                listing.id(),
                listing
        );
        ownerIndex
                .computeIfAbsent(
                        listing.owner(),
                        ignored ->
                                new LinkedHashSet<>()
                )
                .add(
                        listing.id()
                );
        searchIndex.put(
                listing.id(),
                buildSearchDocument(
                        listing
                )
        );
        worthCache.remove(
                listing.id()
        );

        AuctionHouseDatabaseMirror mirror =
                databaseMirror;

        if (mirror != null) {
            mirror.upsert(
                    listing,
                    LISTING_LIFETIME_MILLIS
            );
        }
    }

    private void removeInMemory(
            AuctionHouseListing listing
    ) {
        listings.remove(
                listing.id()
        );
        searchIndex.remove(
                listing.id()
        );
        worthCache.remove(
                listing.id()
        );

        Set<UUID> ids =
                ownerIndex.get(
                        listing.owner()
                );

        if (ids != null) {
            ids.remove(listing.id());

            if (ids.isEmpty()) {
                ownerIndex.remove(
                        listing.owner()
                );
            }
        }

        AuctionHouseDatabaseMirror mirror =
                databaseMirror;

        if (mirror != null) {
            mirror.delete(
                    listing.id()
            );
        }
    }

    private SearchDocument buildSearchDocument(
            AuctionHouseListing listing
    ) {
        ItemStack item =
                listing.item();
        StringBuilder builder =
                new StringBuilder();

        appendSearch(
                builder,
                item.getType().name()
        );
        appendSearch(
                builder,
                itemName(item)
        );

        ItemMeta meta =
                item.getItemMeta();

        if (meta != null) {
            if (meta.hasDisplayName()) {
                Component displayName =
                        meta.displayName();

                if (displayName != null) {
                    appendSearch(
                            builder,
                            PlainTextComponentSerializer
                                    .plainText()
                                    .serialize(
                                            displayName
                                    )
                    );
                }
            }

            if (meta.hasLore()) {
                List<Component> loreLines =
                        meta.lore();

                if (loreLines != null) {
                    for (Component lore
                            : loreLines) {
                        appendSearch(
                                builder,
                                PlainTextComponentSerializer
                                        .plainText()
                                        .serialize(
                                                lore
                                        )
                        );
                    }
                }
            }

            if (meta.hasEnchants()) {
                appendEnchantments(
                        builder,
                        meta.getEnchants()
                                .keySet()
                );
            }

            if (meta
                    instanceof EnchantmentStorageMeta
                    storageMeta
                    && storageMeta
                    .hasStoredEnchants()) {
                appendEnchantments(
                        builder,
                        storageMeta
                                .getStoredEnchants()
                                .keySet()
                );
            }
        }

        return new SearchDocument(
                listing.material(),
                builder.toString()
        );
    }

    private void appendEnchantments(
            StringBuilder builder,
            Set<Enchantment> enchantments
    ) {
        for (Enchantment enchantment
                : enchantments) {
            appendSearch(
                    builder,
                    enchantment.getKey()
                            .getKey()
            );
        }
    }

    private void appendSearch(
            StringBuilder builder,
            String value
    ) {
        String normalized =
                normalizeToken(value);

        if (normalized.isBlank()) {
            return;
        }

        if (!builder.isEmpty()) {
            builder.append(' ');
        }

        builder.append(normalized);
    }

    private List<String> searchTokens(
            String query
    ) {
        String sanitized =
                sanitizeSearchQuery(query);

        if (sanitized.isBlank()) {
            return List.of();
        }

        List<String> tokens =
                new ArrayList<>();

        for (String raw
                : sanitized.split("\\s+")) {
            String normalized =
                    normalizeToken(raw);

            if (normalized.isBlank()) {
                continue;
            }

            List<String> alias =
                    searchAliases.get(
                            normalized
                    );

            if (alias == null
                    || alias.isEmpty()) {
                tokens.add(normalized);
            } else {
                tokens.addAll(alias);
            }
        }

        return List.copyOf(tokens);
    }

    private String normalizeToken(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return "";
        }

        String lower =
                TextColor.strip(value)
                        .toLowerCase(
                                Locale.ROOT
                        );
        StringBuilder output =
                new StringBuilder(
                        lower.length()
                );

        for (int index = 0;
             index < lower.length();
             index++) {
            char character =
                    lower.charAt(index);

            if (Character
                    .isLetterOrDigit(
                            character
                    )) {
                output.append(character);
            }
        }

        return output.toString();
    }

    private Map<String, List<String>>
    loadSearchAliases() {
        ConfigurationSection section =
                config
                        .getConfigurationSection(
                                "search-aliases"
                        );

        if (section == null) {
            return Map.of();
        }

        Map<String, List<String>> result =
                new HashMap<>();

        for (String rawAlias
                : section.getKeys(false)) {
            String alias =
                    normalizeToken(rawAlias);
            String target =
                    config.getString(
                            "search-aliases."
                                    + rawAlias,
                            rawAlias
                    );

            if (alias.isBlank()
                    || target == null
                    || target.isBlank()) {
                continue;
            }

            List<String> targetTokens =
                    new ArrayList<>();

            for (String rawTarget
                    : target.split("\\s+")) {
                String normalized =
                        normalizeToken(
                                rawTarget
                        );

                if (!normalized.isBlank()) {
                    targetTokens.add(
                            normalized
                    );
                }
            }

            if (!targetTokens.isEmpty()) {
                result.put(
                        alias,
                        List.copyOf(
                                targetTokens
                        )
                );
            }
        }

        return Map.copyOf(result);
    }

    private Set<Material>
    loadBlockedMaterials() {
        Set<Material> result =
                EnumSet.of(
                        Material.COMMAND_BLOCK,
                        Material.CHAIN_COMMAND_BLOCK,
                        Material.REPEATING_COMMAND_BLOCK,
                        Material.COMMAND_BLOCK_MINECART,
                        Material.STRUCTURE_BLOCK,
                        Material.STRUCTURE_VOID,
                        Material.JIGSAW,
                        Material.BARRIER,
                        Material.LIGHT,
                        Material.DEBUG_STICK,
                        Material.KNOWLEDGE_BOOK
                );

        for (String raw
                : config.getStringList(
                "listing.blocked-materials"
        )) {
            if (raw == null
                    || raw.isBlank()) {
                continue;
            }

            Material material =
                    Material.matchMaterial(
                            raw.trim()
                    );

            if (material == null) {
                core.getLogger().warning(
                        "[AuctionHouse] Unknown blocked material: "
                                + raw
                );
                continue;
            }

            result.add(material);
        }

        return Set.copyOf(result);
    }

    private Set<String>
    loadLegacyElevatedGroups() {
        Set<String> result =
                new HashSet<>();

        List<String> configured =
                config.getStringList(
                        "listing.legacy-elevated-primary-groups"
                );

        if (configured.isEmpty()) {
            configured =
                    config.getStringList(
                            "listing.plus-groups"
                    );
        }

        for (String raw : configured) {
            String normalized =
                    normalizeGroup(raw);

            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }

        return Set.copyOf(result);
    }

    private String normalizeGroup(
            String raw
    ) {
        if (raw == null) {
            return "";
        }

        String cleaned =
                raw.trim()
                        .toLowerCase(
                                Locale.ROOT
                        );
        StringBuilder output =
                new StringBuilder(
                        cleaned.length()
                );

        for (int index = 0;
             index < cleaned.length();
             index++) {
            char character =
                    cleaned.charAt(index);

            if (Character
                    .isLetterOrDigit(
                            character
                    )
                    || character == '_'
                    || character == '-') {
                output.append(character);
            }
        }

        return output.toString();
    }

    private boolean inventoryFullFor(
            PlayerInventory inventory,
            ItemStack item
    ) {
        int remaining =
                item.getAmount();
        int maxStack =
                item.getMaxStackSize();

        for (ItemStack content
                : inventory
                .getStorageContents()) {
            if (content == null
                    || content.getType()
                    .isAir()) {
                remaining -= maxStack;
            } else if (content
                    .isSimilar(item)) {
                remaining -=
                        Math.max(
                                0,
                                content
                                        .getMaxStackSize()
                                        - content
                                        .getAmount()
                        );
            }

            if (remaining <= 0) {
                return false;
            }
        }

        return true;
    }

    private ItemStack[] cloneStorageContents(
            PlayerInventory inventory
    ) {
        ItemStack[] contents =
                inventory
                        .getStorageContents();
        ItemStack[] copy =
                new ItemStack[
                        contents.length
                        ];

        for (int index = 0;
             index < contents.length;
             index++) {
            copy[index] =
                    contents[index] == null
                            ? null
                            : contents[index]
                            .clone();
        }

        return copy;
    }

    private PurchaseRecovery updateRecovery(
            PurchaseRecovery recovery,
            PurchaseState state
    ) {
        PurchaseRecovery updated =
                recovery.withState(state);

        return storage.saveRecovery(
                updated
        )
                ? updated
                : null;
    }

    private void restorePreparedPurchase(
            AuctionHouseListing listing,
            PurchaseRecovery recovery
    ) {
        if (storage.listingSaveFailed(
                listing
        )) {
            core.getLogger().severe(
                    "[AuctionHouse] Could not restore listing "
                            + listing.id()
                            + "; purchase recovery retained"
            );
            quarantine(recovery);
            return;
        }

        if (recovery != null
                && !storage.deleteRecovery(
                recovery.transactionId()
        )) {
            /*
             * Both artifacts now exist. Keep the listing hidden until the
             * recovery record is resolved instead of risking a second sale.
             */
            quarantine(recovery);
            return;
        }

        if (recovery != null) {
            quarantinedRecoveries.remove(
                    recovery.transactionId()
            );
        }

        addInMemory(listing);
    }

    private boolean refundBuyerOnly(
            Economy economy,
            Player buyer,
            double price,
            PurchaseRecovery recovery
    ) {
        try {
            EconomyResponse response =
                    economy.depositPlayer(
                            buyer,
                            price
                    );

            if (response != null
                    && response
                    .transactionSuccess()) {
                return true;
            }
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "[AuctionHouse] Could not refund buyer for recovery "
                            + recovery.transactionId(),
                    exception
            );
        }

        return false;
    }

    /**
     * Reverse seller credit first, then refund the buyer. If seller reversal
     * fails, do not mint a duplicate buyer refund; quarantine for review.
     */
    private boolean rollbackCompletedPayment(
            Economy economy,
            Player buyer,
            OfflinePlayer seller,
            double price,
            PurchaseRecovery recovery
    ) {
        try {
            EconomyResponse sellerDebit =
                    economy.withdrawPlayer(
                            seller,
                            price
                    );

            if (sellerDebit == null
                    || !sellerDebit
                    .transactionSuccess()) {
                core.getLogger().severe(
                        "[AuctionHouse] Seller payment could not be "
                                + "reversed for recovery "
                                + recovery.transactionId()
                );
                return false;
            }
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "[AuctionHouse] Seller payment reversal threw for recovery "
                            + recovery.transactionId(),
                    exception
            );
            return false;
        }

        return refundBuyerOnly(
                economy,
                buyer,
                price,
                recovery
        );
    }

    private void recoverInterruptedPurchases() {
        for (PurchaseRecovery recovery
                : storage.loadRecoveries()) {
            switch (recovery.state()) {
                case PREPARED -> {
                    if (!storage.listingExists(
                            recovery.listing()
                                    .id()
                    )) {
                        if (storage.listingSaveFailed(
                                recovery.listing()
                        )) {
                            quarantine(recovery);
                            continue;
                        }
                    }

                    if (!storage.deleteRecovery(
                            recovery.transactionId()
                    )) {
                        quarantine(recovery);
                    }
                }
                case DELIVERED -> {
                    if (!storage.deleteRecovery(
                            recovery.transactionId()
                    )) {
                        quarantine(recovery);
                    }
                }
                case PAYMENT_STARTED,
                     PAID -> quarantine(
                        recovery
                );
            }
        }

        if (!quarantinedRecoveries
                .isEmpty()) {
            core.getLogger().severe(
                    "[AuctionHouse] "
                            + quarantinedRecoveries
                            .size()
                            + " interrupted purchase(s) require manual review at "
                            + recoveryPath()
            );
        }
    }

    private void quarantine(
            PurchaseRecovery recovery
    ) {
        if (recovery == null) {
            return;
        }

        quarantinedRecoveries.put(
                recovery.transactionId(),
                recovery
        );
    }

    private boolean isQuarantined(
            UUID listingId
    ) {
        if (listingId == null) {
            return false;
        }

        for (PurchaseRecovery recovery
                : quarantinedRecoveries
                .values()) {
            if (recovery.listing()
                    .id()
                    .equals(listingId)) {
                return true;
            }
        }

        return false;
    }

    public void deliverPendingSaleNotice(
            Player seller
    ) {
        if (seller == null
                || !seller.isOnline()) {
            return;
        }

        SaleReceipt receipt =
                storage.loadSaleReceipt(
                        seller.getUniqueId()
                );

        if (receipt == null) {
            return;
        }

        String message;

        if (receipt.count() == 1) {
            message =
                    text(
                            "messages.sold-offline-single",
                            "&#bbbbbbWhile you were away, &#B078FF%item% &#bbbbbbsold for &a+%price%",
                            "%item%",
                            safeOutput(
                                    receipt.lastItem()
                            ),
                            "%price%",
                            format(
                                    receipt.lastPriceCents()
                            )
                    );
        } else {
            message =
                    text(
                            "messages.sold-offline-multiple",
                            "&#bbbbbbWhile you were away, &#D0AFFF%count% &#bbbbbbauction listings sold for &a+%price%",
                            "%count%",
                            String.valueOf(
                                    receipt.count()
                            ),
                            "%price%",
                            format(
                                    receipt.totalCents()
                            )
                    );
        }

        seller.sendMessage(
                TextColor.color(message)
        );
        SoundService.economyReceive(
                seller,
                core
        );

        if (!storage.clearSaleReceipt(
                seller.getUniqueId()
        )) {
            core.getLogger().warning(
                    "[AuctionHouse] Could not clear delivered sale notice for "
                            + seller.getUniqueId()
                            + "; it may repeat on next join"
            );
        }
    }

    private void notifySeller(
            AuctionHouseListing listing
    ) {
        Player seller =
                Bukkit.getPlayer(
                        listing.owner()
                );

        if (seller == null
                || !seller.isOnline()) {
            if (!storage.recordSaleReceipt(
                    listing.owner(),
                    safeOutput(
                            itemName(
                                    listing.item()
                            )
                    ),
                    listing.priceCents()
            )) {
                core.getLogger().warning(
                        "[AuctionHouse] Could not persist offline sale notice for "
                                + listing.owner()
                );
            }
            return;
        }

        seller.sendMessage(
                TextColor.color(
                        text(
                                "messages.sold",
                                "&#bbbbbbSold &#B078FF%item% &#bbbbbbfor &a+%price%",
                                "%item%",
                                safeOutput(
                                        itemName(
                                                listing.item()
                                        )
                                ),
                                "%price%",
                                format(
                                        listing.priceCents()
                                )
                        )
                )
        );

        SoundService.economyReceive(
                seller,
                core
        );
    }

    private void auditList(
            Player seller,
            AuctionHouseListing listing
    ) {
        if (auditDisabled()) {
            return;
        }

        core.getLogger().info(
                "[AuctionHouse] LIST listing="
                        + listing.id()
                        + " seller="
                        + seller.getUniqueId()
                        + " item="
                        + listing.material()
                        + " amount="
                        + listing.amount()
                        + " price-cents="
                        + listing.priceCents()
        );
    }

    private void auditCancel(
            Player seller,
            AuctionHouseListing listing
    ) {
        if (auditDisabled()) {
            return;
        }

        core.getLogger().info(
                "[AuctionHouse] CANCEL listing="
                        + listing.id()
                        + " seller="
                        + seller.getUniqueId()
                        + " expired="
                        + isExpired(listing)
        );
    }

    private void auditPurchase(
            Player buyer,
            AuctionHouseListing listing,
            UUID transactionId
    ) {
        if (auditDisabled()) {
            return;
        }

        core.getLogger().info(
                "[AuctionHouse] BUY transaction="
                        + transactionId
                        + " listing="
                        + listing.id()
                        + " buyer="
                        + buyer.getUniqueId()
                        + " seller="
                        + listing.owner()
                        + " item="
                        + listing.material()
                        + " amount="
                        + listing.amount()
                        + " price-cents="
                        + listing.priceCents()
        );
    }

    private boolean auditDisabled() {
        return !config.getBoolean(
                "audit.enabled",
                true
        );
    }

    private void startDatabaseMirror() {
        AuctionHouseDatabaseMirror mirror =
                new AuctionHouseDatabaseMirror(
                        core,
                        config
                );

        databaseMirror = mirror;
        mirror.start();

        if (!mirror.enabled()) {
            return;
        }

        mirror.reconcile(
                List.copyOf(
                        listings.values()
                ),
                LISTING_LIFETIME_MILLIS
        );

        long syncSeconds =
                Math.clamp(
                        config.getLong(
                                "database.mirror.sync-seconds",
                                60L
                        ),
                        15L,
                        900L
                );
        long syncTicks =
                syncSeconds * 20L;

        databaseMirrorTask =
                core.getServer()
                        .getScheduler()
                        .runTaskTimer(
                                core,
                                this::reconcileDatabaseMirror,
                                syncTicks,
                                syncTicks
                        );
    }

    private synchronized void reconcileDatabaseMirror() {
        AuctionHouseDatabaseMirror mirror =
                databaseMirror;

        if (mirror == null
                || !mirror.enabled()) {
            return;
        }

        mirror.reconcile(
                List.copyOf(
                        listings.values()
                ),
                LISTING_LIFETIME_MILLIS
        );
    }

    private void stopDatabaseMirror() {
        BukkitTask task =
                databaseMirrorTask;

        if (task != null) {
            task.cancel();
            databaseMirrorTask = null;
        }

        AuctionHouseDatabaseMirror mirror =
                databaseMirror;

        if (mirror != null) {
            databaseMirror = null;
            mirror.shutdown(
                    List.copyOf(
                            listings.values()
                    ),
                    LISTING_LIFETIME_MILLIS
            );
        }
    }

    private void ensureConfigFile() {
        if (configFile.isFile()) {
            return;
        }

        if (configFile.exists()) {
            throw new IllegalStateException(
                    "auctionhouse.yml is not a file"
            );
        }

        try {
            core.saveResource(
                    "auctionhouse.yml",
                    false
            );
        } catch (
                IllegalArgumentException exception
        ) {
            try {
                if (!configFile
                        .createNewFile()) {
                    throw new IOException(
                            "createNewFile returned false"
                    );
                }
            } catch (IOException ioException) {
                throw new IllegalStateException(
                        "Could not create auctionhouse.yml",
                        ioException
                );
            }
        }
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

    private String safeOutput(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return TextColor.strip(value)
                .replace(
                        '§',
                        ' '
                )
                .trim();
    }

    private record SearchDocument(
            Material material,
            String text
    ) {
        private boolean matches(
                List<String> tokens
        ) {
            if (tokens == null
                    || tokens.isEmpty()) {
                return true;
            }

            for (String token : tokens) {
                if (!text.contains(token)) {
                    return false;
                }
            }

            return true;
        }
    }

    private record WorthSnapshot(
            long cents,
            long createdAt
    ) {
    }
}