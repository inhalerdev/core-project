package net.mineacle.core.economy.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class EcoCommand
        implements CommandExecutor, TabCompleter {

    private static final List<String> ACTIONS = List.of(
            "give",
            "take",
            "set",
            "reset"
    );

    private static final List<String> POSITIVE_AMOUNTS = List.of(
            "0.01",
            "1",
            "100",
            "1k",
            "10k",
            "100k",
            "1M",
            "1B"
    );

    private static final List<String> SET_AMOUNTS = List.of(
            "0",
            "0.01",
            "1",
            "100",
            "1k",
            "10k",
            "100k",
            "1M",
            "1B"
    );

    private final Core core;
    private final EconomyService economyService;

    public EcoCommand(
            Core core,
            EconomyService economyService
    ) {
        this.core = core;
        this.economyService = economyService;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission("mineacleeconomy.admin")) {
            error(
                    sender,
                    core.getMessage("general.no-permission")
            );
            return true;
        }

        if (args.length < 2 || args.length > 3) {
            usage(sender);
            return true;
        }

        EconomyService.AdjustmentAction action =
                parseAction(args[0]);

        if (action == null) {
            usage(sender);
            return true;
        }

        if (action == EconomyService.AdjustmentAction.RESET
                && args.length != 2) {
            usage(sender);
            return true;
        }

        if (action != EconomyService.AdjustmentAction.RESET
                && args.length != 3) {
            usage(sender);
            return true;
        }

        Collection<OfflinePlayer> targets =
                resolveTargets(args[1]);

        if (targets.isEmpty()) {
            error(
                    sender,
                    core.getMessage("economy.player-not-found")
            );
            return true;
        }

        long cents = 0L;

        if (action == EconomyService.AdjustmentAction.SET) {
            cents = economyService
                    .parseNonNegativeAmountToCents(args[2]);
        } else if (action != EconomyService.AdjustmentAction.RESET) {
            cents = economyService.parseAmountToCents(args[2]);
        }

        if (action != EconomyService.AdjustmentAction.RESET
                && cents < 0L) {
            error(
                    sender,
                    core.getMessage("economy.invalid-amount")
            );
            return true;
        }

        String noticeSender = sender instanceof Player player
                ? DisplayNames.displayName(player)
                : "Mineacle";

        EconomyService.BulkResult result =
                economyService.applyBulk(
                        targets,
                        action,
                        cents,
                        noticeSender
                );

        notifyOnlineRecipients(
                targets,
                result.changedPlayerIds(),
                action,
                cents
        );

        String targetLabel = targetLabel(args[1], targets);
        String actionName = action.name()
                .toLowerCase(Locale.ROOT);

        if (action == EconomyService.AdjustmentAction.RESET) {
            sender.sendMessage(TextColor.color(
                    "&#bbbbbbEconomy reset applied to "
                            + "&#ff88ff"
                            + result.changed()
                            + " &#bbbbbbtarget(s)"
            ));
        } else {
            sender.sendMessage(TextColor.color(
                    "&#bbbbbbEconomy "
                            + actionName
                            + " applied to &#bbbbbb"
                            + targetLabel
                            + " &#bbbbbb("
                            + "&#ff88ff"
                            + result.changed()
                            + " &#bbbbbbchanged"
                            + (result.skipped() > 0
                            ? ", &c"
                            + result.skipped()
                            + " skipped"
                            : "")
                            + "&#bbbbbb): &a"
                            + economyService.format(cents)
            ));
        }

        if (!result.persisted()) {
            sender.sendMessage(TextColor.color(
                    "&cEconomy changes are active in memory "
                            + "and queued for disk retry"
            ));
        }

        confirmSound(
                sender,
                action == EconomyService.AdjustmentAction.GIVE
                        ? result.changedPlayerIds()
                        : Set.of()
        );
        return true;
    }

    private void notifyOnlineRecipients(
            Collection<OfflinePlayer> targets,
            Set<UUID> changedPlayerIds,
            EconomyService.AdjustmentAction action,
            long cents
    ) {
        if (action != EconomyService.AdjustmentAction.GIVE) {
            return;
        }

        for (OfflinePlayer target : targets) {
            if (!changedPlayerIds.contains(target.getUniqueId())) {
                continue;
            }

            Player online = target.getPlayer();

            if (online == null || !online.isOnline()) {
                continue;
            }

            economyService.sendOnlinePaidMessage(online, cents);
            SoundService.economyReceive(online, core);
        }
    }

    private Collection<OfflinePlayer> resolveTargets(
            String targetInput
    ) {
        Map<UUID, OfflinePlayer> targets =
                new LinkedHashMap<>();

        if (targetInput.equals("*")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                targets.put(player.getUniqueId(), player);
            }

            return List.copyOf(targets.values());
        }

        if (targetInput.equals("**")) {
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                if (player.getName() != null
                        || player.hasPlayedBefore()) {
                    targets.put(player.getUniqueId(), player);
                }
            }

            for (UUID playerId : economyService.accountIds()) {
                targets.putIfAbsent(
                        playerId,
                        Bukkit.getOfflinePlayer(playerId)
                );
            }

            return List.copyOf(targets.values());
        }

        OfflinePlayer target =
                DisplayNames.resolveOffline(targetInput);

        if (target != null
                && (target.getName() != null
                || target.hasPlayedBefore()
                || economyService.hasAccount(
                target.getUniqueId()
        ))) {
            targets.put(target.getUniqueId(), target);
        }

        return List.copyOf(targets.values());
    }

    private String targetLabel(
            String rawTarget,
            Collection<OfflinePlayer> targets
    ) {
        if (rawTarget.equals("*")) {
            return "online players";
        }

        if (rawTarget.equals("**")) {
            return "all known players";
        }

        return targets.stream()
                .findFirst()
                .map(DisplayNames::displayName)
                .orElse(rawTarget);
    }

    private EconomyService.AdjustmentAction parseAction(
            String raw
    ) {
        if (raw == null) {
            return null;
        }

        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "give" ->
                    EconomyService.AdjustmentAction.GIVE;
            case "take" ->
                    EconomyService.AdjustmentAction.TAKE;
            case "set" ->
                    EconomyService.AdjustmentAction.SET;
            case "reset" ->
                    EconomyService.AdjustmentAction.RESET;
            default -> null;
        };
    }

    private void usage(CommandSender sender) {
        error(
                sender,
                "&cUsage: /eco <give|take|set|reset> "
                        + "<player|*|**> [amount]"
        );
    }

    private void confirmSound(
            CommandSender sender,
            Set<UUID> alreadyNotified
    ) {
        if (!(sender instanceof Player player)
                || alreadyNotified.contains(
                player.getUniqueId()
        )) {
            return;
        }

        SoundService.guiConfirm(player, core);
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

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!sender.hasPermission("mineacleeconomy.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            return PlayerTabComplete.options(
                    args[0],
                    ACTIONS
            );
        }

        if (args.length == 2) {
            List<String> options = new ArrayList<>(
                    List.of("*", "**")
            );

            if (sender instanceof Player player) {
                options.addAll(
                        PlayerTabComplete.onlinePlayers(
                                player,
                                args[1],
                                true
                        )
                );
            }

            return PlayerTabComplete.options(
                    args[1],
                    options
            );
        }

        if (args.length == 3
                && !args[0].equalsIgnoreCase("reset")) {
            return PlayerTabComplete.options(
                    args[2],
                    args[0].equalsIgnoreCase("set")
                            ? SET_AMOUNTS
                            : POSITIVE_AMOUNTS
            );
        }

        return List.of();
    }
}
