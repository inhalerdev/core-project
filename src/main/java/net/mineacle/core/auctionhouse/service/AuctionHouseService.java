package net.mineacle.core.auctionhouse.service;

import io.papermc.paper.block.TileStateInventoryHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.auctionhouse.storage.AuctionHouseDatabaseMirror;
import net.mineacle.core.auctionhouse.storage.AuctionHouseStorage;
import net.mineacle.core.auctionhouse.storage.AuctionHouseStorage.PurchaseRecovery;
import net.mineacle.core.auctionhouse.storage.AuctionHouseStorage.SaleReceipt;
import net.mineacle.core.auctionhouse.storage.AuctionTransactionStorage;
import net.mineacle.core.auctionhouse.storage.AuctionTransactionStorage.AuctionTransaction;
import net.mineacle.core.auctionhouse.storage.AuctionTransactionStorage.TransactionState;
import net.mineacle.core.auctionhouse.storage.AuctionTransactionStorage.TransactionType;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.economy.service.EconomyService.MarketTransactionDurability;
import net.mineacle.core.economy.service.EconomyService.MarketTransferStatus;
import net.mineacle.core.sell.SellModule;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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

    private static final long DEFAULT_WORTH_CACHE_MILLIS = 3_000L;
    private static final long DEFAULT_BROWSE_CACHE_MILLIS = 750L;
    private static final int MAX_BROWSE_CACHE_ENTRIES = 128;
    private static final long LISTING_LIFETIME_MILLIS = TimeUnit.HOURS.toMillis(24L);
    private static final String CREATE_PHASE_MARKED = "MARKED";
    private static final String CREATE_PHASE_REMOVED = "REMOVED";

    public enum SortMode {
        LOWEST_PRICE("Lowest Price"),
        LOWEST_UNIT_PRICE("Lowest Each"),
        HIGHEST_PRICE("Highest Price"),
        RECENTLY_LISTED("Recently Listed");

        private final String label;
        SortMode(String label) { this.label = label; }
        public String label() { return label; }
        public SortMode next() {
            SortMode[] modes = values();
            return modes[(ordinal() + 1) % modes.length];
        }
        public SortMode previous() {
            SortMode[] modes = values();
            return modes[(ordinal() + modes.length - 1) % modes.length];
        }
    }

    public enum FilterMode {
        ALL("All"), BLOCKS("Blocks"), TOOLS("Tools"), FOOD("Food"),
        COMBAT("Combat"), POTIONS("Potions"), BOOKS("Books"),
        INGREDIENTS("Materials"), UTILITIES("Utilities");

        private final String label;
        FilterMode(String label) { this.label = label; }
        public String label() { return label; }
        public FilterMode next() {
            FilterMode[] modes = values();
            return modes[(ordinal() + 1) % modes.length];
        }
        public FilterMode previous() {
            FilterMode[] modes = values();
            return modes[(ordinal() + modes.length - 1) % modes.length];
        }

        private static FilterMode classify(Material material) {
            if (material == null || material.isAir()) return UTILITIES;
            String name = material.name();
            if (name.contains("POTION") || name.equals("DRAGON_BREATH")) return POTIONS;
            if (name.contains("BOOK") || name.equals("PAPER")
                    || name.equals("MAP") || name.endsWith("_MAP")) return BOOKS;
            if (material.isEdible()) return FOOD;
            if (isCombat(name)) return COMBAT;
            if (isTool(name)) return TOOLS;
            if (isIngredient(material, name)) return INGREDIENTS;
            if (material.isBlock()) return BLOCKS;
            return UTILITIES;
        }

        private static boolean isTool(String name) {
            return name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                    || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
                    || name.equals("SHEARS") || name.equals("FISHING_ROD")
                    || name.equals("BRUSH") || name.equals("FLINT_AND_STEEL");
        }

        private static boolean isCombat(String name) {
            return name.endsWith("_SWORD") || name.endsWith("_HELMET")
                    || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
                    || name.endsWith("_BOOTS") || name.equals("BOW")
                    || name.equals("CROSSBOW") || name.equals("SHIELD")
                    || name.equals("TRIDENT") || name.equals("MACE")
                    || name.equals("ARROW") || name.endsWith("_ARROW");
        }

        private static boolean isIngredient(Material material, String name) {
            return Tag.ITEMS_COALS.isTagged(material)
                    || name.contains("INGOT") || name.contains("NUGGET")
                    || name.contains("DUST") || name.contains("GEM")
                    || name.contains("SHARD") || name.contains("SCRAP")
                    || name.startsWith("RAW_");
        }
    }

    public enum CreateResult {
        SUCCESS, DISABLED, NO_PERMISSION, NO_ITEM, ITEM_CHANGED,
        INVALID_AMOUNT, NO_SLOT, INVALID_PRICE, BELOW_MINIMUM,
        ABOVE_MAXIMUM, BLOCKED_ITEM, FILLED_CONTAINER, OVERSIZED_ITEM,
        STORAGE_ERROR
    }
    public enum CancelResult {
        SUCCESS, NOT_FOUND, NOT_OWNER, INVENTORY_FULL, STORAGE_ERROR
    }
    public enum BuyResult {
        SUCCESS, PROCESSING, NOT_FOUND, EXPIRED, BUSY, NO_PERMISSION, OWN_ITEM,
        BELOW_SERVER_WORTH, NOT_ENOUGH_MONEY, INVENTORY_FULL,
        ECONOMY_MISSING, PAYMENT_FAILED, STORAGE_ERROR
    }

    public record CreateOutcome(CreateResult result, AuctionHouseListing listing) {}
    public record BuyOutcome(BuyResult result, AuctionHouseListing listing) {}
    public record MarketReference(int comparableListings, long lowestTotalCents, long lowestUnitCents) {
        public MarketReference {
            comparableListings = Math.max(0, comparableListings);
            lowestTotalCents = Math.max(0L, lowestTotalCents);
            lowestUnitCents = Math.max(0L, lowestUnitCents);
        }
        public boolean available() { return comparableListings > 0 && lowestUnitCents > 0L; }
    }

    private enum DeliveryResult { COMPLETED, INVENTORY_FULL, PENDING }

    private final Core core;
    private final File configFile;
    private final AuctionHouseStorage storage;
    private final AuctionTransactionStorage transactionStorage;
    private final NamespacedKey createTransactionKey;
    private final NamespacedKey createPhaseKey;
    private final NamespacedKey deliveryTransactionKey;

    private AuctionHouseDatabaseMirror databaseMirror;
    private BukkitTask databaseMirrorTask;
    private BukkitTask paymentRecoveryTask;

    private final Map<UUID, AuctionHouseListing> listings = new LinkedHashMap<>();
    private final Map<UUID, LinkedHashSet<UUID>> ownerIndex = new HashMap<>();
    private final Map<Material, LinkedHashSet<UUID>> materialIndex = new EnumMap<>(Material.class);
    private final Map<FilterMode, LinkedHashSet<UUID>> categoryIndex = new EnumMap<>(FilterMode.class);
    private final Map<UUID, SearchDocument> searchIndex = new HashMap<>();
    private final Map<UUID, WorthSnapshot> worthCache = new HashMap<>();
    private final Set<UUID> processingListings = new HashSet<>();
    private final Map<UUID, PurchaseRecovery> quarantinedRecoveries = new LinkedHashMap<>();
    private final Map<UUID, AuctionTransaction> transactions = new LinkedHashMap<>();
    private final Set<UUID> runtimeQuarantinedTransactions = new HashSet<>();
    private final Set<UUID> restrictedListings = new HashSet<>();
    private final LinkedHashMap<SearchCacheKey, SearchSnapshot> searchCache =
            new LinkedHashMap<>(16, 0.75F, true);

    private YamlConfiguration config = new YamlConfiguration();
    private Set<Material> blockedMaterials = Set.of();
    private Map<String, List<String>> searchAliases = Map.of();
    private SellService sellService;
    private long listingGeneration;
    private long browseCacheMillis = DEFAULT_BROWSE_CACHE_MILLIS;

    public AuctionHouseService(Core core) {
        this.core = core;
        this.configFile = new File(core.getDataFolder(), "auctionhouse.yml");
        this.storage = new AuctionHouseStorage(core);
        this.transactionStorage = new AuctionTransactionStorage(core);
        this.createTransactionKey = new NamespacedKey(core, "ah_create_transaction");
        this.createPhaseKey = new NamespacedKey(core, "ah_create_phase");
        this.deliveryTransactionKey = new NamespacedKey(core, "ah_delivery_transaction");
    }

    public synchronized void load() {
        stopPaymentRecoveryTask();
        stopDatabaseMirror();
        ensureConfigFile();
        config = YamlConfiguration.loadConfiguration(configFile);
        sellService = SellModule.sellService();
        blockedMaterials = loadBlockedMaterials();
        searchAliases = loadSearchAliases();
        browseCacheMillis = Math.clamp(
                config.getLong("browse.cache-millis", DEFAULT_BROWSE_CACHE_MILLIS),
                0L, 5_000L
        );

        int maximumItemBytes = maximumListingItemBytes();
        storage.configureMaximumItemBytes(maximumItemBytes);
        transactionStorage.configureMaximumItemBytes(maximumItemBytes);

        clearInMemoryState();
        storage.initialize();
        transactionStorage.initialize();
        loadDurableTransactions();
        recoverInterruptedPurchases();
        reconcileTransactionsBeforeListingLoad();

        for (AuctionHouseListing stored : storage.loadListings()) {
            if (isQuarantined(stored.id()) || isListingBlockedByTransaction(stored.id())) {
                core.getLogger().warning(
                        "[AuctionHouse] Listing " + stored.id()
                                + " is hidden while a recovery transaction is unresolved"
                );
                continue;
            }

            AuctionHouseListing normalized =
                    normalizedLoadedListing(stored);
            CreateResult storedPolicy =
                    validateListingItem(normalized.item());

            if (storedPolicy != CreateResult.SUCCESS) {
                restrictedListings.add(normalized.id());
                core.getLogger().warning(
                        "[AuctionHouse] Listing " + normalized.id()
                                + " is restricted from public browsing until its owner reclaims it"
                                + " | reason=" + storedPolicy
                );
            }

            addInMemory(normalized);
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            recoverPlayerTransactions(online);
        }
        retryPendingTransactions();

        core.getLogger().info(
                "Auction House loaded " + listings.size() + " listing(s)" + recoveryLoadSuffix()
        );
        startDatabaseMirror();
    }

    public synchronized void shutdown() {
        stopPaymentRecoveryTask();
        stopDatabaseMirror();
        clearInMemoryState();
    }

    private String recoveryLoadSuffix() {
        int count = quarantinedRecoveries.size() + transactions.size();
        return count == 0 ? "" : " with " + count + " recovery transaction(s)";
    }

    private void clearInMemoryState() {
        listings.clear();
        ownerIndex.clear();
        materialIndex.clear();
        categoryIndex.clear();
        searchIndex.clear();
        worthCache.clear();
        processingListings.clear();
        quarantinedRecoveries.clear();
        transactions.clear();
        runtimeQuarantinedTransactions.clear();
        restrictedListings.clear();
        searchCache.clear();
        listingGeneration = 0L;
    }

    public boolean enabled() { return config.getBoolean("enabled", true); }
    public int pageSize() { return PAGE_SIZE; }

    public SortMode defaultSort() {
        String configured = config.getString("browse.default-sort", "LOWEST_UNIT_PRICE");
        if (configured.isBlank()) return SortMode.LOWEST_UNIT_PRICE;
        try {
            return SortMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SortMode.LOWEST_UNIT_PRICE;
        }
    }

    public boolean quickBuyEnabled() {
        return config.getBoolean("browse.shift-click-quick-buy", true);
    }

    public long minPriceCents() {
        return Math.max(1L, configuredPriceCents("listing.min-price", "1"));
    }

    public long maxPriceCents() {
        return Math.max(minPriceCents(), configuredPriceCents("listing.max-price", "1B"));
    }

    public int maxSearchLength() {
        return Math.clamp(config.getInt("search.max-length", 48), 8, 128);
    }

    public long promptTimeoutTicks() {
        return Math.clamp(config.getLong("search.prompt-timeout-seconds", 60L), 5L, 300L) * 20L;
    }

    public int listingLimit(Player player) {
        if (player == null) return 0;
        int normal = Math.clamp(config.getInt("listing.default-slots", 18), 1, 999);
        if (player.hasPermission("mineacleauctionhouse.admin")) {
            return Math.max(normal, Math.clamp(config.getInt("listing.admin-slots", 999), 1, 999));
        }
        if (hasElevatedListingTier(player)) {
            return Math.max(normal, Math.clamp(config.getInt("listing.elevated-slots", 45), 1, 999));
        }
        return normal;
    }

    public boolean canList(Player player) {
        if (player == null || !player.hasPermission("mineacleauctionhouse.use")) return false;
        if (player.hasPermission("mineacleauctionhouse.admin")
                || player.hasPermission("mineacleauctionhouse.sell")) return true;
        return config.getBoolean("listing.allow-default", true) || hasElevatedListingTier(player);
    }

    private boolean hasElevatedListingTier(Player player) {
        if (player == null) return false;
        if (player.hasPermission("mineacleauctionhouse.admin")) return true;
        String permission = config.getString("listing.elevated-permission", "mineacleauctionhouse.slots.45");
        return !permission.isBlank() && player.hasPermission(permission.trim());
    }

    public synchronized int activeListingCount(UUID owner) { return countOwnerListings(owner, false); }
    public synchronized int expiredListingCount(UUID owner) { return countOwnerListings(owner, true); }

    private int countOwnerListings(UUID owner, boolean expired) {
        if (owner == null) return 0;
        Set<UUID> ids = ownerIndex.get(owner);
        if (ids == null || ids.isEmpty()) return 0;
        long now = System.currentTimeMillis();
        int count = 0;
        for (UUID id : ids) {
            AuctionHouseListing listing = listings.get(id);
            if (listing != null && expiredAt(listing, now) == expired) count++;
        }
        return count;
    }

    public synchronized int occupiedListingCount(UUID owner) {
        if (owner == null) return 0;
        Set<UUID> ids = ownerIndex.get(owner);
        return ids == null ? 0 : ids.size();
    }

    public boolean listingSlotsFull(Player player) {
        return player == null
                || occupiedListingCount(player.getUniqueId()) >= listingLimit(player);
    }

    public synchronized List<AuctionHouseListing> search(
            String query, SortMode sortMode, FilterMode filterMode
    ) {
        List<String> tokens = searchTokens(query);
        SortMode effectiveSort = sortMode == null ? defaultSort() : sortMode;
        FilterMode effectiveFilter = filterMode == null ? FilterMode.ALL : filterMode;
        SearchCacheKey cacheKey = new SearchCacheKey(tokens, effectiveSort, effectiveFilter);
        long now = System.currentTimeMillis();

        if (browseCacheMillis > 0L) {
            SearchSnapshot cached = searchCache.get(cacheKey);
            if (cached != null && cached.generation() == listingGeneration
                    && now < cached.validUntilMillis()) {
                return cached.listings();
            }
        }

        List<AuctionHouseListing> result = new ArrayList<>();
        long validUntil = browseCacheMillis <= 0L ? now : safeAdd(now, browseCacheMillis);

        if (effectiveFilter == FilterMode.ALL) {
            for (AuctionHouseListing listing : listings.values()) {
                validUntil = collectSearchMatch(listing, tokens, result, now, validUntil);
            }
        } else {
            Set<UUID> ids = categoryIndex.get(effectiveFilter);
            if (ids != null) {
                for (UUID id : ids) {
                    AuctionHouseListing listing = listings.get(id);
                    if (listing != null) {
                        validUntil = collectSearchMatch(listing, tokens, result, now, validUntil);
                    }
                }
            }
        }

        result.sort(comparator(effectiveSort));
        List<AuctionHouseListing> immutable = List.copyOf(result);
        if (browseCacheMillis > 0L) {
            searchCache.put(cacheKey, new SearchSnapshot(listingGeneration, validUntil, immutable));
            trimSearchCache();
        }
        return immutable;
    }

    private long collectSearchMatch(
            AuctionHouseListing listing, List<String> tokens,
            List<AuctionHouseListing> result, long now, long validUntil
    ) {
        long expiresAt = expiresAt(listing);
        if (restrictedListings.contains(listing.id())
                || expiresAt <= now) {
            return validUntil;
        }
        SearchDocument document = searchIndex.get(listing.id());
        if (document == null || !document.matches(tokens)) return validUntil;
        result.add(listing);
        return Math.min(validUntil, expiresAt);
    }

    private void trimSearchCache() {
        while (searchCache.size() > MAX_BROWSE_CACHE_ENTRIES) {
            var iterator = searchCache.entrySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    public synchronized List<AuctionHouseListing> ownerListings(UUID owner) {
        if (owner == null) return List.of();
        Set<UUID> ids = ownerIndex.get(owner);
        if (ids == null || ids.isEmpty()) return List.of();
        List<AuctionHouseListing> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            AuctionHouseListing listing = listings.get(id);
            if (listing != null) result.add(listing);
        }
        result.sort(
                Comparator.comparing(this::isExpired)
                        .thenComparing(
                                Comparator.comparingLong(AuctionHouseListing::createdAt).reversed()
                        )
        );
        return List.copyOf(result);
    }

    public synchronized AuctionHouseListing listing(UUID id) {
        return id == null ? null : listings.get(id);
    }


    public synchronized MarketReference marketReference(Player seller, ItemStack rawItem) {
        if (rawItem == null || rawItem.getType().isAir()) {
            return new MarketReference(0, 0L, 0L);
        }

        ItemStack item = cleanItem(rawItem);
        if (item == null || item.getType().isAir()) {
            return new MarketReference(0, 0L, 0L);
        }

        Set<UUID> ids = materialIndex.get(item.getType());
        if (ids == null || ids.isEmpty()) {
            return new MarketReference(0, 0L, 0L);
        }

        UUID sellerId = seller == null ? null : seller.getUniqueId();
        long now = System.currentTimeMillis();
        long lowestUnit = Long.MAX_VALUE;
        int comparable = 0;

        for (UUID id : ids) {
            AuctionHouseListing listing = listings.get(id);
            if (listing == null
                    || restrictedListings.contains(id)
                    || expiredAt(listing, now)
                    || (sellerId != null && sellerId.equals(listing.owner()))
                    || differentIgnoringAmount(item, listing.item())) {
                continue;
            }
            comparable++;
            lowestUnit = Math.min(lowestUnit, unitPriceCents(listing));
        }

        if (comparable == 0 || lowestUnit == Long.MAX_VALUE) {
            return new MarketReference(0, 0L, 0L);
        }

        return new MarketReference(
                comparable,
                safeMultiply(lowestUnit, Math.max(1, item.getAmount())),
                lowestUnit
        );
    }

    public synchronized CreateOutcome createListing(Player player, long priceCents) {
        ItemStack held = cleanedHeldItem(player);
        int amount = held == null ? 0 : held.getAmount();
        return createListing(player, priceCents, amount, null);
    }

    /**
     * Durable LIST transaction. The source item is not considered removed
     * until playerdata contains the same transaction id with phase REMOVED.
     */
    public synchronized CreateOutcome createListing(
            Player player, long priceCents, int amount, ItemStack expectedItem
    ) {
        if (player == null) return outcome(CreateResult.NO_ITEM);
        if (!enabled()) return outcome(CreateResult.DISABLED);
        if (!canList(player)) return outcome(CreateResult.NO_PERMISSION);
        if (priceCents <= 0L) return outcome(CreateResult.INVALID_PRICE);
        if (priceCents > maxPriceCents()) return outcome(CreateResult.ABOVE_MAXIMUM);

        int sourceSlot = player.getInventory().getHeldItemSlot();
        ItemStack original = player.getInventory().getItem(sourceSlot);
        if (original == null || original.getType().isAir() || original.getAmount() <= 0) {
            return outcome(CreateResult.NO_ITEM);
        }

        ItemStack held = cleanItem(original);
        if (expectedItem != null && differentIgnoringAmount(held, expectedItem)) {
            return outcome(CreateResult.ITEM_CHANGED);
        }
        if (amount <= 0 || amount > held.getAmount()) {
            return outcome(CreateResult.INVALID_AMOUNT);
        }
        if (listingSlotsFull(player)) return outcome(CreateResult.NO_SLOT);

        ItemStack saleItem = held.clone();
        saleItem.setAmount(amount);
        CreateResult itemCheck = validateListingItem(saleItem);
        if (itemCheck != CreateResult.SUCCESS) return outcome(itemCheck);

        long minimumPrice = minimumListingPriceCents(player, saleItem);
        if (priceCents < minimumPrice) return outcome(CreateResult.BELOW_MINIMUM);

        AuctionHouseListing listing = new AuctionHouseListing(
                UUID.randomUUID(),
                player.getUniqueId(),
                publicIdentity(
                        player.getUniqueId(),
                        DisplayNames.commandDisplayName(player)
                ),
                saleItem,
                priceCents,
                System.currentTimeMillis()
        );

        AuctionTransaction transaction = transactionStorage.begin(
                TransactionType.LIST, listing, player, sourceSlot
        );
        if (transaction == null) return outcome(CreateResult.STORAGE_ERROR);
        transactions.put(transaction.transactionId(), transaction);

        if (!prepareCreateMarker(player, transaction)) {
            return outcome(CreateResult.STORAGE_ERROR);
        }

        if (storage.listingSaveFailed(listing)) {
            abortUncreatedListing(transaction, player);
            return outcome(CreateResult.STORAGE_ERROR);
        }

        AuctionTransaction listingSaved = advanceTransaction(
                transaction, TransactionState.LISTING_SAVED
        );
        if (listingSaved == null) {
            // PREPARED + durable listing is inferred as LISTING_SAVED on recovery.
            return outcome(CreateResult.STORAGE_ERROR);
        }

        if (listingSourceRemovalFailed(player, listingSaved)) {
            return outcome(CreateResult.STORAGE_ERROR);
        }

        AuctionTransaction sourceRemoved = advanceTransaction(
                listingSaved, TransactionState.SOURCE_REMOVED
        );
        if (sourceRemoved == null) {
            // Playerdata marker REMOVED proves the source mutation and prevents replay.
            return outcome(CreateResult.STORAGE_ERROR);
        }

        finalizeListTransaction(sourceRemoved, player);
        return new CreateOutcome(CreateResult.SUCCESS, listing);
    }

    /**
     * Durable RETURN transaction used for normal cancel and expired reclaim.
     */
    public synchronized CancelResult cancelListing(Player player, UUID id) {
        AuctionHouseListing listing = listings.get(id);
        if (listing == null) return CancelResult.NOT_FOUND;
        if (player == null || !listing.owner().equals(player.getUniqueId())) {
            return CancelResult.NOT_OWNER;
        }

        ItemStack item = listing.item();
        if (inventoryFullFor(player.getInventory(), item)) {
            return CancelResult.INVENTORY_FULL;
        }

        AuctionTransaction transaction = transactionStorage.begin(
                TransactionType.RETURN, listing, player, -1
        );
        if (transaction == null) return CancelResult.STORAGE_ERROR;
        transactions.put(transaction.transactionId(), transaction);

        if (storage.listingDeleteFailed(id)) {
            deleteTransaction(transaction);
            return CancelResult.STORAGE_ERROR;
        }

        removeInMemory(listing);

        AuctionTransaction removed = advanceTransaction(
                transaction, TransactionState.LISTING_REMOVED
        );
        if (removed == null) {
            // PREPARED + missing listing is inferred as LISTING_REMOVED on recovery.
            return CancelResult.STORAGE_ERROR;
        }

        DeliveryResult delivery = deliverTransaction(removed, player);
        if (delivery == DeliveryResult.INVENTORY_FULL) {
            return abortReturnTransaction(removed)
                    ? CancelResult.INVENTORY_FULL
                    : CancelResult.STORAGE_ERROR;
        }

        return delivery == DeliveryResult.COMPLETED
                ? CancelResult.SUCCESS
                : CancelResult.STORAGE_ERROR;
    }

    public synchronized BuyOutcome buy(Player buyer, UUID id) {
        AuctionHouseListing listing = listings.get(id);
        if (listing == null
                || restrictedListings.contains(id)) {
            return new BuyOutcome(BuyResult.NOT_FOUND, null);
        }
        if (isExpired(listing)) return new BuyOutcome(BuyResult.EXPIRED, listing);

        /*
         * A second AH payment may not overwrite the economy checkpoint while
         * an earlier PAYMENT_STARTED transaction still depends on it.
         */
        if (hasAmbiguousPaymentBarrier()) {
            return new BuyOutcome(BuyResult.BUSY, listing);
        }
        if (!processingListings.add(id)) {
            return new BuyOutcome(BuyResult.BUSY, listing);
        }

        try {
            return buyLocked(buyer, listing);
        } finally {
            processingListings.remove(id);
        }
    }

    /**
     * Durable BUY transaction. Listing removal is journaled first, then the
     * native economy persists buyer debit + seller credit + transaction id in
     * one immutable economy snapshot, then item delivery is playerdata-backed.
     */
    private BuyOutcome buyLocked(Player buyer, AuctionHouseListing listing) {
        if (buyer == null) return new BuyOutcome(BuyResult.NOT_FOUND, listing);
        if (!buyer.hasPermission("mineacleauctionhouse.use")) {
            return new BuyOutcome(BuyResult.NO_PERMISSION, listing);
        }
        if (listing.owner().equals(buyer.getUniqueId())) {
            return new BuyOutcome(BuyResult.OWN_ITEM, listing);
        }

        ItemStack item = listing.item();
        if (listing.priceCents() < minimumListingPriceCents(buyer, item)) {
            return new BuyOutcome(BuyResult.BELOW_SERVER_WORTH, listing);
        }
        if (inventoryFullFor(buyer.getInventory(), item)) {
            return new BuyOutcome(BuyResult.INVENTORY_FULL, listing);
        }

        EconomyService economy = nativeEconomy();
        if (economy == null || !economy.enabled()) {
            return new BuyOutcome(BuyResult.ECONOMY_MISSING, listing);
        }
        if (!economy.has(buyer.getUniqueId(), listing.priceCents())) {
            return new BuyOutcome(BuyResult.NOT_ENOUGH_MONEY, listing);
        }

        AuctionTransaction transaction = transactionStorage.begin(
                TransactionType.BUY, listing, buyer, -1
        );
        if (transaction == null) {
            return new BuyOutcome(BuyResult.STORAGE_ERROR, listing);
        }
        transactions.put(transaction.transactionId(), transaction);

        if (storage.listingDeleteFailed(listing.id())) {
            deleteTransaction(transaction);
            return new BuyOutcome(BuyResult.STORAGE_ERROR, listing);
        }

        removeInMemory(listing);

        AuctionTransaction listingRemoved = advanceTransaction(
                transaction, TransactionState.LISTING_REMOVED
        );
        if (listingRemoved == null) {
            return new BuyOutcome(BuyResult.STORAGE_ERROR, listing);
        }

        AuctionTransaction paymentStarted = advanceTransaction(
                listingRemoved, TransactionState.PAYMENT_STARTED
        );
        if (paymentStarted == null) {
            return new BuyOutcome(BuyResult.STORAGE_ERROR, listing);
        }

        MarketTransferStatus payment = economy.durableMarketTransfer(
                paymentStarted.transactionId(),
                buyer.getUniqueId(),
                listing.owner(),
                listing.priceCents(),
                0L
        );

        return switch (payment) {
            case SUCCESS, ALREADY_COMMITTED -> {
                AuctionTransaction paid = advanceTransaction(
                        paymentStarted, TransactionState.PAID
                );
                if (paid == null) {
                    yield new BuyOutcome(BuyResult.STORAGE_ERROR, listing);
                }

                DeliveryResult delivery = deliverTransaction(paid, buyer);
                yield switch (delivery) {
                    case COMPLETED -> new BuyOutcome(BuyResult.SUCCESS, listing);
                    case INVENTORY_FULL -> new BuyOutcome(BuyResult.INVENTORY_FULL, listing);
                    case PENDING -> new BuyOutcome(BuyResult.STORAGE_ERROR, listing);
                };
            }
            case PERSISTENCE_PENDING -> {
                schedulePaymentRecovery();
                yield new BuyOutcome(
                        BuyResult.PROCESSING,
                        listing
                );
            }
            case INSUFFICIENT_FUNDS -> {
                boolean restored = abortUnpaidTransaction(paymentStarted);
                yield new BuyOutcome(
                        restored ? BuyResult.NOT_ENOUGH_MONEY : BuyResult.STORAGE_ERROR,
                        listing
                );
            }
            case RECIPIENT_BALANCE_LIMIT, INVALID -> {
                boolean restored = abortUnpaidTransaction(paymentStarted);
                yield new BuyOutcome(
                        restored ? BuyResult.PAYMENT_FAILED : BuyResult.STORAGE_ERROR,
                        listing
                );
            }
            case DISABLED -> {
                boolean restored = abortUnpaidTransaction(paymentStarted);
                yield new BuyOutcome(
                        restored ? BuyResult.ECONOMY_MISSING : BuyResult.STORAGE_ERROR,
                        listing
                );
            }
            case BUSY -> {
                boolean restored = abortUnpaidTransaction(paymentStarted);
                yield new BuyOutcome(
                        restored ? BuyResult.BUSY : BuyResult.STORAGE_ERROR,
                        listing
                );
            }
        };
    }


    /**
     * Playerdata is loaded when this is called from PlayerJoinEvent. Matching
     * PDC markers are durable evidence and are always recovered first.
     */
    public synchronized void recoverPlayerTransactions(Player player) {
        if (player == null || !player.isOnline()) return;

        List<AuctionTransaction> actorTransactions = transactions.values().stream()
                .filter(transaction -> transaction.actor().equals(player.getUniqueId()))
                .sorted(
                        Comparator.comparingLong(AuctionTransaction::createdAt)
                                .thenComparing(transaction -> transaction.transactionId().toString())
                )
                .toList();

        UUID createMarker = markerUuid(player, createTransactionKey);
        UUID deliveryMarker = markerUuid(player, deliveryTransactionKey);

        if (createMarker != null) {
            AuctionTransaction marked = transactions.get(createMarker);
            if (marked != null && marked.actor().equals(player.getUniqueId())) {
                recoverTransactionForPlayer(marked, player);
            }
        }

        if (deliveryMarker != null) {
            AuctionTransaction marked = transactions.get(deliveryMarker);
            if (marked != null && marked.actor().equals(player.getUniqueId())) {
                recoverTransactionForPlayer(marked, player);
            }
        }

        for (AuctionTransaction transaction : actorTransactions) {
            AuctionTransaction current = transactions.get(transaction.transactionId());
            if (current != null) recoverTransactionForPlayer(current, player);
        }

        clearOrphanPlayerMarkers(player);
    }

    /**
     * Periodic recovery is non-blocking. It requests economy persistence and
     * lets a later tick observe the durable checkpoint rather than waiting.
     */
    public synchronized void retryPendingTransactions() {
        normalizeMultiplePaymentBarriers();

        for (AuctionTransaction transaction : List.copyOf(transactions.values())) {
            AuctionTransaction current = transactions.get(transaction.transactionId());
            if (current == null) continue;
            if (current.state() == TransactionState.QUARANTINED
                    || runtimeQuarantinedTransactions.contains(current.transactionId())) {
                continue;
            }

            if (current.state() == TransactionState.ABORTED) {
                finalizeAbortedTransaction(current);
                continue;
            }

            if (current.state() == TransactionState.DELIVERED) {
                finalizeDeliveredTransaction(current, Bukkit.getPlayer(current.actor()));
                continue;
            }

            if (current.type() == TransactionType.LIST
                    && current.state() == TransactionState.SOURCE_REMOVED) {
                finalizeListTransaction(current, Bukkit.getPlayer(current.actor()));
                continue;
            }

            if (current.type() == TransactionType.BUY
                    && current.state() == TransactionState.LISTING_REMOVED
                    && !hasOtherAmbiguousPaymentBarrier(current.transactionId())) {
                AuctionTransaction paymentStarted = advanceTransaction(
                        current, TransactionState.PAYMENT_STARTED
                );
                if (paymentStarted != null) {
                    recoverPayment(paymentStarted);
                }
                continue;
            }

            if (current.type() == TransactionType.BUY
                    && current.state() == TransactionState.PAYMENT_STARTED) {
                recoverPayment(current);
            }
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            recoverPlayerTransactions(online);
        }
    }

    public synchronized List<String> recoverySummaries() {
        List<String> lines =
                new ArrayList<>();

        for (AuctionTransaction transaction
                : transactions.values()) {
            AuctionHouseListing listing =
                    transaction.listing();

            String actorLabel =
                    transaction.type()
                            == TransactionType.BUY
                            ? "buyer"
                            : "seller";

            lines.add(
                    shortId(
                            transaction.transactionId()
                    )
                            + " "
                            + transaction.type().name()
                            + "/"
                            + transaction.state().name()
                            + " • "
                            + actorLabel
                            + " "
                            + publicIdentity(
                            transaction.actor(),
                            transaction.actorName()
                    )
                            + " • listing "
                            + shortId(
                            listing.id()
                    )
                            + " • "
                            + format(
                            listing.priceCents()
                    )
            );
        }

        for (PurchaseRecovery recovery
                : quarantinedRecoveries.values()) {
            AuctionHouseListing listing =
                    recovery.listing();

            lines.add(
                    shortId(
                            recovery.transactionId()
                    )
                            + " LEGACY/"
                            + recovery.state().name()
                            + " • buyer "
                            + publicIdentity(
                            recovery.buyer(),
                            recovery.buyerName()
                    )
                            + " • seller "
                            + publicIdentity(
                            listing.owner(),
                            listing.ownerName()
                    )
                            + " • listing "
                            + shortId(
                            listing.id()
                    )
                            + " • "
                            + format(
                            listing.priceCents()
                    )
            );
        }

        return List.copyOf(lines);
    }

    private void loadDurableTransactions() {
        for (AuctionTransaction loaded : transactionStorage.load()) {
            AuctionTransaction transaction =
                    normalizeTransactionIdentity(
                            loaded
                    );

            transactions.put(
                    transaction.transactionId(),
                    transaction
            );

            if (!transaction.equals(loaded)
                    && !transactionStorage.save(
                    transaction
            )) {
                core.getLogger().warning(
                        "[AuctionHouse] Could not refresh public identities for transaction "
                                + transaction.transactionId()
                );
            }

            if (transaction.state()
                    == TransactionState.QUARANTINED) {
                runtimeQuarantinedTransactions.add(
                        transaction.transactionId()
                );
            }
        }
    }

    private AuctionTransaction normalizeTransactionIdentity(
            AuctionTransaction transaction
    ) {
        AuctionHouseListing listing =
                transaction.listing();

        String sellerName =
                publicIdentity(
                        listing.owner(),
                        listing.ownerName()
                );
        String actorName =
                publicIdentity(
                        transaction.actor(),
                        transaction.actorName()
                );

        if (sellerName.equals(
                listing.ownerName()
        ) && actorName.equals(
                transaction.actorName()
        )) {
            return transaction;
        }

        AuctionHouseListing normalizedListing =
                new AuctionHouseListing(
                        listing.id(),
                        listing.owner(),
                        sellerName,
                        listing.item(),
                        listing.priceCents(),
                        listing.createdAt()
                );

        return new AuctionTransaction(
                transaction.transactionId(),
                transaction.type(),
                transaction.state(),
                normalizedListing,
                transaction.actor(),
                actorName,
                transaction.sourceSlot(),
                transaction.createdAt()
        );
    }

    /**
     * Resolve file-only states before public listing indexes are rebuilt.
     */
    private void reconcileTransactionsBeforeListingLoad() {
        for (AuctionTransaction transaction : List.copyOf(transactions.values())) {
            if (transaction.state() == TransactionState.QUARANTINED) continue;

            switch (transaction.type()) {
                case LIST -> reconcileListBeforeLoad(transaction);
                case BUY, RETURN -> reconcileRemovalBeforeLoad(transaction);
            }
        }
    }

    private void reconcileListBeforeLoad(AuctionTransaction transaction) {
        switch (transaction.state()) {
            case PREPARED -> {
                if (storage.listingExists(transaction.listing().id())) {
                    advanceTransaction(transaction, TransactionState.LISTING_SAVED);
                } else {
                    deleteTransaction(transaction);
                }
            }
            case SOURCE_REMOVED -> {
                if (storage.listingExists(transaction.listing().id())) {
                    deleteTransaction(transaction);
                } else {
                    quarantineTransaction(transaction, "source-removed-but-listing-missing");
                }
            }
            case ABORTED -> finalizeAbortedTransaction(transaction);
            case DELIVERED -> deleteTransaction(transaction);
            case LISTING_SAVED, QUARANTINED -> {
                // Playerdata is required.
            }
            default -> quarantineTransaction(
                    transaction, "invalid-list-state-" + transaction.state()
            );
        }
    }

    private void reconcileRemovalBeforeLoad(
            AuctionTransaction transaction
    ) {
        switch (transaction.type()) {
            case BUY ->
                    reconcileBuyBeforeLoad(
                            transaction
                    );
            case RETURN ->
                    reconcileReturnBeforeLoad(
                            transaction
                    );
            case LIST ->
                    quarantineTransaction(
                            transaction,
                            "invalid-removal-reconciliation-for-list"
                    );
        }
    }

    private void reconcileBuyBeforeLoad(
            AuctionTransaction transaction
    ) {
        boolean listingExists =
                storage.listingExists(
                        transaction.listing()
                                .id()
                );

        switch (transaction.state()) {
            case PREPARED -> {
                if (listingExists) {
                    deleteTransaction(
                            transaction
                    );
                } else {
                    advanceTransaction(
                            transaction,
                            TransactionState.LISTING_REMOVED
                    );
                }
            }
            case LISTING_REMOVED -> {
                if (listingExists) {
                    AuctionTransaction aborted =
                            advanceTransaction(
                                    transaction,
                                    TransactionState.ABORTED
                            );

                    if (aborted != null) {
                        finalizeAbortedTransaction(
                                aborted
                        );
                    }
                }
            }
            case PAYMENT_STARTED -> {
                /*
                 * recoverPayment() owns this ambiguity because the economy
                 * checkpoint determines whether restoring the listing is safe.
                 */
            }
            case PAID, DELIVERY_STARTED, DELIVERED -> {
                if (listingExists) {
                    quarantineTransaction(
                            transaction,
                            "listing-present-after-paid-buy-state-"
                                    + transaction.state()
                    );
                }
                /*
                 * DELIVERED without a conflicting listing is intentionally
                 * retained until seller receipt finalization.
                 */
            }
            case ABORTED ->
                    finalizeAbortedTransaction(
                            transaction
                    );
            case QUARANTINED -> {
            }
            case LISTING_SAVED, SOURCE_REMOVED ->
                    quarantineTransaction(
                            transaction,
                            "invalid-buy-state-"
                                    + transaction.state()
                    );
        }
    }

    private void reconcileReturnBeforeLoad(
            AuctionTransaction transaction
    ) {
        boolean listingExists =
                storage.listingExists(
                        transaction.listing()
                                .id()
                );

        switch (transaction.state()) {
            case PREPARED -> {
                if (listingExists) {
                    deleteTransaction(
                            transaction
                    );
                } else {
                    advanceTransaction(
                            transaction,
                            TransactionState.LISTING_REMOVED
                    );
                }
            }
            case LISTING_REMOVED -> {
                if (listingExists) {
                    AuctionTransaction aborted =
                            advanceTransaction(
                                    transaction,
                                    TransactionState.ABORTED
                            );

                    if (aborted != null) {
                        finalizeAbortedTransaction(
                                aborted
                        );
                    }
                }
            }
            case DELIVERY_STARTED, DELIVERED -> {
                if (listingExists) {
                    quarantineTransaction(
                            transaction,
                            "listing-present-after-return-delivery-state-"
                                    + transaction.state()
                    );
                }
            }
            case ABORTED ->
                    finalizeAbortedTransaction(
                            transaction
                    );
            case QUARANTINED -> {
            }
            case PAYMENT_STARTED, PAID, LISTING_SAVED, SOURCE_REMOVED ->
                    quarantineTransaction(
                            transaction,
                            "invalid-return-state-"
                                    + transaction.state()
                    );
        }
    }

    private boolean isListingBlockedByTransaction(UUID listingId) {
        if (listingId == null) return false;
        for (AuctionTransaction transaction : transactions.values()) {
            if (transaction.listing().id().equals(listingId)) return true;
        }
        return false;
    }

    private void recoverTransactionForPlayer(
            AuctionTransaction original, Player player
    ) {
        if (original == null || player == null
                || !original.actor().equals(player.getUniqueId())
                || original.state() == TransactionState.QUARANTINED
                || runtimeQuarantinedTransactions.contains(original.transactionId())) {
            return;
        }

        AuctionTransaction transaction = transactions.getOrDefault(
                original.transactionId(), original
        );

        switch (transaction.type()) {
            case LIST -> recoverListTransaction(transaction, player);
            case RETURN -> recoverReturnTransaction(transaction, player);
            case BUY -> recoverBuyTransaction(transaction, player);
        }
    }

    private void recoverListTransaction(AuctionTransaction transaction, Player player) {
        if (transaction.state() == TransactionState.PREPARED) {
            if (!storage.listingExists(transaction.listing().id())) {
                clearCreateMarker(player, transaction.transactionId(), true);
                deleteTransaction(transaction);
                return;
            }

            AuctionTransaction advanced = advanceTransaction(
                    transaction, TransactionState.LISTING_SAVED
            );
            if (advanced == null) return;
            transaction = advanced;
        }

        if (transaction.state() == TransactionState.LISTING_SAVED) {
            if (listingSourceRemovalFailed(player, transaction)) return;

            AuctionTransaction removed = advanceTransaction(
                    transaction, TransactionState.SOURCE_REMOVED
            );
            if (removed == null) return;
            transaction = removed;
        }

        if (transaction.state() == TransactionState.SOURCE_REMOVED) {
            finalizeListTransaction(transaction, player);
        }
    }

    private void recoverReturnTransaction(AuctionTransaction transaction, Player player) {
        if (transaction.state() == TransactionState.PREPARED) {
            if (storage.listingExists(transaction.listing().id())) {
                deleteTransaction(transaction);
                return;
            }

            AuctionTransaction removed = advanceTransaction(
                    transaction, TransactionState.LISTING_REMOVED
            );
            if (removed == null) return;
            transaction = removed;
        }

        if (transaction.state() == TransactionState.ABORTED) {
            finalizeAbortedTransaction(transaction);
            return;
        }

        if (transaction.state() == TransactionState.DELIVERED) {
            finalizeDeliveredTransaction(transaction, player);
            return;
        }

        if (transaction.state() == TransactionState.LISTING_REMOVED
                || transaction.state() == TransactionState.DELIVERY_STARTED) {
            DeliveryResult delivery = deliverTransaction(transaction, player);
            if (delivery == DeliveryResult.INVENTORY_FULL) {
                abortReturnTransaction(
                        transactions.getOrDefault(transaction.transactionId(), transaction)
                );
            }
        }
    }

    private void recoverBuyTransaction(AuctionTransaction transaction, Player player) {
        if (transaction.state() == TransactionState.PREPARED) {
            if (storage.listingExists(transaction.listing().id())) {
                deleteTransaction(transaction);
                return;
            }

            AuctionTransaction removed = advanceTransaction(
                    transaction, TransactionState.LISTING_REMOVED
            );
            if (removed == null) return;
            transaction = removed;
        }

        if (transaction.state() == TransactionState.LISTING_REMOVED) {
            if (hasOtherAmbiguousPaymentBarrier(transaction.transactionId())) {
                return;
            }

            AuctionTransaction paymentStarted = advanceTransaction(
                    transaction, TransactionState.PAYMENT_STARTED
            );
            if (paymentStarted == null) return;
            transaction = paymentStarted;
        }

        if (transaction.state() == TransactionState.PAYMENT_STARTED) {
            recoverPayment(transaction);
            transaction = transactions.get(transaction.transactionId());
            if (transaction == null) return;
        }

        if (transaction.state() == TransactionState.ABORTED) {
            finalizeAbortedTransaction(transaction);
            return;
        }

        if (transaction.state() == TransactionState.PAID
                || transaction.state() == TransactionState.DELIVERY_STARTED
                || transaction.state() == TransactionState.DELIVERED) {
            deliverTransaction(transaction, player);
        }
    }


    private boolean prepareCreateMarker(Player player, AuctionTransaction transaction) {
        UUID existing = markerUuid(player, createTransactionKey);

        if (existing != null && !existing.equals(transaction.transactionId())) {
            AuctionTransaction active = transactions.get(existing);
            if (active != null) {
                core.getLogger().severe(
                        "[AuctionHouse] Refused LIST " + transaction.transactionId()
                                + " because player " + player.getUniqueId()
                                + " still has active LIST marker " + existing
                );
                return false;
            }
            clearCreateMarker(player, existing, false);
        }

        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(
                createTransactionKey,
                PersistentDataType.STRING,
                transaction.transactionId().toString()
        );
        data.set(createPhaseKey, PersistentDataType.STRING, CREATE_PHASE_MARKED);

        return savePlayerData(
                player, "prepare listing source " + transaction.transactionId()
        );
    }

    private boolean listingSourceRemovalFailed(
            Player player, AuctionTransaction transaction
    ) {
        if (player == null || transaction == null
                || transaction.type() != TransactionType.LIST) {
            return true;
        }

        UUID marker = markerUuid(player, createTransactionKey);
        String phase = markerString(player, createPhaseKey);

        if (!transaction.transactionId().equals(marker)) {
            quarantineTransaction(transaction, "missing-or-conflicting-create-marker");
            return true;
        }

        if (CREATE_PHASE_REMOVED.equals(phase)) {
            /*
             * Same-runtime retry after a thrown saveData call: save the current
             * removal+marker pair again before advancing the journal.
             */
            return !savePlayerData(
                    player, "confirm listing source removal " + transaction.transactionId()
            );
        }

        if (!CREATE_PHASE_MARKED.equals(phase)) {
            quarantineTransaction(transaction, "invalid-create-phase-" + phase);
            return true;
        }

        int slot = transaction.sourceSlot();
        if (slot < 0 || slot >= player.getInventory().getSize()) {
            quarantineTransaction(transaction, "invalid-create-source-slot");
            return true;
        }

        ItemStack current = player.getInventory().getItem(slot);
        if (current == null || current.getType().isAir()
                || current.getAmount() < transaction.listing().amount()
                || differentIgnoringAmount(
                        cleanItem(current), transaction.listing().item()
                )) {
            quarantineTransaction(transaction, "create-source-item-mismatch");
            return true;
        }

        ItemStack cleaned = cleanItem(current);
        int remaining = current.getAmount() - transaction.listing().amount();

        if (remaining <= 0) {
            player.getInventory().setItem(slot, new ItemStack(Material.AIR));
        } else {
            cleaned.setAmount(remaining);
            player.getInventory().setItem(slot, cleaned);
        }

        player.getPersistentDataContainer().set(
                createPhaseKey, PersistentDataType.STRING, CREATE_PHASE_REMOVED
        );

        return !savePlayerData(
                player, "remove Auction House listing source " + transaction.transactionId()
        );
    }

    private void abortUncreatedListing(AuctionTransaction transaction, Player player) {
        clearCreateMarker(player, transaction.transactionId(), true);
        if (!deleteTransaction(transaction)) {
            core.getLogger().warning(
                    "[AuctionHouse] LIST abort cleanup remains journaled for "
                            + transaction.transactionId()
            );
        }
    }

    private void finalizeListTransaction(AuctionTransaction transaction, Player player) {
        if (transaction == null || transaction.type() != TransactionType.LIST
                || transaction.state() != TransactionState.SOURCE_REMOVED) {
            return;
        }

        AuctionHouseListing listing = transaction.listing();
        if (!storage.listingExists(listing.id())) {
            quarantineTransaction(transaction, "cannot-finalize-list-without-listing");
            return;
        }

        boolean deleted = deleteTransaction(transaction);

        if (!listings.containsKey(listing.id())) {
            addInMemory(normalizedLoadedListing(listing));
        }

        if (player != null && player.isOnline()) {
            clearCreateMarker(player, transaction.transactionId(), true);
        }

        if (deleted && player != null) {
            auditList(player, listing);
        }
    }

    private boolean abortReturnTransaction(AuctionTransaction transaction) {
        if (transaction == null || transaction.type() != TransactionType.RETURN) {
            return false;
        }

        if (storage.listingSaveFailed(transaction.listing())) {
            quarantineTransaction(transaction, "return-abort-listing-restore-failed");
            return false;
        }

        AuctionTransaction aborted = advanceTransaction(
                transaction, TransactionState.ABORTED
        );
        if (aborted == null) {
            runtimeQuarantinedTransactions.add(transaction.transactionId());
            return false;
        }

        if (!listings.containsKey(transaction.listing().id())) {
            addInMemory(transaction.listing());
        }

        deleteTransaction(aborted);
        return true;
    }

    private boolean abortUnpaidTransaction(AuctionTransaction transaction) {
        if (transaction == null || transaction.type() != TransactionType.BUY) {
            return false;
        }

        EconomyService economy = nativeEconomy();
        if (economy != null
                && economy.marketTransactionDurability(transaction.transactionId())
                != MarketTransactionDurability.UNKNOWN) {
            quarantineTransaction(
                    transaction, "attempted-abort-after-economy-recognized-transaction"
            );
            return false;
        }

        if (storage.listingSaveFailed(transaction.listing())) {
            quarantineTransaction(transaction, "unpaid-abort-listing-restore-failed");
            return false;
        }

        AuctionTransaction aborted = advanceTransaction(
                transaction, TransactionState.ABORTED
        );
        if (aborted == null) {
            /*
             * Listing is restored but PAYMENT_STARTED is still durable, so keep
             * the listing hidden until ABORTED can be journaled.
             */
            runtimeQuarantinedTransactions.add(transaction.transactionId());
            return false;
        }

        if (!listings.containsKey(transaction.listing().id())) {
            addInMemory(transaction.listing());
        }

        deleteTransaction(aborted);
        return true;
    }

    private void finalizeAbortedTransaction(AuctionTransaction transaction) {
        if (transaction == null || transaction.state() != TransactionState.ABORTED) {
            return;
        }

        if (!storage.listingExists(transaction.listing().id())
                && storage.listingSaveFailed(transaction.listing())) {
            quarantineTransaction(transaction, "aborted-transaction-listing-restore-failed");
            return;
        }

        if (!listings.containsKey(transaction.listing().id())) {
            addInMemory(transaction.listing());
        }

        deleteTransaction(transaction);
    }

    private DeliveryResult deliverTransaction(
            AuctionTransaction original, Player player
    ) {
        if (original == null || player == null || !player.isOnline()
                || !original.actor().equals(player.getUniqueId())) {
            return DeliveryResult.PENDING;
        }

        AuctionTransaction transaction = transactions.getOrDefault(
                original.transactionId(), original
        );

        boolean deliverable =
                transaction.type() == TransactionType.BUY
                        ? transaction.state() == TransactionState.PAID
                        || transaction.state() == TransactionState.DELIVERY_STARTED
                        || transaction.state() == TransactionState.DELIVERED
                        : transaction.type() == TransactionType.RETURN
                        && (transaction.state() == TransactionState.LISTING_REMOVED
                        || transaction.state() == TransactionState.DELIVERY_STARTED
                        || transaction.state() == TransactionState.DELIVERED);

        if (!deliverable) return DeliveryResult.PENDING;

        if (transaction.state() == TransactionState.DELIVERED) {
            finalizeDeliveredTransaction(transaction, player);
            return DeliveryResult.COMPLETED;
        }

        UUID existingMarker = markerUuid(player, deliveryTransactionKey);

        if (existingMarker == null
                && inventoryFullFor(player.getInventory(), transaction.listing().item())) {
            return DeliveryResult.INVENTORY_FULL;
        }

        if (transaction.state() != TransactionState.DELIVERY_STARTED) {
            AuctionTransaction deliveryStarted = advanceTransaction(
                    transaction, TransactionState.DELIVERY_STARTED
            );
            if (deliveryStarted == null) return DeliveryResult.PENDING;
            transaction = deliveryStarted;
        }

        existingMarker = markerUuid(player, deliveryTransactionKey);
        if (existingMarker != null
                && !existingMarker.equals(transaction.transactionId())) {
            AuctionTransaction active = transactions.get(existingMarker);
            if (active != null && active.actor().equals(player.getUniqueId())) {
                return DeliveryResult.PENDING;
            }
            clearDeliveryMarker(player, existingMarker, false);
            existingMarker = null;
        }

        if (existingMarker == null) {
            ItemStack[] before = cloneStorageContents(player.getInventory());

            player.getPersistentDataContainer().set(
                    deliveryTransactionKey,
                    PersistentDataType.STRING,
                    transaction.transactionId().toString()
            );

            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(
                    transaction.listing().item()
            );

            if (!leftovers.isEmpty()) {
                player.getInventory().setStorageContents(before);
                clearDeliveryMarker(player, transaction.transactionId(), false);
                return DeliveryResult.INVENTORY_FULL;
            }
        }

        /*
         * Player PDC marker + inventory are one playerdata write. A loaded
         * marker proves the item was already delivered; a same-runtime failed
         * write simply re-saves the existing item/marker pair here.
         */
        if (!savePlayerData(
                player, "persist Auction House delivery " + transaction.transactionId()
        )) {
            return DeliveryResult.PENDING;
        }

        AuctionTransaction delivered = advanceTransaction(
                transaction, TransactionState.DELIVERED
        );

        if (delivered == null) {
            // Playerdata already proves delivery, so recovery will never add twice.
            return DeliveryResult.COMPLETED;
        }

        finalizeDeliveredTransaction(delivered, player);
        return DeliveryResult.COMPLETED;
    }

    private void finalizeDeliveredTransaction(
            AuctionTransaction transaction, Player actor
    ) {
        if (transaction == null || transaction.state() != TransactionState.DELIVERED) {
            return;
        }

        if (transaction.type() == TransactionType.BUY
                && !ensureSaleReceipt(transaction)) {
            return;
        }

        boolean deleted = deleteTransaction(transaction);

        if (actor != null && actor.isOnline()) {
            clearDeliveryMarker(actor, transaction.transactionId(), true);
        }

        if (!deleted) {
            return;
        }

        switch (transaction.type()) {
            case BUY -> {
                if (actor != null && actor.isOnline()) {
                    notifyBuyer(actor, transaction.listing());
                }

                Player seller = Bukkit.getPlayer(
                        transaction.listing().owner()
                );
                if (seller != null && seller.isOnline()) {
                    deliverLiveSaleNotice(seller);
                }

                auditPurchase(
                        transaction.actor(),
                        transaction.listing(),
                        transaction.transactionId()
                );
            }
            case RETURN -> {
                if (actor != null) {
                    auditCancel(actor, transaction.listing());
                }
            }
            case LIST -> {
                // LIST never uses delivery states.
            }
        }
    }

    private boolean ensureSaleReceipt(
            AuctionTransaction transaction
    ) {
        AuctionHouseListing listing = transaction.listing();

        boolean saved = storage.recordSaleReceipt(
                transaction.transactionId(),
                listing.owner(),
                safeOutput(itemName(listing.item())),
                listing.priceCents()
        );

        if (!saved) {
            core.getLogger().severe(
                    "[AuctionHouse] Could not durably record completed sale "
                            + transaction.transactionId()
                            + "; transaction retained for retry"
            );
        }

        return saved;
    }

    private void recoverPayment(AuctionTransaction original) {
        if (original == null || original.type() != TransactionType.BUY
                || original.state() != TransactionState.PAYMENT_STARTED) {
            return;
        }

        AuctionTransaction transaction = transactions.getOrDefault(
                original.transactionId(), original
        );
        EconomyService economy = nativeEconomy();

        if (economy == null) {
            quarantineTransaction(
                    transaction, "native-economy-unavailable-during-payment-recovery"
            );
            return;
        }

        MarketTransactionDurability durability =
                economy.marketTransactionDurability(transaction.transactionId());

        if (storage.listingExists(transaction.listing().id())) {
            if (durability == MarketTransactionDurability.UNKNOWN) {
                AuctionTransaction aborted = advanceTransaction(
                        transaction, TransactionState.ABORTED
                );
                if (aborted != null) {
                    if (!listings.containsKey(aborted.listing().id())) {
                        addInMemory(aborted.listing());
                    }
                    deleteTransaction(aborted);
                }
            } else {
                quarantineTransaction(
                        transaction, "payment-recognized-while-listing-restored"
                );
            }
            return;
        }

        if (durability == MarketTransactionDurability.COMMITTED) {
            advanceTransaction(transaction, TransactionState.PAID);
            return;
        }

        if (durability == MarketTransactionDurability.PENDING) {
            economy.flushIfDirty();
            schedulePaymentRecovery();
            return;
        }

        MarketTransferStatus result = economy.durableMarketTransfer(
                transaction.transactionId(),
                transaction.actor(),
                transaction.listing().owner(),
                transaction.listing().priceCents(),
                0L
        );

        switch (result) {
            case SUCCESS, ALREADY_COMMITTED ->
                    advanceTransaction(transaction, TransactionState.PAID);
            case PERSISTENCE_PENDING -> {
                economy.flushIfDirty();
                schedulePaymentRecovery();
            }
            case INSUFFICIENT_FUNDS, RECIPIENT_BALANCE_LIMIT,
                    DISABLED, INVALID, BUSY -> abortUnpaidTransaction(transaction);
        }
    }

    private void schedulePaymentRecovery() {
        if (paymentRecoveryTask != null) {
            return;
        }

        paymentRecoveryTask =
                core.getServer()
                        .getScheduler()
                        .runTaskTimer(
                                core,
                                this::retryPaymentBarrierFast,
                                1L,
                                1L
                        );
    }

    private synchronized void retryPaymentBarrierFast() {
        List<AuctionTransaction> barriers =
                transactions.values()
                        .stream()
                        .filter(
                                transaction ->
                                        transaction.type()
                                                == TransactionType.BUY
                                                && transaction.state()
                                                == TransactionState.PAYMENT_STARTED
                        )
                        .toList();

        if (barriers.isEmpty()) {
            stopPaymentRecoveryTask();
            return;
        }

        for (AuctionTransaction barrier : barriers) {
            AuctionTransaction current =
                    transactions.get(
                            barrier.transactionId()
                    );

            if (current == null
                    || current.state()
                    != TransactionState.PAYMENT_STARTED) {
                continue;
            }

            recoverPayment(current);

            AuctionTransaction recovered =
                    transactions.get(
                            current.transactionId()
                    );

            if (recovered == null
                    || recovered.state()
                    != TransactionState.PAID) {
                continue;
            }

            Player buyer =
                    Bukkit.getPlayer(
                            recovered.actor()
                    );

            if (buyer != null
                    && buyer.isOnline()) {
                deliverTransaction(
                        recovered,
                        buyer
                );
            }
        }

        if (!hasAmbiguousPaymentBarrier()) {
            stopPaymentRecoveryTask();
        }
    }

    private void stopPaymentRecoveryTask() {
        BukkitTask task =
                paymentRecoveryTask;

        if (task != null) {
            task.cancel();
            paymentRecoveryTask = null;
        }
    }

    private void normalizeMultiplePaymentBarriers() {
        List<AuctionTransaction> barriers = transactions.values().stream()
                .filter(transaction -> transaction.type() == TransactionType.BUY
                        && transaction.state() == TransactionState.PAYMENT_STARTED)
                .toList();

        if (barriers.size() <= 1) return;

        EconomyService economy = nativeEconomy();
        if (economy == null) {
            for (AuctionTransaction barrier : barriers) {
                quarantineTransaction(
                        barrier, "multiple-payment-barriers-without-native-economy"
                );
            }
            return;
        }

        for (AuctionTransaction barrier : barriers) {
            if (economy.marketTransactionDurability(barrier.transactionId())
                    == MarketTransactionDurability.COMMITTED) {
                advanceTransaction(barrier, TransactionState.PAID);
            }
        }

        List<AuctionTransaction> unresolved = transactions.values().stream()
                .filter(transaction -> transaction.type() == TransactionType.BUY
                        && transaction.state() == TransactionState.PAYMENT_STARTED)
                .toList();

        if (unresolved.size() <= 1) return;

        for (AuctionTransaction barrier : unresolved) {
            quarantineTransaction(barrier, "multiple-unresolved-payment-barriers");
        }
    }

    private boolean hasAmbiguousPaymentBarrier() {
        return hasOtherAmbiguousPaymentBarrier(null);
    }

    private boolean hasOtherAmbiguousPaymentBarrier(UUID ignoredTransactionId) {
        for (AuctionTransaction transaction : transactions.values()) {
            if (transaction.type() != TransactionType.BUY
                    || transaction.state() != TransactionState.PAYMENT_STARTED) {
                continue;
            }

            if (ignoredTransactionId == null
                    || !ignoredTransactionId.equals(transaction.transactionId())) {
                return true;
            }
        }
        return false;
    }

    private AuctionTransaction advanceTransaction(
            AuctionTransaction transaction, TransactionState state
    ) {
        if (transaction == null || state == null) return null;
        AuctionTransaction updated = transaction.withState(state);

        if (!transactionStorage.save(updated)) {
            core.getLogger().severe(
                    "[AuctionHouse] Could not advance transaction "
                            + transaction.transactionId() + " from "
                            + transaction.state() + " to " + state
            );
            return null;
        }

        transactions.put(updated.transactionId(), updated);
        if (state == TransactionState.QUARANTINED) {
            runtimeQuarantinedTransactions.add(updated.transactionId());
        }
        return updated;
    }

    private boolean deleteTransaction(AuctionTransaction transaction) {
        if (transaction == null) return false;
        if (!transactionStorage.delete(transaction.transactionId())) return false;
        transactions.remove(transaction.transactionId());
        runtimeQuarantinedTransactions.remove(transaction.transactionId());
        return true;
    }

    private void quarantineTransaction(AuctionTransaction transaction, String reason) {
        if (transaction == null) return;

        runtimeQuarantinedTransactions.add(transaction.transactionId());
        AuctionTransaction quarantined = transaction.withState(TransactionState.QUARANTINED);

        if (transactionStorage.save(quarantined)) {
            transactions.put(quarantined.transactionId(), quarantined);
        }

        core.getLogger().severe(
                "[AuctionHouse] QUARANTINED v2 transaction "
                        + transaction.transactionId()
                        + " | type=" + transaction.type()
                        + " | state=" + transaction.state()
                        + " | listing=" + transaction.listing().id()
                        + " | reason=" + reason
        );
    }

    private EconomyService nativeEconomy() {
        return EconomyModule.economyService();
    }


    private boolean savePlayerData(Player player, String operation) {
        if (player == null) return false;
        try {
            player.updateInventory();
            player.saveData();
            return true;
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "[AuctionHouse] Could not durably save playerdata for "
                            + player.getUniqueId() + " | operation=" + operation,
                    exception
            );
            return false;
        }
    }

    private UUID markerUuid(Player player, NamespacedKey key) {
        String raw = markerString(player, key);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String markerString(Player player, NamespacedKey key) {
        if (player == null || key == null) return null;
        return player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private void clearCreateMarker(Player player, UUID expected, boolean persist) {
        if (player == null) return;
        UUID current = markerUuid(player, createTransactionKey);
        if (expected != null && current != null && !expected.equals(current)) return;

        PersistentDataContainer data = player.getPersistentDataContainer();
        data.remove(createTransactionKey);
        data.remove(createPhaseKey);

        if (persist) savePlayerData(player, "clear Auction House create marker");
    }

    private void clearDeliveryMarker(Player player, UUID expected, boolean persist) {
        if (player == null) return;
        UUID current = markerUuid(player, deliveryTransactionKey);
        if (expected != null && current != null && !expected.equals(current)) return;

        player.getPersistentDataContainer().remove(deliveryTransactionKey);
        if (persist) savePlayerData(player, "clear Auction House delivery marker");
    }

    private void clearOrphanPlayerMarkers(Player player) {
        boolean changed = false;

        UUID create = markerUuid(player, createTransactionKey);
        if (create != null && !transactions.containsKey(create)) {
            player.getPersistentDataContainer().remove(createTransactionKey);
            player.getPersistentDataContainer().remove(createPhaseKey);
            changed = true;
        }

        UUID delivery = markerUuid(player, deliveryTransactionKey);
        if (delivery != null && !transactions.containsKey(delivery)) {
            player.getPersistentDataContainer().remove(deliveryTransactionKey);
            changed = true;
        }

        if (changed) {
            savePlayerData(player, "clear orphan Auction House transaction markers");
        }
    }

    private String publicIdentity(
            UUID playerId,
            String fallback
    ) {
        if (playerId == null) {
            String safeFallback =
                    safeOutput(fallback);

            return safeFallback.isBlank()
                    ? "Unknown"
                    : safeFallback;
        }

        OfflinePlayer player =
                Bukkit.getOfflinePlayer(
                        playerId
                );
        String display =
                safeOutput(
                        DisplayNames.commandDisplayName(
                                player
                        )
                );

        if (!display.isBlank()) {
            return display;
        }

        String safeFallback =
                safeOutput(fallback);

        return safeFallback.isBlank()
                ? "Unknown"
                : safeFallback;
    }

    private String shortId(UUID id) {
        return id == null
                ? "UNKNOWN"
                : id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private long configuredPriceCents(String path, String fallback) {
        Object raw = config.get(path);

        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (Double.isFinite(value) && value > 0.0D) {
                long parsed = MoneyFormatter.parsePositiveCents(
                        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
                );
                if (parsed > 0L) return parsed;
            }
        } else if (raw != null) {
            long parsed = MoneyFormatter.parsePositiveCents(String.valueOf(raw));
            if (parsed > 0L) return parsed;
        }

        return Math.max(1L, MoneyFormatter.parsePositiveCents(fallback));
    }

    public long parsePriceCents(String raw) {
        return MoneyFormatter.parsePositiveCents(raw);
    }

    public String format(long cents) {
        return MoneyFormatter.moneyFromCents(cents);
    }

    public ItemStack previewHeldItem(Player player) {
        ItemStack held = cleanedHeldItem(player);
        return held == null ? null : held.clone();
    }

    public String itemName(ItemStack item) {
        if (item == null || item.getType().isAir()) return "Item";

        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component displayName = meta.displayName();
            if (displayName != null) {
                String display = PlainTextComponentSerializer.plainText()
                        .serialize(displayName)
                        .replace('\n', ' ')
                        .replace('\r', ' ')
                        .trim();
                if (!display.isBlank()) return safeOutput(display);
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

    public String sellerDisplayName(AuctionHouseListing listing) {
        if (listing == null) return "Unknown";

        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.owner());
        String display = TextColor.strip(DisplayNames.displayName(seller));
        if (display.isBlank()) display = TextColor.strip(listing.ownerName());
        return display.isBlank() ? "Unknown" : display;
    }

    public long unitPriceCents(AuctionHouseListing listing) {
        if (listing == null || listing.priceCents() <= 0L) return 0L;
        return Math.max(
                1L,
                Math.ceilDiv(listing.priceCents(), Math.max(1, listing.amount()))
        );
    }

    public long worthCents(ItemStack item) {
        if (item == null || item.getType().isAir()
                || !config.getBoolean("worth.show-reference", true)) {
            return 0L;
        }

        SellService current = currentSellService();
        if (current == null) return 0L;

        try {
            return Math.max(0L, current.visualWorthCents(null, cleanItem(item)));
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.FINE, "Could not resolve Auction House worth reference", exception
            );
            return 0L;
        }
    }

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
            ItemStack cleaned =
                    cleanItem(item);
            long directWorth =
                    Math.max(
                            0L,
                            current.stackWorthCents(
                                    player,
                                    cleaned
                            )
                    );
            long shulkerWorth =
                    shulkerServerSellCents(
                            player,
                            cleaned,
                            current
                    );

            return Math.max(
                    directWorth,
                    shulkerWorth
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

    private long shulkerServerSellCents(
            Player player,
            ItemStack item,
            SellService sell
    ) {
        ItemMeta meta =
                item.getItemMeta();

        if (!(meta instanceof BlockStateMeta state)
                || !(state.getBlockState()
                instanceof ShulkerBox shulker)) {
            return 0L;
        }

        long contentsWorth = 0L;

        for (ItemStack content
                : shulker.getSnapshotInventory()
                .getContents()) {
            if (content == null
                    || content.getType().isAir()) {
                continue;
            }

            long worth =
                    Math.max(
                            0L,
                            sell.stackWorthCents(
                                    player,
                                    cleanItem(content)
                            )
                    );
            contentsWorth =
                    safeAdd(
                            contentsWorth,
                            worth
                    );
        }

        ItemStack emptyShell =
                item.clone();
        ItemMeta shellMeta =
                emptyShell.getItemMeta();

        if (shellMeta
                instanceof BlockStateMeta shellState
                && shellState.getBlockState()
                instanceof ShulkerBox emptyShulker) {
            emptyShulker.getSnapshotInventory()
                    .clear();
            shellState.setBlockState(
                    emptyShulker
            );
            emptyShell.setItemMeta(
                    shellState
            );
        }

        long shellWorth =
                Math.max(
                        0L,
                        sell.stackWorthCents(
                                player,
                                emptyShell
                        )
                );

        return safeAdd(
                shellWorth,
                contentsWorth
        );
    }

    public long minimumListingPriceCents(Player player, ItemStack item) {
        long configuredMinimum = minPriceCents();
        if (!config.getBoolean("listing.enforce-server-sell-floor", true)) {
            return configuredMinimum;
        }
        return Math.max(configuredMinimum, serverSellCents(player, item));
    }

    public long worthCents(AuctionHouseListing listing) {
        if (listing == null || !config.getBoolean("worth.show-reference", true)) return 0L;

        long now = System.currentTimeMillis();
        long cacheMillis = Math.clamp(
                config.getLong("worth.cache-millis", DEFAULT_WORTH_CACHE_MILLIS),
                250L, 60_000L
        );

        WorthSnapshot cached = worthCache.get(listing.id());
        if (cached != null && now - cached.createdAt() <= cacheMillis) {
            return cached.cents();
        }

        long cents = worthCents(listing.item());
        worthCache.put(listing.id(), new WorthSnapshot(cents, now));
        return cents;
    }

    public boolean isExpired(AuctionHouseListing listing) {
        return listing == null || expiredAt(listing, System.currentTimeMillis());
    }

    private boolean expiredAt(AuctionHouseListing listing, long now) {
        return now >= expiresAt(listing);
    }

    private long expiresAt(AuctionHouseListing listing) {
        return safeAdd(listing.createdAt(), listingLifetimeMillis());
    }

    public String expiryText(AuctionHouseListing listing) {
        if (listing == null) return "Expired";

        long remaining = expiresAt(listing) - System.currentTimeMillis();
        if (remaining <= 0L) return "Expired";

        long days = TimeUnit.MILLISECONDS.toDays(remaining);
        remaining -= TimeUnit.DAYS.toMillis(days);
        long hours = TimeUnit.MILLISECONDS.toHours(remaining);
        remaining -= TimeUnit.HOURS.toMillis(hours);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(remaining);

        if (days > 0L) return days + "d " + hours + "h";
        if (hours > 0L) return hours + "h " + minutes + "m";
        return Math.max(1L, minutes) + "m";
    }

    public String sanitizeSearchQuery(String query) {
        if (query == null || query.isBlank()) return "";

        String plain = TextColor.strip(query);
        StringBuilder output = new StringBuilder(
                Math.min(plain.length(), maxSearchLength())
        );
        boolean pendingSpace = false;

        for (int index = 0; index < plain.length(); index++) {
            char character = plain.charAt(index);
            if (Character.isISOControl(character) || Character.isWhitespace(character)) {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace && !output.isEmpty()) output.append(' ');
            output.append(character);
            pendingSpace = false;
            if (output.length() >= maxSearchLength()) break;
        }
        return output.toString().trim();
    }

    public boolean searchQueryTooLong(String query) {
        if (query == null) return false;

        String plain = TextColor.strip(query);
        int visible = 0;
        boolean pendingSpace = false;

        for (int index = 0; index < plain.length(); index++) {
            char character = plain.charAt(index);
            if (Character.isISOControl(character) || Character.isWhitespace(character)) {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace && visible > 0) visible++;
            visible++;
            pendingSpace = false;
            if (visible > maxSearchLength()) return true;
        }
        return false;
    }

    public String text(String path, String fallback, String... replacements) {
        String value = config.getString(path, fallback);
        if (replacements == null) return value;

        for (int index = 0; index + 1 < replacements.length; index += 2) {
            String key = replacements[index];
            String replacement = replacements[index + 1];
            if (key != null && replacement != null) {
                value = value.replace(key, replacement);
            }
        }
        return value;
    }

    private CreateOutcome outcome(CreateResult result) {
        return new CreateOutcome(result, null);
    }

    private CreateResult validateListingItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.getType().isItem()) {
            return CreateResult.NO_ITEM;
        }
        if (blockedMaterials.contains(item.getType())) return CreateResult.BLOCKED_ITEM;
        if (config.getBoolean("listing.reject-overstacked", true)
                && item.getAmount() > item.getMaxStackSize()) {
            return CreateResult.BLOCKED_ITEM;
        }
        try {
            if (config.getBoolean(
                    "listing.block-filled-containers",
                    true
            )
                    && hasContainerContents(item)) {
                return CreateResult.FILLED_CONTAINER;
            }

            CreateResult shulkerContents =
                    validateAllowedShulkerContents(item);
            if (shulkerContents
                    != CreateResult.SUCCESS) {
                return shulkerContents;
            }
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Rejected malformed auction container item",
                    exception
            );
            return CreateResult.BLOCKED_ITEM;
        }

        int maximumBytes = maximumListingItemBytes();

        try {
            if (item.serializeAsBytes().length > maximumBytes) {
                return CreateResult.OVERSIZED_ITEM;
            }
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.WARNING, "Could not serialize a proposed auction item", exception
            );
            return CreateResult.OVERSIZED_ITEM;
        }

        return CreateResult.SUCCESS;
    }

    private boolean hasContainerContents(ItemStack item) {
        ItemMeta meta = item.getItemMeta();

        if (meta instanceof BundleMeta bundle
                && !bundle.getItems().isEmpty()) {
            return true;
        }

        if (!(meta instanceof BlockStateMeta state)) {
            return false;
        }

        var blockState =
                state.getBlockState();

        if (!(blockState
                instanceof TileStateInventoryHolder inventoryHolder)
                || !snapshotHasContents(
                inventoryHolder
        )) {
            return false;
        }

        return !(blockState instanceof ShulkerBox)
                || !config.getBoolean(
                "listing.allow-filled-shulkers",
                true
        );
    }

    private CreateResult validateAllowedShulkerContents(
            ItemStack item
    ) {
        if (!config.getBoolean(
                "listing.allow-filled-shulkers",
                true
        )) {
            return CreateResult.SUCCESS;
        }

        ItemMeta meta =
                item.getItemMeta();

        if (!(meta instanceof BlockStateMeta state)
                || !(state.getBlockState()
                instanceof ShulkerBox shulker)) {
            return CreateResult.SUCCESS;
        }

        for (ItemStack content
                : shulker.getSnapshotInventory()
                .getContents()) {
            if (content == null
                    || content.getType().isAir()) {
                continue;
            }

            if (blockedMaterials.contains(
                    content.getType()
            )) {
                return CreateResult.BLOCKED_ITEM;
            }

            if (config.getBoolean(
                    "listing.reject-overstacked",
                    true
            )
                    && content.getAmount()
                    > content.getMaxStackSize()) {
                return CreateResult.BLOCKED_ITEM;
            }

            if (hasAnyNestedContainerContents(
                    content
            )) {
                return CreateResult.FILLED_CONTAINER;
            }
        }

        return CreateResult.SUCCESS;
    }

    private boolean hasAnyNestedContainerContents(
            ItemStack item
    ) {
        ItemMeta meta =
                item.getItemMeta();

        if (meta instanceof BundleMeta bundle
                && !bundle.getItems().isEmpty()) {
            return true;
        }

        if (!(meta instanceof BlockStateMeta state)
                || !(state.getBlockState()
                instanceof TileStateInventoryHolder inventoryHolder)) {
            return false;
        }

        return snapshotHasContents(
                inventoryHolder
        );
    }

    private boolean snapshotHasContents(
            TileStateInventoryHolder holder
    ) {
        for (ItemStack content
                : holder.getSnapshotInventory()
                .getContents()) {
            if (content != null
                    && !content.getType().isAir()) {
                return true;
            }
        }

        return false;
    }

    private int maximumListingItemBytes() {
        return Math.clamp(
                config.getInt("listing.max-item-nbt-bytes", 262_144),
                16_384,
                4_194_304
        );
    }

    private AuctionHouseListing normalizedLoadedListing(AuctionHouseListing listing) {
        String publicOwnerName =
                publicIdentity(
                        listing.owner(),
                        listing.ownerName()
                );

        AuctionHouseListing normalized =
                new AuctionHouseListing(
                        listing.id(),
                        listing.owner(),
                        publicOwnerName,
                        cleanItem(listing.item()),
                        listing.priceCents(),
                        listing.createdAt()
                );

        if (!publicOwnerName.equals(
                listing.ownerName()
        ) && storage.listingSaveFailed(
                normalized
        )) {
            core.getLogger().warning(
                    "[AuctionHouse] Could not refresh public seller identity for listing "
                            + listing.id()
            );
        }

        return normalized;
    }

    private ItemStack cleanedHeldItem(Player player) {
        return player == null ? null : cleanItem(player.getInventory().getItemInMainHand());
    }

    private SellService currentSellService() {
        SellService current = sellService;
        if (current == null) {
            current = SellModule.sellService();
            sellService = current;
        }
        return current;
    }

    private ItemStack cleanItem(ItemStack raw) {
        if (raw == null || raw.getType().isAir()) return raw;
        SellService current = currentSellService();
        return current == null ? raw.clone() : current.stripWorthLore(raw);
    }

    private boolean differentIgnoringAmount(ItemStack left, ItemStack right) {
        if (left == null || right == null) return left != right;
        ItemStack a = left.clone();
        ItemStack b = right.clone();
        a.setAmount(1);
        b.setAmount(1);
        return !a.isSimilar(b);
    }

    private long listingLifetimeMillis() {
        return LISTING_LIFETIME_MILLIS;
    }


    private Comparator<AuctionHouseListing> comparator(SortMode mode) {
        return switch (mode) {
            case LOWEST_PRICE -> Comparator
                    .comparingLong(AuctionHouseListing::priceCents)
                    .thenComparingLong(AuctionHouseListing::createdAt)
                    .thenComparing(listing -> listing.id().toString());
            case LOWEST_UNIT_PRICE -> Comparator
                    .comparingLong(this::unitPriceCents)
                    .thenComparingLong(AuctionHouseListing::priceCents)
                    .thenComparingLong(AuctionHouseListing::createdAt)
                    .thenComparing(listing -> listing.id().toString());
            case HIGHEST_PRICE -> Comparator
                    .comparingLong(AuctionHouseListing::priceCents)
                    .reversed()
                    .thenComparingLong(AuctionHouseListing::createdAt)
                    .thenComparing(listing -> listing.id().toString());
            case RECENTLY_LISTED -> Comparator
                    .comparingLong(AuctionHouseListing::createdAt)
                    .reversed()
                    .thenComparing(listing -> listing.id().toString());
        };
    }

    private void addInMemory(AuctionHouseListing listing) {
        listings.put(listing.id(), listing);
        ownerIndex.computeIfAbsent(listing.owner(), ignored -> new LinkedHashSet<>())
                .add(listing.id());
        materialIndex.computeIfAbsent(listing.material(), ignored -> new LinkedHashSet<>())
                .add(listing.id());
        categoryIndex.computeIfAbsent(
                FilterMode.classify(listing.material()), ignored -> new LinkedHashSet<>()
        ).add(listing.id());
        searchIndex.put(listing.id(), buildSearchDocument(listing));
        worthCache.remove(listing.id());
        invalidateSearchCache();

        AuctionHouseDatabaseMirror mirror = databaseMirror;
        if (mirror != null
                && !restrictedListings.contains(listing.id())) {
            mirror.upsert(listing, LISTING_LIFETIME_MILLIS);
        }
    }

    private void removeInMemory(AuctionHouseListing listing) {
        listings.remove(listing.id());
        restrictedListings.remove(listing.id());
        searchIndex.remove(listing.id());
        worthCache.remove(listing.id());
        removeIndexed(ownerIndex, listing.owner(), listing.id());
        removeIndexed(materialIndex, listing.material(), listing.id());
        removeIndexed(
                categoryIndex, FilterMode.classify(listing.material()), listing.id()
        );
        invalidateSearchCache();

        AuctionHouseDatabaseMirror mirror = databaseMirror;
        if (mirror != null) mirror.delete(listing.id());
    }

    private <K> void removeIndexed(
            Map<K, ? extends Set<UUID>> index, K key, UUID listingId
    ) {
        Set<UUID> ids = index.get(key);
        if (ids == null) return;
        ids.remove(listingId);
        if (ids.isEmpty()) index.remove(key);
    }

    private void invalidateSearchCache() {
        listingGeneration++;
        searchCache.clear();
    }

    private SearchDocument buildSearchDocument(AuctionHouseListing listing) {
        ItemStack item = listing.item();
        StringBuilder builder = new StringBuilder();
        appendSearch(builder, item.getType().name());
        appendSearch(builder, itemName(item));

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                Component displayName = meta.displayName();
                if (displayName != null) {
                    appendSearch(
                            builder,
                            PlainTextComponentSerializer.plainText().serialize(displayName)
                    );
                }
            }

            if (meta.hasLore()) {
                List<Component> loreLines = meta.lore();
                if (loreLines != null) {
                    for (Component lore : loreLines) {
                        appendSearch(
                                builder,
                                PlainTextComponentSerializer.plainText().serialize(lore)
                        );
                    }
                }
            }

            if (meta.hasEnchants()) {
                appendEnchantments(builder, meta.getEnchants().keySet());
            }

            if (meta instanceof EnchantmentStorageMeta stored
                    && stored.hasStoredEnchants()) {
                appendEnchantments(builder, stored.getStoredEnchants().keySet());
            }
        }

        return new SearchDocument(builder.toString());
    }

    private void appendEnchantments(
            StringBuilder builder, Set<Enchantment> enchantments
    ) {
        for (Enchantment enchantment : enchantments) {
            appendSearch(builder, enchantment.getKey().getKey());
        }
    }

    private void appendSearch(StringBuilder builder, String value) {
        String normalized = normalizeToken(value);
        if (normalized.isBlank()) return;
        if (!builder.isEmpty()) builder.append(' ');
        builder.append(normalized);
    }

    private List<String> searchTokens(String query) {
        String sanitized = sanitizeSearchQuery(query);
        if (sanitized.isBlank()) return List.of();

        List<String> tokens = new ArrayList<>();
        for (String raw : sanitized.split("\\s+")) {
            String normalized = normalizeToken(raw);
            if (normalized.isBlank()) continue;

            List<String> alias = searchAliases.get(normalized);
            if (alias == null || alias.isEmpty()) tokens.add(normalized);
            else tokens.addAll(alias);
        }
        return List.copyOf(tokens);
    }

    private String normalizeToken(String value) {
        if (value == null || value.isBlank()) return "";

        String lower = TextColor.strip(value).toLowerCase(Locale.ROOT);
        StringBuilder output = new StringBuilder(lower.length());
        for (int index = 0; index < lower.length(); index++) {
            char character = lower.charAt(index);
            if (Character.isLetterOrDigit(character)) output.append(character);
        }
        return output.toString();
    }

    private Map<String, List<String>> loadSearchAliases() {
        ConfigurationSection section = config.getConfigurationSection("search-aliases");
        if (section == null) return Map.of();

        Map<String, List<String>> result = new HashMap<>();
        for (String rawAlias : section.getKeys(false)) {
            String alias = normalizeToken(rawAlias);
            String target = config.getString("search-aliases." + rawAlias, rawAlias);
            if (alias.isBlank() || target == null || target.isBlank()) continue;

            List<String> targetTokens = new ArrayList<>();
            for (String rawTarget : target.split("\\s+")) {
                String normalized = normalizeToken(rawTarget);
                if (!normalized.isBlank()) targetTokens.add(normalized);
            }
            if (!targetTokens.isEmpty()) result.put(alias, List.copyOf(targetTokens));
        }
        return Map.copyOf(result);
    }

    private Set<Material> loadBlockedMaterials() {
        Set<Material> result = EnumSet.of(
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

        for (String raw : config.getStringList("listing.blocked-materials")) {
            if (raw == null || raw.isBlank()) continue;
            Material material = Material.matchMaterial(raw.trim());
            if (material == null) {
                core.getLogger().warning(
                        "[AuctionHouse] Unknown blocked material: " + raw
                );
                continue;
            }
            result.add(material);
        }
        return Set.copyOf(result);
    }

    private boolean inventoryFullFor(PlayerInventory inventory, ItemStack item) {
        int remaining = item.getAmount();
        int maxStack = item.getMaxStackSize();

        for (ItemStack content : inventory.getStorageContents()) {
            if (content == null || content.getType().isAir()) {
                remaining -= maxStack;
            } else if (content.isSimilar(item)) {
                remaining -= Math.max(
                        0, content.getMaxStackSize() - content.getAmount()
                );
            }
            if (remaining <= 0) return false;
        }
        return true;
    }

    private ItemStack[] cloneStorageContents(PlayerInventory inventory) {
        ItemStack[] contents = inventory.getStorageContents();
        ItemStack[] copy = new ItemStack[contents.length];

        for (int index = 0; index < contents.length; index++) {
            copy[index] = contents[index] == null ? null : contents[index].clone();
        }
        return copy;
    }

    /*
     * Legacy v1 recovery remains readable for pre-v1.0.82 files only. New
     * operations exclusively use transactions-v2.
     */

    private void recoverInterruptedPurchases() {
        for (PurchaseRecovery loaded : storage.loadRecoveries()) {
            PurchaseRecovery recovery =
                    normalizeLegacyRecoveryIdentity(
                            loaded
                    );

            if (!recovery.equals(loaded)
                    && !storage.saveRecovery(
                    recovery
            )) {
                core.getLogger().warning(
                        "[AuctionHouse] Could not refresh public identities for legacy recovery "
                                + recovery.transactionId()
                );
            }

            switch (recovery.state()) {
                case PREPARED -> {
                    if (!storage.listingExists(recovery.listing().id())
                            && storage.listingSaveFailed(recovery.listing())) {
                        quarantine(recovery);
                        continue;
                    }
                    if (storage.recoveryDeleteFailed(recovery.transactionId())) {
                        quarantine(recovery);
                    }
                }
                case DELIVERED -> {
                    if (storage.recoveryDeleteFailed(recovery.transactionId())) {
                        quarantine(recovery);
                    }
                }
                case PAYMENT_STARTED, PAID -> quarantine(recovery);
            }
        }

        if (!quarantinedRecoveries.isEmpty()) {
            core.getLogger().severe(
                    "[AuctionHouse] " + quarantinedRecoveries.size()
                            + " legacy interrupted transaction(s) require manual review "
                            + "in auctionhouse/recovery"
            );
        }
    }

    private PurchaseRecovery normalizeLegacyRecoveryIdentity(
            PurchaseRecovery recovery
    ) {
        AuctionHouseListing listing =
                recovery.listing();

        String sellerName =
                publicIdentity(
                        listing.owner(),
                        listing.ownerName()
                );
        String buyerName =
                publicIdentity(
                        recovery.buyer(),
                        recovery.buyerName()
                );

        if (sellerName.equals(
                listing.ownerName()
        ) && buyerName.equals(
                recovery.buyerName()
        )) {
            return recovery;
        }

        AuctionHouseListing normalizedListing =
                new AuctionHouseListing(
                        listing.id(),
                        listing.owner(),
                        sellerName,
                        listing.item(),
                        listing.priceCents(),
                        listing.createdAt()
                );

        return new PurchaseRecovery(
                recovery.transactionId(),
                recovery.state(),
                normalizedListing,
                recovery.buyer(),
                buyerName,
                recovery.createdAt()
        );
    }

    private void quarantine(PurchaseRecovery recovery) {
        if (recovery != null) {
            quarantinedRecoveries.put(recovery.transactionId(), recovery);
        }
    }

    private boolean isQuarantined(UUID listingId) {
        if (listingId == null) return false;

        for (PurchaseRecovery recovery : quarantinedRecoveries.values()) {
            if (recovery.listing().id().equals(listingId)) return true;
        }

        for (AuctionTransaction transaction : transactions.values()) {
            if ((transaction.state() == TransactionState.QUARANTINED
                    || runtimeQuarantinedTransactions.contains(transaction.transactionId()))
                    && transaction.listing().id().equals(listingId)) {
                return true;
            }
        }
        return false;
    }

    public void deliverPendingSaleNotice(Player seller) {
        deliverSaleReceipt(
                seller,
                true
        );
    }

    private void deliverLiveSaleNotice(
            Player seller
    ) {
        deliverSaleReceipt(
                seller,
                false
        );
    }

    private void deliverSaleReceipt(
            Player seller,
            boolean joinedAfterSale
    ) {
        if (seller == null || !seller.isOnline()) {
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
            message = joinedAfterSale
                    ? text(
                    "messages.sold-offline-single",
                    "&#bbbbbbWhile you were away, &#B078FF%item% &#bbbbbbsold for &#11fc7b+%price%",
                    "%item%", safeOutput(receipt.lastItem()),
                    "%price%", format(receipt.lastPriceCents())
            )
                    : text(
                    "messages.sold",
                    "&#bbbbbbSold &#B078FF%item% &#bbbbbbfor &#11fc7b+%price%",
                    "%item%", safeOutput(receipt.lastItem()),
                    "%price%", format(receipt.lastPriceCents())
            );
        } else {
            message = joinedAfterSale
                    ? text(
                    "messages.sold-offline-multiple",
                    "&#bbbbbbWhile you were away, &#D0AFFF%count% &#bbbbbbauction listings sold for &#11fc7b+%price%",
                    "%count%", String.valueOf(receipt.count()),
                    "%price%", format(receipt.totalCents())
            )
                    : text(
                    "messages.sold-multiple",
                    "&#bbbbbbSold &#D0AFFF%count% &#bbbbbbauction listings for &#11fc7b+%price%",
                    "%count%", String.valueOf(receipt.count()),
                    "%price%", format(receipt.totalCents())
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

    private void notifyBuyer(
            Player buyer,
            AuctionHouseListing listing
    ) {
        buyer.sendMessage(
                TextColor.color(
                        text(
                                "messages.purchased",
                                "&#bbbbbbPurchased &#B078FF%item% &#bbbbbbfor &#11fc7b%price%",
                                "%item%", safeOutput(itemName(listing.item())),
                                "%price%", format(listing.priceCents())
                        )
                )
        );
        SoundService.economyPay(buyer, core);
    }

    private void auditList(Player seller, AuctionHouseListing listing) {
        if (auditDisabled()) return;
        core.getLogger().info(
                "[AuctionHouse] LIST listing=" + listing.id()
                        + " seller=" + seller.getUniqueId()
                        + " item=" + listing.material()
                        + " amount=" + listing.amount()
                        + " price-cents=" + listing.priceCents()
        );
    }

    private void auditCancel(Player seller, AuctionHouseListing listing) {
        if (auditDisabled()) return;
        core.getLogger().info(
                "[AuctionHouse] CANCEL listing=" + listing.id()
                        + " seller=" + seller.getUniqueId()
                        + " expired=" + isExpired(listing)
        );
    }

    private void auditPurchase(
            UUID buyerId, AuctionHouseListing listing, UUID transactionId
    ) {
        if (auditDisabled()) return;
        core.getLogger().info(
                "[AuctionHouse] BUY transaction=" + transactionId
                        + " listing=" + listing.id()
                        + " buyer=" + buyerId
                        + " seller=" + listing.owner()
                        + " item=" + listing.material()
                        + " amount=" + listing.amount()
                        + " price-cents=" + listing.priceCents()
        );
    }

    private boolean auditDisabled() {
        return !config.getBoolean("audit.enabled", true);
    }

    private void startDatabaseMirror() {
        AuctionHouseDatabaseMirror mirror = new AuctionHouseDatabaseMirror(core, config);
        databaseMirror = mirror;
        mirror.start();

        if (!mirror.enabled()) return;

        mirror.reconcile(publicMirrorListings(), LISTING_LIFETIME_MILLIS);

        long syncSeconds = Math.clamp(
                config.getLong("database.mirror.sync-seconds", 60L),
                15L, 900L
        );
        long syncTicks = syncSeconds * 20L;
        databaseMirrorTask = core.getServer().getScheduler().runTaskTimer(
                core,
                this::reconcileDatabaseMirror,
                syncTicks,
                syncTicks
        );
    }

    private synchronized void reconcileDatabaseMirror() {
        AuctionHouseDatabaseMirror mirror = databaseMirror;
        if (mirror == null || !mirror.enabled()) return;

        mirror.reconcile(publicMirrorListings(), LISTING_LIFETIME_MILLIS);
    }

    private synchronized List<AuctionHouseListing> publicMirrorListings() {
        if (restrictedListings.isEmpty()) {
            return List.copyOf(listings.values());
        }

        List<AuctionHouseListing> visible =
                new ArrayList<>(listings.size());

        for (AuctionHouseListing listing : listings.values()) {
            if (!restrictedListings.contains(listing.id())) {
                visible.add(listing);
            }
        }

        return List.copyOf(visible);
    }

    private void stopDatabaseMirror() {
        BukkitTask task = databaseMirrorTask;
        if (task != null) {
            task.cancel();
            databaseMirrorTask = null;
        }

        AuctionHouseDatabaseMirror mirror = databaseMirror;
        if (mirror != null) {
            databaseMirror = null;
            mirror.shutdown();
        }
    }

    private void ensureConfigFile() {
        if (configFile.isFile()) return;
        if (configFile.exists()) {
            throw new IllegalStateException("auctionhouse.yml is not a file");
        }

        try {
            core.saveResource("auctionhouse.yml", false);
        } catch (IllegalArgumentException exception) {
            try {
                if (!configFile.createNewFile()) {
                    throw new IOException("createNewFile returned false");
                }
            } catch (IOException ioException) {
                throw new IllegalStateException(
                        "Could not create auctionhouse.yml", ioException
                );
            }
        }
    }

    private long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long safeMultiply(long left, int right) {
        try {
            return Math.multiplyExact(left, (long) right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private String safeOutput(String value) {
        if (value == null) return "";
        return TextColor.strip(value)
                .replace('§', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
    }

    private record SearchDocument(String text) {
        private boolean matches(List<String> tokens) {
            if (tokens == null || tokens.isEmpty()) return true;
            for (String token : tokens) {
                if (!text.contains(token)) return false;
            }
            return true;
        }
    }

    private record SearchCacheKey(
            List<String> tokens, SortMode sortMode, FilterMode filterMode
    ) {
        private SearchCacheKey {
            tokens = tokens == null ? List.of() : List.copyOf(tokens);
        }
    }

    private record SearchSnapshot(
            long generation, long validUntilMillis, List<AuctionHouseListing> listings
    ) {}

    private record WorthSnapshot(long cents, long createdAt) {}
}
