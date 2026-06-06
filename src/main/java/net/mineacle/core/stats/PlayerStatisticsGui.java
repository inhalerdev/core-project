package net.mineacle.core.stats;

import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.stats.service.StatsService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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
    private static final int SLOT_PLAYTIME = 11;
    private static final int SLOT_PLAYER_KILLS = 12;
    private static final int SLOT_DEATHS = 13;
    private static final int SLOT_BLOCKS_PLACED = 14;
    private static final int SLOT_BLOCKS_BROKEN = 15;
    private static final int SLOT_MOBS_KILLED = 16;

    private final StatsService statsService;

    public PlayerStatisticsGui(StatsService statsService) {
        this.statsService = statsService;
    }

    public void open(Player viewer, UUID targetId) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
        Inventory inventory = Bukkit.createInventory(
                null,
                SIZE,
                TextColor.color(DisplayNames.displayName(target) + " &#bbbbbbStats")
        );

        inventory.setItem(SLOT_MONEY, statItem(
                Material.EMERALD,
                "&#bbbbbbMoney",
                "&a$" + statsService.money(targetId)
        ));

        inventory.setItem(SLOT_PLAYTIME, statItem(
                Material.CLOCK,
                "&#bbbbbbPlaytime",
                "&#ff88ff" + statsService.playtime(targetId)
        ));

        inventory.setItem(SLOT_PLAYER_KILLS, statItem(
                Material.DIAMOND_SWORD,
                "&#bbbbbbKills",
                "&#ff88ff" + statsService.kills(targetId)
        ));

        inventory.setItem(SLOT_DEATHS, statItem(
                Material.SKELETON_SKULL,
                "&#bbbbbbDeaths",
                "&#ff88ff" + statsService.deaths(targetId)
        ));

        inventory.setItem(SLOT_BLOCKS_PLACED, statItem(
                Material.GRASS_BLOCK,
                "&#bbbbbbBlocks Placed",
                "&#ff88ff" + statsService.blocksPlaced(targetId)
        ));

        inventory.setItem(SLOT_BLOCKS_BROKEN, statItem(
                Material.COBBLESTONE,
                "&#bbbbbbBlocks Broken",
                "&#ff88ff" + statsService.blocksBroken(targetId)
        ));

        inventory.setItem(SLOT_MOBS_KILLED, statItem(
                Material.ZOMBIE_HEAD,
                "&#bbbbbbMobs Killed",
                "&#ff88ff" + statsService.mobsKilled(targetId)
        ));

        viewer.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        HumanEntity clicker = event.getWhoClicked();

        if (!(clicker instanceof Player)) {
            return;
        }

        String title = ChatColor.stripColor(event.getView().getTitle());

        if (title == null || !title.endsWith(" Stats")) {
            return;
        }

        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);
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

    private String color(String input) {
        return TextColor.color(input);
    }
}
