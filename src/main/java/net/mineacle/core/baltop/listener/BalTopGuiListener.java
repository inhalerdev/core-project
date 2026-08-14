package net.mineacle.core.baltop.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.baltop.gui.BalTopGui;
import net.mineacle.core.baltop.service.BalTopLeaderboardCache;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.stats.PlayerStatisticsGui;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BalTopGuiListener implements Listener {

    private static final long SEARCH_TIMEOUT_TICKS =
            20L * 30L;
    private static final int MAX_SEARCH_LENGTH = 32;

    private final Core core;
    private final EconomyService economyService;
    private final BalTopLeaderboardCache leaderboardCache;
    private final PlayerStatisticsGui playerStatisticsGui;
    private final Map<UUID, SearchPrompt> searchPrompts =
            new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> searchTimeouts =
            new ConcurrentHashMap<>();

    public BalTopGuiListener(
            Core core,
            EconomyService economyService,
            BalTopLeaderboardCache leaderboardCache
    ) {
        this.core = core;
        this.economyService = economyService;
        this.leaderboardCache = leaderboardCache;
        this.playerStatisticsGui =
                new PlayerStatisticsGui();
    }

    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(
            InventoryClickEvent event
    ) {
        BalTopGui.BalTopHolder holder =
                BalTopGui.holder(
                        event.getView()
                                .getTopInventory()
                );

        if (holder == null) {
            return;
        }

        event.setCancelled(true);
        event.setResult(
                org.bukkit.event.Event.Result.DENY
        );

        if (!(event.getWhoClicked()
                instanceof Player player)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = event.getView()
                .getTopInventory()
                .getSize();

        if (rawSlot < 0
                || rawSlot >= topSize) {
            return;
        }

        if (rawSlot == BalTopGui.previousSlot()) {
            if (event.getCurrentItem() != null) {
                reopen(
                        player,
                        holder.page() - 1
                );
            }
            return;
        }

        if (rawSlot == BalTopGui.playerHeadSlot()) {
            openStatsFromBalTop(
                    player,
                    holder.page(),
                    player.getUniqueId()
            );
            return;
        }

        if (rawSlot == BalTopGui.refreshSlot()) {
            leaderboardCache.refresh();
            reopen(
                    player,
                    holder.page()
            );
            return;
        }

        if (rawSlot == BalTopGui.searchSlot()) {
            if (event.isRightClick()
                    && BalTopGui.hasSearch(player)) {
                BalTopGui.clearSearch(player);
                sendActionBar(
                        player,
                        "&#bbbbbbBalance Top search cleared"
                );
                reopen(player, 0);
                return;
            }

            beginSearch(
                    player,
                    holder.page()
            );
            return;
        }

        if (rawSlot == BalTopGui.nextSlot()) {
            if (event.getCurrentItem() != null) {
                reopen(
                        player,
                        holder.page() + 1
                );
            }
            return;
        }

        UUID targetId =
                holder.targetAt(rawSlot);

        if (targetId != null) {
            openStatsFromBalTop(
                    player,
                    holder.page(),
                    targetId
            );
        }
    }

    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(
            InventoryDragEvent event
    ) {
        if (!BalTopGui.isBalTopInventory(
                event.getView()
                        .getTopInventory()
        )) {
            return;
        }

        event.setCancelled(true);
        event.setResult(
                org.bukkit.event.Event.Result.DENY
        );
    }

    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSearchChat(
            AsyncChatEvent event
    ) {
        Player player = event.getPlayer();
        SearchPrompt prompt =
                searchPrompts.remove(
                        player.getUniqueId()
                );

        if (prompt == null) {
            return;
        }

        event.setCancelled(true);
        cancelSearchTimeout(
                player.getUniqueId()
        );

        String input =
                PlainTextComponentSerializer
                        .plainText()
                        .serialize(event.message());
        String query = sanitize(input);

        core.getServer()
                .getScheduler()
                .runTask(
                        core,
                        () -> finishSearch(
                                player,
                                prompt,
                                query
                        )
                );
    }

    @EventHandler
    public void onQuit(
            PlayerQuitEvent event
    ) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        searchPrompts.remove(playerId);
        cancelSearchTimeout(playerId);
        BalTopGui.clearSearch(player);
    }

    public void shutdown() {
        for (BukkitTask task
                : searchTimeouts.values()) {
            task.cancel();
        }

        searchTimeouts.clear();
        searchPrompts.clear();
        BalTopGui.clearAllState();
    }

    private void finishSearch(
            Player player,
            SearchPrompt prompt,
            String query
    ) {
        if (!player.isOnline()) {
            return;
        }

        if (query.equalsIgnoreCase("cancel")
                || query.equalsIgnoreCase("cancelled")) {
            sendActionBar(
                    player,
                    "&#bbbbbbBalance Top search cancelled"
            );
            reopen(
                    player,
                    prompt.page()
            );
            return;
        }

        if (query.equalsIgnoreCase("clear")) {
            BalTopGui.clearSearch(player);
            sendActionBar(
                    player,
                    "&#bbbbbbBalance Top search cleared"
            );
            reopen(player, 0);
            return;
        }

        if (query.isBlank()) {
            sendActionBar(
                    player,
                    "&cSearch cannot be empty"
            );
            reopen(
                    player,
                    prompt.page()
            );
            return;
        }

        String exactPublicName =
                leaderboardCache.current()
                        .exactPublicName(query);
        String displayLabel =
                exactPublicName.isBlank()
                        ? query
                        : exactPublicName;

        BalTopGui.setSearch(
                player,
                query,
                displayLabel
        );

        if (!BalTopGui.hasMatches(
                player,
                leaderboardCache
        )) {
            sendActionBar(
                    player,
                    "&cNo Balance Top player found"
            );
        }

        reopen(player, 0);
    }

    private void beginSearch(
            Player player,
            int page
    ) {
        UUID playerId =
                player.getUniqueId();

        searchPrompts.put(
                playerId,
                new SearchPrompt(page)
        );
        cancelSearchTimeout(playerId);

        BukkitTask timeout =
                core.getServer()
                        .getScheduler()
                        .runTaskLater(
                                core,
                                () -> timeoutSearch(
                                        player,
                                        playerId
                                ),
                                SEARCH_TIMEOUT_TICKS
                        );
        searchTimeouts.put(
                playerId,
                timeout
        );

        MenuHistory.closeForInput(
                core,
                player
        );
        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbType a public player name to search Balance Top"
                )
        );
        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbType &#D0AFFFcancel "
                                + "&#bbbbbbto return or "
                                + "&#D0AFFFclear "
                                + "&#bbbbbbto reset search"
                )
        );
        sendActionBar(
                player,
                "&#bbbbbbType a public player name to search Balance Top"
        );
    }

    private void timeoutSearch(
            Player player,
            UUID playerId
    ) {
        SearchPrompt removed =
                searchPrompts.remove(playerId);
        searchTimeouts.remove(playerId);

        if (removed == null
                || !player.isOnline()) {
            return;
        }

        sendActionBar(
                player,
                "&cBalance Top search timed out"
        );
        reopen(
                player,
                removed.page()
        );
    }

    private void reopen(
            Player player,
            int page
    ) {
        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () -> BalTopGui.open(
                        player,
                        economyService,
                        leaderboardCache,
                        page
                )
        );
    }

    private void openStatsFromBalTop(
            Player player,
            int page,
            UUID targetId
    ) {
        if (!player.hasPermission(
                "mineaclestats.use"
        )) {
            player.sendMessage(
                    core.getMessage(
                            "general.no-permission"
                    )
            );
            return;
        }

        MenuHistory.openChild(
                core,
                player,
                () -> BalTopGui.open(
                        player,
                        economyService,
                        leaderboardCache,
                        page
                ),
                () -> playerStatisticsGui.open(
                        player,
                        targetId
                )
        );
    }

    private String sanitize(
            String input
    ) {
        if (input == null) {
            return "";
        }

        String clean =
                TextColor.strip(input)
                        .replaceAll(
                                "[\\p{Cntrl}]",
                                ""
                        )
                        .trim();

        if (clean.length()
                > MAX_SEARCH_LENGTH) {
            clean = clean.substring(
                    0,
                    MAX_SEARCH_LENGTH
            );
        }

        return clean;
    }

    private void cancelSearchTimeout(
            UUID playerId
    ) {
        BukkitTask task =
                searchTimeouts.remove(playerId);

        if (task != null) {
            task.cancel();
        }
    }

    private void sendActionBar(
            Player player,
            String message
    ) {
        player.sendActionBar(
                component(message)
        );
    }

    private Component component(
            String message
    ) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(message)
                );
    }

    private record SearchPrompt(
            int page
    ) {
    }
}
