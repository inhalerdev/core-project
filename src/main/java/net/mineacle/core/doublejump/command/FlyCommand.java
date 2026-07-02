package net.mineacle.core.doublejump.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
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

public final class FlyCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final DoubleJumpListener listener;

    public FlyCommand(Core core, DoubleJumpListener listener) {
        this.core = core;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (!core.getConfig().getBoolean("fly.enabled", true)) {
            listener.dropOutOfFly(player);
            send(player, "&cFlight is disabled");
            SoundService.guiError(player, core);
            return true;
        }

        String permission = core.getConfig().getString("fly.permission", "mineacle.plus");
        boolean adminBypass = player.hasPermission("mineaclefly.admin");

        if (!player.hasPermission(permission) && !adminBypass) {
            listener.dropOutOfFly(player);
            sendUpgrade(player);
            SoundService.guiError(player, core);
            return true;
        }

        if (!listener.isFlyWorld(player.getWorld().getName())) {
            listener.dropOutOfFly(player);
            send(player, core.getConfig().getString("fly.messages.blocked-world", "&cFlight is only available in spawn"));
            SoundService.guiError(player, core);
            return true;
        }

        boolean enabled = listener.toggleFly(player);

        String message = enabled
                ? core.getConfig().getString("fly.messages.enabled", "&#bbbbbbFlight &aEnabled")
                : core.getConfig().getString("fly.messages.disabled", "&#bbbbbbFlight &cDisabled");

        send(player, message);

        if (enabled) {
            SoundService.featureEnable(player, core);
        } else {
            SoundService.featureDisable(player, core);
        }

        return true;
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

        player.sendMessage(" ");
        player.sendMessage(TextColor.color(placeholders(player, lineOne)));

        Component store = legacy(placeholders(player, lineTwo))
                .clickEvent(ClickEvent.openUrl("https://store.mineacle.net"));

        player.sendMessage(store);
        player.sendMessage(" ");
        player.sendActionBar(actionBar(placeholders(player, lineOne)));
    }

    private void send(Player player, String message) {
        String formatted = placeholders(player, message);

        player.sendMessage(TextColor.color(formatted));
        player.sendActionBar(actionBar(formatted));
    }

    private String placeholders(Player player, String message) {
        if (message == null || message.isBlank()) {
            return "";
        }

        boolean flightAllowed = player.getAllowFlight();
        String rawWorld = player.getWorld().getName();
        String displayWorld = displayWorld(rawWorld);

        return message
                .replace("%world%", displayWorld)
                .replace("%world_display%", displayWorld)
                .replace("%display_world%", displayWorld)
                .replace("%world_folder%", rawWorld)
                .replace("%raw_world%", rawWorld)
                .replace("%player%", player.getName())
                .replace("%status%", flightAllowed ? "Enabled" : "Disabled")
                .replace("%status_lower%", flightAllowed ? "enabled" : "disabled");
    }

    private String displayWorld(String world) {
        String configured = core.getConfig().getString("fly.world-display-names." + world);

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

    private Component actionBar(String message) {
        return legacy(message);
    }

    private Component legacy(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
