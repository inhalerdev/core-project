package net.mineacle.core.sell.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.sell.model.SaleResult;
import net.mineacle.core.sell.model.SellHistoryEntry;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }

            if (!canSell(item)) {
                returned.add(item.clone());
                continue;
            }

            Material material = item.getType();
            int amount = item.getAmount();
            long unitCents = unitWorthCents(playerId, material);
            long payout = unitCents * amount;

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
                addMultiplierProgress(playerId, category(material), amount);
                addServerVolume(material, amount, cents);
            }

            save();
        }

        return new SaleResult(totalCents > 0L, totalCents, totalAmount, returned);
    }

    public long unitWorthCents(UUID playerId, Material material) {
        long base = baseWorthCents(material);
        String category = category(material);
        double multiplier = multiplier(playerId, category);

        return Math.max(1L, Math.round(base * multiplier));
    }

    public long unitWorthCents(Player player, Material material) {
        return unitWorthCents(player.getUniqueId(), material);
    }

    public boolean canSell(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }

        Material material = item.getType();

        if (!sellConfig.getBoolean("settings.allow-all-items-with-fallback", true)
                && !sellConfig.contains("prices." + material.name())) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            if (sellConfig.getBoolean("settings.deny-custom-items", true)) {
                if (meta.hasDisplayName() || meta.hasLore() || meta.hasCustomModelData()) {
                    return false;
                }
            }

            if (sellConfig.getBoolean("settings.deny-enchanted-items", true) && meta.hasEnchants()) {
                return false;
            }

            if (sellConfig.getBoolean("settings.deny-damaged-tools", true) && meta instanceof Damageable damageable) {
                if (damageable.hasDamage() && damageable.getDamage() > 0) {
                    return false;
                }
            }

            if (sellConfig.getBoolean("settings.deny-filled-containers", true)
                    && meta instanceof BlockStateMeta blockStateMeta
                    && blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox) {
                for (ItemStack content : shulkerBox.getInventory().getContents()) {
                    if (content != null && content.getType() != Material.AIR) {
                        return false;
                    }
                }
            }
        }

        return baseWorthCents(material) > 0L;
    }

    public long baseWorthCents(Material material) {
        String path = "prices." + material.name() + ".price";

        if (sellConfig.contains(path)) {
            return dollarsToCents(sellConfig.getDouble(path, 0.0D));
        }

        return dollarsToCents(fallbackPrice(material));
    }

    public String category(Material material) {
        String configured = sellConfig.getString("prices." + material.name() + ".category");

        if (configured != null && !configured.isBlank()) {
            return normalizeCategory(configured);
        }

        String name = material.name();

        if (name.contains("INGOT") || name.contains("ORE") || name.contains("DIAMOND") || name.contains("EMERALD") || name.contains("QUARTZ") || name.contains("LAPIS") || name.contains("REDSTONE")) {
            return "ores";
        }

        if (name.contains("LOG") || name.contains("WOOD") || name.contains("PLANK")) {
            return "wood";
        }

        if (name.contains("BEEF") || name.contains("PORK") || name.contains("CHICKEN") || name.contains("MUTTON") || name.contains("ROTTEN") || name.contains("BONE") || name.contains("STRING") || name.contains("GUNPOWDER")) {
            return "mob_drops";
        }

        if (name.contains("WHEAT") || name.contains("CARROT") || name.contains("POTATO") || name.contains("SEEDS") || name.contains("MELON") || name.contains("PUMPKIN") || name.contains("SUGAR_CANE")) {
            return "farming";
        }

        if (name.contains("NETHER")) {
            return "nether";
        }

        if (name.contains("END")) {
            return "end";
        }

        if (material.isBlock()) {
            return "blocks";
        }

        return "misc";
    }

    public String categoryDisplay(Material material) {
        String category = category(material);
        String configured = sellConfig.getString("multipliers.categories." + category + ".display-name");

        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        return prettyCategory(category);
    }

    public double multiplier(UUID playerId, String category) {
        String normalized = normalizeCategory(category);
        long soldAmount = sellConfig.getLong("multipliers.players." + playerId + "." + normalized + ".sold-amount", 0L);

        double base = sellConfig.getDouble("multipliers.categories." + normalized + ".base-multiplier", 1.0D);
        long amountPerLevel = Math.max(1L, sellConfig.getLong("multipliers.categories." + normalized + ".amount-per-level", 5000L));
        double increase = sellConfig.getDouble("multipliers.categories." + normalized + ".increase-per-level", 0.02D);
        double max = sellConfig.getDouble("multipliers.categories." + normalized + ".max-multiplier",
                sellConfig.getDouble("multipliers.max-multiplier", 2.0D));

        long levels = soldAmount / amountPerLevel;
        double multiplier = base + (levels * increase);

        return Math.min(max, multiplier);
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

    private void addMultiplierProgress(UUID playerId, String category, long amount) {
        if (!sellConfig.getBoolean("multipliers.enabled", true)) {
            return;
        }

        String path = "multipliers.players." + playerId + "." + normalizeCategory(category) + ".sold-amount";
        sellConfig.set(path, sellConfig.getLong(path, 0L) + amount);
    }

    private void addServerVolume(Material material, long amount, long cents) {
        String path = "server-volume." + material.name();

        sellConfig.set(path + ".amount", sellConfig.getLong(path + ".amount", 0L) + amount);
        sellConfig.set(path + ".total-cents", sellConfig.getLong(path + ".total-cents", 0L) + cents);
        sellConfig.set(path + ".last-sold", System.currentTimeMillis());
    }

    private double fallbackPrice(Material material) {
        String category = category(material);
        return sellConfig.getDouble("fallback-prices." + category,
                sellConfig.getDouble("fallback-prices.misc", 0.01D));
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

    private void ensureDefaults() {
        sellConfig.addDefault("settings.allow-all-items-with-fallback", true);
        sellConfig.addDefault("settings.deny-custom-items", true);
        sellConfig.addDefault("settings.deny-damaged-tools", true);
        sellConfig.addDefault("settings.deny-filled-containers", true);
        sellConfig.addDefault("settings.deny-enchanted-items", true);

        sellConfig.addDefault("fallback-prices.blocks", 0.03D);
        sellConfig.addDefault("fallback-prices.ores", 1.0D);
        sellConfig.addDefault("fallback-prices.wood", 0.12D);
        sellConfig.addDefault("fallback-prices.farming", 0.08D);
        sellConfig.addDefault("fallback-prices.mob_drops", 0.15D);
        sellConfig.addDefault("fallback-prices.nether", 0.2D);
        sellConfig.addDefault("fallback-prices.end", 0.35D);
        sellConfig.addDefault("fallback-prices.misc", 0.01D);

        defaultPrice(Material.DIAMOND, 250.0D, "ores");
        defaultPrice(Material.EMERALD, 200.0D, "ores");
        defaultPrice(Material.NETHERITE_INGOT, 3000.0D, "ores");
        defaultPrice(Material.GOLD_INGOT, 14.0D, "ores");
        defaultPrice(Material.IRON_INGOT, 8.0D, "ores");
        defaultPrice(Material.COPPER_INGOT, 3.0D, "ores");
        defaultPrice(Material.COAL, 2.0D, "ores");
        defaultPrice(Material.REDSTONE, 1.5D, "ores");
        defaultPrice(Material.LAPIS_LAZULI, 2.0D, "ores");
        defaultPrice(Material.QUARTZ, 2.0D, "nether");

        defaultPrice(Material.OAK_LOG, 1.25D, "wood");
        defaultPrice(Material.SPRUCE_LOG, 1.25D, "wood");
        defaultPrice(Material.BIRCH_LOG, 1.25D, "wood");
        defaultPrice(Material.JUNGLE_LOG, 1.25D, "wood");
        defaultPrice(Material.ACACIA_LOG, 1.25D, "wood");
        defaultPrice(Material.DARK_OAK_LOG, 1.25D, "wood");
        defaultPrice(Material.CHERRY_LOG, 1.25D, "wood");
        defaultPrice(Material.MANGROVE_LOG, 1.25D, "wood");

        defaultPrice(Material.WHEAT, 0.25D, "farming");
        defaultPrice(Material.CARROT, 0.22D, "farming");
        defaultPrice(Material.POTATO, 0.22D, "farming");
        defaultPrice(Material.SUGAR_CANE, 0.18D, "farming");
        defaultPrice(Material.MELON_SLICE, 0.08D, "farming");
        defaultPrice(Material.PUMPKIN, 0.35D, "farming");

        defaultPrice(Material.ROTTEN_FLESH, 0.08D, "mob_drops");
        defaultPrice(Material.BONE, 0.12D, "mob_drops");
        defaultPrice(Material.STRING, 0.1D, "mob_drops");
        defaultPrice(Material.GUNPOWDER, 0.25D, "mob_drops");
        defaultPrice(Material.SPIDER_EYE, 0.18D, "mob_drops");
        defaultPrice(Material.ENDER_PEARL, 2.0D, "end");

        defaultCategory("blocks", "Blocks", 5000L, 0.02D);
        defaultCategory("ores", "Ores", 500L, 0.03D);
        defaultCategory("wood", "Wood", 2500L, 0.02D);
        defaultCategory("farming", "Farming", 2500L, 0.02D);
        defaultCategory("mob_drops", "Mob Drops", 1500L, 0.02D);
        defaultCategory("nether", "Nether", 1500L, 0.025D);
        defaultCategory("end", "End", 1000L, 0.025D);
        defaultCategory("misc", "Misc", 5000L, 0.01D);

        sellConfig.addDefault("multipliers.enabled", true);
        sellConfig.addDefault("multipliers.max-multiplier", 2.0D);

        sellConfig.options().copyDefaults(true);
    }

    private void defaultPrice(Material material, double price, String category) {
        sellConfig.addDefault("prices." + material.name() + ".price", price);
        sellConfig.addDefault("prices." + material.name() + ".category", category);
    }

    private void defaultCategory(String id, String displayName, long amountPerLevel, double increasePerLevel) {
        sellConfig.addDefault("multipliers.categories." + id + ".display-name", displayName);
        sellConfig.addDefault("multipliers.categories." + id + ".base-multiplier", 1.0D);
        sellConfig.addDefault("multipliers.categories." + id + ".amount-per-level", amountPerLevel);
        sellConfig.addDefault("multipliers.categories." + id + ".increase-per-level", increasePerLevel);
        sellConfig.addDefault("multipliers.categories." + id + ".max-multiplier", 2.0D);
    }


    public List<String> multiplierCategories() {
        List<String> categories = new ArrayList<>();
        ConfigurationSection section = sellConfig.getConfigurationSection("multipliers.categories");

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
            categories.add("misc");
        }

        return categories;
    }

    public String categoryDisplay(String category) {
        String normalized = normalizeCategory(category);
        String configured = sellConfig.getString("multipliers.categories." + normalized + ".display-name");

        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        return prettyCategory(normalized);
    }

    public long categorySoldAmount(UUID playerId, String category) {
        String normalized = normalizeCategory(category);
        return sellConfig.getLong("multipliers.players." + playerId + "." + normalized + ".sold-amount", 0L);
    }

    public long categoryAmountPerLevel(String category) {
        String normalized = normalizeCategory(category);
        return Math.max(1L, sellConfig.getLong("multipliers.categories." + normalized + ".amount-per-level", 5000L));
    }

    public double categoryIncreasePerLevel(String category) {
        String normalized = normalizeCategory(category);
        return sellConfig.getDouble("multipliers.categories." + normalized + ".increase-per-level", 0.02D);
    }

    public double categoryMaxMultiplier(String category) {
        String normalized = normalizeCategory(category);
        return sellConfig.getDouble("multipliers.categories." + normalized + ".max-multiplier",
                sellConfig.getDouble("multipliers.max-multiplier", 2.0D));
    }

    public long categoryProgressAmount(UUID playerId, String category) {
        long sold = categorySoldAmount(playerId, category);
        long amountPerLevel = categoryAmountPerLevel(category);
        return sold % amountPerLevel;
    }

    public long categoryRemainingAmount(UUID playerId, String category) {
        long amountPerLevel = categoryAmountPerLevel(category);
        long progress = categoryProgressAmount(playerId, category);
        return Math.max(0L, amountPerLevel - progress);
    }

    public String message(String path, String fallback) {
        return TextColor.color(sellConfig.getString("messages." + path, fallback));
    }
}
