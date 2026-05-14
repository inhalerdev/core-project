package net.mineacle.core.chat.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.chat.service.ChatService;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class ChatFormatListener implements Listener {

    private final Core core;
    private final ChatService chatService;
    private final TeamService teamService;

    public ChatFormatListener(Core core, ChatService chatService, TeamService teamService) {
        this.core = core;
        this.chatService = chatService;
        this.teamService = teamService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();

        if (teamService != null && teamService.isTeamChatEnabled(sender.getUniqueId())) {
            return;
        }

        event.setCancelled(true);

        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        Bukkit.getScheduler().runTask(core, () -> {
            String consoleFormatted = chatService.formatChat(sender, message);

            for (Player recipient : chatService.chatRecipients(sender)) {
                recipient.sendMessage(chatComponent(sender, recipient, message));
            }

            core.getServer().getConsoleSender().sendMessage(consoleFormatted);
        });
    }

    private Component chatComponent(Player sender, Player recipient, String message) {
        Component base = legacy(DisplayNames.prefixedDisplayName(sender) + "&#cccccc: &#cccccc" + message)
                .hoverEvent(HoverEvent.showText(hoverStats(sender)));

        if (!sender.getUniqueId().equals(recipient.getUniqueId())) {
            base = base.clickEvent(ClickEvent.runCommand("/tpamenu " + DisplayNames.commandDisplayName(sender)));
        }

        return base;
    }

    private Component hoverStats(Player player) {
        String displayName = DisplayNames.prefixedDisplayName(player);
        String money = placeholder(player, "%mineacle_balance%", "$0");
        String kills = placeholder(player, "%mineacle_stats_kills%", String.valueOf(stat(player, Statistic.PLAYER_KILLS)));
        String deaths = placeholder(player, "%mineacle_stats_deaths%", String.valueOf(stat(player, Statistic.DEATHS)));
        String playtime = placeholder(player, "%playtime_time%", playtime(player));

        return legacy(
                displayName + "\n"
                        + "&a$ &#ccccccMoney &a" + money + "\n"
                        + "&#ff0000🗡 &#ccccccKills &#ff0000" + kills + "\n"
                        + "&#ff8800☠ &#ccccccDeaths &#ff8800" + deaths + "\n"
                        + "&e⌚ &#ccccccPlaytime &e" + playtime
        );
    }

    private String placeholder(Player player, String placeholder, String fallback) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return fallback;
        }

        try {
            String parsed = PlaceholderAPI.setPlaceholders(player, placeholder);

            if (parsed == null || parsed.isBlank() || parsed.equalsIgnoreCase(placeholder)) {
                return fallback;
            }

            return parsed;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private int stat(Player player, Statistic statistic) {
        try {
            return player.getStatistic(statistic);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String playtime(Player player) {
        int ticks = stat(player, Statistic.PLAY_ONE_MINUTE);
        long totalSeconds = ticks / 20L;
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;

        if (days > 0) {
            return days + "d " + hours + "h";
        }

        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }

        return minutes + "m";
    }

    private Component legacy(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
