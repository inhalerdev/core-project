package net.mineacle.core.webprofiles.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.webprofiles.storage.WebProfileRepository;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MineacleWebCommand
        implements CommandExecutor, TabCompleter {

    private static final long CONFIRMATION_WINDOW_MILLIS =
            30_000L;

    private final Core core;
    private final WebProfileRepository repository;
    private final Map<String, Long> clearConfirmations =
            new ConcurrentHashMap<>();

    public MineacleWebCommand(
            Core core,
            WebProfileRepository repository
    ) {
        this.core = core;
        this.repository = repository;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission(
                "mineacleweb.admin"
        )) {
            sender.sendMessage(
                    core.getMessage(
                            "general.no-permission"
                    )
            );
            errorSound(sender);
            return true;
        }

        if (args.length == 2
                && args[0].equalsIgnoreCase(
                "fights"
        )
                && args[1].equalsIgnoreCase(
                "status"
        )) {
            showStatus(sender);
            return true;
        }

        if (args.length == 2
                && args[0].equalsIgnoreCase(
                "fights"
        )
                && args[1].equalsIgnoreCase(
                "clear"
        )) {
            beginClear(sender);
            return true;
        }

        if (args.length == 3
                && args[0].equalsIgnoreCase(
                "fights"
        )
                && args[1].equalsIgnoreCase(
                "clear"
        )
                && args[2].equalsIgnoreCase(
                "confirm"
        )) {
            confirmClear(sender);
            return true;
        }

        usage(sender);
        return true;
    }

    private void showStatus(CommandSender sender) {
        sender.sendMessage(
                TextColor.color(
                        "&#bbbbbbChecking website fight history"
                )
        );

        core.getServer().getScheduler()
                .runTaskAsynchronously(
                        core,
                        () -> {
                            try {
                                long rows =
                                        repository
                                                .fightHistoryCount();

                                respond(
                                        sender,
                                        "&#bbbbbbWebsite fight rows: "
                                                + "&#ff88ff"
                                                + rows,
                                        false
                                );
                            } catch (Exception exception) {
                                core.getLogger().warning(
                                        "Could not count website fights: "
                                                + exception.getMessage()
                                );
                                respond(
                                        sender,
                                        "&cCould not read website fight history",
                                        true
                                );
                            }
                        }
                );
    }

    private void beginClear(CommandSender sender) {
        String key = confirmationKey(sender);

        clearConfirmations.put(
                key,
                System.currentTimeMillis()
                        + CONFIRMATION_WINDOW_MILLIS
        );

        sender.sendMessage(
                TextColor.color(
                        "&cThis will clear all Recent Duels "
                                + "and fights from the website"
                )
        );
        sender.sendMessage(
                TextColor.color(
                        "&#bbbbbbThe rows will be archived first"
                )
        );
        sender.sendMessage(
                TextColor.color(
                        "&#bbbbbbRun "
                                + "&#ff88ff/mineacleweb "
                                + "fights clear confirm "
                                + "&#bbbbbbwithin 30 seconds"
                )
        );
        cancelSound(sender);
    }

    private void confirmClear(CommandSender sender) {
        String key = confirmationKey(sender);
        Long expiresAt =
                clearConfirmations.remove(key);

        if (expiresAt == null
                || expiresAt
                < System.currentTimeMillis()) {
            sender.sendMessage(
                    TextColor.color(
                            "&cFight-history confirmation expired"
                    )
            );
            errorSound(sender);
            return;
        }

        sender.sendMessage(
                TextColor.color(
                        "&#bbbbbbArchiving and clearing "
                                + "website fight history"
                )
        );

        core.getServer().getScheduler()
                .runTaskAsynchronously(
                        core,
                        () -> {
                            try {
                                WebProfileRepository
                                        .FightClearResult result =
                                        repository
                                                .clearFightHistoryWithBackup();

                                respond(
                                        sender,
                                        "&#bbbbbbCleared "
                                                + "&#ff88ff"
                                                + result.removedRows()
                                                + " &#bbbbbbfight rows"
                                                + "\n&#bbbbbbArchived "
                                                + "&#ff88ff"
                                                + result.archivedRows()
                                                + " &#bbbbbbrows in "
                                                + "&#ff88ff"
                                                + result.backupTable(),
                                        false
                                );
                            } catch (Exception exception) {
                                core.getLogger().warning(
                                        "Could not clear website fights: "
                                                + exception.getMessage()
                                );
                                respond(
                                        sender,
                                        "&cCould not clear website fight history",
                                        true
                                );
                            }
                        }
                );
    }

    private void respond(
            CommandSender sender,
            String message,
            boolean error
    ) {
        core.getServer().getScheduler()
                .runTask(
                        core,
                        () -> {
                            for (String line
                                    : message.split("\\n")) {
                                sender.sendMessage(
                                        TextColor.color(line)
                                );
                            }

                            if (error) {
                                errorSound(sender);
                            } else {
                                confirmSound(sender);
                            }
                        }
                );
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(
                TextColor.color(
                        "&#bbbbbbUsage: "
                                + "&#ff88ff/mineacleweb "
                                + "fights <status|clear>"
                )
        );
        errorSound(sender);
    }

    private String confirmationKey(
            CommandSender sender
    ) {
        if (sender instanceof Player player) {
            return player.getUniqueId().toString();
        }

        return "console:"
                + sender.getName()
                .toLowerCase(Locale.ROOT);
    }

    private void confirmSound(CommandSender sender) {
        if (sender instanceof Player player) {
            SoundService.guiConfirm(
                    player,
                    core
            );
        }
    }

    private void cancelSound(CommandSender sender) {
        if (sender instanceof Player player) {
            SoundService.guiCancel(
                    player,
                    core
            );
        }
    }

    private void errorSound(CommandSender sender) {
        if (sender instanceof Player player) {
            SoundService.guiError(
                    player,
                    core
            );
        }
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!sender.hasPermission(
                "mineacleweb.admin"
        )) {
            return List.of();
        }

        if (args.length == 1) {
            return filtered(
                    args[0],
                    List.of("fights")
            );
        }

        if (args.length == 2
                && args[0].equalsIgnoreCase(
                "fights"
        )) {
            return filtered(
                    args[1],
                    List.of(
                            "status",
                            "clear"
                    )
            );
        }

        if (args.length == 3
                && args[0].equalsIgnoreCase(
                "fights"
        )
                && args[1].equalsIgnoreCase(
                "clear"
        )
                && confirmationActive(sender)) {
            return filtered(
                    args[2],
                    List.of("confirm")
            );
        }

        return List.of();
    }

    private boolean confirmationActive(
            CommandSender sender
    ) {
        Long expiresAt = clearConfirmations.get(
                confirmationKey(sender)
        );

        return expiresAt != null
                && expiresAt
                >= System.currentTimeMillis();
    }

    private List<String> filtered(
            String partial,
            List<String> options
    ) {
        String normalized = partial == null
                ? ""
                : partial.toLowerCase(
                Locale.ROOT
        );

        return options.stream()
                .filter(option ->
                        option.startsWith(normalized))
                .toList();
    }
}
