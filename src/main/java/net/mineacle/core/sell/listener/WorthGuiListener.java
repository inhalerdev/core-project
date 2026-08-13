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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unused")
public final class WorthGuiListener
        implements Listener {

    private static final long SEARCH_TIMEOUT_TICKS =
            20L * 30L;

    private final Core core;
    private final SellService sellService;
    private final Map<UUID, SearchPrompt> prompts =
            new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> timeoutTasks =
            new ConcurrentHashMap<>();

    public WorthGuiListener(
            Core core,
            SellService sellService
    ) {
        this.core = core;
        this.sellService = sellService;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST
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

        int slot =
                event.getRawSlot();
        int topSize =
                event.getView()
                        .getTopInventory()
                        .getSize();

        if (slot < 0
                || slot >= topSize) {
            return;
        }

        if ((slot == WorthGui.PREVIOUS_SLOT
                || slot == WorthGui.NEXT_SLOT)
                && WorthGui.isDisabledNavigation(
                event.getCurrentItem()
        )) {
            return;
        }

        int page =
                WorthGui.currentPage(
                        player
                );

        if (slot
                == WorthGui.PREVIOUS_SLOT) {
            SoundService.guiPage(
                    player,
                    core
            );
            reopen(
                    player,
                    page - 1
            );
            return;
        }

        if (slot
                == WorthGui.SORT_SLOT) {
            SoundService.guiSort(
                    player,
                    core
            );
            WorthGui.cycleSort(
                    player,
                    event.isRightClick()
            );
            reopen(
                    player,
                    0
            );
            return;
        }

        if (slot
                == WorthGui.FILTER_SLOT) {
            SoundService.guiFilter(
                    player,
                    core
            );
            WorthGui.cycleFilter(
                    player,
                    event.isRightClick()
            );
            reopen(
                    player,
                    0
            );
            return;
        }

        if (slot
                == WorthGui.SEARCH_SLOT) {
            if (event.isRightClick()
                    && !WorthGui.query(
                    player
            ).isBlank()) {
                WorthGui.clearQuery(
                        player
                );
                SoundService.guiCancel(
                        player,
                        core
                );
                reopen(
                        player,
                        0
                );
                return;
            }

            SoundService.guiSearch(
                    player,
                    core
            );
            beginSearch(
                    player,
                    page
            );
            return;
        }

        if (slot
                == WorthGui.REFRESH_SLOT) {
            SoundService.guiRefresh(
                    player,
                    core
            );
            sellService
                    .recalculateDemandIfNeeded();
            WorthGui.clearCatalogCache();
            reopen(
                    player,
                    page
            );
            return;
        }

        if (slot
                == WorthGui.NEXT_SLOT) {
            SoundService.guiPage(
                    player,
                    core
            );
            reopen(
                    player,
                    page + 1
            );
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST
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
            priority = EventPriority.LOWEST
    )
    public void onChat(
            AsyncChatEvent event
    ) {
        Player player =
                event.getPlayer();
        SearchPrompt prompt =
                takePrompt(
                        player.getUniqueId()
                );

        if (prompt == null) {
            return;
        }

        event.setCancelled(true);

        String input =
                PlainTextComponentSerializer
                        .plainText()
                        .serialize(
                                event.message()
                        )
                        .trim();

        core.getServer()
                .getScheduler()
                .runTask(
                        core,
                        () -> handleSearchInput(
                                player,
                                prompt,
                                input
                        )
                );
    }

    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onQuit(
            PlayerQuitEvent event
    ) {
        clearPrompt(
                event.getPlayer()
                        .getUniqueId()
        );
        WorthGui.clear(
                event.getPlayer()
        );
    }

    public void shutdown() {
        for (BukkitTask task
                : timeoutTasks.values()) {
            task.cancel();
        }

        timeoutTasks.clear();
        prompts.clear();
        WorthGui.clearAllState();
    }

    private void beginSearch(
            Player player,
            int returnPage
    ) {
        UUID playerId =
                player.getUniqueId();

        clearPrompt(
                playerId
        );

        SearchPrompt prompt =
                new SearchPrompt(
                        returnPage,
                        WorthGui.query(player)
                );

        prompts.put(
                playerId,
                prompt
        );

        BukkitTask timeout =
                core.getServer()
                        .getScheduler()
                        .runTaskLater(
                                core,
                                () -> expireSearch(
                                        playerId,
                                        prompt
                                ),
                                SEARCH_TIMEOUT_TICKS
                        );

        timeoutTasks.put(
                playerId,
                timeout
        );

        MenuHistory.closeForInput(
                core,
                player
        );

        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbType an item name to search"
                )
        );
        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbType &#D0AFFFcancel &#bbbbbbto return or &#D0AFFFclear &#bbbbbbto reset"
                )
        );
    }

    private void handleSearchInput(
            Player player,
            SearchPrompt prompt,
            String input
    ) {
        if (!player.isOnline()) {
            return;
        }

        if (input.equalsIgnoreCase(
                "cancel"
        )
                || input.equalsIgnoreCase(
                "cancelled"
        )) {
            WorthGui.setQuery(
                    player,
                    prompt.previousQuery()
            );
            SoundService.guiCancel(
                    player,
                    core
            );
            reopen(
                    player,
                    prompt.returnPage()
            );
            return;
        }

        if (input.equalsIgnoreCase(
                "clear"
        )) {
            WorthGui.clearQuery(
                    player
            );
            SoundService.guiCancel(
                    player,
                    core
            );
            reopen(
                    player,
                    0
            );
            return;
        }

        WorthGui.setQuery(
                player,
                input
        );
        SoundService.guiSearch(
                player,
                core
        );
        reopen(
                player,
                0
        );
    }

    private void expireSearch(
            UUID playerId,
            SearchPrompt prompt
    ) {
        if (!prompts.remove(
                playerId,
                prompt
        )) {
            return;
        }

        BukkitTask timeout =
                timeoutTasks.remove(
                        playerId
                );

        if (timeout != null
                && !timeout.isCancelled()) {
            timeout.cancel();
        }

        Player player =
                core.getServer()
                        .getPlayer(
                                playerId
                        );

        if (player == null
                || !player.isOnline()) {
            return;
        }

        WorthGui.setQuery(
                player,
                prompt.previousQuery()
        );
        player.sendMessage(
                TextColor.color(
                        "&cSearch timed out"
                )
        );
        SoundService.guiCancel(
                player,
                core
        );
        reopen(
                player,
                prompt.returnPage()
        );
    }

    private SearchPrompt takePrompt(
            UUID playerId
    ) {
        SearchPrompt prompt =
                prompts.remove(
                        playerId
                );

        BukkitTask timeout =
                timeoutTasks.remove(
                        playerId
                );

        if (timeout != null) {
            timeout.cancel();
        }

        return prompt;
    }

    private void clearPrompt(
            UUID playerId
    ) {
        prompts.remove(
                playerId
        );

        BukkitTask timeout =
                timeoutTasks.remove(
                        playerId
                );

        if (timeout != null) {
            timeout.cancel();
        }
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

    private record SearchPrompt(
            int returnPage,
            String previousQuery
    ) {
    }
}
