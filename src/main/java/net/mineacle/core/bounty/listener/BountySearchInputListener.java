package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class BountySearchInputListener implements Listener {

    private static final Set<UUID> WAITING = new HashSet<>();

    private final Core core;
    private final BountyService bountyService;

    public BountySearchInputListener(Core core, BountyService bountyService) {
        this.core = core;
        this.bountyService = bountyService;
    }

    public static void begin(Player player) {
        WAITING.add(player.getUniqueId());
        player.sendMessage(TextColor.color("&#bbbbbbType a player name to search bounties"));
        player.sendMessage(TextColor.color("&#bbbbbbType &#ff88ffclear &#bbbbbbto reset or &#ff88ffcancel &#bbbbbbto stop"));
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        if (!WAITING.remove(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage();

        core.getServer().getScheduler().runTask(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("cancelled")) {
                MenuHistory.openWithoutBackTrigger(core, player, () -> BountyMainGui.open(core, player, bountyService, BountyMainGui.currentPage(player)));
                return;
            }

            if (message.equalsIgnoreCase("clear")) {
                BountyMainGui.clearSearch(player);
            } else {
                BountyMainGui.setSearch(player, message);
            }

            MenuHistory.openWithoutBackTrigger(core, player, () -> BountyMainGui.open(core, player, bountyService, 0));
        });
    }
}
