package net.mineacle.core.sell.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.gui.WorthGui;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
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

public final class WorthGuiListener
        implements Listener {

    private static final long SEARCH_TIMEOUT_TICKS =
            20L * 30L;
    private static final int MAX_SEARCH_LENGTH = 48;

    private final Core core;
    private final SellService sellService;
    private final Map<UUID, SearchPrompt> searchPrompts =
            new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> searchTimeouts =
            new ConcurrentHashMap<>();

    public WorthGuiListener(
            Core core,
            SellService sellService
    ) {
        this.core = core;
        this.sellService = sellService;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onInventoryClick(
            InventoryClickEvent event
    ) {
        if (!WorthGui.isInventory(
                event.getView().getTopInventory()
        )) {
            return;
        }

        event.setCancelled(true);
        event.setResult(Event.Result.DENY);

        if (!(event.getWhoClicked()
                instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        int topSize = event.getView()
                .getTopInventory()
                .getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        if ((slot == WorthGui.PREVIOUS_SLOT
                || slot == WorthGui.NEXT_SLOT)
                && WorthGui.isDisabledNavigation(
                event.getCurrentItem()
        )) {
            return;
        }

        int page = WorthGui.currentPage(player);

        if (slot == WorthGui.PREVIOUS_SLOT) {
            SoundService.guiPage(player, core);
            reopen(player, page - 1);
            return;
        }

        if (slot == WorthGui.SEARCH_SLOT) {
            if (event.isRightClick()
                    && WorthGui.hasSearch(player)) {
                WorthGui.clearSearch(player);
                player.sendMessage(
                        TextColor.color(
                                "&#bbbbbbWorth search cleared"
                        )
                );
                SoundService.guiCancel(player, core);
                reopen(player, 0);
                return;
            }

            beginSearch(player, page);
            return;
        }

        if (slot == WorthGui.SORT_SLOT) {
            SoundService.guiSort(player, core);
            WorthGui.cycleSort(player);
            reopen(player, 0);
            return;
        }

        if (slot == WorthGui.FILTER_SLOT) {
            SoundService.guiFilter(player, core);
            WorthGui.cycleFilter(player);
            reopen(player, 0);
            return;
        }

        if (slot == WorthGui.REFRESH_SLOT) {
            SoundService.guiRefresh(player, core);
            sellService.recalculateDemandIfNeeded();
            WorthGui.clearCatalogCache();
            reopen(player, page);
            return;
        }

        if (slot == WorthGui.NEXT_SLOT) {
            SoundService.guiPage(player, core);
            reopen(player, page + 1);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onInventoryDrag(
            InventoryDragEvent event
    ) {
        if (WorthGui.isInventory(
                event.getView().getTopInventory()
        )) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
        }
    }

    @EventHandler(
            priority = EventPriority.LOWEST,
            ignoreCancelled = false
    )
    public void onSearchChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        SearchPrompt prompt = searchPrompts.remove(
                player.getUniqueId()
        );

        if (prompt == null) {
            return;
        }

        event.setCancelled(true);
        cancelSearchTimeout(player.getUniqueId());

        String input = PlainTextComponentSerializer
                .plainText()
                .serialize(event.message());
        String query = sanitize(input);

        core.getServer()
                .getScheduler()
                .runTask(
                        core,
                        () -> {
                            if (!player.isOnline()) {
                                return;
                            }

                            if (query.equalsIgnoreCase(
                                    "cancel"
                            )
                                    || query.equalsIgnoreCase(
                                    "cancelled"
                            )) {
                                player.sendMessage(
                                        TextColor.color(
                                                "&#bbbbbbWorth search cancelled"
                                        )
                                );
                                SoundService.guiCancel(
                                        player,
                                        core
                                );
                                reopen(
                                        player,
                                        prompt.page()
                                );
                                return;
                            }

                            if (query.equalsIgnoreCase(
                                    "clear"
                            )) {
                                WorthGui.clearSearch(player);
                                player.sendMessage(
                                        TextColor.color(
                                                "&#bbbbbbWorth search cleared"
                                        )
                                );
                                SoundService.guiCancel(
                                        player,
                                        core
                                );
                                reopen(player, 0);
                                return;
                            }

                            if (query.isBlank()) {
                                fail(
                                        player,
                                        "&cSearch cannot be empty"
                                );
                                reopen(
                                        player,
                                        prompt.page()
                                );
                                return;
                            }

                            if (query.length()
                                    > MAX_SEARCH_LENGTH) {
                                fail(
                                        player,
                                        "&cSearch cannot exceed "
                                                + MAX_SEARCH_LENGTH
                                                + " characters"
                                );
                                reopen(
                                        player,
                                        prompt.page()
                                );
                                return;
                            }

                            String displayLabel =
                                    displayLabel(query);
                            WorthGui.setSearch(
                                    player,
                                    query,
                                    displayLabel
                            );

                            if (!WorthGui.hasMatches(
                                    player,
                                    sellService
                            )) {
                                fail(
                                        player,
                                        "&cNo matching Worth items found"
                                );
                            } else {
                                SoundService.guiSearch(
                                        player,
                                        core
                                );
                            }

                            reopen(player, 0);
                        }
                );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer()
                .getUniqueId();
        searchPrompts.remove(playerId);
        cancelSearchTimeout(playerId);
        WorthGui.clear(event.getPlayer());
    }

    public void shutdown() {
        for (BukkitTask task
                : searchTimeouts.values()) {
            task.cancel();
        }

        searchTimeouts.clear();
        searchPrompts.clear();
        WorthGui.clearAllState();
    }

    private void beginSearch(
            Player player,
            int page
    ) {
        UUID playerId = player.getUniqueId();
        searchPrompts.put(
                playerId,
                new SearchPrompt(page)
        );
        cancelSearchTimeout(playerId);

        BukkitTask timeout = core.getServer()
                .getScheduler()
                .runTaskLater(
                        core,
                        () -> {
                            SearchPrompt removed =
                                    searchPrompts.remove(
                                            playerId
                                    );
                            searchTimeouts.remove(playerId);

                            if (removed == null
                                    || !player.isOnline()) {
                                return;
                            }

                            fail(
                                    player,
                                    "&cWorth search timed out"
                            );
                            reopen(
                                    player,
                                    removed.page()
                            );
                        },
                        SEARCH_TIMEOUT_TICKS
                );
        searchTimeouts.put(playerId, timeout);

        MenuHistory.closeForInput(core, player);
        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbType an item name to search Worth"
                )
        );
        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbType &#ff88ffcancel "
                                + "&#bbbbbbto return or "
                                + "&#ff88ffclear "
                                + "&#bbbbbbto reset search"
                )
        );
        SoundService.guiSearch(player, core);
    }

    private void reopen(
            Player player,
            int page
    ) {
        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () -> WorthGui.open(
                        core,
                        player,
                        sellService,
                        page
                )
        );
    }

    private void cancelSearchTimeout(UUID playerId) {
        BukkitTask task = searchTimeouts.remove(
                playerId
        );

        if (task != null) {
            task.cancel();
        }
    }

    private String sanitize(String input) {
        if (input == null) {
            return "";
        }

        String stripped = TextColor.strip(input);

        if (stripped == null) {
            return "";
        }

        return stripped
                .replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String displayLabel(String query) {
        String normalized = query.toLowerCase(
                Locale.ROOT
        );

        for (org.bukkit.Material material
                : sellService.worthCatalogMaterials()) {
            String raw = material.name()
                    .toLowerCase(Locale.ROOT)
                    .replace('_', ' ');
            String pretty = sellService.pretty(material);

            if (raw.equals(normalized)
                    || pretty.equalsIgnoreCase(query)) {
                return pretty;
            }
        }

        return query;
    }

    private void fail(
            Player player,
            String message
    ) {
        player.sendMessage(TextColor.color(message));
        SoundService.guiError(player, core);
    }

    private record SearchPrompt(int page) {
    }
}
