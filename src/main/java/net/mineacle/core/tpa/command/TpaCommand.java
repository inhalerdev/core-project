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
import org.bukkit.Bukkit;
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

        String commandName = label.toLowerCase(Locale.ROOT);

        return switch (commandName) {
            case "tpa", "tpask" -> handleTpa(player, args, TpaRequestType.TO_TARGET);
            case "tpahere", "tphere", "tpah" -> handleTpa(player, args, TpaRequestType.HERE);
            case "tpaccept", "tpyes", "accepttp" -> handleAccept(player);
            case "tpadeny", "tpdeny", "tpno", "denytp" -> handleDeny(player);
            case "tpacancel" -> handleCancel(player);
            case "tpauto" -> handleAuto(player);
            default -> true;
        };
    }

    private boolean handleTpa(Player requester, String[] args, TpaRequestType type) {
        if (args.length < 1) {
            sendBoth(requester, type == TpaRequestType.TO_TARGET ? "&cUsage: /tpa <player>" : "&cUsage: /tpahere <player>");
            SoundService.guiError(requester, core);
            return true;
        }

        Player resolvedTarget = DisplayNames.resolveOnline(args[0]);

        if (resolvedTarget == null) {
            resolvedTarget = Bukkit.getPlayerExact(args[0]);
        }

        if (resolvedTarget == null) {
            sendBoth(requester, "&cThat player is not online");
            SoundService.guiError(requester, core);
            return true;
        }

        if (resolvedTarget.getUniqueId().equals(requester.getUniqueId())) {
            sendBoth(requester, "&cYou cannot send a teleport request to yourself");
            SoundService.guiError(requester, core);
            return true;
        }

        final Player target = resolvedTarget;

        if (tpaService.isAutoAccepting(target.getUniqueId()) && type == TpaRequestType.TO_TARGET) {
            sendBoth(requester, "&#bbbbbbTeleport request auto accepted");
            SoundService.teleportRequest(requester, core);

            teleportService.begin(requester, "TPA", () -> {
                requester.teleport(target.getLocation());
                sendBoth(requester, "&#bbbbbbTeleported to &#ff88ff" + DisplayNames.displayName(target));
                SoundService.teleportComplete(requester, core);
            });

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

        target.sendActionBar(legacy(mainLine));
        target.sendMessage(legacy(mainLine));

        Component accept = legacy("&a[Accept]")
                .clickEvent(ClickEvent.runCommand("/tpaccept"));

        Component deny = legacy("&c[Deny]")
                .clickEvent(ClickEvent.runCommand("/tpadeny"));

        Component buttons = legacy("&#bbbbbbRespond ")
                .append(accept)
                .append(Component.space())
                .append(deny);

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

    private boolean handleCancel(Player player) {
        TpaRequest request = tpaService.removeOutgoing(player.getUniqueId());

        if (request == null) {
            sendBoth(player, "&cYou have no outgoing teleport request");
            SoundService.guiError(player, core);
            return true;
        }

        Player target = tpaService.target(request);

        sendBoth(player, "&cTeleport request cancelled");
        SoundService.guiCancel(player, core);

        if (target != null && target.isOnline()) {
            sendBoth(target, "&cTeleport request cancelled");
            SoundService.guiCancel(target, core);
        }

        return true;
    }

    private boolean handleAuto(Player player) {
        boolean enabled = tpaService.toggleAutoAccept(player.getUniqueId());

        sendBoth(player, enabled ? "&#bbbbbbTPA auto accept enabled" : "&#bbbbbbTPA auto accept disabled");

        if (enabled) {
            SoundService.featureEnable(player, core);
        } else {
            SoundService.featureDisable(player, core);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }

        if (!player.hasPermission("mineacletpa.use")) {
            return List.of();
        }

        String commandName = alias.toLowerCase(Locale.ROOT);

        if ((commandName.equals("tpa")
                || commandName.equals("tpask")
                || commandName.equals("tpahere")
                || commandName.equals("tphere")
                || commandName.equals("tpah")) && args.length == 1) {
            return PlayerTabComplete.onlinePlayers(player, args[0]);
        }

        return List.of();
    }

    private void sendBoth(Player player, String message) {
        Component component = legacy(message);
        player.sendMessage(component);
        player.sendActionBar(component);
    }

    private Component legacy(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
