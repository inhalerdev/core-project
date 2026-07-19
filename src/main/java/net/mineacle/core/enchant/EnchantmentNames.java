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

    private static final Map<String, String> ALIASES =
            new LinkedHashMap<>();
    private static final Map<String, Enchantment> BY_NAME =
            new LinkedHashMap<>();
    private static final List<String> CANONICAL_NAMES;

    static {
        alias("aqua_affinity", "aqua", "water_worker");
        alias(
                "bane_of_arthropods",
                "bane",
                "boa",
                "arthropods"
        );
        alias(
                "binding_curse",
                "curse_of_binding",
                "binding"
        );
        alias(
                "blast_protection",
                "blast_prot",
                "blastprot"
        );
        alias("breach");
        alias("channeling", "channelling");
        alias("density");
        alias("depth_strider", "depth");
        alias("efficiency", "eff", "dig_speed");
        alias(
                "feather_falling",
                "feather",
                "fall_protection"
        );
        alias("fire_aspect", "fire");
        alias(
                "fire_protection",
                "fire_prot",
                "fireprot"
        );
        alias("flame", "arrow_fire");
        alias("fortune", "loot_bonus_blocks");
        alias("frost_walker", "frost");
        alias("impaling");
        alias(
                "infinity",
                "infinite",
                "arrow_infinite"
        );
        alias("knockback", "kb");
        alias("looting", "loot_bonus_mobs");
        alias("loyalty");
        alias(
                "luck_of_the_sea",
                "luck",
                "luck_of_sea"
        );
        alias("lunge");
        alias("lure");
        alias("mending", "mend");
        alias("multishot");
        alias("piercing");
        alias("power", "arrow_damage");
        alias(
                "projectile_protection",
                "projectile_prot",
                "proj_prot",
                "projprot"
        );
        alias("protection", "prot", "protect");
        alias("punch", "arrow_knockback");
        alias("quick_charge", "quickcharge");
        alias("respiration", "oxygen");
        alias("riptide");
        alias("sharpness", "sharp", "damage_all");
        alias("silk_touch", "silk");
        alias("smite", "damage_undead");
        alias("soul_speed", "soulspeed");
        alias(
                "sweeping_edge",
                "sweeping",
                "sweep",
                "damage_sweep"
        );
        alias("swift_sneak", "swiftsneak");
        alias("thorns");
        alias(
                "unbreaking",
                "unbreak",
                "durability"
        );
        alias(
                "vanishing_curse",
                "curse_of_vanishing",
                "vanishing"
        );
        alias("wind_burst", "windburst");

        List<String> canonical = new ArrayList<>();

        for (Enchantment enchantment : Registry.ENCHANTMENT) {
            String key = key(enchantment);
            String normalized = normalize(key);

            BY_NAME.put(normalized, enchantment);
            BY_NAME.put("minecraft:" + normalized, enchantment);
            canonical.add(key);
        }

        for (Map.Entry<String, String> entry
                : ALIASES.entrySet()) {
            Enchantment enchantment =
                    BY_NAME.get(normalize(entry.getValue()));

            if (enchantment != null) {
                BY_NAME.put(
                        normalize(entry.getKey()),
                        enchantment
                );
            }
        }

        canonical.sort(String.CASE_INSENSITIVE_ORDER);
        CANONICAL_NAMES = List.copyOf(canonical);
    }

    private EnchantmentNames() {
    }

    public static Enchantment find(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String normalized = normalize(input);
        Enchantment cached = BY_NAME.get(normalized);

        if (cached != null) {
            return cached;
        }

        String alias = ALIASES.get(normalized);

        if (alias != null) {
            return BY_NAME.get(normalize(alias));
        }

        NamespacedKey key = NamespacedKey.fromString(
                input.trim().toLowerCase(Locale.ROOT)
        );

        if (key == null) {
            key = NamespacedKey.minecraft(normalized);
        }

        return Registry.ENCHANTMENT.get(key);
    }

    public static List<String> completions(String input) {
        String partial = normalize(input);
        List<String> options = new ArrayList<>();

        for (String name : CANONICAL_NAMES) {
            if (partial.isBlank()
                    || normalize(name).startsWith(partial)) {
                options.add(name);
            }
        }

        return List.copyOf(options);
    }

    public static List<String> allNamesWithMaxLevel() {
        List<String> names = new ArrayList<>();

        for (Enchantment enchantment : Registry.ENCHANTMENT) {
            names.add(
                    key(enchantment)
                            + " "
                            + enchantment.getMaxLevel()
            );
        }

        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }

    public static String key(Enchantment enchantment) {
        return enchantment.getKey().getKey();
    }

    public static String namespacedKey(
            Enchantment enchantment
    ) {
        return enchantment.getKey().toString();
    }

    public static String displayName(
            Enchantment enchantment
    ) {
        String[] parts = key(enchantment).split("_");
        StringBuilder builder = new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            if (!builder.isEmpty()) {
                builder.append(' ');
            }

            builder.append(
                    Character.toUpperCase(part.charAt(0))
            );

            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }

        return builder.toString();
    }

    private static void alias(
            String vanillaKey,
            String... aliases
    ) {
        String normalizedVanilla = normalize(vanillaKey);
        ALIASES.put(normalizedVanilla, normalizedVanilla);

        for (String alias : aliases) {
            ALIASES.put(
                    normalize(alias),
                    normalizedVanilla
            );
        }
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }

        String normalized = input
                .trim()
                .toLowerCase(Locale.ROOT);

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring(
                    "minecraft:".length()
            );
        }

        return normalized
                .replace('-', '_')
                .replace(' ', '_');
    }
}
