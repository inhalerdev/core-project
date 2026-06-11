package net.mineacle.core.auctionhouse.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AuctionHouseService {

    public enum SortMode {
        LOWEST_PRICE,
        HIGHEST_PRICE,
        RECENTLY_LISTED;

        public SortMode next() {
            return switch (this) {
                case LOWEST_PRICE -> HIGHEST_PRICE;
                case HIGHEST_PRICE -> RECENTLY_LISTED;
                case RECENTLY_LISTED -> LOWEST_PRICE;
            };
        }

        public String label() {
            return switch (this) {
                case LOWEST_PRICE -> "Lowest Price";
                case HIGHEST_PRICE -> "Highest Price";
                case RECENTLY_LISTED -> "Recently Listed";
            };
        }
    }

    public enum BuyResult {
        SUCCESS,
        NOT_FOUND,
        OWN_ITEM,
        NOT_ENOUGH_MONEY,
        INVENTORY_FULL,
        ECONOMY_MISSING
    }

    private final Core core;
    private final File storageFile;
    private final File configFile;
    private YamlConfiguration storage;
    private YamlConfiguration config;
    private final Map<UUID, AuctionHouseListing> listings = new LinkedHashMap<>();

    public AuctionHouseService(Core core) {
        this.core = core;
        this.storageFile = new File(core.getDataFolder(), "auctionhouse-data.yml");
        this.configFile = new File(core.getDataFolder(), "auctionhouse.yml");
    }

    public void load() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        if (!configFile.exists()) {
            core.saveResource("auctionhouse.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(configFile);
        storage = YamlConfiguration.loadConfiguration(storageFile);
        listings.clear();

        ConfigurationSection section = storage.getConfigurationSection("listings");

        if (section == null) {
            return;
        }

        for (String rawId : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(rawId);
                UUID owner = UUID.fromString(section.getString(rawId + ".owner", ""));
                String ownerName = section.getString(rawId + ".owner-name", "Unknown");
                long priceCents = section.getLong(rawId + ".price-cents", 0L);
                long createdAt = section.getLong(rawId + ".created-at", System.currentTimeMillis());
                ItemStack item = section.getItemStack(rawId + ".item");

                if (item == null || item.getType().isAir() || priceCents <= 0L) {
                    continue;
                }

                listings.put(id, new AuctionHouseListing(id, owner, ownerName, item, priceCents, createdAt));
            } catch (Exception exception) {
                core.getLogger().warning("Skipped broken auction listing " + rawId);
            }
        }
    }

    public void save() {
        if (storage == null) {
            storage = new YamlConfiguration();
        }

        storage.set("listings", null);

        for (AuctionHouseListing listing : listings.values()) {
            String path = "listings." + listing.id();

            storage.set(path + ".owner", listing.owner().toString());
            storage.set(path + ".owner-name", listing.ownerName());
            storage.set(path + ".price-cents", listing.priceCents());
            storage.set(path + ".created-at", listing.createdAt());
            storage.set(path + ".item", listing.item());
        }

        try {
            storage.save(storageFile);
        } catch (IOException exception) {
            core.getLogger().severe("Could not save auctionhouse-data.yml");
            exception.printStackTrace();
        }
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public int pageSize() {
        return 45;
    }

    public long minPriceCents() {
        return Math.max(1L, dollarsToCents(config.getDouble("listing.min-price", 1.0D)));
    }

    public long maxPriceCents() {
        return Math.max(minPriceCents(), dollarsToCents(config.getDouble("listing.max-price", 1000000000.0D)));
    }

    public int listingLimit(Player player) {
        if (player.hasPermission("mineacleauctionhouse.admin")) {
            return Math.max(1, config.getInt("listing.admin-slots", 999));
        }

        if (player.hasPermission(config.getString("listing.plus-permission", "mineacle.plus"))) {
            return Math.max(1, config.getInt("listing.plus-slots", 12));
        }

        return Math.max(0, config.getInt("listing.default-slots", 3));
    }

    public boolean canList(Player player) {
        if (!config.getBoolean("listing.allow-default", true) && !player.hasPermission(config.getString("listing.plus-permission", "mineacle.plus"))) {
            return player.hasPermission("mineacleauctionhouse.admin") || player.hasPermission("mineacleauctionhouse.sell");
        }

        return player.hasPermission("mineacleauctionhouse.use");
    }

    public int activeListingCount(UUID owner) {
        int count = 0;

        for (AuctionHouseListing listing : listings.values()) {
            if (listing.owner().equals(owner)) {
                count++;
            }
        }

        return count;
    }

    public List<AuctionHouseListing> search(String query, SortMode sortMode) {
        String normalizedQuery = normalizeSearchQuery(query);
        List<AuctionHouseListing> result = new ArrayList<>();

        for (AuctionHouseListing listing : listings.values()) {
            if (normalizedQuery.isBlank() || matches(listing.item(), normalizedQuery)) {
                result.add(listing);
            }
        }

        Comparator<AuctionHouseListing> comparator = switch (sortMode) {
            case LOWEST_PRICE -> Comparator.comparingLong(AuctionHouseListing::priceCents).thenComparingLong(AuctionHouseListing::createdAt);
            case HIGHEST_PRICE -> Comparator.comparingLong(AuctionHouseListing::priceCents).reversed().thenComparingLong(AuctionHouseListing::createdAt);
            case RECENTLY_LISTED -> Comparator.comparingLong(AuctionHouseListing::createdAt).reversed();
        };

        result.sort(comparator);
        return result;
    }

    public List<AuctionHouseListing> ownerListings(UUID owner) {
        List<AuctionHouseListing> result = new ArrayList<>();

        for (AuctionHouseListing listing : listings.values()) {
            if (listing.owner().equals(owner)) {
                result.add(listing);
            }
        }

        result.sort(Comparator.comparingLong(AuctionHouseListing::createdAt).reversed());
        return result;
    }

    public AuctionHouseListing listing(UUID id) {
        return listings.get(id);
    }

    public AuctionHouseListing createListing(Player player, ItemStack item, long priceCents) {
        ItemStack saleItem = item.clone();
        UUID id = UUID.randomUUID();
        AuctionHouseListing listing = new AuctionHouseListing(id, player.getUniqueId(), player.getName(), saleItem, priceCents, System.currentTimeMillis());

        listings.put(id, listing);
        save();
        return listing;
    }

    public boolean cancelListing(Player player, UUID id) {
        AuctionHouseListing listing = listings.get(id);

        if (listing == null) {
            return false;
        }

        if (!listing.owner().equals(player.getUniqueId()) && !player.hasPermission("mineacleauctionhouse.admin")) {
            return false;
        }

        if (!hasSpace(player, listing.item())) {
            return false;
        }

        player.getInventory().addItem(listing.item().clone());
        listings.remove(id);
        save();
        return true;
    }

    public BuyResult buy(Player buyer, UUID id) {
        AuctionHouseListing listing = listings.get(id);

        if (listing == null) {
            return BuyResult.NOT_FOUND;
        }

        if (listing.owner().equals(buyer.getUniqueId())) {
            return BuyResult.OWN_ITEM;
        }

        if (!hasSpace(buyer, listing.item())) {
            return BuyResult.INVENTORY_FULL;
        }

        Economy economy = economy();

        if (economy == null) {
            return BuyResult.ECONOMY_MISSING;
        }

        double price = listing.priceCents() / 100.0D;

        if (economy.getBalance(buyer) < price) {
            return BuyResult.NOT_ENOUGH_MONEY;
        }

        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.owner());

        if (!economy.withdrawPlayer(buyer, price).transactionSuccess()) {
            return BuyResult.NOT_ENOUGH_MONEY;
        }

        economy.depositPlayer(seller, price);
        buyer.getInventory().addItem(listing.item().clone());

        listings.remove(id);
        save();
        return BuyResult.SUCCESS;
    }

    public Economy economy() {
        RegisteredServiceProvider<Economy> provider = core.getServer().getServicesManager().getRegistration(Economy.class);

        if (provider == null) {
            return null;
        }

        return provider.getProvider();
    }

    public long parsePriceCents(String raw) {
        if (raw == null) {
            return -1L;
        }

        String cleaned = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("$", "");

        if (cleaned.isBlank()) {
            return -1L;
        }

        double multiplier = 1.0D;

        if (cleaned.endsWith("k")) {
            multiplier = 1000.0D;
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        } else if (cleaned.endsWith("m")) {
            multiplier = 1000000.0D;
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        } else if (cleaned.endsWith("b")) {
            multiplier = 1000000000.0D;
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }

        try {
            double dollars = Double.parseDouble(cleaned) * multiplier;
            return Math.round(dollars * 100.0D);
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    public String format(long cents) {
        long abs = Math.abs(cents);
        boolean negative = cents < 0;
        double dollars = abs / 100.0D;

        String prefix = negative ? "-$" : "$";

        if (dollars >= 1000000000.0D) {
            return prefix + trim(dollars / 1000000000.0D) + "B";
        }

        if (dollars >= 1000000.0D) {
            return prefix + trim(dollars / 1000000.0D) + "M";
        }

        if (dollars >= 1000.0D) {
            return prefix + trim(dollars / 1000.0D) + "K";
        }

        long whole = abs / 100L;
        long pennies = abs % 100L;
        DecimalFormat wholeFormat = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));

        if (pennies == 0L) {
            return prefix + wholeFormat.format(whole);
        }

        return prefix + wholeFormat.format(whole) + "." + String.format(Locale.US, "%02d", pennies);
    }

    private String trim(double value) {
        return String.format(Locale.US, "%.2f", value).replaceAll("\\.?0+$", "");
    }

    public String itemName(ItemStack item) {
        if (item == null) {
            return "Item";
        }

        ItemMeta meta = item.getItemMeta();

        if (meta != null && meta.hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }

        String raw = item.getType().name().toLowerCase(Locale.ROOT).replace("_", " ");
        String[] parts = raw.split(" ");
        StringBuilder name = new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            if (!name.isEmpty()) {
                name.append(' ');
            }

            name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }

        return name.toString();
    }

    private boolean hasSpace(Player player, ItemStack item) {
        return player.getInventory().firstEmpty() != -1 || canStackIntoInventory(player, item);
    }

    private boolean canStackIntoInventory(Player player, ItemStack item) {
        for (ItemStack content : player.getInventory().getStorageContents()) {
            if (content == null || content.getType().isAir()) {
                return true;
            }

            if (content.isSimilar(item) && content.getAmount() < content.getMaxStackSize()) {
                return true;
            }
        }

        return false;
    }

    private long dollarsToCents(double dollars) {
        return Math.round(dollars * 100.0D);
    }

    private boolean matches(ItemStack item, String query) {
        if (query.isBlank()) {
            return true;
        }

        String haystack = searchableText(item);

        for (String token : query.split(" ")) {
            if (token.isBlank()) {
                continue;
            }

            if (!haystack.contains(token)) {
                return false;
            }
        }

        return true;
    }

    private String normalizeSearchQuery(String query) {
        if (query == null) {
            return "";
        }

        String normalized = normalize(query);

        for (String alias : config.getConfigurationSection("search-aliases") == null ? List.<String>of() : config.getConfigurationSection("search-aliases").getKeys(false)) {
            String aliasNormalized = normalize(alias);
            String targetNormalized = normalize(config.getString("search-aliases." + alias, alias));

            if (normalized.equals(aliasNormalized)) {
                return targetNormalized;
            }
        }

        return normalized;
    }

    private String searchableText(ItemStack item) {
        StringBuilder builder = new StringBuilder();

        builder.append(normalize(item.getType().name())).append(' ');
        builder.append(normalize(itemName(item))).append(' ');

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return builder.toString();
        }

        if (meta.hasDisplayName()) {
            builder.append(normalize(PlainTextComponentSerializer.plainText().serialize(meta.displayName()))).append(' ');
        }

        if (meta.hasLore() && meta.lore() != null) {
            for (Component lore : meta.lore()) {
                builder.append(normalize(PlainTextComponentSerializer.plainText().serialize(lore))).append(' ');
            }
        }

        if (meta.hasEnchants()) {
            for (Enchantment enchantment : meta.getEnchants().keySet()) {
                builder.append(normalize(enchantment.getKey().getKey())).append(' ');
            }
        }

        if (meta instanceof EnchantmentStorageMeta storageMeta && storageMeta.hasStoredEnchants()) {
            for (Enchantment enchantment : storageMeta.getStoredEnchants().keySet()) {
                builder.append(normalize(enchantment.getKey().getKey())).append(' ');
            }
        }

        return builder.toString();
    }

    private String normalize(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
    }
}
