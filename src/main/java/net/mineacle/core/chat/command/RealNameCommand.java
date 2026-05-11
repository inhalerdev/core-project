package net.mineacle.core.chat.command;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.NicknameService;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;

import java.util.Collections;
import java.util.List;

public final class RealNameCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final NicknameService nicknameService;

    public RealNameCommand(Core core, NicknameService nicknameService) {
        this.core = core;
        this.nicknameService = nicknameService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mineaclechat.realname")) {
            sender.sendMessage(core.getMessage("general.no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(core.getMessage("chat.realname-usage"));
            return true;
        }

        OfflinePlayer target = nicknameService.findByNickname(args[0]);

        if (target == null) {
            sender.sendMessage(core.getMessage("chat.realname-not-found"));
            return true;
        }

        sender.sendMessage(core.getMessage("chat.realname-result")
                .replace("%nickname%", args[0])
                .replace("%player%", nicknameService.username(target)));

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}