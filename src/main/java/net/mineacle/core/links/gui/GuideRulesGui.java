package net.mineacle.core.links.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.links.service.GuideRulesService;
import net.mineacle.core.links.service.GuideRulesService.MenuDefinition;
import net.mineacle.core.links.service.GuideRulesService.MenuItemDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GuideRulesGui {

    private final Core core;
    private final GuideRulesService service;

    public GuideRulesGui(
            Core core,
            GuideRulesService service
    ) {
        this.core = core;
        this.service = service;
    }

    public void openGuide(Player player) {
        open(player, GuideRulesService.GUIDE);
    }

    public void openRules(Player player) {
        open(player, GuideRulesService.RULES);
    }

    public void open(Player player, String menuKey) {
        MenuDefinition definition = service.menu(menuKey);
        Holder holder = new Holder(
                definition.key(),
                definition.items()
        );
        Inventory inventory = Bukkit.createInventory(
                holder,
                definition.size(),
                legacy(definition.title())
        );
        holder.inventory = inventory;

        for (Map.Entry<Integer, MenuItemDefinition> entry
                : definition.items().entrySet()) {
            inventory.setItem(
                    entry.getKey(),
                    item(entry.getValue())
            );
        }

        player.openInventory(inventory);
    }

    public boolean isInventory(Inventory inventory) {
        return inventory != null
                && inventory.getHolder(false) instanceof Holder;
    }

    public Holder holder(Inventory inventory) {
        if (inventory == null
                || !(inventory.getHolder(false)
                instanceof Holder holder)) {
            return null;
        }

        return holder;
    }

    private ItemStack item(MenuItemDefinition definition) {
        ItemStack item = new ItemStack(definition.material());
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.displayName(legacy(definition.name()));

        List<Component> lore = new ArrayList<>();

        for (String line : definition.lore()) {
            lore.add(legacy(line));
        }

        meta.lore(lore);
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_UNBREAKABLE,
                ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_DYE
        );
        item.setItemMeta(meta);
        return item;
    }

    private Component legacy(String text) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(
                        TextColor.color(
                                text == null ? "" : text
                        )
                )
                .decoration(TextDecoration.ITALIC, false);
    }

    public static final class Holder
            implements InventoryHolder {

        private final String menuKey;
        private final Map<Integer, MenuItemDefinition> items;
        private Inventory inventory;

        private Holder(
                String menuKey,
                Map<Integer, MenuItemDefinition> items
        ) {
            this.menuKey = menuKey;
            this.items = items;
        }

        public String menuKey() {
            return menuKey;
        }

        public String action(int slot) {
            MenuItemDefinition definition = items.get(slot);

            return definition == null
                    ? ""
                    : definition.action();
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
