package net.mineacle.core.tpa.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
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
            sender.sendMessage("Players only");
            return true;
        }

        if (!player.hasPermission("mineacletpa.use")) {
            sendBoth(player, "&cYou do not have permission");
            SoundService.guiError(player, core);
            return true;
        }

        String commandName = command.getName().toLowerCase(Locale.ROOT);
        String usedLabel = label == null ? commandName : label.toLowerCase(Locale.ROOT);

        if (commandName.equals("tpa") || usedLabel.equals("tpa") || usedLabel.equals("tpask")) {
            return handleTpa(player, args, TpaRequestType.TO_TARGET);
        }

        if (commandName.equals("tpahere")
                || usedLabel.equals("tpahere")
                || usedLabel.equals("tphere")
                || usedLabel.equals("tpah")) {
            return handleTpa(player, args, TpaRequestType.HERE);
        }

        if (commandName.equals("tpaccept") || usedLabel.equals("tpaccept") || usedLabel.equals("tpyes") || usedLabel.equals("accepttp")) {
            return handleAccept(player);
        }

        if (commandName.equals("tpdeny") || usedLabel.equals("tpdeny") || usedLabel.equals("tpno") || usedLabel.equals("denytp")) {
            return handleDeny(player);
        }

        return true;
    }

    private boolean handleTpa(Player requester, String[] args, TpaRequestType type) {
        if (args.length < 1) {
            sendBoth(requester, type == TpaRequestType.TO_TARGET ? "&cUsage: /tpa <player>" : "&cUsage: /tpahere <player>");
            SoundService.guiError(requester, core);
            return true;
        }

        Player target = DisplayNames.resolveOnline(args[0]);

        if (target == null) {
            sendBoth(requester, "&cThat player is not online");
            SoundService.guiError(requester, core);
            return true;
        }

        if (target.getUniqueId().equals(requester.getUniqueId())) {
            sendBoth(requester, "&cYou cannot send a teleport request to yourself");
            SoundService.guiError(requester, core);
            return true;
        }

        if (!tpaService.createRequest(requester, target, type)) {
            sendBoth(requester, "&cCould not send teleport request");
            SoundService.guiError(requester, core);
            return true;
        }

        String requesterName = DisplayNames.prefixedDisplayName(requester);
        String targetName = DisplayNames.prefixedDisplayName(target);

        sendBoth(requester, "&aTeleport request sent to " + targetName);
        SoundService.teleportRequest(requester, core);

        sendRequestMessage(requester, target, requesterName, type);
        SoundService.teleportReceived(target, core);

        scheduleExpiration(requester, target, targetName);
        return true;
    }

    private void sendRequestMessage(Player requester, Player target, String requesterName, TpaRequestType type) {
        String mainLine = type == TpaRequestType.TO_TARGET
                ? requesterName + " &#bbbbbbwants to teleport to you"
                : requesterName + " &#bbbbbbwants you to teleport to them";

        target.sendActionBar(actionBar(mainLine));
        target.sendMessage(legacy(mainLine));

        Component accept = legacy("&d[Accept]")
                .clickEvent(ClickEvent.runCommand("/tpaccept"));

        Component deny = legacy("&d[Deny]")
                .clickEvent(ClickEvent.runCommand("/tpdeny"));

        Component view = legacy("&d[View]")
                .clickEvent(ClickEvent.runCommand("/tpaccept"));

        Component buttons = legacy("&#bbbbbbRespond ")
                .append(accept)
                .append(Component.space())
                .append(deny)
                .append(Component.space())
                .append(view);

        target.sendMessage(buttons);
    }

    private void scheduleExpiration(Player requester, Player target, String targetName) {
        core.getServer().getScheduler().runTaskLater(core, () -> {
            TpaRequest request = tpaService.getRequest(target.getUniqueId());

            if (request == null) {
                return;
            }

            if (!request.requesterId().equals(requester.getUniqueId())) {
                return;
            }

            tpaService.removeRequest(target.getUniqueId());

            if (requester.isOnline()) {
                sendBoth(requester, "&cTeleport request to " + targetName + " &cexpired");
                SoundService.guiError(requester, core);
            }

            if (target.isOnline()) {
                sendBoth(target, "&cTeleport request expired");
                SoundService.guiError(target, core);
            }
        }, tpaService.timeoutSeconds() * 20L);
    }

    private boolean handleAccept(Player player) {
        TpaRequest request = tpaService.getRequest(player.getUniqueId());

        if (request == null) {
            sendBoth(player, "&cYou have no pending teleport requests");
            SoundService.guiError(player, core);
            return true;
        }

        SoundService.guiClick(player, core);
        MenuHistory.openRoot(core, player, () -> TpaRequestGui.open(core, player, request));
        return true;
    }

    private boolean handleDeny(Player player) {
        TpaRequest request = tpaService.removeRequest(player.getUniqueId());

        if (request == null) {
            sendBoth(player, "&cYou have no pending teleport requests");
            SoundService.guiError(player, core);
            return true;
        }

        Player requester = tpaService.requester(request);

        sendBoth(player, "&cTeleport request denied");
        SoundService.guiCancel(player, core);

        if (requester != null && requester.isOnline()) {
            sendBoth(requester, "&c" + DisplayNames.prefixedDisplayName(player) + " denied your teleport request");
            SoundService.guiCancel(requester, core);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }

        String commandName = command.getName().toLowerCase(Locale.ROOT);
        String usedAlias = alias == null ? commandName : alias.toLowerCase(Locale.ROOT);

        boolean isRequestCommand = commandName.equals("tpa")
                || commandName.equals("tpahere")
                || usedAlias.equals("tpa")
                || usedAlias.equals("tpask")
                || usedAlias.equals("tpahere")
                || usedAlias.equals("tphere")
                || usedAlias.equals("tpah");

        if (isRequestCommand && args.length == 1) {
            return PlayerTabComplete.onlinePlayers(player, args[0]);
        }

        return List.of();
    }

    private void sendBoth(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return legacy(message);
    }

    private Component legacy(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
