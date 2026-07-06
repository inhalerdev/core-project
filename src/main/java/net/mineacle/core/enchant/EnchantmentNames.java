package net.mineacle.core.enchant;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EnchantmentNames {

    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        alias("aqua_affinity", "aqua", "water_worker");
        alias("bane_of_arthropods", "bane", "boa", "arthropods");
        alias("binding_curse", "curse_of_binding", "binding");
        alias("blast_protection", "blast_prot", "blastprot");
        alias("breach");
        alias("channeling", "channelling");
        alias("density");
        alias("depth_strider", "depth");
        alias("efficiency", "eff", "dig_speed");
        alias("feather_falling", "feather", "fall_protection");
        alias("fire_aspect", "fire");
        alias("fire_protection", "fire_prot", "fireprot");
        alias("flame", "arrow_fire");
        alias("fortune", "loot_bonus_blocks");
        alias("frost_walker", "frost");
        alias("impaling");
        alias("infinity", "infinite", "arrow_infinite");
        alias("knockback", "kb");
        alias("looting", "loot_bonus_mobs");
        alias("loyalty");
        alias("luck_of_the_sea", "luck", "luck_of_sea");
        alias("lure");
        alias("mending", "mend");
        alias("multishot");
        alias("piercing");
        alias("power", "arrow_damage");
        alias("projectile_protection", "projectile_prot", "proj_prot", "projprot");
        alias("protection", "prot", "protect");
        alias("punch", "arrow_knockback");
        alias("quick_charge", "quickcharge");
        alias("respiration", "oxygen");
        alias("riptide");
        alias("sharpness", "sharp", "damage_all");
        alias("silk_touch", "silk");
        alias("smite", "damage_undead");
        alias("soul_speed", "soulspeed");
        alias("sweeping_edge", "sweeping", "sweep", "damage_sweep");
        alias("swift_sneak", "swiftsneak");
        alias("thorns");
        alias("unbreaking", "unbreak", "durability");
        alias("vanishing_curse", "curse_of_vanishing", "vanishing");
        alias("wind_burst", "windburst");
    }

    private EnchantmentNames() {
    }

    public static Enchantment find(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String normalized = normalize(input);
        String alias = ALIASES.getOrDefault(normalized, normalized);

        Enchantment direct = byKey(alias);
        if (direct != null) {
            return direct;
        }

        for (Enchantment enchantment : Registry.ENCHANTMENT) {
            String key = key(enchantment);
            if (normalize(key).equals(alias)) {
                return enchantment;
            }
        }

        return null;
    }

    public static List<String> completions(String input) {
        String partial = normalize(input);
        List<String> options = new ArrayList<>();

        for (Enchantment enchantment : Registry.ENCHANTMENT) {
            String key = key(enchantment);

            if (partial.isBlank() || normalize(key).startsWith(partial)) {
                options.add(key);
            }
        }

        options.sort(String.CASE_INSENSITIVE_ORDER);
        return options;
    }

    public static List<String> allNamesWithMaxLevel() {
        List<String> names = new ArrayList<>();

        for (Enchantment enchantment : Registry.ENCHANTMENT) {
            names.add(key(enchantment) + " " + enchantment.getMaxLevel());
        }

        names.sort(Comparator.naturalOrder());
        return names;
    }

    public static String key(Enchantment enchantment) {
        return enchantment.getKey().getKey();
    }

    public static String displayName(Enchantment enchantment) {
        String key = key(enchantment);
        String[] parts = key.split("_");
        StringBuilder builder = new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            if (!builder.isEmpty()) {
                builder.append(' ');
            }

            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }

        return builder.toString();
    }

    private static Enchantment byKey(String key) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        if (namespacedKey == null) {
            namespacedKey = NamespacedKey.minecraft(key);
        }

        Enchantment enchantment = Registry.ENCHANTMENT.get(namespacedKey);
        if (enchantment != null) {
            return enchantment;
        }

        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
    }

    private static void alias(String vanillaKey, String... aliases) {
        ALIASES.put(normalize(vanillaKey), vanillaKey);

        for (String alias : aliases) {
            ALIASES.put(normalize(alias), vanillaKey);
        }
    }

    private static String normalize(String input) {
        String normalized = input.trim().toLowerCase(Locale.ROOT);

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }

        return normalized
                .replace('-', '_')
                .replace(" ", "_");
    }
}
