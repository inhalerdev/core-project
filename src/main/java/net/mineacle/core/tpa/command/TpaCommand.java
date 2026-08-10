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
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

public final class TpaCommand
        implements CommandExecutor, TabCompleter {

    private static final String PRIMARY = "&#8436FE";
    private static final String SECONDARY = "&#B078FF";
    private static final String ACCENT = "&#D0AFFF";
    private static final String NEUTRAL = "&#bbbbbb";

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
            sender.sendMessage("Players only");
            return true;
        }

        if (!player.hasPermission(
                "mineacletpa.use"
        )) {
            sendBoth(
                    player,
                    "&cYou do not have permission"
            );
            SoundService.guiError(
                    player,
                    core
            );
            return true;
        }

        String commandName =
                label.toLowerCase(
                        Locale.ROOT
                );

        switch (commandName) {
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
            sendBoth(
                    requester,
                    type == TpaRequestType.TO_TARGET
                            ? "&cUsage: /tpa <player>"
                            : "&cUsage: /tpahere <player>"
            );
            SoundService.guiError(
                    requester,
                    core
            );
            return;
        }

        Player target =
                resolveTarget(args[0]);

        if (target == null) {
            sendBoth(
                    requester,
                    "&cThat player is not online"
            );
            SoundService.guiError(
                    requester,
                    core
            );
            return;
        }

        if (target.getUniqueId().equals(
                requester.getUniqueId()
        )) {
            sendBoth(
                    requester,
                    "&cYou cannot send a teleport request to yourself"
            );
            SoundService.guiError(
                    requester,
                    core
            );
            return;
        }

        if (tpaService.isAutoAccepting(
                target.getUniqueId()
        ) && type == TpaRequestType.TO_TARGET) {
            sendBoth(
                    requester,
                    NEUTRAL
                            + "Teleport request auto accepted by "
                            + playerName(target)
            );

            beginPlayerTeleport(
                    requester,
                    target
            );
            return;
        }

        if (!tpaService.createRequest(
                requester,
                target,
                type
        )) {
            sendBoth(
                    requester,
                    "&cCould not send teleport request"
            );
            SoundService.guiError(
                    requester,
                    core
            );
            return;
        }

        sendBoth(
                requester,
                NEUTRAL
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

        scheduleExpiration(
                requester,
                target
        );
    }

    private Player resolveTarget(
            String input
    ) {
        Player target =
                DisplayNames.resolveOnline(
                        input
                );

        if (target != null) {
            return target;
        }

        return Bukkit.getPlayerExact(input);
    }

    private void sendRequestMessage(
            Player requester,
            Player target,
            TpaRequestType type
    ) {
        String mainLine =
                playerName(requester)
                        + NEUTRAL
                        + (
                        type == TpaRequestType.TO_TARGET
                                ? " wants to teleport to you"
                                : " wants you to teleport to them"
                );

        Component main =
                legacy(mainLine);
        target.sendActionBar(main);
        target.sendMessage(main);

        Component accept =
                legacy(
                        PRIMARY + "[Accept]"
                ).clickEvent(
                        ClickEvent.runCommand(
                                "/tpaccept"
                        )
                );

        Component deny =
                legacy(
                        "&c[Deny]"
                ).clickEvent(
                        ClickEvent.runCommand(
                                "/tpadeny"
                        )
                );

        Component buttons =
                legacy(
                        ACCENT + "Respond "
                )
                        .append(accept)
                        .append(
                                Component.space()
                        )
                        .append(deny);

        target.sendMessage(buttons);
    }

    private void scheduleExpiration(
            Player requester,
            Player target
    ) {
        core.getServer()
                .getScheduler()
                .runTaskLater(
                        core,
                        () -> {
                            TpaRequest request =
                                    tpaService.getRequest(
                                            target.getUniqueId()
                                    );

                            if (request == null
                                    || !request.requesterId()
                                    .equals(
                                            requester.getUniqueId()
                                    )) {
                                return;
                            }

                            tpaService.removeRequest(
                                    target.getUniqueId()
                            );

                            if (requester.isOnline()) {
                                sendBoth(
                                        requester,
                                        "&cTeleport request to "
                                                + playerName(target)
                                                + " &cexpired"
                                );
                                SoundService.guiError(
                                        requester,
                                        core
                                );
                            }

                            if (target.isOnline()) {
                                sendBoth(
                                        target,
                                        "&cTeleport request expired"
                                );
                                SoundService.guiError(
                                        target,
                                        core
                                );
                            }
                        },
                        tpaService.timeoutSeconds()
                                * 20L
                );
    }

    private void handleAccept(
            Player player
    ) {
        TpaRequest request =
                tpaService.getRequest(
                        player.getUniqueId()
                );

        if (request == null) {
            sendBoth(
                    player,
                    "&cYou have no pending teleport requests"
            );
            SoundService.guiError(
                    player,
                    core
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

    private void handleDeny(
            Player player
    ) {
        TpaRequest request =
                tpaService.removeRequest(
                        player.getUniqueId()
                );

        if (request == null) {
            sendBoth(
                    player,
                    "&cYou have no pending teleport requests"
            );
            SoundService.guiError(
                    player,
                    core
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

    private void handleCancel(
            Player player
    ) {
        TpaRequest request =
                tpaService.removeOutgoing(
                        player.getUniqueId()
                );

        if (request == null) {
            sendBoth(
                    player,
                    "&cYou have no outgoing teleport request"
            );
            SoundService.guiError(
                    player,
                    core
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

    private void handleAuto(
            Player player
    ) {
        boolean enabled =
                tpaService.toggleAutoAccept(
                        player.getUniqueId()
                );

        sendBoth(
                player,
                enabled
                        ? NEUTRAL
                        + "TPA auto accept "
                        + PRIMARY
                        + "enabled"
                        : NEUTRAL
                        + "TPA auto accept "
                        + ACCENT
                        + "disabled"
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

    private void beginPlayerTeleport(
            Player traveler,
            Player destination
    ) {
        String destinationName =
                DisplayNames.displayName(
                        destination
                );

        teleportService.beginTpa(
                traveler,
                destinationName,
                () -> {
                    if (!destination.isOnline()) {
                        sendBoth(
                                traveler,
                                "&cThat player is no longer online"
                        );
                        return false;
                    }

                    boolean teleported =
                            traveler.teleport(
                                    destination,
                                    PlayerTeleportEvent
                                            .TeleportCause
                                            .COMMAND
                            );

                    if (teleported) {
                        sendBoth(
                                traveler,
                                NEUTRAL
                                        + "Teleported to "
                                        + playerName(
                                        destination
                                )
                        );
                    }

                    return teleported;
                }
        );
    }

    @Override
    public @NotNull List<String> onTabComplete(
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

        String commandName =
                alias.toLowerCase(
                        Locale.ROOT
                );

        boolean playerArgument =
                commandName.equals("tpa")
                        || commandName.equals("tpask")
                        || commandName.equals("tpahere")
                        || commandName.equals("tphere")
                        || commandName.equals("tpah");

        if (playerArgument
                && args.length == 1) {
            return PlayerTabComplete.onlinePlayers(
                    player,
                    args[0]
            );
        }

        return List.of();
    }

    private String playerName(
            Player player
    ) {
        return SECONDARY
                + DisplayNames.displayName(
                player
        );
    }

    private void sendBoth(
            Player player,
            String message
    ) {
        Component component =
                legacy(message);
        player.sendMessage(component);
        player.sendActionBar(component);
    }

    private Component legacy(
            String message
    ) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(
                                message
                        )
                );
    }
}
