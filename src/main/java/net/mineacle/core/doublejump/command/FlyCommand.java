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
            send(player, "&cFlight is disabled");
            SoundService.guiError(player, core);
            return true;
        }

        String permission = core.getConfig().getString("fly.permission", "mineacle.plus");

        if (!player.hasPermission(permission) && !player.hasPermission("mineaclefly.admin")) {
            sendUpgrade(player);
            SoundService.guiError(player, core);
            return true;
        }

        if (!listener.isFlyWorld(player.getWorld().getName())) {
            send(player, core.getConfig().getString("fly.messages.blocked-world", "&cFlight is only available in spawn"));
            SoundService.guiError(player, core);
            return true;
        }

        boolean enabled = listener.toggleFly(player);
        String message = enabled
                ? core.getConfig().getString("fly.messages.enabled", "&aFlight enabled")
                : core.getConfig().getString("fly.messages.disabled", "&cFlight disabled");

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
                "&fElevate your gameplay experience with &dMineacle+"
        );

        String lineTwo = core.getConfig().getString(
                "fly.messages.upgrade-line-2",
                "&d♦ &fhttps://store.mineacle.net"
        );

        player.sendMessage(" ");
        player.sendMessage(TextColor.color(lineOne));

        Component store = legacy(lineTwo)
                .clickEvent(ClickEvent.openUrl("https://store.mineacle.net"));

        player.sendMessage(store);
        player.sendMessage(" ");
        player.sendActionBar(actionBar(lineOne));
    }

    private void send(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    private Component legacy(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
