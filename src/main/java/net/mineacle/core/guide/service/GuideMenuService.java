package net.mineacle.core.guide.service;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.guide.gui.GuideMenuHolder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GuideMenuService {

    private final Core core;
    private final Map<String, File> files = new HashMap<>();
    private final Map<String, FileConfiguration> menus = new HashMap<>();

    public GuideMenuService(Core core) {
        this.core = core;
        files.put("guide", new File(core.getDataFolder(), "guide.yml"));
        files.put("rules", new File(core.getDataFolder(), "rules.yml"));
        reload();
    }

    public void reload() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        loadMenu("guide");
        loadMenu("rules");
    }

    public void open(Player player, String menuKey) {
        String normalizedKey = menuKey.toLowerCase(Locale.ROOT);
        FileConfiguration config = menus.get(normalizedKey);

        if (config == null) {
            player.sendMessage(TextColor.color("&cThat menu is not available"));
            return;
        }

        int size = sanitizeSize(config.getInt("size", 27));
        String title = plainTitle(config.getString("menu_title", menuKey));
        Map<Integer, List<String>> commandMap = new HashMap<>();
        GuideMenuHolder holder = new GuideMenuHolder(normalizedKey, commandMap);
        Inventory inventory = Bukkit.createInventory(holder, size, title);

        ConfigurationSection items = config.getConfigurationSection("items");

        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection section = items.getConfigurationSection(key);

                if (section == null) {
                    continue;
                }

                ItemStack item = item(player, section);
                List<Integer> slots = slots(section, size);
                List<String> commands = section.getStringList("left_click_commands");

                for (int slot : slots) {
                    inventory.setItem(slot, item.clone());

                    if (!commands.isEmpty()) {
                        commandMap.put(slot, commands);
                    }
                }
            }
        }

        player.openInventory(inventory);
    }

    public void clearSessions() {
        // Inventory holders carry click state.
    }

    public void execute(Player player, String currentMenuKey, List<String> commands) {
        for (String rawCommand : commands) {
            if (rawCommand == null || rawCommand.isBlank()) {
                continue;
            }

            String command = replace(player, rawCommand.trim());
            String lower = command.toLowerCase(Locale.ROOT);

            if (command.equalsIgnoreCase("[close]")) {
                player.closeInventory();
                continue;
            }

            if (lower.startsWith("[player]")) {
                String playerCommand = command.substring("[player]".length()).trim();

                if (playerCommand.startsWith("/")) {
                    playerCommand = playerCommand.substring(1);
                }

                if (shouldOpenRulesAsGuideChild(currentMenuKey, playerCommand)) {
                    MenuHistory.openChild(
                            core,
                            player,
                            () -> open(player, "guide"),
                            () -> open(player, "rules")
                    );
                    continue;
                }

                player.closeInventory();
                player.performCommand(playerCommand);
                continue;
            }

            if (lower.startsWith("[menu]")) {
                String targetMenu = command.substring("[menu]".length()).trim().toLowerCase(Locale.ROOT);

                if (targetMenu.equals("rules") && currentMenuKey.equalsIgnoreCase("guide")) {
                    MenuHistory.openChild(
                            core,
                            player,
                            () -> open(player, "guide"),
                            () -> open(player, "rules")
                    );
                    continue;
                }

                MenuHistory.openRoot(core, player, () -> open(player, targetMenu));
                continue;
            }

            if (lower.startsWith("[console]")) {
                String consoleCommand = command.substring("[console]".length()).trim();

                if (consoleCommand.startsWith("/")) {
                    consoleCommand = consoleCommand.substring(1);
                }

                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCommand);
                continue;
            }

            if (lower.startsWith("[message]")) {
                String message = command.substring("[message]".length()).trim();
                player.sendMessage(TextColor.color(message));
            }
        }
    }

    private boolean shouldOpenRulesAsGuideChild(String currentMenuKey, String playerCommand) {
        if (!currentMenuKey.equalsIgnoreCase("guide")) {
            return false;
        }

        String normalized = playerCommand.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("rules") || normalized.equals("rule");
    }

    private void loadMenu(String menuKey) {
        File file = files.get(menuKey);

        if (file == null) {
            return;
        }

        if (!file.exists()) {
            core.saveResource(file.getName(), false);
        }

        menus.put(menuKey, YamlConfiguration.loadConfiguration(file));
    }

    private ItemStack item(Player player, ConfigurationSection section) {
        Material material = material(section.getString("material", "STONE"));
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        if (meta instanceof SkullMeta skullMeta && material == Material.PLAYER_HEAD) {
            skullMeta.setOwningPlayer(player);
        }

        String displayName = section.getString("display_name", "");

        if (!displayName.isBlank()) {
            meta.displayName(component(replace(player, displayName)));
        }

        List<String> lore = section.getStringList("lore");

        if (!lore.isEmpty()) {
            List<Component> components = new ArrayList<>();

            for (String line : lore) {
                components.add(component(replace(player, line)));
            }

            meta.lore(components);
        }

        for (String flag : section.getStringList("item_flags")) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(flag.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Ignore invalid DeluxeMenus flags so one bad flag does not break the menu.
            }
        }

        // Guide and Rules are informational menus. Do not show vanilla tooltip noise.
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_UNBREAKABLE,
                ItemFlag.HIDE_DESTROYS,
                ItemFlag.HIDE_PLACED_ON,
                ItemFlag.HIDE_ADDITIONAL_TOOLTIP
        );

        item.setItemMeta(meta);
        return item;
    }

    private List<Integer> slots(ConfigurationSection section, int size) {
        List<Integer> slots = new ArrayList<>();

        if (section.isInt("slot")) {
            int slot = section.getInt("slot");

            if (slot >= 0 && slot < size) {
                slots.add(slot);
            }
        }

        for (int slot : section.getIntegerList("slots")) {
            if (slot >= 0 && slot < size && !slots.contains(slot)) {
                slots.add(slot);
            }
        }

        return slots;
    }

    private int sanitizeSize(int size) {
        if (size < 9) {
            return 9;
        }

        if (size > 54) {
            return 54;
        }

        return ((size + 8) / 9) * 9;
    }

    private Material material(String input) {
        if (input == null || input.isBlank()) {
            return Material.STONE;
        }

        try {
            return Material.valueOf(input.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Material.STONE;
        }
    }

    private String plainTitle(String input) {
        return ChatColor.stripColor(TextColor.color(input == null ? "" : input));
    }

    private Component component(String input) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(input == null ? "" : input))
                .decoration(TextDecoration.ITALIC, false);
    }

    private String replace(Player player, String input) {
        String output = input
                .replace("%player_name%", player.getName())
                .replace("%player_displayname%", DisplayNames.displayName(player))
                .replace("%player_uuid%", player.getUniqueId().toString());

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                output = PlaceholderAPI.setPlaceholders(player, output);
            } catch (Throwable ignored) {
                // Keep the unparsed string if PlaceholderAPI fails.
            }
        }

        return output;
    }
}
