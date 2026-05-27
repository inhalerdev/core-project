package net.mineacle.core.enchant.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EnchantInfoCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final Map<String, Info> info = new LinkedHashMap<>();

    public EnchantInfoCommand(Core core) {
        this.core = core;
        registerInfo();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (!player.hasPermission("mineacleenchant.admin")) {
            player.sendMessage(color("&cYou do not have permission"));
            SoundService.guiError(player, core);
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(color("&#ff88ffEnchant Info"));
            player.sendMessage(color("&#bbbbbb/enchantinfo <enchant>"));
            player.sendMessage(color("&#bbbbbbExample: &#ff88ff/enchantinfo sharpness"));
            SoundService.economyBalance(player, core);
            return true;
        }

        Enchantment enchantment = enchantment(args[0]);

        if (enchantment == null) {
            player.sendMessage(color("&cUnknown enchantment"));
            SoundService.guiError(player, core);
            return true;
        }

        String key = key(enchantment);
        Info enchantInfo = info.getOrDefault(key, fallback(enchantment));

        player.sendMessage(color("&#ff88ff" + pretty(enchantment)));
        player.sendMessage(color("&#bbbbbbMax Level: &#ff88ff" + Math.max(1, enchantment.getMaxLevel())));
        player.sendMessage(color("&#bbbbbbUse: &#ff88ff" + enchantInfo.description()));
        player.sendMessage(color("&#bbbbbbRarity: &#ff88ff" + enchantInfo.rarity()));
        player.sendMessage(color("&#bbbbbbFound: &#ff88ff" + enchantInfo.found()));
        player.sendMessage(color("&#bbbbbbApplies To: &#ff88ff" + enchantInfo.items()));
        SoundService.economyBalance(player, core);
        return true;
    }

    private void registerInfo() {
        add("aqua_affinity", "Speeds up underwater mining", "Common", "Enchanting table, books, fishing, loot, villagers", "Helmets and turtle shells");
        add("bane_of_arthropods", "Increases damage to spiders, cave spiders, bees, silverfish, and endermites", "Common", "Enchanting table, books, loot, villagers", "Swords and axes");
        add("blast_protection", "Reduces explosion damage and knockback", "Uncommon", "Enchanting table, books, loot, villagers", "Armor");
        add("breach", "Reduces armor effectiveness when hitting with a mace", "Rare", "Enchanting table, ominous vaults, books", "Maces");
        add("channeling", "Summons lightning with a trident during thunderstorms", "Very Rare", "Enchanting table, books, loot, villagers", "Tridents");
        add("binding_curse", "Prevents armor from being removed normally", "Curse", "Loot, fishing, villagers, ancient sources", "Wearable items");
        add("vanishing_curse", "Destroys the item when the holder dies", "Curse", "Loot, fishing, villagers, ancient sources", "Most enchantable items");
        add("density", "Increases mace smash damage based on fall distance", "Rare", "Enchanting table, ominous vaults, books", "Maces");
        add("depth_strider", "Improves underwater movement speed", "Rare", "Enchanting table, books, loot, villagers", "Boots");
        add("efficiency", "Increases mining speed", "Common", "Enchanting table, books, loot, villagers", "Tools, shears, axes, hoes");
        add("feather_falling", "Reduces fall damage", "Uncommon", "Enchanting table, books, loot, villagers", "Boots");
        add("fire_aspect", "Sets targets on fire", "Rare", "Enchanting table, books, loot, villagers", "Swords");
        add("fire_protection", "Reduces fire and lava damage", "Uncommon", "Enchanting table, books, loot, villagers", "Armor");
        add("flame", "Sets arrows on fire", "Rare", "Enchanting table, books, loot, villagers", "Bows");
        add("fortune", "Increases block drops from many ores and crops", "Rare", "Enchanting table, books, loot, villagers", "Pickaxes, axes, shovels, hoes");
        add("frost_walker", "Creates temporary ice while walking over water", "Treasure", "Loot, fishing, villagers", "Boots");
        add("impaling", "Increases trident damage against aquatic mobs in Java", "Rare", "Enchanting table, books, loot, villagers", "Tridents");
        add("infinity", "Allows shooting arrows without consuming normal arrows", "Very Rare", "Enchanting table, books, loot, villagers", "Bows");
        add("knockback", "Increases sword knockback", "Uncommon", "Enchanting table, books, loot, villagers", "Swords");
        add("looting", "Increases mob drops", "Rare", "Enchanting table, books, loot, villagers", "Swords");
        add("loyalty", "Returns a thrown trident", "Uncommon", "Enchanting table, books, loot, villagers", "Tridents");
        add("luck_of_the_sea", "Improves fishing treasure odds", "Rare", "Enchanting table, books, fishing, loot, villagers", "Fishing rods");
        add("lure", "Reduces fishing wait time", "Rare", "Enchanting table, books, fishing, loot, villagers", "Fishing rods");
        add("mending", "Repairs the item using experience", "Treasure", "Villagers, fishing, loot, ancient city/chest sources", "Most durable items");
        add("multishot", "Shoots three projectiles at once", "Rare", "Enchanting table, books, loot, villagers", "Crossbows");
        add("piercing", "Allows crossbow arrows to pierce entities and shields", "Common", "Enchanting table, books, loot, villagers", "Crossbows");
        add("power", "Increases bow damage", "Common", "Enchanting table, books, loot, villagers", "Bows");
        add("projectile_protection", "Reduces projectile damage", "Uncommon", "Enchanting table, books, loot, villagers", "Armor");
        add("protection", "Reduces most incoming damage", "Rare", "Enchanting table, books, loot, villagers", "Armor");
        add("punch", "Increases bow knockback", "Rare", "Enchanting table, books, loot, villagers", "Bows");
        add("quick_charge", "Reduces crossbow reload time", "Uncommon", "Enchanting table, books, loot, villagers", "Crossbows");
        add("respiration", "Extends underwater breathing", "Rare", "Enchanting table, books, loot, villagers", "Helmets and turtle shells");
        add("riptide", "Launches the player with a trident in water or rain", "Rare", "Enchanting table, books, loot, villagers", "Tridents");
        add("sharpness", "Increases melee damage", "Common", "Enchanting table, books, loot, villagers", "Swords and axes");
        add("silk_touch", "Drops blocks themselves instead of normal drops", "Very Rare", "Enchanting table, books, loot, villagers", "Tools");
        add("smite", "Increases damage to undead mobs", "Common", "Enchanting table, books, loot, villagers", "Swords and axes");
        add("soul_speed", "Increases speed on soul sand and soul soil", "Treasure", "Piglin bartering, bastion loot", "Boots");
        add("sweeping_edge", "Increases sweep attack damage", "Rare", "Enchanting table, books, loot, villagers", "Swords");
        add("swift_sneak", "Increases movement speed while sneaking", "Treasure", "Ancient city loot", "Leggings");
        add("thorns", "Damages attackers", "Very Rare", "Enchanting table, books, loot, villagers", "Armor");
        add("unbreaking", "Increases item durability", "Uncommon", "Enchanting table, books, loot, villagers", "Most durable items");
        add("wind_burst", "Launches the wielder upward after a mace smash hit", "Very Rare", "Ominous vaults", "Maces");
    }

    private void add(String key, String description, String rarity, String found, String items) {
        info.put(key, new Info(description, rarity, found, items));
    }

    private Info fallback(Enchantment enchantment) {
        String items = "Compatible items only";
        String found = "Enchanting table, books, loot, or villagers";
        String rarity = enchantment.getMaxLevel() <= 1 ? "Rare" : "Common";
        return new Info("Vanilla enchantment effect", rarity, found, items);
    }

    private Enchantment enchantment(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String normalized = input.toLowerCase(Locale.ROOT)
                .replace("minecraft:", "")
                .replace(" ", "_")
                .replace("-", "_");

        Enchantment exact = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(normalized));

        if (exact != null) {
            return exact;
        }

        for (Enchantment enchantment : enchantments()) {
            if (key(enchantment).equalsIgnoreCase(normalized)) {
                return enchantment;
            }

            if (pretty(enchantment).replace(" ", "_").equalsIgnoreCase(normalized)) {
                return enchantment;
            }
        }

        return null;
    }

    private List<Enchantment> enchantments() {
        List<Enchantment> list = new ArrayList<>();
        Registry.ENCHANTMENT.forEach(list::add);
        list.sort(Comparator.comparing(this::key));
        return list;
    }

    private String key(Enchantment enchantment) {
        NamespacedKey key = enchantment.getKey();
        return key == null ? pretty(enchantment).toLowerCase(Locale.ROOT).replace(" ", "_") : key.getKey();
    }

    private String pretty(Enchantment enchantment) {
        String raw = key(enchantment).replace("_", " ");
        StringBuilder builder = new StringBuilder();

        for (String part : raw.split(" ")) {
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

    private String color(String message) {
        return TextColor.color(message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player player) || !player.hasPermission("mineacleenchant.admin")) {
            return completions;
        }

        if (args.length != 1) {
            return completions;
        }

        String partial = args[0].toLowerCase(Locale.ROOT);

        for (Enchantment enchantment : enchantments()) {
            String key = key(enchantment);

            if (key.startsWith(partial)) {
                completions.add(key);
            }
        }

        return completions;
    }

    private record Info(String description, String rarity, String found, String items) {
    }
}
