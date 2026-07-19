package net.mineacle.core.auctionhouse.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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

    public enum CreateResult {
        SUCCESS,
        DISABLED,
        NO_PERMISSION,
        NO_ITEM,
        NO_SLOT,
        INVALID_PRICE,
        BELOW_MINIMUM,
        ABOVE_MAXIMUM,
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
        OWN_ITEM,
        NOT_ENOUGH_MONEY,
        INVENTORY_FULL,
        ECONOMY_MISSING,
        PAYMENT_FAILED,
        STORAGE_ERROR
    }

    public record CreateOutcome(CreateResult result, AuctionHouseListing listing) {
    }

    public record BuyOutcome(BuyResult result, AuctionHouseListing listing) {
    }

    private final Core core;
    private final File storageFile;
    private final File configFile;
    private final Map<UUID, AuctionHouseListing> listings = new LinkedHashMap<>();

    private YamlConfiguration storage = new YamlConfiguration();
    private YamlConfiguration config = new YamlConfiguration();

    public AuctionHouseService(Core core) {
        this.core = core;
        this.storageFile = new File(core.getDataFolder(), "auctionhouse-data.yml");
        this.configFile = new File(core.getDataFolder(), "auctionhouse.yml");
    }

    public synchronized void load() {
        ensureDataFolder();
        ensureConfigFile();

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

                if (item == null || item.getType().isAir() || item.getAmount() <= 0 || priceCents <= 0L) {
                    core.getLogger().warning("Skipped invalid auction listing " + rawId);
                    continue;
                }

                listings.put(
                        id,
                        new AuctionHouseListing(
                                id,
                                owner,
                                ownerName,
                                item,
                                priceCents,
                                createdAt
                        )
                );
            } catch (Exception exception) {
                core.getLogger().warning(
                        "Skipped broken auction listing "
                                + rawId
                                + ": "
                                + exception.getMessage()
                );
            }
        }
    }

    public synchronized boolean save() {
        return persistListings();
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public int pageSize() {
        return 45;
    }

    public long minPriceCents() {
        return Math.max(
                1L,
                dollarsToCents(config.getDouble("listing.min-price", 1.0D))
        );
    }

    public long maxPriceCents() {
        return Math.max(
                minPriceCents(),
                dollarsToCents(config.getDouble("listing.max-price", 1_000_000_000.0D))
        );
    }

    public int maxSearchLength() {
        return Math.max(8, config.getInt("search.max-length", 48));
    }

    public long searchPromptTimeoutTicks() {
        long seconds = Math.max(
                1L,
                config.getLong("search.prompt-timeout-seconds", 60L)
        );
        return seconds * 20L;
    }

    public int listingLimit(Player player) {
        if (player == null) {
            return 0;
        }

        if (player.hasPermission("mineacleauctionhouse.admin")) {
            return Math.max(1, config.getInt("listing.admin-slots", 999));
        }

        String plusPermission = config.getString(
                "listing.plus-permission",
                "mineacle.plus"
        );

        if (plusPermission != null
                && !plusPermission.isBlank()
                && player.hasPermission(plusPermission)) {
            return Math.max(1, config.getInt("listing.plus-slots", 12));
        }

        return Math.max(0, config.getInt("listing.default-slots", 3));
    }

    public boolean canList(Player player) {
        if (player == null) {
            return false;
        }

        if (player.hasPermission("mineacleauctionhouse.admin")
                || player.hasPermission("mineacleauctionhouse.sell")) {
            return true;
        }

        if (!player.hasPermission("mineacleauctionhouse.use")) {
            return false;
        }

        if (config.getBoolean("listing.allow-default", true)) {
            return true;
        }

        String plusPermission = config.getString(
                "listing.plus-permission",
                "mineacle.plus"
        );

        return plusPermission != null
                && !plusPermission.isBlank()
                && player.hasPermission(plusPermission);
    }

    public synchronized int activeListingCount(UUID owner) {
        if (owner == null) {
            return 0;
        }

        int count = 0;

        for (AuctionHouseListing listing : listings.values()) {
            if (listing.owner().equals(owner)) {
                count++;
            }
        }

        return count;
    }

    public synchronized List<AuctionHouseListing> search(
            String query,
            SortMode sortMode
    ) {
        String normalizedQuery = normalizeSearchQuery(query);
        SortMode effectiveSort = sortMode == null
                ? SortMode.LOWEST_PRICE
                : sortMode;

        List<AuctionHouseListing> result = new ArrayList<>();

        for (AuctionHouseListing listing : listings.values()) {
            if (normalizedQuery.isBlank()
                    || matches(listing.item(), normalizedQuery)) {
                result.add(listing);
            }
        }

        Comparator<AuctionHouseListing> comparator = switch (effectiveSort) {
            case LOWEST_PRICE -> Comparator
                    .comparingLong(AuctionHouseListing::priceCents)
                    .thenComparingLong(AuctionHouseListing::createdAt);
            case HIGHEST_PRICE -> Comparator
                    .comparingLong(AuctionHouseListing::priceCents)
                    .reversed()
                    .thenComparingLong(AuctionHouseListing::createdAt);
            case RECENTLY_LISTED -> Comparator
                    .comparingLong(AuctionHouseListing::createdAt)
                    .reversed();
        };

        result.sort(comparator);
        return result;
    }

    public synchronized List<AuctionHouseListing> ownerListings(UUID owner) {
        List<AuctionHouseListing> result = new ArrayList<>();

        if (owner == null) {
            return result;
        }

        for (AuctionHouseListing listing : listings.values()) {
            if (listing.owner().equals(owner)) {
                result.add(listing);
            }
        }

        result.sort(
                Comparator.comparingLong(AuctionHouseListing::createdAt)
                        .reversed()
        );
        return result;
    }

    public synchronized AuctionHouseListing listing(UUID id) {
        return id == null ? null : listings.get(id);
    }

    public synchronized CreateOutcome createListing(
            Player player,
            long priceCents
    ) {
        if (!enabled()) {
            return new CreateOutcome(CreateResult.DISABLED, null);
        }

        if (!canList(player)) {
            return new CreateOutcome(CreateResult.NO_PERMISSION, null);
        }

        if (priceCents <= 0L) {
            return new CreateOutcome(CreateResult.INVALID_PRICE, null);
        }

        if (priceCents < minPriceCents()) {
            return new CreateOutcome(CreateResult.BELOW_MINIMUM, null);
        }

        if (priceCents > maxPriceCents()) {
            return new CreateOutcome(CreateResult.ABOVE_MAXIMUM, null);
        }

        ItemStack held = player.getInventory().getItemInMainHand();

        if (held == null || held.getType().isAir() || held.getAmount() <= 0) {
            return new CreateOutcome(CreateResult.NO_ITEM, null);
        }

        if (activeListingCount(player.getUniqueId()) >= listingLimit(player)) {
            return new CreateOutcome(CreateResult.NO_SLOT, null);
        }

        ItemStack saleItem = held.clone();
        AuctionHouseListing listing = new AuctionHouseListing(
                UUID.randomUUID(),
                player.getUniqueId(),
                DisplayNames.username(player),
                saleItem,
                priceCents,
                System.currentTimeMillis()
        );

        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        listings.put(listing.id(), listing);

        if (!persistListings()) {
            listings.remove(listing.id());
            player.getInventory().setItemInMainHand(held);
            return new CreateOutcome(CreateResult.STORAGE_ERROR, null);
        }

        return new CreateOutcome(CreateResult.SUCCESS, listing);
    }

    public synchronized CancelResult cancelListing(Player player, UUID id) {
        AuctionHouseListing listing = listings.get(id);

        if (listing == null) {
            return CancelResult.NOT_FOUND;
        }

        if (!listing.owner().equals(player.getUniqueId())
                && !player.hasPermission("mineacleauctionhouse.admin")) {
            return CancelResult.NOT_OWNER;
        }

        ItemStack item = listing.item();

        if (!canFit(player.getInventory(), item)) {
            return CancelResult.INVENTORY_FULL;
        }

        listings.remove(id);

        if (!persistListings()) {
            listings.put(id, listing);
            return CancelResult.STORAGE_ERROR;
        }

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);

        if (!leftovers.isEmpty()) {
            int leftoverAmount = totalAmount(leftovers);
            int addedAmount = Math.max(0, item.getAmount() - leftoverAmount);
            removeSimilarItems(player.getInventory(), item, addedAmount);

            listings.put(id, listing);

            if (!persistListings()) {
                core.getLogger().severe(
                        "Could not restore auction listing "
                                + id
                                + " after an inventory delivery failure"
                );
                return CancelResult.STORAGE_ERROR;
            }

            return CancelResult.INVENTORY_FULL;
        }

        return CancelResult.SUCCESS;
    }

    public synchronized BuyOutcome buy(Player buyer, UUID id) {
        AuctionHouseListing listing = listings.get(id);

        if (listing == null) {
            return new BuyOutcome(BuyResult.NOT_FOUND, null);
        }

        if (listing.owner().equals(buyer.getUniqueId())) {
            return new BuyOutcome(BuyResult.OWN_ITEM, listing);
        }

        ItemStack item = listing.item();

        if (!canFit(buyer.getInventory(), item)) {
            return new BuyOutcome(BuyResult.INVENTORY_FULL, listing);
        }

        Economy economy = economy();

        if (economy == null) {
            return new BuyOutcome(BuyResult.ECONOMY_MISSING, listing);
        }

        double price = listing.priceCents() / 100.0D;
        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.owner());

        try {
            if (!economy.has(buyer, price)) {
                return new BuyOutcome(BuyResult.NOT_ENOUGH_MONEY, listing);
            }
        } catch (Exception exception) {
            core.getLogger().warning(
                    "Could not check auction buyer balance: "
                            + exception.getMessage()
            );
            return new BuyOutcome(BuyResult.PAYMENT_FAILED, listing);
        }

        EconomyResponse withdrawal;

        try {
            withdrawal = economy.withdrawPlayer(buyer, price);
        } catch (Exception exception) {
            core.getLogger().warning(
                    "Could not withdraw auction payment: "
                            + exception.getMessage()
            );
            return new BuyOutcome(BuyResult.PAYMENT_FAILED, listing);
        }

        if (withdrawal == null || !withdrawal.transactionSuccess()) {
            return new BuyOutcome(BuyResult.NOT_ENOUGH_MONEY, listing);
        }

        EconomyResponse deposit;

        try {
            deposit = economy.depositPlayer(seller, price);
        } catch (Exception exception) {
            refundBuyer(economy, buyer, price, listing.id());
            core.getLogger().warning(
                    "Could not deposit auction payment: "
                            + exception.getMessage()
            );
            return new BuyOutcome(BuyResult.PAYMENT_FAILED, listing);
        }

        if (deposit == null || !deposit.transactionSuccess()) {
            refundBuyer(economy, buyer, price, listing.id());
            return new BuyOutcome(BuyResult.PAYMENT_FAILED, listing);
        }

        listings.remove(id);

        if (!persistListings()) {
            listings.put(id, listing);
            rollbackPayment(economy, buyer, seller, price, listing.id());
            return new BuyOutcome(BuyResult.STORAGE_ERROR, listing);
        }

        Map<Integer, ItemStack> leftovers = buyer.getInventory().addItem(item);

        if (!leftovers.isEmpty()) {
            int leftoverAmount = totalAmount(leftovers);
            int addedAmount = Math.max(0, item.getAmount() - leftoverAmount);
            removeSimilarItems(buyer.getInventory(), item, addedAmount);

            listings.put(id, listing);
            boolean restored = persistListings();
            rollbackPayment(economy, buyer, seller, price, listing.id());

            if (!restored) {
                core.getLogger().severe(
                        "Could not restore auction listing "
                                + id
                                + " after a buyer inventory failure"
                );
                return new BuyOutcome(BuyResult.STORAGE_ERROR, listing);
            }

            return new BuyOutcome(BuyResult.INVENTORY_FULL, listing);
        }

        notifySeller(listing);
        return new BuyOutcome(BuyResult.SUCCESS, listing);
    }

    public Economy economy() {
        RegisteredServiceProvider<Economy> provider = core
                .getServer()
                .getServicesManager()
                .getRegistration(Economy.class);

        return provider == null ? null : provider.getProvider();
    }

    public long parsePriceCents(String raw) {
        return MoneyFormatter.parsePositiveCents(raw);
    }

    public String format(long cents) {
        return MoneyFormatter.moneyFromCents(cents);
    }

    public String itemName(ItemStack item) {
        if (item == null) {
            return "Item";
        }

        ItemMeta meta = item.getItemMeta();

        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return PlainTextComponentSerializer.plainText()
                    .serialize(meta.displayName());
        }

        String raw = item
                .getType()
                .name()
                .toLowerCase(Locale.ROOT)
                .replace("_", " ");

        String[] parts = raw.split(" ");
        StringBuilder name = new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            if (!name.isEmpty()) {
                name.append(' ');
            }

            name.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1));
        }

        return name.toString();
    }

    public String sellerDisplayName(AuctionHouseListing listing) {
        if (listing == null) {
            return "Unknown";
        }

        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.owner());
        String displayName = TextColor.strip(DisplayNames.displayName(seller));

        if (displayName == null || displayName.isBlank()) {
            displayName = TextColor.strip(listing.ownerName());
        }

        return displayName == null || displayName.isBlank()
                ? "Unknown"
                : displayName;
    }

    public String sanitizeSearchQuery(String query) {
        if (query == null) {
            return "";
        }

        String sanitized = TextColor
                .strip(query)
                .replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (sanitized.length() > maxSearchLength()) {
            return sanitized.substring(0, maxSearchLength()).trim();
        }

        return sanitized;
    }

    public boolean searchQueryTooLong(String query) {
        if (query == null) {
            return false;
        }

        String plain = TextColor
                .strip(query)
                .replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return plain.length() > maxSearchLength();
    }

    private void ensureDataFolder() {
        if (!core.getDataFolder().exists()
                && !core.getDataFolder().mkdirs()) {
            core.getLogger().warning(
                    "Could not create MineacleCore data folder"
            );
        }
    }

    private void ensureConfigFile() {
        if (configFile.exists()) {
            return;
        }

        try {
            core.saveResource("auctionhouse.yml", false);
        } catch (IllegalArgumentException exception) {
            try {
                if (!configFile.createNewFile()) {
                    core.getLogger().warning(
                            "Could not create auctionhouse.yml"
                    );
                }
            } catch (IOException ioException) {
                core.getLogger().severe(
                        "Could not create auctionhouse.yml: "
                                + ioException.getMessage()
                );
            }
        }
    }

    private boolean persistListings() {
        ensureDataFolder();

        YamlConfiguration next = new YamlConfiguration();

        for (AuctionHouseListing listing : listings.values()) {
            String path = "listings." + listing.id();

            next.set(path + ".owner", listing.owner().toString());
            next.set(path + ".owner-name", listing.ownerName());
            next.set(path + ".price-cents", listing.priceCents());
            next.set(path + ".created-at", listing.createdAt());
            next.set(path + ".item", listing.item());
        }

        File temporary = new File(
                storageFile.getParentFile(),
                storageFile.getName() + ".tmp"
        );

        try {
            next.save(temporary);

            try {
                Files.move(
                        temporary.toPath(),
                        storageFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary.toPath(),
                        storageFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            storage = next;
            return true;
        } catch (IOException exception) {
            core.getLogger().severe(
                    "Could not save auctionhouse-data.yml: "
                            + exception.getMessage()
            );

            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (IOException ignored) {
            }

            return false;
        }
    }

    private boolean canFit(PlayerInventory inventory, ItemStack item) {
        int remaining = item.getAmount();
        int maxStackSize = item.getMaxStackSize();

        for (ItemStack content : inventory.getStorageContents()) {
            if (content == null || content.getType().isAir()) {
                remaining -= maxStackSize;
            } else if (content.isSimilar(item)) {
                remaining -= Math.max(
                        0,
                        content.getMaxStackSize() - content.getAmount()
                );
            }

            if (remaining <= 0) {
                return true;
            }
        }

        return false;
    }

    private int totalAmount(Map<Integer, ItemStack> items) {
        int total = 0;

        for (ItemStack item : items.values()) {
            if (item != null) {
                total += item.getAmount();
            }
        }

        return total;
    }

    private void removeSimilarItems(
            PlayerInventory inventory,
            ItemStack template,
            int amount
    ) {
        int remaining = Math.max(0, amount);

        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            if (remaining <= 0) {
                return;
            }

            ItemStack content = inventory.getItem(slot);

            if (content == null
                    || content.getType().isAir()
                    || !content.isSimilar(template)) {
                continue;
            }

            int removed = Math.min(content.getAmount(), remaining);
            int newAmount = content.getAmount() - removed;
            remaining -= removed;

            if (newAmount <= 0) {
                inventory.setItem(slot, null);
            } else {
                content.setAmount(newAmount);
                inventory.setItem(slot, content);
            }
        }
    }

    private void refundBuyer(
            Economy economy,
            Player buyer,
            double price,
            UUID listingId
    ) {
        try {
            EconomyResponse response = economy.depositPlayer(buyer, price);

            if (response == null || !response.transactionSuccess()) {
                core.getLogger().severe(
                        "Could not refund buyer "
                                + buyer.getUniqueId()
                                + " for auction listing "
                                + listingId
                );
            }
        } catch (Exception exception) {
            core.getLogger().severe(
                    "Could not refund buyer "
                            + buyer.getUniqueId()
                            + " for auction listing "
                            + listingId
                            + ": "
                            + exception.getMessage()
            );
        }
    }

    private void rollbackPayment(
            Economy economy,
            Player buyer,
            OfflinePlayer seller,
            double price,
            UUID listingId
    ) {
        boolean sellerReversed = false;
        boolean buyerRefunded = false;

        try {
            EconomyResponse response = economy.withdrawPlayer(seller, price);
            sellerReversed = response != null && response.transactionSuccess();
        } catch (Exception ignored) {
        }

        try {
            EconomyResponse response = economy.depositPlayer(buyer, price);
            buyerRefunded = response != null && response.transactionSuccess();
        } catch (Exception ignored) {
        }

        if (!sellerReversed || !buyerRefunded) {
            core.getLogger().severe(
                    "Incomplete auction payment rollback for listing "
                            + listingId
                            + " sellerReversed="
                            + sellerReversed
                            + " buyerRefunded="
                            + buyerRefunded
            );
        }
    }

    private void notifySeller(AuctionHouseListing listing) {
        Player seller = Bukkit.getPlayer(listing.owner());

        if (seller == null || !seller.isOnline()) {
            return;
        }

        seller.sendMessage(
                TextColor.color(
                        "&#bbbbbbYour &d"
                                + itemName(listing.item())
                                + " &#bbbbbbsold for &a+"
                                + format(listing.priceCents())
                )
        );
        SoundService.economyReceive(seller, core);
    }

    private long dollarsToCents(double dollars) {
        if (!Double.isFinite(dollars) || dollars <= 0.0D) {
            return 0L;
        }

        BigDecimal value = BigDecimal.valueOf(dollars)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP);

        try {
            return value.longValueExact();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
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
        String sanitized = sanitizeSearchQuery(query);
        String normalized = normalize(sanitized);

        ConfigurationSection aliases = config.getConfigurationSection(
                "search-aliases"
        );

        if (aliases == null) {
            return normalized;
        }

        for (String alias : aliases.getKeys(false)) {
            String aliasNormalized = normalize(alias);
            String targetNormalized = normalize(
                    config.getString("search-aliases." + alias, alias)
            );

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

        if (meta.hasDisplayName() && meta.displayName() != null) {
            builder.append(
                    normalize(
                            PlainTextComponentSerializer.plainText()
                                    .serialize(meta.displayName())
                    )
            ).append(' ');
        }

        if (meta.hasLore() && meta.lore() != null) {
            for (Component lore : meta.lore()) {
                builder.append(
                        normalize(
                                PlainTextComponentSerializer.plainText()
                                        .serialize(lore)
                        )
                ).append(' ');
            }
        }

        if (meta.hasEnchants()) {
            for (Enchantment enchantment : meta.getEnchants().keySet()) {
                builder.append(
                        normalize(enchantment.getKey().getKey())
                ).append(' ');
            }
        }

        if (meta instanceof EnchantmentStorageMeta storageMeta
                && storageMeta.hasStoredEnchants()) {
            for (Enchantment enchantment : storageMeta
                    .getStoredEnchants()
                    .keySet()) {
                builder.append(
                        normalize(enchantment.getKey().getKey())
                ).append(' ');
            }
        }

        return builder.toString();
    }

    private String normalize(String input) {
        return input == null
                ? ""
                : input
                .toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
    }

}
