package net.mineacle.core.doublejump.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.doublejump.listener.DoubleJumpListener;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class FlyCommand
        implements CommandExecutor, TabCompleter {

    private static final float DEFAULT_FLY_SPEED = 0.10F;

    private static final List<String> SPEED_OPTIONS = List.of(
            "speed",
            "slow",
            "normal",
            "fast",
            "max",
            "reset",
            "1",
            "2",
            "3",
            "4",
            "5",
            "6",
            "7",
            "8",
            "9",
            "10"
    );

    private static final List<String> SPEED_VALUE_OPTIONS =
            SPEED_OPTIONS.subList(1, SPEED_OPTIONS.size());

    private final Core core;
    private final DoubleJumpListener listener;

    public FlyCommand(
            Core core,
            DoubleJumpListener listener
    ) {
        this.core = core;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    core.getMessage("general.players-only")
            );
            return true;
        }

        if (args.length > 0 && isSpeedInput(args[0])) {
            handleSpeed(player, args);
            return true;
        }

        if (args.length != 0) {
            error(player, "&cUsage: /fly");
            return true;
        }

        if (!core.getConfig().getBoolean(
                "fly.enabled",
                true
        )) {
            listener.dropOutOfFly(player);
            error(player, "&cFlight is disabled");
            return true;
        }

        String configuredPermission = core.getConfig().getString(
                "fly.permission",
                "mineacle.plus"
        );
        String permission = configuredPermission == null
                || configuredPermission.isBlank()
                ? "mineacle.plus"
                : configuredPermission;

        if (!player.hasPermission(permission)
                && !player.hasPermission("mineaclefly.admin")) {
            listener.dropOutOfFly(player);
            sendUpgrade(player);
            SoundService.mineaclePlus(player, core);
            return true;
        }

        if (!listener.isFlyWorld(
                player.getWorld().getName()
        )) {
            listener.dropOutOfFly(player);
            error(
                    player,
                    core.getConfig().getString(
                            "fly.messages.blocked-world",
                            "&cFlight is only available in spawn"
                    )
            );
            return true;
        }

        boolean enabled = listener.toggleFly(player);

        String message = enabled
                ? core.getConfig().getString(
                "fly.messages.enabled",
                "&#bbbbbbFlight &aEnabled"
        )
                : core.getConfig().getString(
                "fly.messages.disabled",
                "&#bbbbbbFlight &cDisabled"
        );

        send(player, message);

        if (enabled) {
            SoundService.featureEnable(player, core);
        } else {
            SoundService.featureDisable(player, core);
        }

        return true;
    }

    private void handleSpeed(
            Player player,
            String[] args
    ) {
        if (!hasSpeedPermission(player)) {
            error(
                    player,
                    speedMessage(
                            "no-permission",
                            "&cYou do not have permission"
                    )
            );
            return;
        }

        int speedArgIndex = isSpeedKeyword(args[0])
                ? 1
                : 0;
        int maximumArguments = speedArgIndex + 2;

        if (args.length <= speedArgIndex
                || args.length > maximumArguments) {
            error(
                    player,
                    speedMessage(
                            "usage",
                            "&cUsage: /fly speed "
                                    + "<slow|normal|fast|1-10> [player]"
                    )
            );
            return;
        }

        FlySpeed speed = parseSpeed(args[speedArgIndex]);

        if (speed == null) {
            error(
                    player,
                    speedMessage(
                            "invalid",
                            "&cUse slow, normal, fast, reset, or 1-10"
                    )
            );
            return;
        }

        Player target = player;
        int targetArgIndex = speedArgIndex + 1;

        if (args.length > targetArgIndex) {
            Player resolved = DisplayNames.resolveOnline(
                    args[targetArgIndex]
            );

            if (resolved == null) {
                error(
                        player,
                        speedMessage(
                                "player-not-found",
                                "&cPlayer not found"
                        )
                );
                return;
            }

            if (!resolved.getUniqueId()
                    .equals(player.getUniqueId())
                    && !hasSpeedOthersPermission(player)) {
                error(
                        player,
                        speedMessage(
                                "no-permission",
                                "&cYou do not have permission"
                        )
                );
                return;
            }

            target = resolved;
        }

        target.setFlySpeed(speed.value());

        if (target.getUniqueId().equals(player.getUniqueId())) {
            send(
                    player,
                    speedMessage(
                            "set-self",
                            "&#bbbbbbFly speed set to &#ff88ff%speed%"
                    ).replace("%speed%", speed.label())
            );
        } else {
            String targetName = DisplayNames.displayName(target);

            send(
                    player,
                    speedMessage(
                            "set-other",
                            "&#bbbbbbSet &#bbbbbb%player%"
                                    + "&#bbbbbb's fly speed to "
                                    + "&#ff88ff%speed%"
                    )
                            .replace("%player%", targetName)
                            .replace("%speed%", speed.label())
            );

            send(
                    target,
                    speedMessage(
                            "set-by-other",
                            "&#bbbbbbFly speed set to "
                                    + "&#ff88ff%speed%"
                    ).replace("%speed%", speed.label())
            );
        }

        SoundService.featureEnable(player, core);

        if (!target.getUniqueId().equals(player.getUniqueId())) {
            SoundService.featureEnable(target, core);
        }
    }

    private boolean isSpeedInput(String input) {
        return isSpeedKeyword(input)
                || parseSpeed(input) != null;
    }

    private boolean isSpeedKeyword(String input) {
        if (input == null) {
            return false;
        }

        return switch (input.toLowerCase(Locale.ROOT)) {
            case "speed", "flyspeed", "fspeed" -> true;
            default -> false;
        };
    }

    private FlySpeed parseSpeed(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        return switch (input.toLowerCase(Locale.ROOT)) {
            case "slow" -> new FlySpeed("Slow", 0.03F);
            case "normal", "default", "reset" ->
                    new FlySpeed("Normal", DEFAULT_FLY_SPEED);
            case "fast" -> new FlySpeed("Fast", 0.50F);
            case "max" -> new FlySpeed("Max", 1.00F);
            default -> parseNumberSpeed(input);
        };
    }

    private FlySpeed parseNumberSpeed(String input) {
        try {
            int level = Integer.parseInt(input);

            if (level == 0) {
                return new FlySpeed(
                        "Normal",
                        DEFAULT_FLY_SPEED
                );
            }

            if (level < 1 || level > 10) {
                return null;
            }

            return new FlySpeed(
                    String.valueOf(level),
                    level / 10.0F
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean hasSpeedPermission(Player player) {
        return player.hasPermission("mineaclespeed.use")
                || player.hasPermission("mineaclespeed.admin")
                || player.hasPermission(
                "mineaclespeed.developer"
        )
                || player.hasPermission("mineaclefly.admin");
    }

    private boolean hasSpeedOthersPermission(Player player) {
        return player.hasPermission("mineaclespeed.others")
                || player.hasPermission("mineaclespeed.admin")
                || player.hasPermission(
                "mineaclespeed.developer"
        )
                || player.hasPermission("mineaclefly.admin");
    }

    private String speedMessage(
            String key,
            String fallback
    ) {
        return core.getConfig().getString(
                "fly.messages.speed-" + key,
                fallback
        );
    }

    private void sendUpgrade(Player player) {
        String lineOne = core.getConfig().getString(
                "fly.messages.upgrade-line-1",
                "&#bbbbbbUnlock flight in spawn with &dMineacle+"
        );
        String lineTwo = core.getConfig().getString(
                "fly.messages.upgrade-line-2",
                "&d♦ &#bbbbbbhttps://store.mineacle.net"
        );

        player.sendMessage(Component.empty());
        player.sendMessage(
                legacy(placeholders(player, lineOne))
        );
        player.sendMessage(
                legacy(placeholders(player, lineTwo))
                        .clickEvent(
                                ClickEvent.openUrl(
                                        "https://store.mineacle.net"
                                )
                        )
        );
        player.sendMessage(Component.empty());
        player.sendActionBar(
                legacy(placeholders(player, lineOne))
        );
    }

    private void send(Player player, String message) {
        String formatted = placeholders(player, message);
        Component component = legacy(formatted);

        player.sendMessage(component);
        player.sendActionBar(component);
    }

    private void error(Player player, String message) {
        send(player, message);
        SoundService.guiError(player, core);
    }

    private String placeholders(
            Player player,
            String message
    ) {
        if (message == null || message.isBlank()) {
            return "";
        }

        boolean flightEnabled = listener.isFlyEnabled(player);
        String rawWorld = player.getWorld().getName();
        String displayWorld = displayWorld(rawWorld);

        return message
                .replace("%world%", displayWorld)
                .replace("%world_display%", displayWorld)
                .replace("%display_world%", displayWorld)
                .replace("%world_folder%", rawWorld)
                .replace("%raw_world%", rawWorld)
                .replace(
                        "%player%",
                        DisplayNames.displayName(player)
                )
                .replace(
                        "%status%",
                        flightEnabled ? "Enabled" : "Disabled"
                )
                .replace(
                        "%status_lower%",
                        flightEnabled ? "enabled" : "disabled"
                );
    }

    private String displayWorld(String world) {
        String configured = core.getConfig().getString(
                "fly.world-display-names." + world
        );

        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        return switch (world.toLowerCase(Locale.ROOT)) {
            case "spawn1" -> "Spawn 1";
            case "spawn2" -> "Spawn 2";
            case "spawn3" -> "Spawn 3";
            default -> world;
        };
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }

        if (args.length == 1 && hasSpeedPermission(player)) {
            return PlayerTabComplete.options(
                    args[0],
                    SPEED_OPTIONS
            );
        }

        if (args.length == 2
                && hasSpeedPermission(player)) {
            if (isSpeedKeyword(args[0])) {
                return PlayerTabComplete.options(
                        args[1],
                        SPEED_VALUE_OPTIONS
                );
            }

            if (parseSpeed(args[0]) != null
                    && hasSpeedOthersPermission(player)) {
                return PlayerTabComplete.onlinePlayers(
                        player,
                        args[1],
                        true
                );
            }
        }

        if (args.length == 3
                && hasSpeedOthersPermission(player)
                && isSpeedKeyword(args[0])
                && parseSpeed(args[1]) != null) {
            return PlayerTabComplete.onlinePlayers(
                    player,
                    args[2],
                    true
            );
        }

        return List.of();
    }

    private Component legacy(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(message));
    }

    private record FlySpeed(
            String label,
            float value
    ) {
    }
}
