package net.mineacle.core.orders.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.Locale;

public final class OrdersGuiItems {

    private OrdersGuiItems() {
    }

    public static ItemStack item(
            Material material,
            String name,
            String... lore
    ) {
        return item(
                material,
                name,
                List.of(lore)
        );
    }

    public static ItemStack item(
            Material material,
            String name,
            List<String> lore
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.displayName(component(name));
        meta.lore(
                lore.stream()
                        .map(OrdersGuiItems::component)
                        .toList()
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_UNBREAKABLE,
                ItemFlag.HIDE_DYE
        );
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack playerHead(
            Player player,
            String name,
            List<String> lore
    ) {
        ItemStack item = item(
                Material.PLAYER_HEAD,
                name,
                lore
        );
        ItemMeta meta = item.getItemMeta();

        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(player);
            item.setItemMeta(skullMeta);
        }

        return item;
    }

    public static String cfg(
            String path,
            String fallback
    ) {
        Core core = Core.instance();

        if (core == null) {
            return brand(fallback);
        }

        return brand(
                core.getConfig().getString(
                        path,
                        fallback
                )
        );
    }

    public static List<String> lore(
            String path,
            List<String> fallback
    ) {
        Core core = Core.instance();

        if (core == null
                || !core.getConfig().isList(path)) {
            return fallback.stream()
                    .map(OrdersGuiItems::brand)
                    .toList();
        }

        return core.getConfig()
                .getStringList(path)
                .stream()
                .map(OrdersGuiItems::brand)
                .toList();
    }

    public static Material material(
            String path,
            Material fallback
    ) {
        Core core = Core.instance();

        if (core == null) {
            return fallback;
        }

        String raw = core.getConfig().getString(
                path,
                fallback.name()
        );

        if (raw.isBlank()) {
            return fallback;
        }

        Material material = Material.matchMaterial(
                raw.trim().toUpperCase(Locale.ROOT)
        );

        return material == null || !material.isItem()
                ? fallback
                : material;
    }

    public static ItemStack navigation(
            boolean previous,
            boolean enabled,
            int targetPage
    ) {
        if (!enabled) {
            return null;
        }

        return item(
                Material.ARROW,
                previous
                        ? "&#B078FFPrevious"
                        : "&#B078FFNext",
                "&#bbbbbbPage &#D0AFFF" + targetPage
        );
    }

    public static boolean isDisabledNavigation(ItemStack item) {
        return item == null
                || item.getType()
                == Material.GRAY_STAINED_GLASS_PANE;
    }

    public static Component component(String text) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(
                        TextColor.color(
                                brand(
                                        text == null ? "" : text
                                )
                        )
                )
                .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Orders is one of the systems being migrated to the current Mineacle GUI
     * palette. Normalize legacy configurable aliases here so an older
     * config.yml cannot visually revert the menu while preserving operators'
     * wording/material customization.
     */
    private static String brand(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }

        return value
                .replace("&#8436FE", "&#B078FF")
                .replace("&#8436fe", "&#B078FF")
                .replace("&#ff55ff", "&#B078FF")
                .replace("&#FF55FF", "&#B078FF")
                .replace("&#ff88ff", "&#D0AFFF")
                .replace("&#FF88FF", "&#D0AFFF")
                .replace("&d", "&#B078FF");
    }
}
