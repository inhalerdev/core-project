package net.mineacle.core.links.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class GuideRulesGui {

    public static final String GUIDE_TITLE = "Guide";
    public static final String RULES_TITLE = "Rules";

    private GuideRulesGui() {
    }

    public static void openGuide(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, legacy(GUIDE_TITLE));

        inventory.setItem(10, item(Material.COMPASS, "&dGetting Started",
                "&#bbbbbbUse &d/spawn &#bbbbbbto open the spawn menu",
                "&#bbbbbbUse &d/rtp &#bbbbbbto begin survival",
                "&#bbbbbbUse &d/sethome <name> &#bbbbbbto save a home"));

        inventory.setItem(12, item(Material.OAK_BED, "&dHomes",
                "&#bbbbbbDefault players can set 3 homes",
                "&#bbbbbbMineacle+ players can set 5 homes",
                "&#bbbbbbUse &d/home &#bbbbbbto manage homes"));

        inventory.setItem(14, item(Material.DIAMOND_SWORD, "&dDuels",
                "&#bbbbbbUse &d/duel <player> &#bbbbbbto challenge someone",
                "&#bbbbbbDuels teleport both players into Origins",
                "&#bbbbbbItems are risked in survival combat"));

        inventory.setItem(16, item(Material.EMERALD, "&dEconomy",
                "&#bbbbbbUse &d/sell &#bbbbbbto sell items",
                "&#bbbbbbUse &d/worth &#bbbbbbto view item prices",
                "&#bbbbbbUse &d/baltop &#bbbbbbto view top balances"));

        inventory.setItem(22, item(Material.WRITABLE_BOOK, "&dRules",
                "&#bbbbbbClick to open the server rules",
                "&#bbbbbbThese keep the survival economy fair"));

        player.openInventory(inventory);
    }

    public static void openRules(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, legacy(RULES_TITLE));

        inventory.setItem(10, item(Material.SHIELD, "&cNo Cheating",
                "&#bbbbbbNo hacked clients, macros, xray, dupes, or exploits",
                "&#bbbbbbUnfair advantages are not allowed"));

        inventory.setItem(12, item(Material.TNT, "&cNo Griefing Spawn",
                "&#bbbbbbDo not abuse protected or public areas",
                "&#bbbbbbDo not intentionally disrupt server systems"));

        inventory.setItem(14, item(Material.PLAYER_HEAD, "&cRespect Players",
                "&#bbbbbbNo harassment, hate speech, threats, or spam",
                "&#bbbbbbKeep chat clean and playable"));

        inventory.setItem(16, item(Material.CHEST, "&cNo Economy Abuse",
                "&#bbbbbbNo duping, alt abuse, market exploit abuse",
                "&#bbbbbbReport broken prices or bugs immediately"));

        inventory.setItem(22, item(Material.COMPASS, "&dGuide",
                "&#bbbbbbClick to return to the server guide"));

        player.openInventory(inventory);
    }

    private static ItemStack item(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(legacy(name));

        List<Component> lore = new ArrayList<>();

        for (String line : loreLines) {
            lore.add(legacy(line));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(text));
    }
}
