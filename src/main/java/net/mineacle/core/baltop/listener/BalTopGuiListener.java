package net.mineacle.core.baltop.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.baltop.gui.BalTopGui;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.stats.PlayerStatisticsGui;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class BalTopGuiListener implements Listener {

    private final Core core;
    private final EconomyService economyService;
    private final PlayerStatisticsGui playerStatisticsGui;
    private final Set<UUID> waitingForSearch = new HashSet<>();

    public BalTopGuiListener(Core core, EconomyService economyService) {
        this.core = core;
        this.economyService = economyService;
        this.playerStatisticsGui = new PlayerStatisticsGui();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        String title = ChatColor.stripColor(event.getView().getTitle());

        if (!BalTopGui.isTitle(title)) {
            return;
        }

        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getClick() == ClickType.DOUBLE_CLICK
                || event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (rawSlot < 0 || rawSlot >= topSize) {
            return;
        }

        int page = BalTopGui.currentPage(player);

        if (rawSlot == BalTopGui.previousSlot()) {
            SoundService.guiClick(player, core);
            MenuHistory.openWithoutBackTrigger(core, player, () -> BalTopGui.open(core, player, economyService, page - 1));
            return;
        }

        if (rawSlot == BalTopGui.playerHeadSlot()) {
            SoundService.guiClick(player, core);
            openStatsFromBalTop(player, page, player.getUniqueId());
            return;
        }

        if (rawSlot == BalTopGui.refreshSlot()) {
            SoundService.guiClick(player, core);
            MenuHistory.openWithoutBackTrigger(core, player, () -> BalTopGui.open(core, player, economyService, page));
            return;
        }

        if (rawSlot == BalTopGui.searchSlot()) {
            SoundService.guiClick(player, core);
            beginSearch(player);
            return;
        }

        if (rawSlot == BalTopGui.nextSlot()) {
            SoundService.guiClick(player, core);
            MenuHistory.openWithoutBackTrigger(core, player, () -> BalTopGui.open(core, player, economyService, page + 1));
            return;
        }

        if (!BalTopGui.isEntrySlot(rawSlot)) {
            return;
        }

        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) {
            return;
        }

        UUID targetId = targetIdAtSlot(player, page, rawSlot);

        if (targetId == null) {
            return;
        }

        SoundService.guiClick(player, core);
        openStatsFromBalTop(player, page, targetId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = ChatColor.stripColor(event.getView().getTitle());

        if (!BalTopGui.isTitle(title)) {
            return;
        }

        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSearchChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        if (!waitingForSearch.remove(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage();

        core.getServer().getScheduler().runTask(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("cancelled")) {
                sendActionBar(player, "&#bbbbbbBalance Top search cancelled");
                MenuHistory.openWithoutBackTrigger(core, player, () -> BalTopGui.open(core, player, economyService, BalTopGui.currentPage(player)));
                return;
            }

            if (message.equalsIgnoreCase("clear")) {
                BalTopGui.clearSearch(player);
                sendActionBar(player, "&#bbbbbbBalance Top search cleared");
                MenuHistory.openWithoutBackTrigger(core, player, () -> BalTopGui.open(core, player, economyService, 0));
                return;
            }

            UUID targetId = findPlayer(message);

            if (targetId == null) {
                sendActionBar(player, "&cNo Balance Top player found");
                MenuHistory.openWithoutBackTrigger(core, player, () -> BalTopGui.open(core, player, economyService, 0));
                return;
            }

            BalTopGui.setSearch(player, message);
            openStatsFromBalTop(player, 0, targetId);
        });
    }

    private void beginSearch(Player player) {
        waitingForSearch.add(player.getUniqueId());
        player.closeInventory();

        player.sendMessage(TextColor.color("&#bbbbbbType a player name to search Balance Top"));
        player.sendMessage(TextColor.color("&#bbbbbbType &dcancel &#bbbbbbto cancel or &dclear &#bbbbbbto reset search"));
        player.sendActionBar(actionBar("&#bbbbbbType a player name to search Balance Top"));
    }

    private void openStatsFromBalTop(Player player, int page, UUID targetId) {
        MenuHistory.openChild(
                core,
                player,
                () -> BalTopGui.open(core, player, economyService, page),
                () -> playerStatisticsGui.open(player, targetId)
        );
    }

    private UUID findPlayer(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }

        String lowerQuery = query.toLowerCase();

        for (Map.Entry<UUID, Long> entry : economyService.topBalances(Integer.MAX_VALUE)) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(entry.getKey());
            String username = target.getName() == null ? "" : target.getName();
            String displayName = DisplayNames.displayName(target);

            if (username.equalsIgnoreCase(query)
                    || displayName.equalsIgnoreCase(query)
                    || username.toLowerCase().contains(lowerQuery)
                    || displayName.toLowerCase().contains(lowerQuery)) {
                return entry.getKey();
            }
        }

        return null;
    }

    private UUID targetIdAtSlot(Player player, int page, int slot) {
        List<Map.Entry<UUID, Long>> entries = BalTopGui.filteredEntries(player, economyService);
        int index = (page * BalTopGui.entriesPerPage()) + slot;

        if (index < 0 || index >= entries.size()) {
            return null;
        }

        return entries.get(index).getKey();
    }

    private void sendActionBar(Player player, String message) {
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
