package net.mineacle.core.doublejump.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SpeedCommand
        implements CommandExecutor, TabCompleter {

    private static final int MIN_SPEED = -20;
    private static final int MAX_SPEED = 20;

    private static final float DEFAULT_WALK_SPEED = 0.20F;
    private static final float DEFAULT_FLY_SPEED = 0.10F;

    private static final float MIN_WALK_SPEED = 0.02F;
    private static final float MIN_FLY_SPEED = 0.01F;

    private static final List<String> SPEED_OPTIONS =
            createSpeedOptions();

    private final Core core;

    public SpeedCommand(Core core) {
        this.core = core;
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

        if (lacksUsePermission(player)) {
            error(player, "&cYou do not have permission");
            return true;
        }

        if (args.length < 1 || args.length > 2) {
            error(
                    player,
                    "&cUsage: /speed <-20--1|1-20> [player]"
            );
            return true;
        }

        Integer input = parseSpeed(args[0]);

        if (input == null) {
            error(
                    player,
                    "&cSpeed must be -20 through -1 or 1 through 20"
            );
            return true;
        }

        Player target = player;

        if (args.length == 2) {
            Player resolved = DisplayNames.resolveOnline(args[1]);

            if (resolved == null) {
                error(player, "&cPlayer not found");
                return true;
            }

            if (!resolved.getUniqueId()
                    .equals(player.getUniqueId())
                    && lacksOthersPermission(player)) {
                error(player, "&cYou do not have permission");
                return true;
            }

            target = resolved;
        }

        SpeedMode mode = target.isFlying()
                ? SpeedMode.FLY
                : SpeedMode.WALK;
        float appliedSpeed = apply(target, mode, input);

        if (target.getUniqueId().equals(player.getUniqueId())) {
            success(
                    player,
                    "&#bbbbbb"
                            + mode.display()
                            + " speed set to &#ff88ff"
                            + input
                            + " &#bbbbbb("
                            + decimal(appliedSpeed)
                            + ")"
            );
        } else {
            String targetName = DisplayNames.displayName(target);

            success(
                    player,
                    "&#bbbbbbSet &#bbbbbb"
                            + targetName
                            + "&#bbbbbb's "
                            + mode.display()
                            .toLowerCase(Locale.ROOT)
                            + " speed to &#ff88ff"
                            + input
                            + " &#bbbbbb("
                            + decimal(appliedSpeed)
                            + ")"
            );
            success(
                    target,
                    "&#bbbbbb"
                            + mode.display()
                            + " speed set to &#ff88ff"
                            + input
                            + " &#bbbbbb("
                            + decimal(appliedSpeed)
                            + ")"
            );
        }

        return true;
    }

    private float apply(
            Player player,
            SpeedMode mode,
            int input
    ) {
        if (input == 1) {
            player.setWalkSpeed(DEFAULT_WALK_SPEED);
            player.setFlySpeed(DEFAULT_FLY_SPEED);
            return mode.defaultSpeed();
        }

        float speed = positiveBukkitSpeed(mode, input);

        if (mode == SpeedMode.FLY) {
            player.setFlySpeed(speed);
        } else {
            player.setWalkSpeed(speed);
        }

        return speed;
    }

    private float positiveBukkitSpeed(
            SpeedMode mode,
            int input
    ) {
        float speed = input < 0
                ? slowerSpeed(mode, Math.abs(input))
                : fasterSpeed(mode, input);

        return clampPositive(speed, mode.defaultSpeed());
    }

    private float slowerSpeed(
            SpeedMode mode,
            int amount
    ) {
        int clamped = Math.min(20, Math.max(1, amount));

        if (clamped >= 20) {
            return mode.minimumSpeed();
        }

        float progress = clamped / 20.0F;

        return mode.defaultSpeed()
                - ((mode.defaultSpeed()
                - mode.minimumSpeed()) * progress);
    }

    private float fasterSpeed(
            SpeedMode mode,
            int amount
    ) {
        int clamped = Math.min(20, Math.max(1, amount));

        if (clamped <= 1) {
            return mode.defaultSpeed();
        }

        float progress = (clamped - 1) / 19.0F;

        return mode.defaultSpeed()
                + ((1.0F - mode.defaultSpeed()) * progress);
    }

    private float clampPositive(
            float speed,
            float fallback
    ) {
        if (!Float.isFinite(speed)) {
            return fallback;
        }

        return Math.max(0.001F, Math.min(1.0F, speed));
    }

    private Integer parseSpeed(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        try {
            int value = Integer.parseInt(input);

            if (value == 0
                    || value < MIN_SPEED
                    || value > MAX_SPEED) {
                return null;
            }

            return value;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean lacksUsePermission(Player player) {
        return !player.hasPermission("mineaclespeed.use")
                && !player.hasPermission("mineaclespeed.admin")
                && !player.hasPermission(
                "mineaclespeed.developer"
        )
                && !player.hasPermission("mineaclefly.admin");
    }

    private boolean lacksOthersPermission(Player player) {
        return !player.hasPermission("mineaclespeed.others")
                && !player.hasPermission("mineaclespeed.admin")
                && !player.hasPermission(
                "mineaclespeed.developer"
        )
                && !player.hasPermission("mineaclefly.admin");
    }

    private String decimal(float value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private void success(Player player, String message) {
        send(player, message);
        SoundService.featureEnable(player, core);
    }

    private void error(Player player, String message) {
        send(player, message);
        SoundService.guiError(player, core);
    }

    private void send(Player player, String message) {
        Component component = LegacyComponentSerializer
                .legacySection()
                .deserialize(TextColor.color(message));

        player.sendMessage(component);
        player.sendActionBar(component);
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!(sender instanceof Player player)
                || lacksUsePermission(player)) {
            return List.of();
        }

        if (args.length == 1) {
            return PlayerTabComplete.options(
                    args[0],
                    SPEED_OPTIONS
            );
        }

        if (args.length == 2
                && !lacksOthersPermission(player)) {
            return PlayerTabComplete.onlinePlayers(
                    player,
                    args[1],
                    true
            );
        }

        return List.of();
    }

    private static List<String> createSpeedOptions() {
        List<String> options = new ArrayList<>();

        for (int speed = MIN_SPEED; speed <= MAX_SPEED; speed++) {
            if (speed != 0) {
                options.add(String.valueOf(speed));
            }
        }

        return List.copyOf(options);
    }

    private enum SpeedMode {
        WALK("Walk", DEFAULT_WALK_SPEED, MIN_WALK_SPEED),
        FLY("Fly", DEFAULT_FLY_SPEED, MIN_FLY_SPEED);

        private final String display;
        private final float defaultSpeed;
        private final float minimumSpeed;

        SpeedMode(
                String display,
                float defaultSpeed,
                float minimumSpeed
        ) {
            this.display = display;
            this.defaultSpeed = defaultSpeed;
            this.minimumSpeed = minimumSpeed;
        }

        private String display() {
            return display;
        }

        private float defaultSpeed() {
            return defaultSpeed;
        }

        private float minimumSpeed() {
            return minimumSpeed;
        }
    }
}
