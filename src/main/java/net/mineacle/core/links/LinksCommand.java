package net.mineacle.core.links;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LinksCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final LinksService service;

    public LinksCommand(Core core, LinksService service) {
        this.core = core;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (!player.hasPermission("mineaclelinks.use")) {
            sendError(player, service.noPermission());
            return true;
        }

        String commandName = command.getName().toLowerCase(Locale.ROOT);

        if (commandName.equals("links")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!player.hasPermission("mineaclelinks.admin")) {
                    sendError(player, service.noPermission());
                    return true;
                }

                service.reload();
                player.sendMessage(TextColor.color(service.reloaded()));
                SoundService.guiConfirm(player, core);
                return true;
            }

            if (args.length > 0) {
                return sendLink(player, args[0]);
            }

            player.sendMessage(TextColor.color("&#bbbbbbQuick links: &#ff88ff/store &#bbbbbb| &#5865F2/discord &#bbbbbb| &#ffffff/x &#bbbbbb| &c/appeal"));
            return true;
        }

        return sendLink(player, commandName);
    }

    private boolean sendLink(Player player, String key) {
        if (!service.enabled()) {
            sendError(player, "&cQuick links are disabled");
            return true;
        }

        LinkEntry entry = service.find(key);

        if (entry == null || entry.url() == null || entry.url().isBlank()) {
            sendError(player, service.unknownLink());
            return true;
        }

        if (service.blankLines()) {
            player.sendMessage(Component.empty());
        }

        player.sendMessage(TextColor.color(entry.header()));

        for (String line : entry.lines()) {
            player.sendMessage(TextColor.color(line));
        }

        player.sendMessage(clickLine(entry.button(), entry.url(), entry.hover()));

        if (service.fallbackUrl()) {
            player.sendMessage(TextColor.color(entry.fallbackColor() + entry.url()));
        }

        if (service.blankLines()) {
            player.sendMessage(Component.empty());
        }

        return true;
    }

    private Component clickLine(String text, String url, String hover) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(text))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(
                        LegacyComponentSerializer.legacySection().deserialize(TextColor.color(hover))
                ));
    }

    private void sendError(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message)));
        SoundService.guiError(player, core);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!command.getName().equalsIgnoreCase("links")) {
            return completions;
        }

        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);

            if (sender.hasPermission("mineaclelinks.admin") && "reload".startsWith(partial)) {
                completions.add("reload");
            }

            for (LinkEntry entry : service.entries()) {
                if (entry.key().startsWith(partial)) {
                    completions.add(entry.key());
                }
            }
        }

        return completions;
    }
}
