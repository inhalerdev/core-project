package net.mineacle.core.chat.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.chat.service.ChatService;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.stats.StatsModule;
import net.mineacle.core.stats.service.StatsService;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class ChatFormatListener
        implements Listener {

    private final Core.Core core;
    private final ChatService chatService;
    private final TeamService teamService;

    public ChatFormatListener(
            Core.Core core,
            ChatService chatService,
            TeamService teamService
    ) {
        this.core = core;
        this.chatService = chatService;
        this.teamService = teamService;
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();

        if (teamService != null
                && teamService.isTeamChatEnabled(
                sender.getUniqueId()
        )) {
            return;
        }

        event.setCancelled(true);

        String message = chatService.sanitizeMessage(
                PlainTextComponentSerializer.plainText()
                        .serialize(event.message())
        );

        core.getServer().getScheduler().runTask(
                core,
                () -> sendChat(sender, message)
        );
    }

    private void sendChat(
            Player sender,
            String message
    ) {
        if (!sender.isOnline() || message.isBlank()) {
            return;
        }

        if (!chatService.enabled()) {
            sender.sendMessage(
                    Component.text(
                            "Chat is currently disabled",
                            net.kyori.adventure.text.format.TextColor.color(
                                    0xFF5555
                            )
                    )
            );
            SoundService.guiError(sender, core);
            return;
        }

        for (Player recipient
                : chatService.chatRecipients(sender)) {
            recipient.sendMessage(
                    chatComponent(
                            sender,
                            recipient,
                            message
                    )
            );
        }

        core.getServer()
                .getConsoleSender()
                .sendMessage(
                        chatService.formatChat(
                                sender,
                                message
                        )
                );
    }

    private Component chatComponent(
            Player sender,
            Player recipient,
            String message
    ) {
        Component identity = legacyPrefix(
                compactRankPrefix(sender)
        ).append(
                neutral(
                        DisplayNames.displayName(sender)
                )
        ).hoverEvent(
                HoverEvent.showText(
                        hoverStats(sender)
                )
        );

        if (!sender.getUniqueId()
                .equals(recipient.getUniqueId())) {
            identity = identity.clickEvent(
                    ClickEvent.runCommand(
                            "/tpa "
                                    + DisplayNames.commandDisplayName(
                                    sender
                            )
                    )
            );
        }

        return identity
                .append(neutral(": "))
                .append(neutral(message));
    }

    private Component hoverStats(Player player) {
        EconomyService economy =
                EconomyModule.economyService();
        StatsService stats =
                StatsModule.statsService();

        String money = economy == null
                ? "0"
                : withoutCurrencySymbol(economy.format(
                economy.getBalanceCents(
                        player.getUniqueId()
                )
        ));
        long kills = stats == null
                ? 0L
                : stats.kills(
                player.getUniqueId()
        );
        long deaths = stats == null
                ? 0L
                : stats.deaths(
                player.getUniqueId()
        );
        String playtime = stats == null
                ? "0m"
                : stats.playtime(
                player.getUniqueId()
        );

        Component hover = legacyPrefix(
                compactRankPrefix(player)
        ).append(
                neutral(DisplayNames.displayName(player))
        );
        TeamRecord team = teamService == null
                ? null
                : teamService.getTeamByPlayer(
                player.getUniqueId()
        );

        /*
         * Team information is a real conditional row, not a reserved blank
         * line. Team changes are read when each chat message is rendered, so
         * the next message always reflects the current team state.
         */
        if (team != null
                && team.name() != null
                && !team.name().isBlank()) {
            hover = hover
                    .append(Component.newline())
                    .append(
                            legacyPrefix(
                                    "&#ff88ff🔥&#bbbbbb "
                                            + team.name()
                            )
                    );
        }

        return hover
                .append(Component.newline())
                .append(
                        legacyPrefix(
                                "&a$ &#bbbbbb" + money
                        )
                )
                .append(Component.newline())
                .append(
                        legacyPrefix(
                                "&e⌚&#bbbbbb " + playtime
                        )
                )
                .append(Component.newline())
                .append(
                        legacyPrefix(
                                "&c🗡 &#bbbbbb" + kills
                        )
                )
                .append(Component.newline())
                .append(
                        legacyPrefix(
                                "&#ffa033☠&#bbbbbb " + deaths
                        )
                );
    }

    private String compactRankPrefix(Player player) {
        String prefix = DisplayNames.luckPermsPrefix(player);

        if (prefix == null || prefix.isEmpty()) {
            return "";
        }

        /*
         * DisplayNames.luckPermsPrefix intentionally adds one separator for
         * normal player-facing text. Chat places the neutral display name
         * directly against the rank, so remove that separator here.
         */
        return prefix.endsWith(" ")
                ? prefix.substring(0, prefix.length() - 1)
                : prefix.stripTrailing();
    }

    private String withoutCurrencySymbol(String value) {
        if (value == null || value.isBlank()) {
            return "0";
        }

        if (value.startsWith("-$")) {
            return "-" + value.substring(2);
        }

        return value.startsWith("$")
                ? value.substring(1)
                : value;
    }

    private Component legacyPrefix(String text) {
        if (text == null || text.isBlank()) {
            return Component.empty();
        }

        return net.kyori.adventure.text.serializer.legacy
                .LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        net.mineacle.core.common.text.TextColor.color(
                                text
                        )
                )
                .decoration(
                        TextDecoration.ITALIC,
                        false
                );
    }

    private Component neutral(String text) {
        return Component.text(
                        text == null ? "" : text,
                        net.kyori.adventure.text.format.TextColor.color(
                                0xBBBBBB
                        )
                )
                .decoration(
                        TextDecoration.ITALIC,
                        false
                );
    }

}
