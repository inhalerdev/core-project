package net.mineacle.core.gamemode.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class GamemodeShortcutCommand implements CommandExecutor {

    private final Core core;
    private final GameMode gameMode;

    public GamemodeShortcutCommand(Core core, GameMode gameMode) {
        this.core = core;
        this.gameMode = gameMode;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (!player.hasPermission("mineacle.gamemode")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            SoundService.guiError(player, core);
            return true;
        }

        player.setGameMode(gameMode);

        String mode = switch (gameMode) {
            case CREATIVE -> "Creative";
            case SURVIVAL -> "Survival";
            case SPECTATOR -> "Spectator";
            case ADVENTURE -> "Adventure";
        };

        String message = core.getConfig().getString("gamemode.message", "&#ccccccGamemode set to &#ff88ff%mode%")
                .replace("%mode%", mode);

        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
        SoundService.guiConfirm(player, core);
        return true;
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
