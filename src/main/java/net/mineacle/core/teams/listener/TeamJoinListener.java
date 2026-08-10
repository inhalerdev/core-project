package net.mineacle.core.teams.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.teams.gui.TeamsMainGui;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

@SuppressWarnings("unused")
public final class TeamJoinListener implements Listener {

    private final Core core;
    private final TeamService teamService;

    public TeamJoinListener(Core core, TeamService teamService) {
        this.core = core;
        this.teamService = teamService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (!teamService.isTeamChatEnabled(player.getUniqueId())) {
            return;
        }

        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    String message = "&#bbbbbbTeam chat &aenabled";
                    player.sendMessage(TextColor.color(message));
                    player.sendActionBar(actionBar(message));
                },
                20L
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        TeamsMainGui.clearPlayerState(event.getPlayer().getUniqueId());
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(TextColor.color(message));
    }
}
