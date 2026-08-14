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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class BalanceCommand
        implements CommandExecutor, TabCompleter {

    private static final String BODY = "&#bbbbbb";
    private static final String MONEY = "&#11fc7b";

    private final Core core;
    private final EconomyService economyService;
    private final PublicNameIndex publicNameIndex;

    public BalanceCommand(
            Core core,
            EconomyService economyService
    ) {
        this.core = core;
        this.economyService = economyService;
        this.publicNameIndex =
                new PublicNameIndex(
                        economyService
                );
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args
    ) {
        if (!sender.hasPermission(
                "mineacleeconomy.use"
        )) {
            error(
                    sender,
                    core.getMessage(
                            "general.no-permission"
                    )
            );
            return true;
        }

        if (!economyService.enabled()) {
            error(
                    sender,
                    "&cEconomy is currently disabled"
            );
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(
                        core.getMessage(
                                "general.players-only"
                        )
                );
                return true;
            }

            sendBalance(
                    player,
                    null,
                    player.getUniqueId()
            );
            SoundService.economyBalance(
                    player,
                    core
            );
            return true;
        }

        if (args.length != 1) {
            error(
                    sender,
                    "&cUsage: /balance [player]"
            );
            return true;
        }

        Resolution resolution =
                publicNameIndex.resolveExact(
                        args[0]
                );

        if (resolution.ambiguous()) {
            error(
                    sender,
                    "&cMultiple players use that display name"
            );
            return true;
        }

        OfflinePlayer target =
                resolution.player();

        if (target == null) {
            error(
                    sender,
                    core.getMessage(
                            "economy.player-not-found"
                    )
            );
            return true;
        }

        sendBalance(
                sender,
                target,
                target.getUniqueId()
        );

        if (sender instanceof Player player) {
            SoundService.economyBalance(
                    player,
                    core
            );
        }

        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String @NotNull [] args
    ) {
        if (!(sender instanceof Player player)
                || !player.hasPermission(
                "mineacleeconomy.use"
        )
                || args.length != 1) {
            return List.of();
        }

        return PlayerTabComplete.onlinePlayers(
                player,
                args[0],
                true
        );
    }

    private void sendBalance(
            CommandSender sender,
            OfflinePlayer target,
            UUID playerId
    ) {
        String balance =
                economyService.format(
                        economyService.getBalanceCents(
                                playerId
                        )
                );
        String message =
                target == null
                        ? BODY
                        + "Balance: "
                        + MONEY
                        + balance
                        : BODY
                        + DisplayNames.displayName(target)
                        + BODY
                        + "'s Balance: "
                        + MONEY
                        + balance;

        sender.sendMessage(
                TextColor.color(message)
        );
    }

    private void error(
            CommandSender sender,
            String message
    ) {
        sender.sendMessage(
                TextColor.color(message)
        );

        if (sender instanceof Player player) {
            SoundService.guiError(
                    player,
                    core
            );
        }
    }

    private static final class PublicNameIndex {

        private static final long CACHE_TTL_NANOS =
                TimeUnit.SECONDS.toNanos(5L);

        private final EconomyService economyService;
        private Snapshot snapshot = Snapshot.empty();

        private PublicNameIndex(
                EconomyService economyService
        ) {
            this.economyService = economyService;
        }

        private synchronized Resolution resolveExact(
                String rawInput
        ) {
            String normalized = normalize(rawInput);

            if (normalized.isBlank()) {
                return Resolution.none();
            }

            Snapshot current = current();
            List<UUID> matches =
                    current.byPublicName().get(normalized);

            if (matches == null
                    || matches.isEmpty()) {
                return Resolution.none();
            }

            if (matches.size() > 1) {
                return Resolution.ambiguousResult();
            }

            return Resolution.found(
                    Bukkit.getOfflinePlayer(
                            matches.getFirst()
                    )
            );
        }

        private Snapshot current() {
            long now = System.nanoTime();

            if (snapshot.validAt(now)) {
                return snapshot;
            }

            snapshot = rebuild(now);
            return snapshot;
        }

        private Snapshot rebuild(
                long builtAtNanos
        ) {
            Map<String, List<UUID>> mutable =
                    new LinkedHashMap<>();

            for (UUID playerId
                    : economyService.accountIds()) {
                OfflinePlayer player =
                        Bukkit.getOfflinePlayer(playerId);
                String publicName =
                        DisplayNames.commandDisplayName(
                                player
                        );
                String normalized =
                        normalize(publicName);

                if (normalized.isBlank()) {
                    continue;
                }

                mutable.computeIfAbsent(
                        normalized,
                        ignored -> new ArrayList<>()
                ).add(playerId);
            }

            Map<String, List<UUID>> immutable =
                    new LinkedHashMap<>();

            for (Map.Entry<String, List<UUID>> entry
                    : mutable.entrySet()) {
                immutable.put(
                        entry.getKey(),
                        List.copyOf(entry.getValue())
                );
            }

            return new Snapshot(
                    builtAtNanos,
                    Map.copyOf(immutable)
            );
        }

        private static String normalize(
                String input
        ) {
            if (input == null) {
                return "";
            }

            String clean =
                    TextColor.strip(input)
                            .trim();

            if (clean.startsWith(".")) {
                clean = clean.substring(1);
            }

            return clean.toLowerCase(Locale.ROOT);
        }
    }

    private record Resolution(
            OfflinePlayer player,
            boolean ambiguous
    ) {
        private static Resolution none() {
            return new Resolution(
                    null,
                    false
            );
        }

        private static Resolution found(
                OfflinePlayer player
        ) {
            return new Resolution(
                    player,
                    false
            );
        }

        private static Resolution ambiguousResult() {
            return new Resolution(
                    null,
                    true
            );
        }
    }

    private record Snapshot(
            long builtAtNanos,
            Map<String, List<UUID>> byPublicName
    ) {
        private static Snapshot empty() {
            return new Snapshot(
                    Long.MIN_VALUE,
                    Map.of()
            );
        }

        private boolean validAt(
                long now
        ) {
            return builtAtNanos != Long.MIN_VALUE
                    && now - builtAtNanos
                    < PublicNameIndex.CACHE_TTL_NANOS;
        }
    }
}
