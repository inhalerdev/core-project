package net.mineacle.core.sell.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.sell.model.SaleResult;
import net.mineacle.core.sell.model.SellHistoryEntry;
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
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class SellService {

    private final Core core;

    private File sellFile;
    private FileConfiguration sellConfig;

    public SellService(Core core) {
        this.core = core;
        reload();
    }

    public void reload() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        sellFile = new File(core.getDataFolder(), "sell.yml");

        if (!sellFile.exists()) {
            try {
                core.saveResource("sell.yml", false);
            } catch (IllegalArgumentException ignored) {
                try {
                    sellFile.createNewFile();
                } catch (IOException exception) {
                    core.getLogger().severe("Could not create sell.yml");
                    exception.printStackTrace();
                }
            }
        }

        sellConfig = YamlConfiguration.loadConfiguration(sellFile);
        ensureDefaults();
        save();
    }

    public void save() {
        if (sellFile == null || sellConfig == null) {
            return;
        }

        try {
            sellConfig.save(sellFile);
        } catch (IOException exception) {
            core.getLogger().severe("Could not save sell.yml");
            exception.printStackTrace();
        }
    }

    public String format(long cents) {
        return MoneyFormatter.moneyFromCents(cents);
    }

    public SaleResult sellInventory(UUID playerId, Inventory inventory) {
        Map<Material, Long> soldAmounts = new EnumMap<>(Material.class);
        Map<Material, Long> soldCents = new EnumMap<>(Material.class);
        List<ItemStack> returned = new ArrayList<>();

        long totalCents = 0L;
        long totalAmount = 0L;

        for (ItemStack rawItem : inventory.getContents()) {
            ItemStack item = stripWorthLore(rawItem);

            if (item == null || item.getType().isAir()) {
                continue;
            }

            if (!canSell(item)) {
                returned.add(item.clone());
                continue;
            }

            Material material = item.getType();
            int amount = item.getAmount();
            long payout = stackWorthCents(playerId, item);

            if (payout <= 0L) {
                returned.add(item.clone());
                continue;
            }

            totalCents += payout;
            totalAmount += amount;
            soldAmounts.merge(material, (long) amount, Long::sum);
            soldCents.merge(material, payout, Long::sum);
        }

        inventory.clear();

        if (totalCents > 0L) {
            EconomyService economy = EconomyModule.economyService();
            if (economy != null) {
                economy.give(playerId, totalCents);
            }

            long now = System.currentTimeMillis();

            for (Map.Entry<Material, Long> entry : soldAmounts.entrySet()) {
                Material material = entry.getKey();
                long amount = entry.getValue();
                long cents = soldCents.getOrDefault(material, 0L);

                addHistory(playerId, material, amount, cents, now);
                addServerVolume(material, amount, cents);
            }

            save();
        }

        return new SaleResult(totalCents > 0L, totalCents, totalAmount, returned);
    }

    public long unitWorthCents(UUID playerId, Material material) {
        long base = baseWorthCents(material);

        if (base <= 0L) {
            return 0L;
        }

        double demandMultiplier = demandMultiplier(material);
        return Math.max(1L, Math.round(base * demandMultiplier));
    }

    public long unitWorthCents(Player player, Material material) {
        return unitWorthCents(player.getUniqueId(), material);
    }

    public boolean canSell(ItemStack item) {
        item = stripWorthLore(item);

        if (item == null || item.getType().isAir()) {
            return false;
        }

        Material material = item.getType();

        if (!material.isItem()) {
            return false;
        }

        if (isBlockedMaterial(material)) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();

        if (sellConfig.getBoolean("settings.deny-damaged-tools", false)
                && meta instanceof Damageable damageable
                && damageable.hasDamage()
                && damageable.getDamage() > 0) {
            return false;
        }

        if (sellConfig.getBoolean("settings.deny-enchanted-items", false) && meta != null && meta.hasEnchants()) {
            return false;
        }

        if (sellConfig.getBoolean("settings.deny-filled-containers", true)
                && meta instanceof BlockStateMeta blockStateMeta
                && blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox) {
            for (ItemStack content : shulkerBox.getInventory().getContents()) {
                if (content != null && !content.getType().isAir()) {
                    return false;
                }
            }
        }

        return baseWorthCents(material) > 0L;
    }

    public void applyWorthLore(Player player, Inventory inventory) {
        if (player == null || inventory == null) {
            return;
        }

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack rawItem = inventory.getItem(slot);
            ItemStack item = stripWorthLore(rawItem);

            if (item == null || item.getType().isAir()) {
                continue;
            }

            if (!canSell(item)) {
                inventory.setItem(slot, item);
                continue;
            }

            ItemMeta meta = item.getItemMeta();

            if (meta == null) {
                inventory.setItem(slot, item);
                continue;
            }

            long totalWorth = stackWorthCents(player, item);
            List<String> lore = new ArrayList<>();
            lore.add(TextColor.color("&#bbbbbbWorth: &a" + format(totalWorth)));
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }
    }

    public ItemStack stripWorthLore(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return item;
        }

        ItemStack clone = item.clone();
        ItemMeta meta = clone.getItemMeta();

        if (meta == null || !meta.hasLore() || meta.getLore() == null) {
            return clone;
        }

        List<String> cleaned = new ArrayList<>();

        for (String line : meta.getLore()) {
            String stripped = ChatColor.stripColor(line);

            if (stripped == null) {
                continue;
            }

            String lower = stripped.toLowerCase(Locale.ROOT);

            if (lower.startsWith("worth:")
                    || lower.startsWith("price:")
                    || lower.startsWith("base:")
                    || lower.startsWith("stack:")
                    || lower.startsWith("stack worth:")
                    || lower.startsWith("enchant value:")
                    || lower.startsWith("demand:")
                    || lower.startsWith("category:")
                    || lower.startsWith("sold this cycle:")) {
                continue;
            }

            cleaned.add(line);
        }

        meta.setLore(cleaned.isEmpty() ? null : cleaned);
        clone.setItemMeta(meta);
        return clone;
    }

    public long stackWorthCents(UUID playerId, ItemStack item) {
        item = stripWorthLore(item);

        if (item == null || item.getType().isAir()) {
            return 0L;
        }

        long baseStackWorth = unitWorthCents(playerId, item.getType()) * Math.max(1, item.getAmount());
        long enchantWorth = enchantWorthCents(item);

        return Math.max(1L, baseStackWorth + enchantWorth);
    }

    public long stackWorthCents(Player player, ItemStack item) {
        if (player == null) {
            return 0L;
        }

        return stackWorthCents(player.getUniqueId(), item);
    }

    public long enchantWorthCents(ItemStack item) {
        item = stripWorthLore(item);

        if (item == null || item.getType().isAir()) {
            return 0L;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null || !meta.hasEnchants()) {
            return 0L;
        }

        long total = 0L;
        long perLevel = Math.max(0L, sellConfig.getLong("enchant-values.default-per-level-cents", 2500L));
        double treasureMultiplier = Math.max(0.0D, sellConfig.getDouble("enchant-values.treasure-multiplier", 2.0D));
        double unsafeMultiplier = Math.max(0.0D, sellConfig.getDouble("enchant-values.unsafe-multiplier", 1.25D));

        for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
            Enchantment enchantment = entry.getKey();
            int level = Math.max(1, entry.getValue());

            long enchantBase = sellConfig.getLong(
                    "enchant-values.enchants." + enchantment.getKey().getKey().toUpperCase(Locale.ROOT),
                    perLevel
            );

            double multiplier = 1.0D;

            if (enchantment.isTreasure()) {
                multiplier *= treasureMultiplier;
            }

            if (level > enchantment.getMaxLevel()) {
                multiplier *= unsafeMultiplier;
            }

            total += Math.round(enchantBase * level * multiplier);
        }

        return Math.max(0L, total);
    }

    public long baseWorthCents(Material material) {
        if (material == null || !material.isItem() || isBlockedMaterial(material)) {
            return 0L;
        }

        String path = "prices." + material.name() + ".price";

        if (sellConfig.contains(path)) {
            return dollarsToCents(sellConfig.getDouble(path, 0.0D));
        }

        if (!sellConfig.getBoolean("settings.allow-all-items-with-fallback", true)) {
            return 0L;
        }

        return dollarsToCents(fallbackPrice(material));
    }

    public String category(Material material) {
        String configured = sellConfig.getString("prices." + material.name() + ".category");

        if (configured != null && !configured.isBlank()) {
            return normalizeCategory(configured);
        }

        String name = material.name();

        if (name.contains("INGOT")
                || name.contains("ORE")
                || name.contains("DIAMOND")
                || name.contains("EMERALD")
                || name.contains("QUARTZ")
                || name.contains("LAPIS")
                || name.contains("REDSTONE")
                || name.contains("NETHERITE")
                || name.contains("DEBRIS")) {
            return "ores";
        }

        if (name.contains("LOG") || name.contains("WOOD") || name.contains("PLANK")) {
            return "wood";
        }

        if (name.contains("BEEF")
                || name.contains("PORK")
                || name.contains("CHICKEN")
                || name.contains("MUTTON")
                || name.contains("ROTTEN")
                || name.contains("BONE")
                || name.contains("STRING")
                || name.contains("GUNPOWDER")) {
            return "mob_drops";
        }

        if (name.contains("WHEAT")
                || name.contains("CARROT")
                || name.contains("POTATO")
                || name.contains("SEEDS")
                || name.contains("MELON")
                || name.contains("PUMPKIN")
                || name.contains("SUGAR_CANE")
                || name.contains("CACTUS")) {
            return "farming";
        }

        if (name.contains("NETHER") || name.contains("BLAZE") || name.contains("GHAST") || name.contains("MAGMA")) {
            return "nether";
        }

        if (name.contains("END") || name.contains("SHULKER") || name.contains("ELYTRA")) {
            return "end";
        }

        if (name.contains("SWORD")
                || name.contains("HELMET")
                || name.contains("CHESTPLATE")
                || name.contains("LEGGINGS")
                || name.contains("BOOTS")
                || name.equals("BOW")
                || name.equals("CROSSBOW")
                || name.equals("TRIDENT")
                || name.equals("SHIELD")) {
            return "combat";
        }

        if (material.isBlock()) {
            return "blocks";
        }

        return "misc";
    }

    public String categoryDisplay(Material material) {
        return categoryDisplay(category(material));
    }

    public String categoryDisplay(String category) {
        String normalized = normalizeCategory(category);
        String configured = sellConfig.getString("categories." + normalized + ".display-name");

        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        return prettyCategory(normalized);
    }

    public List<SellHistoryEntry> history(UUID playerId) {
        List<SellHistoryEntry> entries = new ArrayList<>();
        ConfigurationSection section = sellConfig.getConfigurationSection("history." + playerId);

        if (section == null) {
            return entries;
        }

        for (String key : section.getKeys(false)) {
            Material material = material(key);

            if (material == null) {
                continue;
            }

            String path = "history." + playerId + "." + key;
            entries.add(new SellHistoryEntry(
                    material,
                    sellConfig.getLong(path + ".amount", 0L),
                    sellConfig.getLong(path + ".total-cents", 0L),
                    sellConfig.getLong(path + ".last-sold", 0L)
            ));
        }

        return entries;
    }

    public String pretty(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            if (!builder.isEmpty()) {
                builder.append(' ');
            }

            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }

        return builder.toString();
    }

    public static String formatMultiplier(double value) {
        BigDecimal decimal = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return decimal.toPlainString();
    }

    private void addHistory(UUID playerId, Material material, long amount, long cents, long now) {
        String path = "history." + playerId + "." + material.name();

        sellConfig.set(path + ".amount", sellConfig.getLong(path + ".amount", 0L) + amount);
        sellConfig.set(path + ".total-cents", sellConfig.getLong(path + ".total-cents", 0L) + cents);
        sellConfig.set(path + ".last-sold", now);
    }

    private void addServerVolume(Material material, long amount, long cents) {
        String totalPath = "server-volume." + material.name();
        sellConfig.set(totalPath + ".amount", sellConfig.getLong(totalPath + ".amount", 0L) + amount);
        sellConfig.set(totalPath + ".total-cents", sellConfig.getLong(totalPath + ".total-cents", 0L) + cents);
        sellConfig.set(totalPath + ".last-sold", System.currentTimeMillis());

        if (isActiveDemandItem(material)) {
            String activePath = "demand.active." + material.name();
            long sold = sellConfig.getLong(activePath + ".sold-this-cycle", 0L) + amount;
            long soldCents = sellConfig.getLong(activePath + ".sold-cents-this-cycle", 0L) + cents;

            sellConfig.set(activePath + ".sold-this-cycle", sold);
            sellConfig.set(activePath + ".sold-cents-this-cycle", soldCents);
            sellConfig.set(activePath + ".multiplier", adjustedDemandMultiplier(material));
        }
    }

    private double fallbackPrice(Material material) {
        String category = category(material);
        return sellConfig.getDouble("fallback-prices." + category, sellConfig.getDouble("fallback-prices.misc", 0.01D));
    }

    private long dollarsToCents(double dollars) {
        if (dollars <= 0.0D) {
            return 0L;
        }

        return BigDecimal.valueOf(dollars)
                .setScale(2, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100L))
                .longValue();
    }

    private Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return Material.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String normalizeCategory(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
    }

    private String prettyCategory(String raw) {
        String[] parts = normalizeCategory(raw).split("_");
        StringBuilder builder = new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            if (!builder.isEmpty()) {
                builder.append(' ');
            }

            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }

        return builder.toString();
    }

    public String message(String path, String fallback) {
        return TextColor.color(sellConfig.getString("messages." + path, fallback));
    }

    public boolean demandEnabled() {
        return sellConfig.getBoolean("demand.enabled", true);
    }

    public long demandLastUpdate() {
        return sellConfig.getLong("demand.last-rotation", 0L);
    }

    public long demandUpdateIntervalMillis() {
        return Math.max(1L, sellConfig.getLong("demand.cycle-hours", 24L)) * 60L * 60L * 1000L;
    }

    public boolean demandNeedsUpdate() {
        return demandEnabled() && System.currentTimeMillis() - demandLastUpdate() >= demandUpdateIntervalMillis();
    }

    public void recalculateDemandIfNeeded() {
        if (!demandEnabled()) {
            return;
        }

        if (demandNeedsUpdate() || activeDemandMaterials().isEmpty()) {
            rotateDemand();
            return;
        }

        for (Material material : activeDemandMaterials()) {
            sellConfig.set("demand.active." + material.name() + ".multiplier", adjustedDemandMultiplier(material));
        }
    }

    public void recalculateDemand() {
        rotateDemand();
    }

    public void rotateDemand() {
        if (!demandEnabled()) {
            return;
        }

        List<Material> pool = demandPool();
        Collections.shuffle(pool, new Random(System.currentTimeMillis()));

        int activeLimit = Math.max(1, sellConfig.getInt("demand.active-items", 12));
        List<Material> active = pool.stream()
                .filter(material -> !isDemandExcluded(material))
                .filter(material -> baseWorthCents(material) > 0L)
                .limit(activeLimit)
                .toList();

        sellConfig.set("demand.active", null);

        List<String> tiers = demandTierOrder();
        int tierIndex = 0;

        for (Material material : active) {
            String tier = tiers.get(tierIndex % tiers.size());
            String path = "demand.active." + material.name();

            sellConfig.set(path + ".tier", tier);
            sellConfig.set(path + ".base-multiplier", tierMultiplier(tier));
            sellConfig.set(path + ".multiplier", tierMultiplier(tier));
            sellConfig.set(path + ".sold-this-cycle", 0L);
            sellConfig.set(path + ".sold-cents-this-cycle", 0L);

            tierIndex++;
        }

        sellConfig.set("demand.last-rotation", System.currentTimeMillis());
        save();
    }

    public void resetDemandData() {
        sellConfig.set("demand.active", null);
        sellConfig.set("server-volume", null);
        sellConfig.set("demand.last-rotation", 0L);
        save();
    }

    public boolean hasDemandAdjustment(Material material) {
        return Math.abs(demandMultiplier(material) - 1.0D) >= 0.01D;
    }

    public double demandMultiplier(Material material) {
        if (!demandEnabled() || material == null || isDemandExcluded(material)) {
            return 1.0D;
        }

        recalculateDemandIfNeeded();

        if (!isActiveDemandItem(material)) {
            return 1.0D;
        }

        return adjustedDemandMultiplier(material);
    }

    public double adjustedDemandMultiplier(Material material) {
        if (material == null || !isActiveDemandItem(material)) {
            return 1.0D;
        }

        String path = "demand.active." + material.name();
        double base = sellConfig.getDouble(path + ".base-multiplier", 1.0D);
        long sold = sellConfig.getLong(path + ".sold-this-cycle", 0L);
        long reduceAt = Math.max(1L, sellConfig.getLong("demand.reduce-after-sold", 10000L));
        long oversoldAt = Math.max(reduceAt, sellConfig.getLong("demand.oversold-after-sold", 25000L));
        double oversoldMultiplier = sellConfig.getDouble("demand.tiers.oversold", 0.65D);

        double multiplier = base;

        if (sold >= oversoldAt) {
            multiplier = oversoldMultiplier;
        } else if (sold >= reduceAt) {
            double progress = (double) (sold - reduceAt) / Math.max(1.0D, (double) (oversoldAt - reduceAt));
            multiplier = base - ((base - 1.0D) * Math.min(1.0D, progress));
        }

        return BigDecimal.valueOf(Math.max(0.01D, multiplier)).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public long demandWindowAmount(Material material) {
        if (material == null) {
            return 0L;
        }

        if (isActiveDemandItem(material)) {
            return Math.max(0L, sellConfig.getLong("demand.active." + material.name() + ".sold-this-cycle", 0L));
        }

        return Math.max(0L, sellConfig.getLong("server-volume." + material.name() + ".amount", 0L));
    }

    public long demandWindowTotalCents(Material material) {
        if (material == null) {
            return 0L;
        }

        if (isActiveDemandItem(material)) {
            return Math.max(0L, sellConfig.getLong("demand.active." + material.name() + ".sold-cents-this-cycle", 0L));
        }

        return Math.max(0L, sellConfig.getLong("server-volume." + material.name() + ".total-cents", 0L));
    }

    public boolean isDemandExcluded(Material material) {
        if (material == null) {
            return true;
        }

        for (String excluded : sellConfig.getStringList("demand.excluded-items")) {
            if (material.name().equalsIgnoreCase(excluded)) {
                return true;
            }
        }

        return false;
    }

    public boolean isActiveDemandItem(Material material) {
        return material != null && sellConfig.contains("demand.active." + material.name());
    }

    public String demandTier(Material material) {
        if (material == null || !isActiveDemandItem(material)) {
            return "normal";
        }

        return sellConfig.getString("demand.active." + material.name() + ".tier", "normal");
    }

    public String demandTierDisplay(Material material) {
        return prettyCategory(demandTier(material));
    }

    private List<Material> activeDemandMaterials() {
        ConfigurationSection section = sellConfig.getConfigurationSection("demand.active");

        if (section == null) {
            return List.of();
        }

        List<Material> materials = new ArrayList<>();

        for (String key : section.getKeys(false)) {
            Material material = material(key);

            if (material != null) {
                materials.add(material);
            }
        }

        materials.sort(Comparator.comparing(this::pretty, String.CASE_INSENSITIVE_ORDER));
        return materials;
    }

    private List<Material> demandPool() {
        Set<Material> materials = new LinkedHashSet<>();
        ConfigurationSection section = sellConfig.getConfigurationSection("demand.pool");

        if (section != null) {
            for (String category : section.getKeys(false)) {
                for (String raw : sellConfig.getStringList("demand.pool." + category)) {
                    Material material = material(raw);

                    if (material != null && canSell(new ItemStack(material))) {
                        materials.add(material);
                    }
                }
            }
        }

        if (!materials.isEmpty()) {
            return new ArrayList<>(materials);
        }

        for (Material material : Material.values()) {
            if (material.isItem() && material != Material.AIR && canSell(new ItemStack(material))) {
                materials.add(material);
            }
        }

        return new ArrayList<>(materials);
    }

    private List<String> demandTierOrder() {
        List<String> configured = sellConfig.getStringList("demand.tier-order");
        List<String> result = new ArrayList<>();

        for (String tier : configured) {
            if (tier != null && !tier.isBlank() && sellConfig.contains("demand.tiers." + normalizeCategory(tier))) {
                result.add(normalizeCategory(tier));
            }
        }

        if (result.isEmpty()) {
            result.add("wanted");
            result.add("high_demand");
            result.add("premium");
        }

        return result;
    }

    private double tierMultiplier(String tier) {
        return Math.max(0.01D, sellConfig.getDouble("demand.tiers." + normalizeCategory(tier), 1.0D));
    }

    private boolean isBlockedMaterial(Material material) {
        if (material == null || material == Material.AIR) {
            return true;
        }

        for (String blocked : sellConfig.getStringList("settings.blocked-items")) {
            if (material.name().equalsIgnoreCase(blocked)) {
                return true;
            }
        }

        return false;
    }

    /*
     * Compatibility methods kept for older GUI/command code.
     * Mineacle no longer uses per-player sell multipliers.
     */
    public double multiplier(UUID playerId, String category) {
        return 1.0D;
    }

    public List<String> multiplierCategories() {
        List<String> categories = new ArrayList<>();
        ConfigurationSection section = sellConfig.getConfigurationSection("categories");

        if (section != null) {
            categories.addAll(section.getKeys(false));
        }

        if (categories.isEmpty()) {
            categories.add("blocks");
            categories.add("ores");
            categories.add("wood");
            categories.add("farming");
            categories.add("mob_drops");
            categories.add("nether");
            categories.add("end");
            categories.add("combat");
            categories.add("misc");
        }

        return categories;
    }

    public long categorySoldAmount(UUID playerId, String category) {
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

    public long categoryProgressAmount(UUID playerId, String category) {
        return 0L;
    }

    public long categoryRemainingAmount(UUID playerId, String category) {
        return 0L;
    }

    private void ensureDefaults() {
        sellConfig.addDefault("settings.allow-all-items-with-fallback", true);
        sellConfig.addDefault("settings.deny-custom-items", false);
        sellConfig.addDefault("settings.deny-damaged-tools", false);
        sellConfig.addDefault("settings.deny-filled-containers", true);
        sellConfig.addDefault("settings.deny-enchanted-items", false);
        sellConfig.addDefault("settings.blocked-items", List.of(
                "BEDROCK",
                "BARRIER",
                "COMMAND_BLOCK",
                "CHAIN_COMMAND_BLOCK",
                "REPEATING_COMMAND_BLOCK",
                "STRUCTURE_BLOCK",
                "STRUCTURE_VOID",
                "JIGSAW",
                "DEBUG_STICK",
                "PLAYER_HEAD"
        ));

        sellConfig.addDefault("fallback-prices.blocks", 0.03D);
        sellConfig.addDefault("fallback-prices.ores", 1.0D);
        sellConfig.addDefault("fallback-prices.wood", 0.12D);
        sellConfig.addDefault("fallback-prices.farming", 0.08D);
        sellConfig.addDefault("fallback-prices.mob_drops", 0.15D);
        sellConfig.addDefault("fallback-prices.nether", 0.2D);
        sellConfig.addDefault("fallback-prices.end", 0.35D);
        sellConfig.addDefault("fallback-prices.combat", 0.5D);
        sellConfig.addDefault("fallback-prices.misc", 0.01D);

        defaultCategory("blocks", "Blocks");
        defaultCategory("ores", "Ores");
        defaultCategory("wood", "Wood");
        defaultCategory("farming", "Farming");
        defaultCategory("mob_drops", "Mob Drops");
        defaultCategory("nether", "Nether");
        defaultCategory("end", "End");
        defaultCategory("combat", "Combat");
        defaultCategory("misc", "Misc");

        defaultPrices();

        sellConfig.addDefault("demand.enabled", true);
        sellConfig.addDefault("demand.cycle-hours", 24);
        sellConfig.addDefault("demand.active-items", 12);
        sellConfig.addDefault("demand.reduce-after-sold", 10000);
        sellConfig.addDefault("demand.oversold-after-sold", 25000);
        sellConfig.addDefault("demand.tiers.oversold", 0.65D);
        sellConfig.addDefault("demand.tiers.normal", 1.0D);
        sellConfig.addDefault("demand.tiers.wanted", 1.25D);
        sellConfig.addDefault("demand.tiers.high_demand", 1.75D);
        sellConfig.addDefault("demand.tiers.premium", 2.25D);
        sellConfig.addDefault("demand.tiers.shortage", 2.5D);
        sellConfig.addDefault("demand.tier-order", List.of("wanted", "high_demand", "premium", "high_demand", "wanted"));
        sellConfig.addDefault("demand.excluded-items", sellConfig.getStringList("settings.blocked-items"));

        sellConfig.addDefault("demand.pool.farming", List.of("SUGAR_CANE", "WHEAT", "CARROT", "POTATO", "PUMPKIN", "MELON_SLICE", "CACTUS"));
        sellConfig.addDefault("demand.pool.mob_drops", List.of("GUNPOWDER", "BONE", "STRING", "ROTTEN_FLESH", "SPIDER_EYE", "SLIME_BALL"));
        sellConfig.addDefault("demand.pool.mining", List.of("COAL", "IRON_INGOT", "COPPER_INGOT", "GOLD_INGOT", "REDSTONE", "LAPIS_LAZULI", "AMETHYST_SHARD", "DIAMOND", "EMERALD"));
        sellConfig.addDefault("demand.pool.nether", List.of("BLAZE_ROD", "BLAZE_POWDER", "GHAST_TEAR", "MAGMA_CREAM", "QUARTZ"));
        sellConfig.addDefault("demand.pool.end", List.of("ENDER_PEARL", "SHULKER_SHELL"));
        sellConfig.addDefault("demand.pool.utility", List.of("ENDER_CHEST", "ANVIL", "NAME_TAG", "SADDLE"));

        sellConfig.addDefault("enchant-values.default-per-level-cents", 2500L);
        sellConfig.addDefault("enchant-values.treasure-multiplier", 2.0D);
        sellConfig.addDefault("enchant-values.unsafe-multiplier", 1.25D);
        sellConfig.addDefault("enchant-values.enchants.MENDING", 10000L);
        sellConfig.addDefault("enchant-values.enchants.PROTECTION", 3500L);
        sellConfig.addDefault("enchant-values.enchants.SHARPNESS", 3500L);
        sellConfig.addDefault("enchant-values.enchants.EFFICIENCY", 3000L);
        sellConfig.addDefault("enchant-values.enchants.UNBREAKING", 2500L);
        sellConfig.addDefault("enchant-values.enchants.FORTUNE", 5000L);
        sellConfig.addDefault("enchant-values.enchants.SILK_TOUCH", 7500L);

        sellConfig.addDefault("messages.sold-chat", "&#bbbbbbSold &#ff88ff%amount%x items &#bbbbbbfor &a+%money%");
        sellConfig.addDefault("messages.sold-actionbar", "&a+%money%");

        sellConfig.options().copyDefaults(true);
    }

    private void defaultCategory(String id, String displayName) {
        sellConfig.addDefault("categories." + id + ".display-name", displayName);
    }

    private void defaultPrices() {
        defaultPrice(Material.DRAGON_EGG, 50000.0D, "end");
        defaultPrice(Material.NETHERITE_BLOCK, 27000.0D, "ores");
        defaultPrice(Material.ELYTRA, 15000.0D, "end");
        defaultPrice(Material.BEACON, 12000.0D, "misc");
        defaultPrice(Material.NETHER_STAR, 10000.0D, "misc");
        defaultPrice(Material.ENCHANTED_GOLDEN_APPLE, 5000.0D, "combat");
        defaultPrice(Material.NETHERITE_INGOT, 3000.0D, "ores");
        defaultPrice(Material.TOTEM_OF_UNDYING, 2500.0D, "combat");
        defaultPrice(Material.DIAMOND_BLOCK, 2250.0D, "ores");
        defaultPrice(Material.EMERALD_BLOCK, 1800.0D, "ores");
        defaultPrice(Material.NETHERITE_SCRAP, 750.0D, "ores");
        defaultPrice(Material.ANCIENT_DEBRIS, 750.0D, "ores");
        defaultPrice(Material.GOLDEN_APPLE, 750.0D, "combat");
        defaultPrice(Material.END_CRYSTAL, 350.0D, "combat");
        defaultPrice(Material.DIAMOND, 250.0D, "ores");
        defaultPrice(Material.EMERALD, 200.0D, "ores");
        defaultPrice(Material.GOLD_BLOCK, 126.0D, "ores");
        defaultPrice(Material.IRON_BLOCK, 72.0D, "ores");
        defaultPrice(Material.COPPER_BLOCK, 27.0D, "ores");
        defaultPrice(Material.AMETHYST_BLOCK, 24.0D, "ores");
        defaultPrice(Material.COAL_BLOCK, 18.0D, "ores");
        defaultPrice(Material.LAPIS_BLOCK, 18.0D, "ores");
        defaultPrice(Material.REDSTONE_BLOCK, 13.5D, "ores");
        defaultPrice(Material.QUARTZ_BLOCK, 8.0D, "nether");
        defaultPrice(Material.RAW_GOLD_BLOCK, 126.0D, "ores");
        defaultPrice(Material.RAW_IRON_BLOCK, 72.0D, "ores");
        defaultPrice(Material.RAW_COPPER_BLOCK, 27.0D, "ores");
        defaultPrice(Material.GOLD_INGOT, 14.0D, "ores");
        defaultPrice(Material.IRON_INGOT, 8.0D, "ores");
        defaultPrice(Material.AMETHYST_SHARD, 6.0D, "ores");
        defaultPrice(Material.COPPER_INGOT, 3.0D, "ores");
        defaultPrice(Material.COAL, 2.0D, "ores");
        defaultPrice(Material.QUARTZ, 2.0D, "nether");
        defaultPrice(Material.LAPIS_LAZULI, 2.0D, "ores");
        defaultPrice(Material.ENDER_PEARL, 2.0D, "end");
        defaultPrice(Material.REDSTONE, 1.5D, "ores");
        defaultPrice(Material.BLAZE_ROD, 1.5D, "nether");
        defaultPrice(Material.CHARCOAL, 1.5D, "ores");
        defaultPrice(Material.OAK_LOG, 1.25D, "wood");
        defaultPrice(Material.SPRUCE_LOG, 1.25D, "wood");
        defaultPrice(Material.BIRCH_LOG, 1.25D, "wood");
        defaultPrice(Material.JUNGLE_LOG, 1.25D, "wood");
        defaultPrice(Material.ACACIA_LOG, 1.25D, "wood");
        defaultPrice(Material.DARK_OAK_LOG, 1.25D, "wood");
        defaultPrice(Material.CHERRY_LOG, 1.25D, "wood");
        defaultPrice(Material.MANGROVE_LOG, 1.25D, "wood");
        defaultPrice(Material.GHAST_TEAR, 4.0D, "nether");
        defaultPrice(Material.SHULKER_SHELL, 20.0D, "end");
        defaultPrice(Material.BLAZE_POWDER, 0.75D, "nether");
        defaultPrice(Material.MAGMA_CREAM, 0.7D, "nether");
        defaultPrice(Material.SLIME_BALL, 0.6D, "mob_drops");
        defaultPrice(Material.PUMPKIN, 0.35D, "farming");
        defaultPrice(Material.WHEAT, 0.25D, "farming");
        defaultPrice(Material.GUNPOWDER, 0.25D, "mob_drops");
        defaultPrice(Material.CARROT, 0.22D, "farming");
        defaultPrice(Material.POTATO, 0.22D, "farming");
        defaultPrice(Material.SUGAR_CANE, 0.18D, "farming");
        defaultPrice(Material.SPIDER_EYE, 0.18D, "mob_drops");
        defaultPrice(Material.BONE, 0.12D, "mob_drops");
        defaultPrice(Material.STRING, 0.1D, "mob_drops");
        defaultPrice(Material.ROTTEN_FLESH, 0.08D, "mob_drops");
        defaultPrice(Material.MELON_SLICE, 0.08D, "farming");
    }

    private void defaultPrice(Material material, double price, String category) {
        sellConfig.addDefault("prices." + material.name() + ".price", price);
        sellConfig.addDefault("prices." + material.name() + ".category", category);
    }
}
