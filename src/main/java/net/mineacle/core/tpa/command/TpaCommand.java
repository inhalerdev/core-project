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
import net.mineacle.core.common.teleport.TeleportService;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class TpaCommand
        implements CommandExecutor, TabCompleter {

    private static final String PRIMARY = "&#8436FE";
    private static final String SECONDARY = "&#B078FF";
    private static final String ACCENT = "&#D0AFFF";
    private static final String BODY = "&#bbbbbb";

    private final Core core;
    private final TpaService tpaService;
    private final TeleportService teleportService;

    public TpaCommand(
            Core core,
            TpaService tpaService,
            TeleportService teleportService
    ) {
        this.core = core;
        this.tpaService = tpaService;
        this.teleportService = teleportService;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            String @NotNull [] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    TextColor.color("&cPlayers only")
            );
            return true;
        }

        if (!player.hasPermission("mineacletpa.use")) {
            error(
                    player,
                    "&cYou do not have permission"
            );
            return true;
        }

        switch (label.toLowerCase(Locale.ROOT)) {
            case "tpa", "tpask" ->
                    handleTpa(
                            player,
                            args,
                            TpaRequestType.TO_TARGET
                    );
            case "tpahere", "tphere", "tpah" ->
                    handleTpa(
                            player,
                            args,
                            TpaRequestType.HERE
                    );
            case "tpaccept", "tpyes", "accepttp" ->
                    handleAccept(player);
            case "tpadeny", "tpdeny", "tpno", "denytp" ->
                    handleDeny(player);
            case "tpacancel" ->
                    handleCancel(player);
            case "tpauto" ->
                    handleAuto(player);
            default -> {
            }
        }

        return true;
    }

    private void handleTpa(
            Player requester,
            String[] args,
            TpaRequestType type
    ) {
        if (args.length < 1) {
            error(
                    requester,
                    type == TpaRequestType.TO_TARGET
                            ? "&cUsage: /tpa <player>"
                            : "&cUsage: /tpahere <player>"
            );
            return;
        }

        Player target = resolveTarget(args[0]);

        if (target == null) {
            error(
                    requester,
                    "&cThat player is not online"
            );
            return;
        }

        if (target.getUniqueId()
                .equals(requester.getUniqueId())) {
            error(
                    requester,
                    "&cYou cannot send a teleport request to yourself"
            );
            return;
        }

        if (tpaService.isAutoAccepting(
                target.getUniqueId()
        ) && type == TpaRequestType.TO_TARGET) {
            if (teleportService.beginPlayer(
                    requester,
                    target
            )) {
                sendBoth(
                        requester,
                        BODY
                                + "Teleport request "
                                + "&aauto accepted "
                                + BODY
                                + "by "
                                + playerName(target)
                );
            }
            return;
        }

        if (!tpaService.createRequest(
                requester,
                target,
                type
        )) {
            error(
                    requester,
                    "&cCould not send teleport request"
            );
            return;
        }

        sendBoth(
                requester,
                BODY
                        + "Teleport request sent to "
                        + playerName(target)
        );
        SoundService.teleportRequest(
                requester,
                core
        );

        sendRequestMessage(
                requester,
                target,
                type
        );
        SoundService.teleportReceived(
                target,
                core
        );
    }

    private Player resolveTarget(String input) {
        Player target =
                DisplayNames.resolveOnline(input);

        return target != null
                ? target
                : Bukkit.getPlayerExact(input);
    }

    private void sendRequestMessage(
            Player requester,
            Player target,
            TpaRequestType type
    ) {
        String mainLine =
                playerName(requester)
                        + BODY
                        + (
                        type == TpaRequestType.TO_TARGET
                                ? " wants to teleport to you"
                                : " wants you to teleport to them"
                );

        Component main = component(mainLine);
        target.sendActionBar(main);
        target.sendMessage(main);

        Component accept =
                component("&a[Accept]")
                        .clickEvent(
                                ClickEvent.runCommand(
                                        "/tpaccept"
                                )
                        );
        Component deny =
                component("&c[Deny]")
                        .clickEvent(
                                ClickEvent.runCommand(
                                        "/tpadeny"
                                )
                        );
        Component buttons =
                component(
                        ACCENT + "Respond "
                )
                        .append(accept)
                        .append(Component.space())
                        .append(deny);

        target.sendMessage(buttons);
    }

    private void handleAccept(Player player) {
        TpaRequest request =
                tpaService.getRequest(
                        player.getUniqueId()
                );

        if (request == null) {
            error(
                    player,
                    "&cYou have no pending teleport requests"
            );
            return;
        }

        SoundService.guiClick(
                player,
                core
        );
        MenuHistory.openRoot(
                core,
                player,
                () -> TpaRequestGui.open(
                        player,
                        request
                )
        );
    }

    private void handleDeny(Player player) {
        TpaRequest request =
                tpaService.removeRequest(
                        player.getUniqueId()
                );

        if (request == null) {
            error(
                    player,
                    "&cYou have no pending teleport requests"
            );
            return;
        }

        Player requester =
                tpaService.requester(request);

        sendBoth(
                player,
                "&cTeleport request denied"
        );
        SoundService.guiCancel(
                player,
                core
        );

        if (requester != null
                && requester.isOnline()) {
            sendBoth(
                    requester,
                    playerName(player)
                            + " &cdenied your teleport request"
            );
            SoundService.guiCancel(
                    requester,
                    core
            );
        }
    }

    private void handleCancel(Player player) {
        TpaRequest request =
                tpaService.removeOutgoing(
                        player.getUniqueId()
                );

        if (request == null) {
            error(
                    player,
                    "&cYou have no outgoing teleport request"
            );
            return;
        }

        Player target =
                tpaService.target(request);

        sendBoth(
                player,
                "&cTeleport request cancelled"
        );
        SoundService.guiCancel(
                player,
                core
        );

        if (target != null
                && target.isOnline()) {
            sendBoth(
                    target,
                    playerName(player)
                            + " &ccancelled the teleport request"
            );
            SoundService.guiCancel(
                    target,
                    core
            );
        }
    }

    private void handleAuto(Player player) {
        boolean enabled =
                tpaService.toggleAutoAccept(
                        player.getUniqueId()
                );

        sendBoth(
                player,
                enabled
                        ? BODY
                        + "TPA auto accept "
                        + "&aenabled"
                        : BODY
                        + "TPA auto accept "
                        + "&cdisabled"
        );

        if (enabled) {
            SoundService.featureEnable(
                    player,
                    core
            );
        } else {
            SoundService.featureDisable(
                    player,
                    core
            );
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            String @NotNull [] args
    ) {
        if (!(sender instanceof Player player)
                || !player.hasPermission(
                "mineacletpa.use"
        )) {
            return List.of();
        }

        String name =
                alias.toLowerCase(Locale.ROOT);
        boolean playerArgument =
                name.equals("tpa")
                        || name.equals("tpask")
                        || name.equals("tpahere")
                        || name.equals("tphere")
                        || name.equals("tpah");

        return playerArgument
                && args.length == 1
                ? PlayerTabComplete.onlinePlayers(
                player,
                args[0]
        )
                : List.of();
    }

    private String playerName(Player player) {
        return SECONDARY
                + DisplayNames.displayName(player);
    }

    private void error(
            Player player,
            String message
    ) {
        sendBoth(player, message);
        SoundService.guiError(
                player,
                core
        );
    }

    private void sendBoth(
            Player player,
            String message
    ) {
        Component value = component(message);
        player.sendMessage(value);
        player.sendActionBar(value);
    }

    private Component component(String message) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(message)
                );
    }
}
