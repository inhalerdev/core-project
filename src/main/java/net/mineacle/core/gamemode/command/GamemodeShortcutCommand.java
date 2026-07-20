package net.mineacle.core.gamemode.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class GamemodeShortcutCommand
        implements CommandExecutor, TabCompleter {

    private static final String PERMISSION =
            "mineacle.gamemode";

    private final Core core;
    private final String commandName;
    private final GameMode gameMode;

    public GamemodeShortcutCommand(
            Core core,
            String commandName,
            GameMode gameMode
    ) {
        this.core = core;
        this.commandName = commandName;
        this.gameMode = gameMode;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission(PERMISSION)) {
            error(
                    sender,
                    core.getMessage("general.no-permission")
            );
            return true;
        }

        if (args.length > 1) {
            error(
                    sender,
                    "&cUsage: /"
                            + commandName
                            + " [player]"
            );
            return true;
        }

        Player target;

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(
                        core.getMessage("general.players-only")
                );
                return true;
            }

            target = player;
        } else {
            target = resolveVisibleTarget(sender, args[0]);

            if (target == null) {
                error(sender, "&cThat player is not online");
                return true;
            }
        }

        apply(sender, target);
        return true;
    }

    private void apply(
            CommandSender sender,
            Player target
    ) {
        String mode = displayMode(gameMode);
        boolean self = sender instanceof Player player
                && player.getUniqueId().equals(
                target.getUniqueId()
        );

        if (target.getGameMode() == gameMode) {
            if (self) {
                send(
                        target,
                        configured(
                                "gamemode.already-self",
                                "&#bbbbbbYour gamemode is already "
                                        + "&#ff88ff%mode%"
                        ).replace("%mode%", mode)
                );
            } else {
                sender.sendMessage(TextColor.color(
                        configured(
                                "gamemode.already-other",
                                "&#bbbbbb"
                                        + "%player%"
                                        + "&#bbbbbb is already in "
                                        + "&#ff88ff%mode%"
                        )
                                .replace(
                                        "%player%",
                                        DisplayNames.displayName(
                                                target
                                        )
                                )
                                .replace("%mode%", mode)
                ));
            }

            return;
        }

        target.setGameMode(gameMode);

        if (self) {
            String message = configured(
                    "gamemode.message",
                    "&#bbbbbbGamemode set to "
                            + "&#ff88ff%mode%"
            ).replace("%mode%", mode);

            send(target, message);
            SoundService.guiConfirm(target, core);
            return;
        }

        String targetName = DisplayNames.displayName(target);
        String senderMessage = configured(
                "gamemode.other",
                "&#bbbbbbSet "
                        + "%player%"
                        + "&#bbbbbb's gamemode to "
                        + "&#ff88ff%mode%"
        )
                .replace("%player%", targetName)
                .replace("%mode%", mode);
        String targetMessage = configured(
                "gamemode.target",
                "&#bbbbbbYour gamemode was set to "
                        + "&#ff88ff%mode%"
        ).replace("%mode%", mode);

        sender.sendMessage(TextColor.color(senderMessage));
        send(target, targetMessage);

        if (sender instanceof Player player) {
            player.sendActionBar(component(senderMessage));
            SoundService.guiConfirm(player, core);
        }

        SoundService.guiConfirm(target, core);
    }

    private Player resolveVisibleTarget(
            CommandSender sender,
            String input
    ) {
        Player target = DisplayNames.resolveOnline(input);

        if (target == null) {
            return null;
        }

        if (sender instanceof Player viewer
                && !viewer.canSee(target)) {
            return null;
        }

        return target;
    }

    private String configured(
            String path,
            String fallback
    ) {
        return core.getConfig().getString(path, fallback);
    }

    private String displayMode(GameMode mode) {
        return switch (mode) {
            case CREATIVE -> "Creative";
            case SURVIVAL -> "Survival";
            case SPECTATOR -> "Spectator";
            case ADVENTURE -> "Adventure";
        };
    }

    private void error(
            CommandSender sender,
            String message
    ) {
        sender.sendMessage(TextColor.color(message));

        if (sender instanceof Player player) {
            SoundService.guiError(player, core);
        }
    }

    private void send(
            Player player,
            String message
    ) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(component(message));
    }

    private Component component(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(message));
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!sender.hasPermission(PERMISSION)
                || args.length != 1) {
            return List.of();
        }

        if (sender instanceof Player player) {
            return PlayerTabComplete.onlinePlayers(
                    player,
                    args[0],
                    true
            );
        }

        String input = args[0] == null
                ? ""
                : args[0].toLowerCase(
                        java.util.Locale.ROOT
                );
        List<String> names = new ArrayList<>();

        for (Player online : Bukkit.getOnlinePlayers()) {
            String displayName =
                    DisplayNames.displayName(online);

            if (displayName.toLowerCase(
                    java.util.Locale.ROOT
            ).startsWith(input)) {
                names.add(displayName);
            }
        }

        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }
}
