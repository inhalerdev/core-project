package net.mineacle.core.baltop.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.baltop.gui.BalTopGui;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.stats.PlayerStatisticsGui;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BalTopGuiListener implements Listener {

    private static final long SEARCH_TIMEOUT_TICKS = 20L * 30L;
    private static final int MAX_SEARCH_LENGTH = 32;

    private final Core core;
    private final EconomyService economyService;
    private final PlayerStatisticsGui playerStatisticsGui;
    private final Map<UUID, SearchPrompt> searchPrompts = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> searchTimeouts = new ConcurrentHashMap<>();

    public BalTopGuiListener(Core core, EconomyService economyService) {
        this.core = core;
        this.economyService = economyService;
        this.playerStatisticsGui = new PlayerStatisticsGui();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        BalTopGui.BalTopHolder holder = BalTopGui.holder(
                event.getView().getTopInventory()
        );

        if (holder == null) {
            return;
        }

        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (rawSlot < 0 || rawSlot >= topSize) {
            return;
        }

        if ((rawSlot == BalTopGui.previousSlot()
                || rawSlot == BalTopGui.nextSlot())
                && BalTopGui.isDisabledNavigation(
                event.getCurrentItem()
        )) {
            return;
        }

        if (rawSlot == BalTopGui.previousSlot()) {
            reopen(player, holder.page() - 1);
            return;
        }

        if (rawSlot == BalTopGui.playerHeadSlot()) {
            openStatsFromBalTop(player, holder.page(), player.getUniqueId());
            return;
        }

        if (rawSlot == BalTopGui.refreshSlot()) {
            reopen(player, holder.page());
            return;
        }

        if (rawSlot == BalTopGui.searchSlot()) {
            if (event.isRightClick() && BalTopGui.hasSearch(player)) {
                BalTopGui.clearSearch(player);
                sendActionBar(player, "&#bbbbbbBalance Top search cleared");
                reopen(player, 0);
                return;
            }

            beginSearch(player, holder.page());
            return;
        }

        if (rawSlot == BalTopGui.nextSlot()) {
            reopen(player, holder.page() + 1);
            return;
        }

        UUID targetId = holder.targetAt(rawSlot);

        if (targetId != null) {
            openStatsFromBalTop(player, holder.page(), targetId);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!BalTopGui.isBalTopInventory(event.getView().getTopInventory())) {
            return;
        }

        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(
            priority = EventPriority.LOWEST,
            ignoreCancelled = false
    )
    public void onSearchChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        SearchPrompt prompt = searchPrompts.remove(player.getUniqueId());

        if (prompt == null) {
            return;
        }

        event.setCancelled(true);
        cancelSearchTimeout(player.getUniqueId());

        String input = PlainTextComponentSerializer.plainText()
                .serialize(event.message());
        String query = sanitize(input);

        core.getServer().getScheduler().runTask(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (query.equalsIgnoreCase("cancel")
                    || query.equalsIgnoreCase("cancelled")) {
                sendActionBar(player, "&#bbbbbbBalance Top search cancelled");
                reopen(player, prompt.page());
                return;
            }

            if (query.equalsIgnoreCase("clear")) {
                BalTopGui.clearSearch(player);
                sendActionBar(player, "&#bbbbbbBalance Top search cleared");
                reopen(player, 0);
                return;
            }

            if (query.isBlank()) {
                sendActionBar(player, "&cSearch cannot be empty");
                reopen(player, prompt.page());
                return;
            }

            String displayLabel = displaySearchLabel(query);
            BalTopGui.setSearch(player, query, displayLabel);

            if (!BalTopGui.hasMatches(player, economyService)) {
                sendActionBar(player, "&cNo Balance Top player found");
            }

            reopen(player, 0);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        searchPrompts.remove(playerId);
        cancelSearchTimeout(playerId);
        BalTopGui.clearSearch(event.getPlayer());
    }

    public void shutdown() {
        for (BukkitTask task : searchTimeouts.values()) {
            task.cancel();
        }

        searchTimeouts.clear();
        searchPrompts.clear();
        BalTopGui.clearAllState();
    }

    private void beginSearch(Player player, int page) {
        UUID playerId = player.getUniqueId();
        searchPrompts.put(playerId, new SearchPrompt(page));
        cancelSearchTimeout(playerId);

        BukkitTask timeout = core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    SearchPrompt removed = searchPrompts.remove(playerId);
                    searchTimeouts.remove(playerId);

                    if (removed == null || !player.isOnline()) {
                        return;
                    }

                    sendActionBar(player, "&cBalance Top search timed out");
                    reopen(player, removed.page());
                },
                SEARCH_TIMEOUT_TICKS
        );
        searchTimeouts.put(playerId, timeout);

        MenuHistory.closeForInput(core, player);
        player.sendMessage(TextColor.color(
                "&#bbbbbbType a player name to search Balance Top"
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbType &#ff88ffcancel "
                        + "&#bbbbbbto return or "
                        + "&#ff88ffclear "
                        + "&#bbbbbbto reset search"
        ));
        sendActionBar(player, "&#bbbbbbType a player name to search Balance Top");
    }

    private void reopen(Player player, int page) {
        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () -> BalTopGui.open(core, player, economyService, page)
        );
    }

    private void openStatsFromBalTop(Player player, int page, UUID targetId) {
        MenuHistory.openChild(
                core,
                player,
                () -> BalTopGui.open(core, player, economyService, page),
                () -> playerStatisticsGui.open(player, targetId)
        );
    }

    private String displaySearchLabel(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);

        for (Map.Entry<UUID, Long> entry
                : economyService.topBalances(Integer.MAX_VALUE)) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(entry.getKey());
            String username = target.getName() == null ? "" : target.getName();
            String displayName = DisplayNames.displayName(target);

            if (username.toLowerCase(Locale.ROOT).equals(normalized)
                    || displayName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return displayName;
            }
        }

        return query;
    }

    private String sanitize(String input) {
        if (input == null) {
            return "";
        }

        String clean = TextColor.strip(input)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();

        if (clean.length() > MAX_SEARCH_LENGTH) {
            clean = clean.substring(0, MAX_SEARCH_LENGTH);
        }

        return clean;
    }

    private void cancelSearchTimeout(UUID playerId) {
        BukkitTask task = searchTimeouts.remove(playerId);

        if (task != null) {
            task.cancel();
        }
    }

    private void sendActionBar(Player player, String message) {
        player.sendActionBar(component(message));
    }

    private Component component(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(message));
    }

    private static final class SearchPrompt {

        private final int page;

        private SearchPrompt(int page) {
            this.page = page;
        }

        private int page() {
            return page;
        }
    }
}
