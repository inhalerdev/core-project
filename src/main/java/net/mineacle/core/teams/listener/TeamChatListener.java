package net.mineacle.core.teams.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.UUID;

public final class TeamChatListener implements Listener {

    private final Core core;
    private final TeamService teamService;

    public TeamChatListener(Core core, TeamService teamService) {
        this.core = core;
        this.teamService = teamService;
    }

    @EventHandler
    public void onAsyncChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();

        if (!teamService.isTeamChatEnabled(sender.getUniqueId())) {
            return;
        }

        TeamRecord team = teamService.getTeamByPlayer(sender.getUniqueId());

        if (team == null) {
            teamService.setTeamChat(sender.getUniqueId(), false);
            sender.sendMessage(TextColor.color("&cTeam chat disabled because you are not in a team."));
            return;
        }

        event.setCancelled(true);

        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        Bukkit.getScheduler().runTask(core, () -> sendTeamMessage(sender, team, message));
    }

    public void sendTeamMessage(Player sender, TeamRecord team, String message) {
        String formatted = TextColor.color(
                "&d[" + team.name() + "] "
                        + DisplayNames.prefixedDisplayName(sender)
                        + "&#bbbbbb: &#bbbbbb" + message
        );

        for (UUID memberId : teamService.getTeamMembers(team.teamId())) {
            Player member = Bukkit.getPlayer(memberId);

            if (member != null && member.isOnline()) {
                member.sendMessage(formatted);
            }
        }
    }
}