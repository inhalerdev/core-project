package net.mineacle.core.teams.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.chat.ChatPauseService;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.UUID;

public final class TeamChatListener
        implements Listener {

    private static final String PRIMARY =
            "&#8436FE";
    private static final String BODY =
            "&#bbbbbb";

    private final Core core;
    private final TeamService teamService;

    public TeamChatListener(
            Core core,
            TeamService teamService
    ) {
        this.core = core;
        this.teamService = teamService;
    }

    @SuppressWarnings("unused")
    @EventHandler(ignoreCancelled = true)
    public void onAsyncChat(
            AsyncChatEvent event
    ) {
        Player sender =
                event.getPlayer();

        if (!teamService.isTeamChatEnabled(
                sender.getUniqueId()
        )) {
            return;
        }

        event.setCancelled(true);

        String message =
                PlainTextComponentSerializer
                        .plainText()
                        .serialize(
                                event.message()
                        );

        Bukkit.getScheduler()
                .runTask(
                        core,
                        () -> {
                            TeamRecord team =
                                    teamService
                                            .getTeamByPlayer(
                                                    sender.getUniqueId()
                                            );

                            if (team == null) {
                                teamService.setTeamChat(
                                        sender.getUniqueId(),
                                        false
                                );

                                if (sender.isOnline()) {
                                    sender.sendMessage(
                                            TextColor.color(
                                                    "&cTeam chat disabled because you are not in a team"
                                            )
                                    );
                                }
                                return;
                            }

                            sendTeamMessage(
                                    sender,
                                    team,
                                    message
                            );
                        }
                );
    }

    public void sendTeamMessage(
            Player sender,
            TeamRecord team,
            String message
    ) {
        Component prefix =
                LegacyComponentSerializer
                        .legacySection()
                        .deserialize(
                                TextColor.color(
                                        PRIMARY
                                                + "["
                                                + team.name()
                                                + "] "
                                                + DisplayNames
                                                .coloredDisplayName(
                                                        sender
                                                )
                                                + BODY
                                                + ": "
                                )
                        );
        Component formatted =
                prefix.append(
                        Component.text(message)
                                .color(
                                        net.kyori.adventure.text.format.TextColor
                                                .color(0xBBBBBB)
                                )
                );

        for (UUID memberId :
                teamService.getTeamMembers(
                        team.teamId()
                )) {
            Player member =
                    Bukkit.getPlayer(
                            memberId
                    );

            if (member != null
                    && member.isOnline()) {
                ChatPauseService.deliver(
                        core,
                        sender,
                        member,
                        formatted
                );
            }
        }
    }
}
