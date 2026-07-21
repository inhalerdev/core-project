package net.mineacle.core.sell.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.sell.model.ItemValuation;
import net.mineacle.core.sell.model.MarketDefinition;
import net.mineacle.core.sell.model.SaleResult;
import net.mineacle.core.sell.model.SellHistoryEntry;
import net.mineacle.core.sell.model.SellQuote;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class SellService {

    private static final int MAX_CONTAINER_DEPTH = 3;

    private final Core core;

    private File sellFile;
    private FileConfiguration sellConfig;
    private MarketPricingService marketPricingService;

    private volatile Map<Material, MarketDefinition> definitions = Map.of();
    private volatile Map<String, String> categoryDisplayNames = Map.of();
    private volatile Map<Enchantment, Long> enchantValues = Map.of();
    private volatile Set<Material> blockedMaterials = Set.of();
    private volatile Set<Material> unsellableMaterials = Set.of();
    private volatile Set<Material> explicitlyPricedMaterials = Set.of();
    private volatile Map<String, Long> fallbackPrices = Map.of();
    private volatile Map<String, Double> categoryBuybackMultipliers = Map.of();
    private volatile Map<String, Double> categoryEnchantBuybackMultipliers = Map.of();
    private volatile Map<Material, Double> itemBuybackMultipliers = Map.of();
    private volatile Map<Material, Double> itemEnchantBuybackMultipliers = Map.of();

    private boolean started;
    private volatile boolean allowFallbackPrices;
    private volatile boolean allowFallbackItemsForSell;
    private volatile boolean denyCustomItems;
    private volatile boolean denyFilledContainers;
    private volatile boolean denyEnchantedItems;
    private volatile boolean enchantValuesEnabled;
    private volatile long defaultEnchantPerLevelCents;
    private volatile double treasureEnchantMultiplier;
    private volatile double unsafeEnchantMultiplier;
    private volatile boolean allowUnsafeEnchantLevels;
    private volatile double enchantedBookMultiplier;
    private volatile double appliedEnchantMultiplier;
    private volatile double fourEnchantPackageMultiplier;
    private volatile double sixEnchantPackageMultiplier;
    private volatile double eightEnchantPackageMultiplier;
    private volatile boolean durabilityEnabled;
    private volatile double minimumBaseDurabilityMultiplier;
    private volatile double minimumEnchantDurabilityMultiplier;
    private volatile double mendingMinimumBaseDurabilityMultiplier;
    private volatile double mendingMinimumEnchantDurabilityMultiplier;

    public SellService(Core core) {
        this.core = core;
        reload();
    }

    public synchronized void start() {
        started = true;

        if (marketPricingService != null) {
            marketPricingService.start();
        }
    }

    public synchronized void tick() {
        if (marketPricingService == null) {
            return;
        }

        marketPricingService.tick();

        if (marketPricingService.consumePriceChange()) {
            for (Player player
                    : core.getServer().getOnlinePlayers()) {
                player.updateInventory();
            }
        }
    }

    public synchronized void shutdown() {
        if (marketPricingService != null) {
            marketPricingService.shutdown();
        }

        save();
        started = false;
    }

    public synchronized void reload() {
        ensureSellFile();
        sellConfig = YamlConfiguration.loadConfiguration(sellFile);
        ensureDefaults();
        migrateLegacySettings();
        loadConfigurationSnapshot();

        if (marketPricingService == null) {
            marketPricingService = new MarketPricingService(
                    core,
                    sellConfig,
                    definitions
            );
        } else {
            marketPricingService.reload(
                    sellConfig,
                    definitions
            );
        }

        migrateLegacyHistory();
        save();

        if (started) {
            marketPricingService.start();
        }
    }

    public synchronized void save() {
        if (sellFile == null || sellConfig == null) {
            return;
        }

        try {
            atomicSave(sellConfig, sellFile);
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not save sell.yml",
                    exception
            );
        }
    }

    public String format(long cents) {
        return MoneyFormatter.moneyFromCents(cents);
    }

    public SaleResult sellInventory(
            UUID playerId,
            Inventory inventory
    ) {
        if (playerId == null || inventory == null) {
            return new SaleResult(
                    false,
                    0L,
                    0L,
                    List.of(),
                    "&cCould not process this sale"
            );
        }

        List<ItemStack> originalItems = new ArrayList<>();
        List<ItemStack> returnedItems = new ArrayList<>();
        Map<Material, SoldTotals> soldTotals =
                new EnumMap<>(Material.class);

        long totalCents = 0L;
        long totalAmount = 0L;

        for (ItemStack rawItem : inventory.getContents()) {
            ItemStack item = stripWorthLore(rawItem);

            if (item != null && !item.getType().isAir()) {
                originalItems.add(item.clone());
            }
        }

        for (ItemStack item : originalItems) {
            SellQuote quote = quote(playerId, item);

            if (!quote.sellable() || quote.totalCents() <= 0L) {
                returnedItems.add(item.clone());
                continue;
            }

            try {
                totalCents = Math.addExact(
                        totalCents,
                        quote.totalCents()
                );
                totalAmount = Math.addExact(
                        totalAmount,
                        item.getAmount()
                );
            } catch (ArithmeticException exception) {
                inventory.clear();

                return new SaleResult(
                        false,
                        0L,
                        0L,
                        List.copyOf(originalItems),
                        "&cThis sale is too large to process"
                );
            }

            SoldTotals totals = soldTotals.computeIfAbsent(
                    item.getType(),
                    ignored -> new SoldTotals()
            );
            totals.amount = safeAdd(
                    totals.amount,
                    item.getAmount()
            );
            totals.payoutCents = safeAdd(
                    totals.payoutCents,
                    quote.totalCents()
            );
        }

        /*
         * The custom Sell inventory must be emptied before its close event
         * finishes. Every unsold item is explicitly returned by the listener.
         */
        inventory.clear();

        if (totalCents <= 0L) {
            return new SaleResult(
                    false,
                    0L,
                    0L,
                    List.copyOf(returnedItems),
                    ""
            );
        }

        EconomyService economy = EconomyModule.economyService();

        if (economy == null
                || !economy.enabled()
                || !economy.tryGive(playerId, totalCents)) {
            return new SaleResult(
                    false,
                    0L,
                    0L,
                    List.copyOf(originalItems),
                    "&cCould not credit this sale — your items were returned"
            );
        }

        long now = System.currentTimeMillis();

        for (Map.Entry<Material, SoldTotals> entry
                : soldTotals.entrySet()) {
            SoldTotals totals = entry.getValue();

            marketPricingService.recordSale(
                    playerId,
                    entry.getKey(),
                    totals.amount,
                    totals.payoutCents,
                    now
            );
        }

        return new SaleResult(
                true,
                totalCents,
                totalAmount,
                List.copyOf(returnedItems),
                ""
        );
    }

    public SellQuote quote(UUID playerId, ItemStack rawItem) {
        ItemValuation valuation = appraise(
                playerId,
                rawItem,
                true
        );

        if (!valuation.sellable()
                || valuation.serverSellCents() <= 0L) {
            return SellQuote.unsellable(
                    valuation.material(),
                    valuation.amount(),
                    valuation.sellReason()
            );
        }

        return new SellQuote(
                true,
                "",
                valuation.material(),
                valuation.amount(),
                valuation.baseUnitCents(),
                valuation.marketMultiplier(),
                valuation.featuredMultiplier(),
                valuation.durabilityPercent(),
                valuation.baseDurabilityMultiplier(),
                valuation.enchantDurabilityMultiplier(),
                valuation.serverBaseCents(),
                valuation.serverEnchantCents(),
                valuation.serverSellCents()
        );
    }

    public SellQuote displayQuote(
            UUID playerId,
            ItemStack rawItem
    ) {
        ItemValuation valuation = appraise(
                playerId,
                rawItem,
                false
        );

        if (!valuation.sellable()
                || valuation.serverSellCents() <= 0L) {
            return SellQuote.unsellable(
                    valuation.material(),
                    valuation.amount(),
                    valuation.sellReason()
            );
        }

        return new SellQuote(
                true,
                "",
                valuation.material(),
                valuation.amount(),
                valuation.baseUnitCents(),
                valuation.marketMultiplier(),
                valuation.featuredMultiplier(),
                valuation.durabilityPercent(),
                valuation.baseDurabilityMultiplier(),
                valuation.enchantDurabilityMultiplier(),
                valuation.serverBaseCents(),
                valuation.serverEnchantCents(),
                valuation.serverSellCents()
        );
    }

    public ItemValuation appraise(
            UUID playerId,
            ItemStack rawItem
    ) {
        return appraise(
                playerId,
                rawItem,
                true
        );
    }

    public ItemValuation appraise(
            Player player,
            ItemStack rawItem
    ) {
        return appraise(
                player == null
                        ? null
                        : player.getUniqueId(),
                rawItem,
                true
        );
    }

    private ItemValuation appraise(
            UUID playerId,
            ItemStack rawItem,
            boolean enforceContainerRules
    ) {
        ItemStack item = stripWorthLore(rawItem);

        if (item == null || item.getType().isAir()) {
            return ItemValuation.unpriced(
                    Material.AIR,
                    1,
                    "empty"
            );
        }

        Material material = item.getType();
        int amount = Math.max(1, item.getAmount());
        MarketDefinition definition = definitions.get(material);

        if (definition == null || definition.baseCents() <= 0L) {
            return ItemValuation.unpriced(
                    material,
                    amount,
                    "no-price"
            );
        }

        double marketMultiplier =
                definition.marketEnabled()
                        ? marketPricingService.marketMultiplier(
                        material
                )
                        : 1.0D;
        double featuredMultiplier =
                definition.marketEnabled()
                        ? marketPricingService.featuredMultiplier(
                        material
                )
                        : 1.0D;
        double combinedMultiplier =
                definition.marketEnabled()
                        ? marketPricingService.combinedMultiplier(
                        material
                )
                        : 1.0D;
        long currentUnit = multiply(
                definition.baseCents(),
                combinedMultiplier
        );

        DurabilityValue durability = durability(item);
        long fullBaseStack = multiply(
                currentUnit,
                amount
        );
        long appraisedBase = multiply(
                fullBaseStack,
                durability.baseMultiplier()
        );
        long rawEnchant = multiply(
                enchantWorthCents(item),
                amount
        );
        long appraisedEnchant = multiply(
                rawEnchant,
                durability.enchantMultiplier()
        );
        long appraisedTotal = safeAdd(
                appraisedBase,
                appraisedEnchant
        );
        String category = definition.category();
        double baseBuyback = buybackMultiplier(
                material,
                category
        );
        double enchantBuyback = enchantBuybackMultiplier(
                material,
                category
        );
        String rejection = rejectionReason(
                item,
                enforceContainerRules
        );

        if (rejection == null
                && !allowFallbackItemsForSell
                && !explicitlyPricedMaterials.contains(material)) {
            rejection = "fallback-appraisal";
        }

        if (rejection == null
                && baseBuyback <= 0.0D
                && enchantBuyback <= 0.0D) {
            rejection = "player-market-only";
        }

        long serverBase = rejection == null
                ? multiply(appraisedBase, baseBuyback)
                : 0L;
        long serverEnchant = rejection == null
                ? multiply(appraisedEnchant, enchantBuyback)
                : 0L;
        long serverSell = rejection == null
                ? safeAdd(serverBase, serverEnchant)
                : 0L;
        boolean sellable = rejection == null
                && serverSell > 0L;

        return new ItemValuation(
                material,
                amount,
                appraisedTotal > 0L,
                explicitlyPricedMaterials.contains(material),
                sellable,
                rejection == null ? "" : rejection,
                category,
                Math.max(0L, currentUnit),
                marketMultiplier,
                featuredMultiplier,
                combinedMultiplier,
                durability.percent(),
                durability.mending(),
                durability.baseMultiplier(),
                durability.enchantMultiplier(),
                Math.max(0L, appraisedBase),
                Math.max(0L, appraisedEnchant),
                Math.max(0L, appraisedTotal),
                baseBuyback,
                enchantBuyback,
                Math.max(0L, serverBase),
                Math.max(0L, serverEnchant),
                Math.max(0L, serverSell)
        );
    }

    public long visualWorthCents(
            UUID playerId,
            ItemStack item
    ) {
        return visualWorthCents(playerId, item, 0);
    }

    public long visualUnitWorthCents(
            UUID playerId,
            ItemStack rawItem
    ) {
        if (rawItem == null || rawItem.getType().isAir()) {
            return 0L;
        }

        ItemStack one = stripWorthLore(rawItem);
        one.setAmount(1);
        return visualWorthCents(playerId, one, 0);
    }

    private long visualWorthCents(
            UUID playerId,
            ItemStack rawItem,
            int depth
    ) {
        if (rawItem == null
                || rawItem.getType().isAir()
                || depth > MAX_CONTAINER_DEPTH) {
            return 0L;
        }

        ItemStack item = stripWorthLore(rawItem);
        ItemValuation valuation = appraise(
                playerId,
                item,
                false
        );
        long total = valuation.appraisedTotalCents();
        ItemMeta meta = item.getItemMeta();

        if (meta instanceof BundleMeta bundleMeta) {
            for (ItemStack content : bundleMeta.getItems()) {
                total = safeAdd(
                        total,
                        visualWorthCents(
                                playerId,
                                content,
                                depth + 1
                        )
                );
            }
        }

        if (meta instanceof BlockStateMeta stateMeta
                && stateMeta.getBlockState()
                instanceof ShulkerBox shulkerBox) {
            for (ItemStack content
                    : shulkerBox.getInventory().getContents()) {
                total = safeAdd(
                        total,
                        visualWorthCents(
                                playerId,
                                content,
                                depth + 1
                        )
                );
            }
        }

        return Math.max(0L, total);
    }

    public long unitWorthCents(
            UUID playerId,
            Material material
    ) {
        MarketDefinition definition = definitions.get(material);

        if (definition == null || definition.baseCents() <= 0L) {
            return 0L;
        }

        double multiplier = definition.marketEnabled()
                ? marketPricingService.combinedMultiplier(material)
                : 1.0D;

        return Math.max(
                1L,
                multiply(definition.baseCents(), multiplier)
        );
    }

    public long unitWorthCents(
            Player player,
            Material material
    ) {
        return unitWorthCents(
                player == null ? null : player.getUniqueId(),
                material
        );
    }

    public boolean canSell(ItemStack item) {
        return quote(null, item).sellable();
    }

    public void applyWorthLore(
            Player player,
            Inventory inventory
    ) {
        if (player == null || inventory == null) {
            return;
        }

        UUID playerId = player.getUniqueId();

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack raw = inventory.getItem(slot);

            if (raw == null || raw.getType().isAir()) {
                continue;
            }

            ItemStack item = stripWorthLore(raw);
            long worth = visualWorthCents(playerId, item);

            if (worth <= 0L) {
                inventory.setItem(slot, item);
                continue;
            }

            ItemMeta meta = item.getItemMeta();

            if (meta == null) {
                continue;
            }

            List<String> lore = meta.hasLore()
                    && meta.getLore() != null
                    ? new ArrayList<>(meta.getLore())
                    : new ArrayList<>();
            lore.add(
                    0,
                    TextColor.color(
                            "&#bbbbbbWorth: &a" + format(worth)
                    )
            );
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }
    }

    public ItemStack stripWorthLore(ItemStack rawItem) {
        if (rawItem == null || rawItem.getType().isAir()) {
            return rawItem;
        }

        ItemStack item = rawItem.clone();
        ItemMeta meta = item.getItemMeta();

        if (meta == null
                || !meta.hasLore()
                || meta.getLore() == null) {
            return item;
        }

        List<String> cleaned = new ArrayList<>();

        for (String line : meta.getLore()) {
            if (!isWorthLine(line)) {
                cleaned.add(line);
            }
        }

        meta.setLore(cleaned.isEmpty() ? null : cleaned);
        item.setItemMeta(meta);
        return item;
    }

    public boolean hasInjectedWorthLore(ItemStack item) {
        if (item == null
                || item.getType().isAir()
                || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null
                || !meta.hasLore()
                || meta.getLore() == null) {
            return false;
        }

        return meta.getLore().stream().anyMatch(this::isWorthLine);
    }

    public long stackWorthCents(
            UUID playerId,
            ItemStack item
    ) {
        SellQuote quote = quote(playerId, item);
        return quote.sellable() ? quote.totalCents() : 0L;
    }

    public long stackWorthCents(
            Player player,
            ItemStack item
    ) {
        return stackWorthCents(
                player == null ? null : player.getUniqueId(),
                item
        );
    }

    public long enchantWorthCents(ItemStack rawItem) {
        ItemStack item = stripWorthLore(rawItem);

        if (item == null
                || item.getType().isAir()
                || !enchantValuesEnabled) {
            return 0L;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return 0L;
        }

        Map<Enchantment, Integer> enchants =
                new LinkedHashMap<>();
        boolean storedBook = false;

        if (meta.hasEnchants()) {
            enchants.putAll(meta.getEnchants());
        }

        if (meta instanceof EnchantmentStorageMeta storageMeta
                && storageMeta.hasStoredEnchants()) {
            enchants.putAll(storageMeta.getStoredEnchants());
            storedBook = true;
        }

        long total = 0L;
        int valuableEnchantments = 0;

        for (Map.Entry<Enchantment, Integer> entry
                : enchants.entrySet()) {
            Enchantment enchantment = entry.getKey();
            int rawLevel = Math.max(1, entry.getValue());
            int maximumLevel = Math.max(
                    1,
                    enchantment.getMaxLevel()
            );
            int valuedLevel = allowUnsafeEnchantLevels
                    ? rawLevel
                    : Math.min(rawLevel, maximumLevel);
            String key = enchantment.getKey()
                    .getKey()
                    .toLowerCase(Locale.ROOT);
            long configured = enchantValues.getOrDefault(
                    enchantment,
                    key.endsWith("_curse")
                            ? 0L
                            : defaultEnchantPerLevelCents
            );

            if (configured <= 0L) {
                continue;
            }

            double multiplier = storedBook
                    ? enchantedBookMultiplier
                    : appliedEnchantMultiplier;

            if (enchantment.isTreasure()) {
                multiplier *= treasureEnchantMultiplier;
            }

            if (allowUnsafeEnchantLevels
                    && rawLevel > maximumLevel) {
                multiplier *= unsafeEnchantMultiplier;
            }

            total = safeAdd(
                    total,
                    multiply(
                            multiply(
                                    configured,
                                    valuedLevel
                            ),
                            multiplier
                    )
            );
            valuableEnchantments++;
        }

        double packageMultiplier = 1.0D;

        if (valuableEnchantments >= 8) {
            packageMultiplier = eightEnchantPackageMultiplier;
        } else if (valuableEnchantments >= 6) {
            packageMultiplier = sixEnchantPackageMultiplier;
        } else if (valuableEnchantments >= 4) {
            packageMultiplier = fourEnchantPackageMultiplier;
        }

        return Math.max(
                0L,
                multiply(total, packageMultiplier)
        );
    }

    public long baseWorthCents(Material material) {
        MarketDefinition definition = definitions.get(material);
        return definition == null ? 0L : definition.baseCents();
    }

    public long serverUnitSellCents(
            UUID playerId,
            Material material
    ) {
        if (material == null
                || material == Material.AIR
                || !material.isItem()) {
            return 0L;
        }

        ItemValuation valuation = appraise(
                playerId,
                new ItemStack(material),
                true
        );
        return valuation.serverSellCents();
    }

    public long serverUnitSellCents(
            Player player,
            Material material
    ) {
        return serverUnitSellCents(
                player == null
                        ? null
                        : player.getUniqueId(),
                material
        );
    }

    public boolean isExplicitlyPriced(Material material) {
        return material != null
                && explicitlyPricedMaterials.contains(material);
    }

    public boolean isWorthVisible(Material material) {
        if (material == null
                || material == Material.AIR
                || !material.isItem()
                || blockedMaterials.contains(material)) {
            return false;
        }

        String name = material.name();

        return !name.endsWith("_SPAWN_EGG")
                && !name.startsWith("LEGACY_")
                && !name.startsWith("POTTED_")
                && !name.startsWith("INFESTED_")
                && !name.contains("COMMAND_BLOCK")
                && baseWorthCents(material) > 0L;
    }

    public List<Material> worthCatalogMaterials() {
        List<Material> materials = new ArrayList<>();

        for (Material material : Material.values()) {
            if (isWorthVisible(material)) {
                materials.add(material);
            }
        }

        return List.copyOf(materials);
    }

    public CatalogCoverage catalogCoverage() {
        int visible = 0;
        int explicit = 0;
        int fallback = 0;
        int serverSellable = 0;
        int playerMarketOnly = 0;

        for (Material material : Material.values()) {
            if (!isWorthVisible(material)) {
                continue;
            }

            visible++;

            if (isExplicitlyPriced(material)) {
                explicit++;
            } else {
                fallback++;
            }

            ItemValuation valuation = appraise(
                    null,
                    new ItemStack(material),
                    true
            );

            if (valuation.sellable()) {
                serverSellable++;
            } else {
                playerMarketOnly++;
            }
        }

        return new CatalogCoverage(
                visible,
                explicit,
                fallback,
                serverSellable,
                playerMarketOnly
        );
    }

    private double buybackMultiplier(
            Material material,
            String category
    ) {
        Double item = itemBuybackMultipliers.get(material);

        if (item != null) {
            return clamp(item, 0.0D, 1.0D);
        }

        return clamp(
                categoryBuybackMultipliers.getOrDefault(
                        normalizeCategory(category),
                        1.0D
                ),
                0.0D,
                1.0D
        );
    }

    private double enchantBuybackMultiplier(
            Material material,
            String category
    ) {
        Double item = itemEnchantBuybackMultipliers.get(material);

        if (item != null) {
            return clamp(item, 0.0D, 1.0D);
        }

        String normalized = normalizeCategory(category);
        return clamp(
                categoryEnchantBuybackMultipliers.getOrDefault(
                        normalized,
                        buybackMultiplier(material, normalized)
                ),
                0.0D,
                1.0D
        );
    }

    public String category(Material material) {
        MarketDefinition definition = definitions.get(material);

        return definition == null
                ? deriveCategory(material)
                : definition.category();
    }

    public String categoryDisplay(Material material) {
        return categoryDisplay(category(material));
    }

    public String categoryDisplay(String category) {
        String normalized = normalizeCategory(category);

        return categoryDisplayNames.getOrDefault(
                normalized,
                prettyCategory(normalized)
        );
    }

    public List<SellHistoryEntry> history(UUID playerId) {
        return marketPricingService.history(playerId);
    }

    public String pretty(Material material) {
        if (material == null) {
            return "";
        }

        return prettyCategory(
                material.name().toLowerCase(Locale.ROOT)
        );
    }

    public static String formatMultiplier(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    public String message(String path, String fallback) {
        return TextColor.color(
                sellConfig.getString(
                        "messages." + path,
                        fallback
                )
        );
    }

    public boolean demandEnabled() {
        return sellConfig.getBoolean("market.enabled", true);
    }

    public long demandLastUpdate() {
        return marketPricingService.lastRepriceAt();
    }

    public long demandUpdateIntervalMillis() {
        return marketPricingService.refreshIntervalMillis();
    }

    public boolean demandNeedsUpdate() {
        return System.currentTimeMillis() - demandLastUpdate()
                >= demandUpdateIntervalMillis();
    }

    public void recalculateDemandIfNeeded() {
        if (demandNeedsUpdate()) {
            marketPricingService.forceReprice();
        }
    }

    public void recalculateDemand() {
        marketPricingService.forceReprice();
    }

    public void rotateDemand() {
        marketPricingService.forceFeaturedRotation();
    }

    public void resetDemandData() {
        marketPricingService.reset();
    }

    public boolean hasDemandAdjustment(Material material) {
        return Math.abs(demandMultiplier(material) - 1.0D)
                >= 0.01D;
    }

    public double demandMultiplier(Material material) {
        MarketDefinition definition = definitions.get(material);

        if (definition == null || !definition.marketEnabled()) {
            return 1.0D;
        }

        return marketPricingService.combinedMultiplier(material);
    }

    public double adjustedDemandMultiplier(Material material) {
        return demandMultiplier(material);
    }

    public long demandWindowAmount(Material material) {
        return marketPricingService.rollingUnits(
                material,
                24L * 60L * 60L * 1000L
        );
    }

    public long demandWindowTotalCents(Material material) {
        return marketPricingService.rollingPayoutCents(
                material,
                24L * 60L * 60L * 1000L
        );
    }

    public boolean isDemandExcluded(Material material) {
        MarketDefinition definition = definitions.get(material);
        return definition == null || !definition.marketEnabled();
    }

    public boolean isActiveDemandItem(Material material) {
        return marketPricingService.isFeatured(material);
    }

    public String demandTier(Material material) {
        return marketPricingService.tier(material);
    }

    public String demandTierDisplay(Material material) {
        return prettyCategory(demandTier(material));
    }

    public double marketSupplyRatio(Material material) {
        return marketPricingService.supplyRatio(material);
    }

    public long marketTargetUnits(Material material) {
        MarketDefinition definition = definitions.get(material);
        return definition == null
                ? 0L
                : definition.targetUnitsPerDay();
    }

    public long featuredUntil(Material material) {
        return marketPricingService.featuredUntil(material);
    }

    public void flushMarket() {
        marketPricingService.flushIfDirty();
    }

    public double categoryMarketMultiplier(String rawCategory) {
        String category = normalizeCategory(rawCategory);
        double total = 0.0D;
        int count = 0;

        for (MarketDefinition definition : definitions.values()) {
            if (!definition.category().equals(category)
                    || !definition.marketEnabled()
                    || definition.baseCents() <= 0L) {
                continue;
            }

            total += marketPricingService.combinedMultiplier(
                    definition.material()
            );
            count++;
        }

        return count == 0 ? 1.0D : total / count;
    }

    public long categoryRollingAmount(String rawCategory) {
        String category = normalizeCategory(rawCategory);
        long total = 0L;

        for (MarketDefinition definition : definitions.values()) {
            if (definition.category().equals(category)
                    && definition.marketEnabled()) {
                total = safeAdd(
                        total,
                        demandWindowAmount(definition.material())
                );
            }
        }

        return total;
    }

    public long categoryTargetUnits(String rawCategory) {
        String category = normalizeCategory(rawCategory);
        long total = 0L;

        for (MarketDefinition definition : definitions.values()) {
            if (definition.category().equals(category)
                    && definition.marketEnabled()) {
                total = safeAdd(
                        total,
                        definition.targetUnitsPerDay()
                );
            }
        }

        return total;
    }

    public int categoryFeaturedItems(String rawCategory) {
        String category = normalizeCategory(rawCategory);
        int total = 0;

        for (MarketDefinition definition : definitions.values()) {
            if (definition.category().equals(category)
                    && marketPricingService.isFeatured(
                    definition.material()
            )) {
                total++;
            }
        }

        return total;
    }

    /*
     * Compatibility methods retained for the old Sell Multipliers GUI.
     * Mineacle pricing is intentionally server-wide, never per-player.
     */
    public double multiplier(UUID playerId, String category) {
        return 1.0D;
    }

    public List<String> multiplierCategories() {
        return categoryDisplayNames.keySet()
                .stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public long categorySoldAmount(
            UUID playerId,
            String category
    ) {
        return 0L;
    }

    public long categoryAmountPerLevel(String category) {
        return 0L;
    }

    public double categoryIncreasePerLevel(String category) {
        return 0.0D;
    }

    public double categoryMaxMultiplier(String category) {
        return 1.0D;
    }

    public long categoryProgressAmount(
            UUID playerId,
            String category
    ) {
        return 0L;
    }

    public long categoryRemainingAmount(
            UUID playerId,
            String category
    ) {
        return 0L;
    }

    private void ensureSellFile() {
        if (!core.getDataFolder().exists()
                && !core.getDataFolder().mkdirs()
                && !core.getDataFolder().exists()) {
            throw new IllegalStateException(
                    "Could not create MineacleCore data folder"
            );
        }

        sellFile = new File(core.getDataFolder(), "sell.yml");

        if (!sellFile.exists()) {
            core.saveResource("sell.yml", false);
        }

        if (!sellFile.isFile()) {
            throw new IllegalStateException(
                    "Could not initialize sell.yml"
            );
        }
    }

    private void loadConfigurationSnapshot() {
        allowFallbackPrices = sellConfig.getBoolean(
                "settings.allow-all-items-with-fallback",
                true
        );
        allowFallbackItemsForSell = sellConfig.getBoolean(
                "settings.allow-fallback-items-for-sell",
                false
        );
        denyCustomItems = sellConfig.getBoolean(
                "settings.deny-custom-items",
                false
        );
        denyFilledContainers = sellConfig.getBoolean(
                "settings.deny-filled-containers",
                true
        );
        denyEnchantedItems = sellConfig.getBoolean(
                "settings.deny-enchanted-items",
                false
        );
        durabilityEnabled = sellConfig.getBoolean(
                "durability.enabled",
                true
        );
        minimumBaseDurabilityMultiplier = clamp(
                sellConfig.getDouble(
                        "durability.minimum-base-multiplier",
                        0.10D
                ),
                0.0D,
                1.0D
        );
        minimumEnchantDurabilityMultiplier = clamp(
                sellConfig.getDouble(
                        "durability.minimum-enchant-multiplier",
                        0.65D
                ),
                0.0D,
                1.0D
        );
        mendingMinimumBaseDurabilityMultiplier = clamp(
                sellConfig.getDouble(
                        "durability.mending-minimum-base-multiplier",
                        0.35D
                ),
                minimumBaseDurabilityMultiplier,
                1.0D
        );
        mendingMinimumEnchantDurabilityMultiplier = clamp(
                sellConfig.getDouble(
                        "durability.mending-minimum-enchant-multiplier",
                        0.95D
                ),
                minimumEnchantDurabilityMultiplier,
                1.0D
        );

        loadBlockedMaterials();
        loadCategoryDisplays();
        loadFallbackPrices();
        loadValuationPolicy();
        loadEnchantValues();
        loadDefinitions();
    }

    private void loadBlockedMaterials() {
        Set<Material> blocked = new HashSet<>();
        Set<Material> unsellable = new HashSet<>();

        for (String raw : sellConfig.getStringList(
                "settings.blocked-items"
        )) {
            Material material = Material.matchMaterial(raw);

            if (material != null) {
                blocked.add(material);
            }
        }

        for (String raw : sellConfig.getStringList(
                "settings.unsellable-items"
        )) {
            Material material = Material.matchMaterial(raw);

            if (material != null) {
                unsellable.add(material);
            }
        }

        blockedMaterials = Set.copyOf(blocked);
        unsellableMaterials = Set.copyOf(unsellable);
    }

    private void loadCategoryDisplays() {
        Map<String, String> displayNames = new HashMap<>();
        ConfigurationSection categories =
                sellConfig.getConfigurationSection("categories");

        if (categories != null) {
            for (String category : categories.getKeys(false)) {
                String normalized = normalizeCategory(category);
                displayNames.put(
                        normalized,
                        sellConfig.getString(
                                "categories."
                                        + category
                                        + ".display-name",
                                prettyCategory(normalized)
                        )
                );
            }
        }

        categoryDisplayNames = Map.copyOf(displayNames);
    }

    private void loadFallbackPrices() {
        Map<String, Long> values = new HashMap<>();
        ConfigurationSection section =
                sellConfig.getConfigurationSection(
                        "fallback-prices"
                );

        if (section != null) {
            for (String category : section.getKeys(false)) {
                values.put(
                        normalizeCategory(category),
                        configuredMoneyCents(
                                "fallback-prices." + category,
                                0L
                        )
                );
            }
        }

        fallbackPrices = Map.copyOf(values);
    }

    private void loadValuationPolicy() {
        Map<String, Double> baseFactors = new HashMap<>();
        Map<String, Double> enchantFactors = new HashMap<>();
        ConfigurationSection categories =
                sellConfig.getConfigurationSection(
                        "valuation.category-buyback"
                );

        if (categories != null) {
            for (String rawCategory : categories.getKeys(false)) {
                String category = normalizeCategory(rawCategory);
                baseFactors.put(
                        category,
                        clamp(
                                sellConfig.getDouble(
                                        "valuation.category-buyback."
                                                + rawCategory
                                                + ".base",
                                        1.0D
                                ),
                                0.0D,
                                1.0D
                        )
                );
                enchantFactors.put(
                        category,
                        clamp(
                                sellConfig.getDouble(
                                        "valuation.category-buyback."
                                                + rawCategory
                                                + ".enchants",
                                        baseFactors.get(category)
                                ),
                                0.0D,
                                1.0D
                        )
                );
            }
        }

        Map<Material, Double> itemBase =
                new EnumMap<>(Material.class);
        Map<Material, Double> itemEnchants =
                new EnumMap<>(Material.class);
        ConfigurationSection prices =
                sellConfig.getConfigurationSection("prices");

        if (prices != null) {
            for (String rawMaterial : prices.getKeys(false)) {
                Material material = Material.matchMaterial(rawMaterial);

                if (material == null) {
                    continue;
                }

                String path = "prices."
                        + rawMaterial;

                if (sellConfig.contains(
                        path + ".buyback-multiplier"
                )) {
                    itemBase.put(
                            material,
                            clamp(
                                    sellConfig.getDouble(
                                            path + ".buyback-multiplier",
                                            1.0D
                                    ),
                                    0.0D,
                                    1.0D
                            )
                    );
                }

                if (sellConfig.contains(
                        path + ".enchant-buyback-multiplier"
                )) {
                    itemEnchants.put(
                            material,
                            clamp(
                                    sellConfig.getDouble(
                                            path
                                                    + ".enchant-buyback-multiplier",
                                            1.0D
                                    ),
                                    0.0D,
                                    1.0D
                            )
                    );
                }
            }
        }

        categoryBuybackMultipliers = Map.copyOf(baseFactors);
        categoryEnchantBuybackMultipliers =
                Map.copyOf(enchantFactors);
        itemBuybackMultipliers = Map.copyOf(itemBase);
        itemEnchantBuybackMultipliers =
                Map.copyOf(itemEnchants);
    }

    private void loadEnchantValues() {
        enchantValuesEnabled = sellConfig.getBoolean(
                "enchant-values.enabled",
                true
        );
        defaultEnchantPerLevelCents = Math.max(
                0L,
                configuredCents(
                        "enchant-values.default-per-level-cents",
                        2_500L
                )
        );
        treasureEnchantMultiplier = clampPositive(
                sellConfig.getDouble(
                        "enchant-values.treasure-multiplier",
                        2.0D
                ),
                2.0D
        );
        unsafeEnchantMultiplier = clampPositive(
                sellConfig.getDouble(
                        "enchant-values.unsafe-multiplier",
                        1.0D
                ),
                1.0D
        );
        allowUnsafeEnchantLevels = sellConfig.getBoolean(
                "enchant-values.allow-unsafe-levels",
                false
        );
        enchantedBookMultiplier = clampPositive(
                sellConfig.getDouble(
                        "enchant-values.enchanted-book-multiplier",
                        1.15D
                ),
                1.15D
        );
        appliedEnchantMultiplier = clampPositive(
                sellConfig.getDouble(
                        "enchant-values.applied-item-multiplier",
                        1.0D
                ),
                1.0D
        );
        fourEnchantPackageMultiplier = clampPositive(
                sellConfig.getDouble(
                        "enchant-values.package-bonus.four-plus",
                        1.10D
                ),
                1.10D
        );
        sixEnchantPackageMultiplier = clampPositive(
                sellConfig.getDouble(
                        "enchant-values.package-bonus.six-plus",
                        1.20D
                ),
                1.20D
        );
        eightEnchantPackageMultiplier = clampPositive(
                sellConfig.getDouble(
                        "enchant-values.package-bonus.eight-plus",
                        1.35D
                ),
                1.35D
        );

        Map<Enchantment, Long> values = new HashMap<>();
        ConfigurationSection section =
                sellConfig.getConfigurationSection(
                        "enchant-values.enchants"
                );

        if (section != null) {
            for (String raw : section.getKeys(false)) {
                Enchantment enchantment = Enchantment.getByKey(
                        org.bukkit.NamespacedKey.minecraft(
                                raw.toLowerCase(Locale.ROOT)
                        )
                );

                if (enchantment != null) {
                    values.put(
                            enchantment,
                            Math.max(
                                    0L,
                                    configuredCents(
                                            "enchant-values.enchants."
                                                    + raw,
                                            defaultEnchantPerLevelCents
                                    )
                            )
                    );
                }
            }
        }

        enchantValues = Map.copyOf(values);
    }

    private void loadDefinitions() {
        Map<Material, MarketDefinition> loaded =
                new EnumMap<>(Material.class);
        Set<Material> explicit = new HashSet<>();

        for (Material material : Material.values()) {
            if (!material.isItem()
                    || material == Material.AIR
                    || blockedMaterials.contains(material)) {
                continue;
            }

            String category = deriveCategory(material);
            String pricePath = "prices."
                    + material.name()
                    + ".base-price";
            String legacyPricePath = "prices."
                    + material.name()
                    + ".price";
            boolean explicitPrice = sellConfig.contains(pricePath)
                    || sellConfig.contains(legacyPricePath);

            if (explicitPrice) {
                explicit.add(material);
            }

            long baseCents;

            if (sellConfig.contains(pricePath)) {
                baseCents = configuredMoneyCents(
                        pricePath,
                        0L
                );
            } else if (sellConfig.contains(legacyPricePath)) {
                baseCents = configuredMoneyCents(
                        legacyPricePath,
                        0L
                );
            } else if (allowFallbackPrices) {
                baseCents = fallbackPrices.getOrDefault(
                        category,
                        fallbackPrices.getOrDefault("misc", 0L)
                );
            } else {
                baseCents = 0L;
            }

            String configuredCategory = sellConfig.getString(
                    "prices."
                            + material.name()
                            + ".category"
            );

            if (configuredCategory != null
                    && !configuredCategory.isBlank()) {
                category = normalizeCategory(configuredCategory);
            }

            boolean categoryEnabled = sellConfig.getBoolean(
                    "market.categories."
                            + category
                            + ".enabled",
                    defaultMarketEnabled(category)
            );
            boolean fallbackMarketEnabled = sellConfig.getBoolean(
                    "market.fallback-items-enabled",
                    false
            );
            boolean defaultItemMarket = categoryEnabled
                    && (explicitPrice || fallbackMarketEnabled);
            boolean marketEnabled = sellConfig.getBoolean(
                    "prices."
                            + material.name()
                            + ".market-enabled",
                    defaultItemMarket
            );
            long target = Math.max(
                    1L,
                    sellConfig.getLong(
                            "prices."
                                    + material.name()
                                    + ".target-units-per-day",
                            sellConfig.getLong(
                                    "market.targets."
                                            + category,
                                    1_000L
                            )
                    )
            );
            double minimum = clamp(
                    sellConfig.getDouble(
                            "prices."
                                    + material.name()
                                    + ".minimum-multiplier",
                            sellConfig.getDouble(
                                    "market.minimum-multiplier",
                                    0.35D
                            )
                    ),
                    0.01D,
                    100.0D
            );
            double maximum = clamp(
                    sellConfig.getDouble(
                            "prices."
                                    + material.name()
                                    + ".maximum-multiplier",
                            sellConfig.getDouble(
                                    "market.maximum-multiplier",
                                    1.75D
                            )
                    ),
                    minimum,
                    100.0D
            );

            loaded.put(
                    material,
                    new MarketDefinition(
                            material,
                            Math.max(0L, baseCents),
                            category,
                            marketEnabled,
                            target,
                            minimum,
                            maximum
                    )
            );
        }

        definitions = Map.copyOf(loaded);
        explicitlyPricedMaterials = Set.copyOf(explicit);
    }

    private void migrateLegacySettings() {
        if (sellConfig.contains(
                "settings.deny-damaged-tools"
        )) {
            sellConfig.set(
                    "settings.deny-damaged-tools",
                    null
            );
        }

        for (Material material : Material.values()) {
            String legacy = "prices."
                    + material.name()
                    + ".price";
            String current = "prices."
                    + material.name()
                    + ".base-price";

            if (sellConfig.contains(legacy)
                    && !sellConfig.contains(current)) {
                sellConfig.set(
                        current,
                        sellConfig.get(legacy)
                );
            }
        }
    }

    private void migrateLegacyHistory() {
        ConfigurationSection players =
                sellConfig.getConfigurationSection("history");

        if (players == null) {
            return;
        }

        for (String rawPlayer : players.getKeys(false)) {
            UUID playerId;

            try {
                playerId = UUID.fromString(rawPlayer);
            } catch (IllegalArgumentException exception) {
                continue;
            }

            ConfigurationSection materials =
                    sellConfig.getConfigurationSection(
                            "history." + rawPlayer
                    );

            if (materials == null) {
                continue;
            }

            for (String rawMaterial : materials.getKeys(false)) {
                Material material = Material.matchMaterial(
                        rawMaterial
                );

                if (material == null) {
                    continue;
                }

                String path = "history."
                        + rawPlayer
                        + "."
                        + rawMaterial;
                long amount = sellConfig.getLong(
                        path + ".amount",
                        0L
                );
                long totalCents = sellConfig.getLong(
                        path + ".total-cents",
                        0L
                );
                long lastSold = sellConfig.getLong(
                        path + ".last-sold",
                        0L
                );

                if (amount > 0L) {
                    marketPricingService.importHistory(
                            playerId,
                            material,
                            amount,
                            Math.max(0L, totalCents),
                            Math.max(0L, lastSold)
                    );
                }
            }
        }

    }

    private String rejectionReason(
            ItemStack item,
            boolean enforceContainerRules
    ) {
        Material material = item.getType();

        if (!material.isItem()
                || blockedMaterials.contains(material)) {
            return "blocked";
        }

        if (unsellableMaterials.contains(material)) {
            return "player-market-only";
        }

        ItemMeta meta = item.getItemMeta();

        if (denyCustomItems && isCustomItem(meta)) {
            return "custom-item";
        }

        if (denyEnchantedItems
                && meta != null
                && (meta.hasEnchants()
                || meta instanceof EnchantmentStorageMeta
                && ((EnchantmentStorageMeta) meta)
                .hasStoredEnchants())) {
            return "enchanted-item";
        }

        if (enforceContainerRules
                && denyFilledContainers
                && containsItems(meta)) {
            return "filled-container";
        }

        return null;
    }

    private boolean isCustomItem(ItemMeta meta) {
        if (meta == null) {
            return false;
        }

        return meta.hasCustomModelData()
                || !meta.getPersistentDataContainer()
                .getKeys()
                .isEmpty();
    }

    private boolean containsItems(ItemMeta meta) {
        if (meta instanceof BundleMeta bundleMeta
                && !bundleMeta.getItems().isEmpty()) {
            return true;
        }

        if (meta instanceof BlockStateMeta stateMeta
                && stateMeta.getBlockState()
                instanceof ShulkerBox shulkerBox) {
            for (ItemStack content
                    : shulkerBox.getInventory().getContents()) {
                if (content != null
                        && !content.getType().isAir()) {
                    return true;
                }
            }
        }

        return false;
    }

    private DurabilityValue durability(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        boolean mending = hasMending(meta);

        if (!durabilityEnabled) {
            return new DurabilityValue(
                    100,
                    1.0D,
                    1.0D,
                    mending
            );
        }

        int maximum = item.getType().getMaxDurability();

        if (!(meta instanceof Damageable damageable)
                || maximum <= 0
                || meta.isUnbreakable()) {
            return new DurabilityValue(
                    100,
                    1.0D,
                    1.0D,
                    mending
            );
        }

        int damage = Math.max(
                0,
                Math.min(maximum, damageable.getDamage())
        );
        double remaining = clamp(
                (maximum - damage) / (double) maximum,
                0.0D,
                1.0D
        );
        int percent = (int) Math.round(remaining * 100.0D);
        double baseMinimum = mending
                ? mendingMinimumBaseDurabilityMultiplier
                : minimumBaseDurabilityMultiplier;
        double enchantMinimum = mending
                ? mendingMinimumEnchantDurabilityMultiplier
                : minimumEnchantDurabilityMultiplier;

        return new DurabilityValue(
                percent,
                Math.max(baseMinimum, remaining),
                Math.max(enchantMinimum, remaining),
                mending
        );
    }

    private boolean hasMending(ItemMeta meta) {
        if (meta == null) {
            return false;
        }

        for (Enchantment enchantment
                : meta.getEnchants().keySet()) {
            if (enchantment.getKey()
                    .getKey()
                    .equalsIgnoreCase("mending")) {
                return true;
            }
        }

        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            for (Enchantment enchantment
                    : storageMeta.getStoredEnchants().keySet()) {
                if (enchantment.getKey()
                        .getKey()
                        .equalsIgnoreCase("mending")) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isWorthLine(String line) {
        if (line == null) {
            return false;
        }

        String stripped = ChatColor.stripColor(line);

        if (stripped == null) {
            return false;
        }

        String lower = stripped
                .trim()
                .toLowerCase(Locale.ROOT);

        return lower.startsWith("worth:")
                || lower.startsWith("appraised worth:")
                || lower.startsWith("price:")
                || lower.startsWith("base:")
                || lower.startsWith("stack:")
                || lower.startsWith("stack worth:")
                || lower.startsWith("stack price:")
                || lower.startsWith("stack appraisal:")
                || lower.startsWith("stack sell:")
                || lower.startsWith("server sell:")
                || lower.startsWith("player market only")
                || lower.startsWith("enchant value:")
                || lower.startsWith("demand:")
                || lower.startsWith("market:")
                || lower.startsWith("durability:")
                || lower.startsWith("category:")
                || lower.startsWith("sold this cycle:");
    }

    private String deriveCategory(Material material) {
        if (material == null) {
            return "misc";
        }

        String name = material.name();

        /*
         * Equipment must be classified before resource-name checks.
         * DIAMOND_SWORD, IRON_PICKAXE and NETHERITE_HELMET previously matched
         * ores simply because their names contain a resource word.
         */
        if (name.endsWith("_SWORD")
                || name.endsWith("_PICKAXE")
                || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS")
                || name.endsWith("_HORSE_ARMOR")
                || name.equals("BOW")
                || name.equals("CROSSBOW")
                || name.equals("TRIDENT")
                || name.equals("MACE")
                || name.equals("SHIELD")
                || name.equals("ELYTRA")
                || name.equals("FISHING_ROD")
                || name.equals("SHEARS")
                || name.equals("BRUSH")
                || name.equals("FLINT_AND_STEEL")
                || name.equals("WOLF_ARMOR")
                || name.equals("TURTLE_HELMET")) {
            return "equipment";
        }

        if (name.equals("DRAGON_EGG")
                || name.equals("NETHER_STAR")
                || name.equals("BEACON")
                || name.equals("ENCHANTED_GOLDEN_APPLE")
                || name.equals("TOTEM_OF_UNDYING")
                || name.equals("HEART_OF_THE_SEA")
                || name.equals("CONDUIT")
                || name.equals("RECOVERY_COMPASS")
                || name.equals("HEAVY_CORE")
                || name.equals("TRIAL_KEY")
                || name.equals("OMINOUS_TRIAL_KEY")
                || name.equals("ECHO_SHARD")
                || name.equals("DISC_FRAGMENT_5")
                || name.startsWith("MUSIC_DISC_")
                || name.endsWith("_SMITHING_TEMPLATE")
                || name.endsWith("_BANNER_PATTERN")) {
            return "rare";
        }

        if (name.contains("POTION")
                || name.equals("MILK_BUCKET")
                || name.equals("HONEY_BOTTLE")
                || name.equals("GOLDEN_APPLE")
                || name.contains("STEW")
                || name.contains("SOUP")
                || name.contains("BEEF")
                || name.contains("PORKCHOP")
                || name.contains("CHICKEN")
                || name.contains("MUTTON")
                || name.contains("RABBIT")
                || name.contains("COD")
                || name.contains("SALMON")
                || name.contains("BREAD")
                || name.contains("COOKIE")
                || name.contains("CAKE")) {
            return "consumables";
        }

        if (name.contains("INGOT")
                || name.contains("ORE")
                || name.equals("DIAMOND")
                || name.equals("DIAMOND_BLOCK")
                || name.equals("EMERALD")
                || name.equals("EMERALD_BLOCK")
                || name.contains("QUARTZ")
                || name.contains("LAPIS")
                || name.contains("REDSTONE")
                || name.contains("NETHERITE")
                || name.contains("DEBRIS")
                || name.startsWith("RAW_")
                || name.equals("COAL")
                || name.equals("COAL_BLOCK")
                || name.equals("CHARCOAL")
                || name.contains("AMETHYST")) {
            return "ores";
        }

        if (name.contains("LOG")
                || name.contains("WOOD")
                || name.contains("PLANK")
                || name.contains("STEM")
                || name.contains("HYPHAE")) {
            return "wood";
        }

        if (name.contains("ROTTEN")
                || name.contains("BONE")
                || name.contains("STRING")
                || name.contains("GUNPOWDER")
                || name.contains("SPIDER_EYE")
                || name.contains("SLIME")
                || name.contains("PHANTOM_MEMBRANE")
                || name.contains("INK_SAC")
                || name.contains("FEATHER")
                || name.contains("LEATHER")) {
            return "mob_drops";
        }

        if (name.contains("WHEAT")
                || name.contains("CARROT")
                || name.contains("POTATO")
                || name.contains("SEEDS")
                || name.contains("MELON")
                || name.contains("PUMPKIN")
                || name.contains("SUGAR_CANE")
                || name.contains("CACTUS")
                || name.contains("COCOA")
                || name.contains("BAMBOO")
                || name.contains("KELP")
                || name.contains("BEETROOT")
                || name.contains("NETHER_WART")) {
            return "farming";
        }

        if (name.contains("NETHER")
                || name.contains("BLAZE")
                || name.contains("GHAST")
                || name.contains("MAGMA")) {
            return "nether";
        }

        if (name.contains("END")
                || name.contains("SHULKER")
                || name.contains("CHORUS")) {
            return "end";
        }

        if (name.contains("COMPASS")
                || name.contains("CLOCK")
                || name.contains("MAP")
                || name.contains("BUCKET")
                || name.contains("MINECART")
                || name.contains("BOAT")
                || name.equals("SADDLE")
                || name.equals("LEAD")
                || name.equals("NAME_TAG")
                || name.equals("SPYGLASS")
                || name.equals("ENCHANTED_BOOK")) {
            return "utility";
        }

        if (material.isBlock()) {
            return "blocks";
        }

        return "misc";
    }

    private boolean defaultMarketEnabled(String category) {
        return switch (category) {
            case "blocks",
                 "ores",
                 "wood",
                 "farming",
                 "mob_drops",
                 "nether",
                 "end" -> true;
            default -> false;
        };
    }

    private String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return "misc";
        }

        return raw.toLowerCase(Locale.ROOT)
                .replace("-", "_")
                .replace(" ", "_");
    }

    private String prettyCategory(String raw) {
        String[] parts = normalizeCategory(raw).split("_");
        StringBuilder result = new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            if (!result.isEmpty()) {
                result.append(' ');
            }

            result.append(
                    Character.toUpperCase(part.charAt(0))
            ).append(part.substring(1));
        }

        return result.toString();
    }

    private long configuredMoneyCents(
            String path,
            long fallback
    ) {
        Object value = sellConfig.get(path);

        if (value == null) {
            return fallback;
        }

        long parsed = MoneyFormatter.parseNonNegativeCents(
                String.valueOf(value)
        );

        return parsed >= 0L ? parsed : fallback;
    }

    private long configuredCents(
            String path,
            long fallback
    ) {
        Object value = sellConfig.get(path);

        if (value == null) {
            return fallback;
        }

        try {
            return Math.max(
                    0L,
                    new BigDecimal(String.valueOf(value))
                            .setScale(
                                    0,
                                    RoundingMode.HALF_UP
                            )
                            .longValueExact()
            );
        } catch (NumberFormatException
                 | ArithmeticException exception) {
            return fallback;
        }
    }

    private void ensureDefaults() {
        sellConfig.addDefault(
                "settings.allow-all-items-with-fallback",
                true
        );
        sellConfig.addDefault(
                "settings.allow-fallback-items-for-sell",
                false
        );
        sellConfig.addDefault(
                "settings.deny-custom-items",
                true
        );
        sellConfig.addDefault(
                "settings.deny-filled-containers",
                true
        );
        sellConfig.addDefault(
                "settings.deny-enchanted-items",
                false
        );
        sellConfig.addDefault(
                "settings.unsellable-items",
                List.of("DRAGON_EGG")
        );
        sellConfig.addDefault(
                "durability.enabled",
                true
        );
        sellConfig.addDefault(
                "durability.minimum-base-multiplier",
                0.10D
        );
        sellConfig.addDefault(
                "durability.minimum-enchant-multiplier",
                0.65D
        );
        sellConfig.addDefault(
                "durability.mending-minimum-base-multiplier",
                0.35D
        );
        sellConfig.addDefault(
                "durability.mending-minimum-enchant-multiplier",
                0.95D
        );
        sellConfig.addDefault(
                "enchant-values.allow-unsafe-levels",
                false
        );
        sellConfig.addDefault(
                "enchant-values.enchanted-book-multiplier",
                1.15D
        );
        sellConfig.addDefault(
                "enchant-values.applied-item-multiplier",
                1.0D
        );
        sellConfig.addDefault(
                "enchant-values.package-bonus.four-plus",
                1.10D
        );
        sellConfig.addDefault(
                "enchant-values.package-bonus.six-plus",
                1.20D
        );
        sellConfig.addDefault(
                "enchant-values.package-bonus.eight-plus",
                1.35D
        );
        sellConfig.addDefault("market.enabled", true);
        sellConfig.addDefault(
                "market.fallback-items-enabled",
                false
        );
        sellConfig.addDefault("market.storage", "mysql");
        sellConfig.addDefault(
                "market.database-config-file",
                "webprofiles.yml"
        );
        sellConfig.addDefault(
                "market.table-prefix",
                "mineacle_sell"
        );
        sellConfig.addDefault(
                "market.reprice-interval-minutes",
                15
        );
        sellConfig.addDefault(
                "market.flush-seconds",
                30
        );
        sellConfig.addDefault(
                "market.bucket-minutes",
                60
        );
        sellConfig.addDefault(
                "market.retention-days",
                7
        );
        sellConfig.addDefault(
                "market.maximum-change-per-refresh-percent",
                8.0D
        );
        sellConfig.addDefault(
                "market.minimum-observation-hours",
                6
        );
        sellConfig.addDefault(
                "market.minimum-multiplier",
                0.35D
        );
        sellConfig.addDefault(
                "market.maximum-multiplier",
                1.75D
        );
        sellConfig.addDefault(
                "market.weights.last-6-hours",
                0.60D
        );
        sellConfig.addDefault(
                "market.weights.last-24-hours",
                0.30D
        );
        sellConfig.addDefault(
                "market.weights.last-7-days",
                0.10D
        );
        sellConfig.addDefault(
                "market.featured.rotation-hours",
                12
        );
        sellConfig.addDefault(
                "market.featured.active-items",
                8
        );
        sellConfig.addDefault(
                "market.featured.minimum-multiplier",
                1.10D
        );
        sellConfig.addDefault(
                "market.featured.maximum-multiplier",
                1.35D
        );
        sellConfig.options().copyDefaults(true);
    }

    private void atomicSave(
            FileConfiguration configuration,
            File destination
    ) throws IOException {
        File temporary = new File(
                destination.getParentFile(),
                destination.getName() + ".tmp"
        );

        configuration.save(temporary);

        try {
            Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private long multiply(long value, long multiplier) {
        try {
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long multiply(long value, double multiplier) {
        if (value <= 0L
                || !Double.isFinite(multiplier)
                || multiplier <= 0.0D) {
            return 0L;
        }

        try {
            return BigDecimal.valueOf(value)
                    .multiply(BigDecimal.valueOf(multiplier))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long safeAdd(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private double clamp(
            double value,
            double minimum,
            double maximum
    ) {
        if (!Double.isFinite(value)) {
            return minimum;
        }

        return Math.max(minimum, Math.min(maximum, value));
    }

    private double clampPositive(
            double value,
            double fallback
    ) {
        return Double.isFinite(value) && value >= 0.0D
                ? value
                : fallback;
    }

    public record CatalogCoverage(
            int visibleItems,
            int explicitlyPricedItems,
            int fallbackAppraisals,
            int serverSellableItems,
            int playerMarketOnlyItems
    ) {
    }

    private static final class SoldTotals {

        private long amount;
        private long payoutCents;
    }

    private record DurabilityValue(
            int percent,
            double baseMultiplier,
            double enchantMultiplier,
            boolean mending
    ) {
    }
}
