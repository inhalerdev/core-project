package net.mineacle.core.tpa.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.service.TeleportService;
import net.mineacle.core.tpa.gui.TpaRequestGui;
import net.mineacle.core.tpa.service.TpaRequest;
import net.mineacle.core.tpa.service.TpaRequestType;
import net.mineacle.core.tpa.service.TpaService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TpaCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final TpaService tpaService;
    private final TeleportService teleportService;

    public TpaCommand(Core core, TpaService tpaService, TeleportService teleportService) {
        this.core = core;
        this.tpaService = tpaService;
        this.teleportService = teleportService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (!player.hasPermission("mineacletpa.use")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            SoundService.guiError(player, core);
            return true;
        }

        String used = label == null || label.isBlank() ? command.getName() : label;
        String name = used.toLowerCase(Locale.ROOT);

        if (name.equals("tpaccept") || name.equals("tpyes") || name.equals("accepttp")) {
            return accept(player);
        }

        if (name.equals("tpdeny") || name.equals("tpno") || name.equals("denytp")) {
            return deny(player);
        }

        if (args.length < 1) {
            player.sendMessage(TextColor.color(name.equals("tpahere") || name.equals("tphere") || name.equals("tpah")
                    ? "&cUsage: /tpahere <player>"
                    : "&cUsage: /tpa <player>"));
            SoundService.guiError(player, core);
            return true;
        }

        Player target = DisplayNames.resolveOnline(args[0]);

        if (target == null) {
            player.sendMessage(TextColor.color("&cThat player is offline"));
            SoundService.guiError(player, core);
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(TextColor.color("&cYou cannot send a teleport request to yourself"));
            SoundService.guiError(player, core);
            return true;
        }

        TpaRequestType type = name.equals("tpahere") || name.equals("tphere") || name.equals("tpah")
                ? TpaRequestType.HERE
                : TpaRequestType.TO_TARGET;

        if (!tpaService.createRequest(player, target, type)) {
            player.sendMessage(TextColor.color("&cCould not send teleport request"));
            SoundService.guiError(player, core);
            return true;
        }

        String targetName = DisplayNames.displayName(target);
        String playerName = DisplayNames.displayName(player);

        player.sendMessage(TextColor.color("&#bbbbbbTeleport request sent to &d" + targetName));
        player.sendActionBar(component("&#bbbbbbTeleport request sent to &d" + targetName));
        SoundService.teleportRequest(player, core);

        Component accept = component("&d[Accept]").clickEvent(ClickEvent.runCommand("/tpaccept"));
        Component deny = component("&d[Deny]").clickEvent(ClickEvent.runCommand("/tpdeny"));

        Component message = type == TpaRequestType.TO_TARGET
                ? component("&d" + playerName + " &#bbbbbbwants to teleport to you ")
                : component("&d" + playerName + " &#bbbbbbwants you to teleport to them ");

        target.sendMessage(message.append(accept).append(Component.space()).append(deny));
        target.sendActionBar(component("&d" + playerName + " &#bbbbbbsent a teleport request"));
        SoundService.teleportReceived(target, core);
        return true;
    }

    private boolean accept(Player target) {
        TpaRequest request = tpaService.removeRequest(target.getUniqueId());

        if (request == null) {
            target.sendMessage(TextColor.color("&cYou have no pending teleport requests"));
            SoundService.guiError(target, core);
            return true;
        }

        Player requester = tpaService.requester(request);

        if (requester == null || !requester.isOnline()) {
            target.sendMessage(TextColor.color("&cThat player is no longer online"));
            SoundService.guiError(target, core);
            return true;
        }

        target.sendMessage(TextColor.color("&#bbbbbbTeleport request accepted"));
        requester.sendMessage(TextColor.color("&#bbbbbbTeleport request accepted"));
        SoundService.guiConfirm(target, core);

        if (request.type() == TpaRequestType.TO_TARGET) {
            teleportService.begin(requester, "TPA", () -> {
                requester.teleport(target.getLocation());
                requester.sendMessage(TextColor.color("&#bbbbbbTeleported to &d" + DisplayNames.displayName(target)));
            });
            return true;
        }

        teleportService.begin(target, "TPA", () -> {
            target.teleport(requester.getLocation());
            target.sendMessage(TextColor.color("&#bbbbbbTeleported to &d" + DisplayNames.displayName(requester)));
        });
        return true;
    }

    private boolean deny(Player target) {
        TpaRequest request = tpaService.removeRequest(target.getUniqueId());

        if (request == null) {
            target.sendMessage(TextColor.color("&cYou have no pending teleport requests"));
            SoundService.guiError(target, core);
            return true;
        }

        Player requester = tpaService.requester(request);
        target.sendMessage(TextColor.color("&cTeleport request denied"));
        SoundService.guiCancel(target, core);

        if (requester != null && requester.isOnline()) {
            requester.sendMessage(TextColor.color("&c" + DisplayNames.displayName(target) + " denied your teleport request"));
            SoundService.guiCancel(requester, core);
        }

        return true;
    }

    public void openRequestGui(Player player) {
        TpaRequest request = tpaService.getRequest(player.getUniqueId());
        TpaRequestGui.open(core, player, request);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return new ArrayList<>();
        }

        if (args.length == 1 && (command.getName().equalsIgnoreCase("tpa") || command.getName().equalsIgnoreCase("tpahere"))) {
            return PlayerTabComplete.onlinePlayers(player, args[0]);
        }

        return new ArrayList<>();
    }

    private Component component(String value) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(value));
    }
}
