package net.mineacle.core.tpa.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.player.VanishRegistry;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.common.teleport.TeleportService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

public final class TpCommand
        implements CommandExecutor, TabCompleter {

    public static final String PERMISSION =
            "mineacleadmin.tp";

    private static final String SECONDARY =
            "&#B078FF";
    private static final String BODY =
            "&#bbbbbb";

    private final Core core;

    public TpCommand(Core core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args
    ) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(
                    core.getMessage("general.players-only")
            );
            return true;
        }

        if (!viewer.hasPermission(PERMISSION)) {
            fail(viewer, "&cYou do not have permission");
            return true;
        }

        if (args.length < 1 || args.length > 2) {
            fail(viewer, "&cUsage: /tp <player> [target]");
            return true;
        }

        if (args.length == 1) {
            Player target = resolveForStaff(
                    viewer,
                    args[0]
            );

            if (target == null) {
                fail(viewer, "&cThat player is not online");
                return true;
            }

            core.teleports().forceLocation(
                    viewer,
                    DisplayNames.displayName(target),
                    target.getLocation()
            );
            return true;
        }

        Player moving = resolveForStaff(
                viewer,
                args[0]
        );
        Player target = resolveForStaff(
                viewer,
                args[1]
        );

        if (moving == null || target == null) {
            fail(viewer, "&cThat player is not online");
            return true;
        }

        boolean viewerIsMoving =
                moving.getUniqueId()
                        .equals(viewer.getUniqueId());

        core.teleports().forceLocation(
                moving,
                DisplayNames.displayName(target),
                target.getLocation(),
                viewerIsMoving
                        ? null
                        : () -> {
                            viewer.sendMessage(
                                    TextColor.color(
                                            BODY
                                                    + "Teleported "
                                                    + SECONDARY
                                                    + DisplayNames.displayName(moving)
                                                    + BODY
                                                    + " to "
                                                    + SECONDARY
                                                    + DisplayNames.displayName(target)
                                    )
                            );
                            SoundService.teleportComplete(
                                    viewer,
                                    core
                            );
                        },
                viewerIsMoving
                        ? null
                        : reason -> fail(
                                viewer,
                                reason
                                        == TeleportService.FailureReason
                                        .DESTINATION_UNAVAILABLE
                                        ? "&cTeleport failed — destination unavailable"
                                        : "&cTeleport failed"
                        )
        );
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String @NotNull [] args
    ) {
        if (!(sender instanceof Player viewer)
                || !viewer.hasPermission(PERMISSION)) {
            return List.of();
        }

        if (args.length == 1) {
            return staffCompletions(viewer, args[0]);
        }

        if (args.length == 2) {
            return staffCompletions(viewer, args[1]);
        }

        return List.of();
    }

    static Player resolveForStaff(
            Player viewer,
            String input
    ) {
        if (viewer == null
                || input == null
                || input.isBlank()) {
            return null;
        }

        String normalized = normalize(input);
        Player match = null;

        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean visible = viewer.canSee(online)
                    || (viewer.hasPermission(
                    "mineacleadmin.inspect.hidden"
            ) && VanishRegistry.isVanished(
                    online.getUniqueId()
            ));

            if (!visible
                    || !normalize(
                    DisplayNames.commandDisplayName(online)
            ).equals(normalized)) {
                continue;
            }

            if (match != null
                    && !match.getUniqueId()
                    .equals(online.getUniqueId())) {
                return null;
            }

            match = online;
        }

        return match;
    }

    static List<String> staffCompletions(
            Player viewer,
            String input
    ) {
        /*
         * Public completion remains visibility-aware. Vanished staff are only
         * suggested to viewers whom VanishService has explicitly allowed to
         * see through Player#showPlayer.
         */
        return PlayerTabComplete.onlinePlayers(
                viewer,
                input,
                true
        );
    }

    private static String normalize(String input) {
        return TextColor.strip(input)
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private void fail(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        SoundService.guiError(player, core);
    }
}
