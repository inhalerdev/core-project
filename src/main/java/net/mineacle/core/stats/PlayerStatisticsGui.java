package net.mineacle.core.stats;

import me.clip.placeholderapi.PlaceholderAPI;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.UUID;

public final class PlayerStatisticsGui implements Listener {

    private static final int SIZE = 27;

    private static final int SLOT_MONEY = 10;
    private static final int SLOT_PLAYER_KILLS = 11;
    private static final int SLOT_DEATHS = 12;
    private static final int SLOT_PLAYTIME = 13;
    private static final int SLOT_BLOCKS_PLACED = 14;
    private static final int SLOT_BLOCKS_BROKEN = 15;
    private static final int SLOT_MOBS_KILLED = 16;

    public void open(Player viewer, UUID targetId) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);

        Inventory inventory = Bukkit.createInventory(
                null,
                SIZE,
                DisplayNames.displayName(target) + " Stats"
        );

        inventory.setItem(SLOT_MONEY, statItem(
                Material.EMERALD,
                "&dMoney",
                "&#bbbbbb" + placeholder(target, "%mineacle_balance%", "$0")
        ));

        inventory.setItem(SLOT_PLAYER_KILLS, statItem(
                Material.DIAMOND_SWORD,
                "&dKills",
                "&#bbbbbb" + placeholder(target, "%mineacle_stats_kills%", fallbackStatistic(targetId, Statistic.PLAYER_KILLS))
        ));

        inventory.setItem(SLOT_DEATHS, statItem(
                Material.SKELETON_SKULL,
                "&dDeaths",
                "&#bbbbbb" + placeholder(target, "%mineacle_stats_deaths%", fallbackStatistic(targetId, Statistic.DEATHS))
        ));

        inventory.setItem(SLOT_PLAYTIME, statItem(
                Material.CLOCK,
                "&dPlaytime",
                "&#bbbbbb" + placeholder(target, "%playtime_time%", fallbackPlaytime(targetId))
        ));

        inventory.setItem(SLOT_BLOCKS_PLACED, statItem(
                Material.GRASS_BLOCK,
                "&dBlocks Placed",
                "&#bbbbbb" + placeholder(target, "%mineacle_stats_blocks_placed%", "0")
        ));

        inventory.setItem(SLOT_BLOCKS_BROKEN, statItem(
                Material.COBBLESTONE,
                "&dBlocks Broken",
                "&#bbbbbb" + placeholder(target, "%mineacle_stats_blocks_broken%", "0")
        ));

        inventory.setItem(SLOT_MOBS_KILLED, statItem(
                Material.ZOMBIE_HEAD,
                "&dMobs Killed",
                "&#bbbbbb" + placeholder(target, "%mineacle_stats_mobs_killed%", fallbackStatistic(targetId, Statistic.MOB_KILLS))
        ));

        viewer.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        HumanEntity clicker = event.getWhoClicked();

        if (!(clicker instanceof Player player)) {
            return;
        }

        String title = ChatColor.stripColor(event.getView().getTitle());

        if (title == null || !title.endsWith(" Stats")) {
            return;
        }

        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);

        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (rawSlot < 0 || rawSlot >= topSize) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();

        if (clicked == null || clicked.getType().isAir()) {
            return;
        }

        SoundService.guiClick(player, Core.instance());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        String title = ChatColor.stripColor(event.getView().getTitle());

        if (title != null && title.endsWith(" Stats")) {
            event.setCancelled(true);
            event.setResult(org.bukkit.event.Event.Result.DENY);
        }
    }

    private ItemStack statItem(Material material, String name, String value) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(color(name));
        meta.setLore(List.of(color(value)));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        item.setItemMeta(meta);
        return item;
    }

    private String placeholder(OfflinePlayer target, String placeholder, String fallback) {
        if (target == null) {
            return fallback;
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return fallback;
        }

        try {
            String parsed = PlaceholderAPI.setPlaceholders(target, placeholder);

            if (parsed == null || parsed.isBlank() || parsed.equalsIgnoreCase(placeholder)) {
                return fallback;
            }

            return parsed;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private String fallbackStatistic(UUID targetId, Statistic statistic) {
        Player player = Bukkit.getPlayer(targetId);

        if (player == null) {
            return "0";
        }

        try {
            return String.valueOf(player.getStatistic(statistic));
        } catch (Exception ignored) {
            return "0";
        }
    }

    private String fallbackPlaytime(UUID targetId) {
        Player player = Bukkit.getPlayer(targetId);

        if (player == null) {
            return "0m";
        }

        int ticks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long totalSeconds = ticks / 20L;
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;

        if (days > 0) {
            return days + "d " + hours + "h";
        }

        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }

        return minutes + "m";
    }

    private String color(String input) {
        return TextColor.color(input);
    }
}
