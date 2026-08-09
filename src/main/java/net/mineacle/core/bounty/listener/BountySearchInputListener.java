package net.mineacle.core.bounty;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BountySearchInputListener implements Listener {

    private static final long TIMEOUT_TICKS = 20L * 30L;
    private static final int MAX_QUERY_LENGTH = 32;

    private final Core core;
    private final BountyService bountyService;
    private final Map<UUID, SearchPrompt> prompts =
            new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> timeouts =
            new ConcurrentHashMap<>();

    public BountySearchInputListener(
            Core core,
            BountyService bountyService
    ) {
        this.core = core;
        this.bountyService = bountyService;
    }

    public void begin(Player player, int page) {
        UUID playerId = player.getUniqueId();
        prompts.put(playerId, new SearchPrompt(page));
        cancelTimeout(playerId);

        BukkitTask timeout = core.getServer()
                .getScheduler()
                .runTaskLater(
                        core,
                        () -> {
                            SearchPrompt prompt =
                                    prompts.remove(playerId);
                            timeouts.remove(playerId);

                            if (prompt == null
                                    || !player.isOnline()) {
                                return;
                            }

                            sendActionBar(
                                    player,
                                    "&cBounty search timed out"
                            );
                            reopen(player, prompt.page());
                        },
                        TIMEOUT_TICKS
                );

        timeouts.put(playerId, timeout);
        MenuHistory.closeForInput(core, player);
        player.sendMessage(TextColor.color(
                "&#bbbbbbType a player name to search bounties"
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbType &#ff88ffcancel "
                        + "&#bbbbbbto return or "
                        + "&#ff88ffclear "
                        + "&#bbbbbbto reset search"
        ));
        sendActionBar(
                player,
                "&#bbbbbbType a player name to search bounties"
        );
    }

    @EventHandler(
            priority = EventPriority.LOWEST,
            ignoreCancelled = false
    )
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        SearchPrompt prompt = prompts.remove(
                player.getUniqueId()
        );

        if (prompt == null) {
            return;
        }

        event.setCancelled(true);
        cancelTimeout(player.getUniqueId());

        String query = sanitize(
                PlainTextComponentSerializer.plainText()
                        .serialize(event.message())
        );

        core.getServer().getScheduler().runTask(
                core,
                () -> handleInput(
                        player,
                        prompt,
                        query
                )
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        prompts.remove(playerId);
        cancelTimeout(playerId);
        BountyMainGui.clearState(event.getPlayer());
    }

    public void shutdown() {
        for (BukkitTask task : timeouts.values()) {
            task.cancel();
        }

        timeouts.clear();
        prompts.clear();
    }

    private void handleInput(
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
                    "&#bbbbbbBounty search cancelled"
            );
            reopen(player, prompt.page());
            return;
        }

        if (query.equalsIgnoreCase("clear")) {
            BountyMainGui.clearSearch(player);
            sendActionBar(
                    player,
                    "&#bbbbbbBounty search cleared"
            );
            reopen(player, 0);
            return;
        }

        if (query.isBlank()) {
            sendActionBar(
                    player,
                    "&cSearch cannot be empty"
            );
            reopen(player, prompt.page());
            return;
        }

        String displayLabel =
                bountyService.displaySearchLabel(query);

        BountyMainGui.setSearch(
                player,
                query,
                displayLabel
        );

        if (!bountyService.hasMatches(
                BountyMainGui.sortMode(player),
                BountyMainGui.search(player)
        )) {
            sendActionBar(
                    player,
                    "&cNo bounty target found"
            );
        }

        reopen(player, 0);
    }

    private void reopen(Player player, int page) {
        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () -> BountyMainGui.open(
                        core,
                        player,
                        bountyService,
                        page
                )
        );
    }

    private String sanitize(String input) {
        if (input == null) {
            return "";
        }

        String clean = TextColor.strip(input)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();

        if (clean.length() > MAX_QUERY_LENGTH) {
            clean = clean.substring(0, MAX_QUERY_LENGTH);
        }

        return clean;
    }

    private void cancelTimeout(UUID playerId) {
        BukkitTask task = timeouts.remove(playerId);

        if (task != null) {
            task.cancel();
        }
    }

    private void sendActionBar(
            Player player,
            String message
    ) {
        player.sendActionBar(
                LegacyComponentSerializer.legacySection()
                        .deserialize(TextColor.color(message))
        );
    }

    private record SearchPrompt(int page) {
    }
}
