package net.mineacle.core.chat.command;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.NicknameService;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;

public final class NickCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final NicknameService nicknameService;

    public NickCommand(Core core, NicknameService nicknameService) {
        this.core = core;
        this.nicknameService = nicknameService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (!player.hasPermission("mineaclechat.nick")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(core.getMessage("chat.nick-usage"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("off")) {
            nicknameService.clearNickname(player);
            player.sendMessage(core.getMessage("chat.nick-reset"));
            return true;
        }

        if (!nicknameService.setNickname(player, args[0])) {
            player.sendMessage(core.getMessage("chat.nick-invalid")
                    .replace("%max%", String.valueOf(nicknameService.maxLength())));
            return true;
        }

        player.sendMessage(core.getMessage("chat.nick-set")
                .replace("%nickname%", nicknameService.displayName(player)));

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && "reset".startsWith(args[0].toLowerCase())) {
            return List.of("reset");
        }

        return List.of();
    }
}