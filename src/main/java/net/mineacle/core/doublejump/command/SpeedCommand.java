package net.mineacle.core.doublejump.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("NullableProblems")
public final class SpeedCommand implements CommandExecutor, TabCompleter {

    private static final int MIN_SPEED = -20;
    private static final int MAX_SPEED = 20;

    private static final float DEFAULT_WALK_SPEED = 0.20F;
    private static final float DEFAULT_FLY_SPEED = 0.10F;

    private static final float MIN_WALK_SPEED = 0.01F;
    private static final float MIN_FLY_SPEED = 0.005F;

    private final Core core;

    public SpeedCommand(Core core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (lacksUsePermission(player)) {
            error(player, "&cYou do not have permission");
            return true;
        }

        if (args.length == 0) {
            error(player, "&#bbbbbbUsage: &d/speed <-20--1|1-20> [player]");
            return true;
        }

        Integer input = parseSpeed(args[0]);

        if (input == null) {
            error(player, "&cSpeed must be -20 through -1 or 1 through 20");
            return true;
        }

        Player target = player;

        if (args.length >= 2) {
            Player found = Bukkit.getPlayerExact(args[1]);

            if (found == null) {
                error(player, "&cPlayer not found");
                return true;
            }

            if (!found.getUniqueId().equals(player.getUniqueId()) && lacksOthersPermission(player)) {
                error(player, "&cYou do not have permission");
                return true;
            }

            target = found;
        }

        SpeedMode mode = target.isFlying() ? SpeedMode.FLY : SpeedMode.WALK;
        apply(target, mode, input);

        if (target.getUniqueId().equals(player.getUniqueId())) {
            success(player, "&#bbbbbb" + mode.display() + " speed set to &#ff88ff" + input);
        } else {
            String targetName = TextColor.color(DisplayNames.displayName(target));

            success(player, "&#bbbbbbSet &#ff88ff" + targetName + "&#bbbbbb's " + mode.display().toLowerCase(Locale.ROOT) + " speed to &#ff88ff" + input);
            success(target, "&#bbbbbb" + mode.display() + " speed set to &#ff88ff" + input);
        }

        return true;
    }

    private void apply(Player player, SpeedMode mode, int input) {
        if (input == 1) {
            player.setWalkSpeed(DEFAULT_WALK_SPEED);
            player.setFlySpeed(DEFAULT_FLY_SPEED);
            return;
        }

        if (mode == SpeedMode.FLY) {
            player.setFlySpeed(speedValue(SpeedMode.FLY, input));
            player.setWalkSpeed(DEFAULT_WALK_SPEED);
            return;
        }

        player.setWalkSpeed(speedValue(SpeedMode.WALK, input));
        player.setFlySpeed(DEFAULT_FLY_SPEED);
    }

    private float speedValue(SpeedMode mode, int input) {
        int magnitude = Math.abs(input);

        if (input < 0) {
            return slowSpeedValue(mode, magnitude);
        }

        return fastSpeedValue(mode, magnitude);
    }

    private float slowSpeedValue(SpeedMode mode, int magnitude) {
        float base = mode.defaultSpeed();
        float minimum = mode.minimumSpeed();

        if (magnitude >= 20) {
            return minimum;
        }

        float progress = magnitude / 20.0F;
        float value = base - ((base - minimum) * progress);

        return Math.max(minimum, value);
    }

    private float fastSpeedValue(SpeedMode mode, int magnitude) {
        if (magnitude <= 1) {
            return mode.defaultSpeed();
        }

        float base = mode.defaultSpeed();
        float progress = (magnitude - 1) / 19.0F;
        float value = base + ((1.0F - base) * progress);

        return Math.min(1.0F, value);
    }

    private Integer parseSpeed(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        try {
            int value = Integer.parseInt(input);

            if (value == 0 || value < MIN_SPEED || value > MAX_SPEED) {
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
                && !player.hasPermission("mineaclespeed.developer")
                && !player.hasPermission("mineaclefly.admin")
                && !player.hasPermission("mineaclefly.developer");
    }

    private boolean lacksOthersPermission(Player player) {
        return !player.hasPermission("mineaclespeed.others")
                && !player.hasPermission("mineaclespeed.admin")
                && !player.hasPermission("mineaclespeed.developer")
                && !player.hasPermission("mineaclefly.admin")
                && !player.hasPermission("mineaclefly.developer");
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
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player player) || lacksUsePermission(player)) {
            return completions;
        }

        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);

            for (int speed = MIN_SPEED; speed <= MAX_SPEED; speed++) {
                if (speed == 0) {
                    continue;
                }

                String option = String.valueOf(speed);

                if (option.startsWith(partial)) {
                    completions.add(option);
                }
            }

            return completions;
        }

        if (args.length == 2 && !lacksOthersPermission(player)) {
            String partial = args[1].toLowerCase(Locale.ROOT);

            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    completions.add(target.getName());
                }
            }
        }

        return completions;
    }

    private enum SpeedMode {
        WALK("Walk", DEFAULT_WALK_SPEED, MIN_WALK_SPEED),
        FLY("Fly", DEFAULT_FLY_SPEED, MIN_FLY_SPEED);

        private final String display;
        private final float defaultSpeed;
        private final float minimumSpeed;

        SpeedMode(String display, float defaultSpeed, float minimumSpeed) {
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
