package net.mineacle.core.hide;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.nametag.NametagModule;
import net.mineacle.core.webprofiles.WebProfilesModule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class VanishCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final VanishService service;

    public VanishCommand(
            Core core,
            VanishService service
    ) {
        this.core = core;
        this.service = service;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    core.getMessage("general.players-only")
            );
            return true;
        }

        if (args.length != 0) {
            fail(player, "&cUsage: /vanish");
            return true;
        }

        if (!service.enabled()) {
            fail(player, "&cVanish is currently disabled");
            return true;
        }

        if (service.cannotUse(player)) {
            fail(
                    player,
                    service.message(
                            "blocked",
                            "&cYou do not have permission to use vanish"
                    )
            );
            return true;
        }

        boolean vanished = service.toggle(player);
        NametagModule.refresh(player);
        WebProfilesModule.refreshPlayer(player);

        if (vanished) {
            send(
                    player,
                    service.message(
                            "enabled",
                            "&#bbbbbbVanish &#B078FFenabled"
                    )
            );
            SoundService.featureEnable(player, core);
        } else {
            send(
                    player,
                    service.message(
                            "disabled",
                            "&#bbbbbbVanish &cdisabled"
                    )
            );
            SoundService.featureDisable(player, core);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String @NotNull [] args
    ) {
        return List.of();
    }

    private void fail(Player player, String message) {
        send(player, message);
        SoundService.guiError(player, core);
    }

    private void send(Player player, String message) {
        String colored = TextColor.color(message);
        player.sendMessage(colored);
        player.sendActionBar(component(colored));
    }

    private Component component(String colored) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(colored);
    }
}
