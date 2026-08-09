package net.mineacle.core.hide;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class HideCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final HideService service;

    public HideCommand(
            Core core,
            HideService service
    ) {
        this.core = core;
        this.service = service;
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

        if (args.length != 0) {
            error(player, "&cUsage: /hide");
            return true;
        }

        if (!service.enabled()) {
            error(player, "&cHide is currently disabled");
            return true;
        }

        if (!service.canUse(player)) {
            error(
                    player,
                    service.message(
                            "blocked",
                            "&cYou do not have permission to use /hide"
                    )
            );
            return true;
        }

        boolean hidden = service.toggle(player);

        if (hidden) {
            send(
                    player,
                    service.message(
                            "enabled",
                            "&#bbbbbbNametag hidden"
                    )
            );
            SoundService.featureEnable(player, core);
        } else {
            send(
                    player,
                    service.message(
                            "disabled",
                            "&#bbbbbbNametag visible"
                    )
            );
            SoundService.featureDisable(player, core);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        return List.of();
    }

    private void error(
            Player player,
            String message
    ) {
        send(player, message);
        SoundService.guiError(player, core);
    }

    private void send(
            Player player,
            String message
    ) {
        String colored = TextColor.color(message);
        player.sendMessage(colored);
        player.sendActionBar(component(colored));
    }

    private Component component(String coloredMessage) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(coloredMessage);
    }
}
