package net.mineacle.core.chat.command;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.ChatService;
import net.mineacle.core.common.player.PlayerTabComplete;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class IgnoreCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final ChatService chatService;

    public IgnoreCommand(Core core, ChatService chatService) {
        this.core = core;
        this.chatService = chatService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (!player.hasPermission("mineaclechat.ignore")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(core.getMessage("chat.ignore-usage"));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);

        if (target.getName() == null && !target.hasPlayedBefore()) {
            player.sendMessage(core.getMessage("chat.player-not-found"));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(core.getMessage("chat.cannot-ignore-self"));
            return true;
        }

        boolean nowIgnoring = chatService.toggleIgnore(player, target);
        String targetName = chatService.nicknames().displayName(target);

        player.sendMessage(core.getMessage(nowIgnoring ? "chat.now-ignoring" : "chat.no-longer-ignoring")
                .replace("%player%", targetName));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player player)) {
            return completions;
        }

        if (args.length == 1) {
            return PlayerTabComplete.onlinePlayers(player, args[0]);
        }

        return completions;
    }
}
