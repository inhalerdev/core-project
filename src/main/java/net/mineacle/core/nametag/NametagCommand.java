package net.mineacle.core.nametag;

import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class NametagCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final NametagService service;

    public NametagCommand(Core core, NametagService service) {
        this.core = core;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mineaclenametags.admin")) {
            sender.sendMessage(TextColor.color("&cYou do not have permission"));

            if (sender instanceof Player player) {
                SoundService.guiError(player, core);
            }

            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            service.reload();
            service.refreshAll();
            sender.sendMessage(TextColor.color("&#bbbbbbNametags reloaded"));

            if (sender instanceof Player player) {
                SoundService.guiConfirm(player, core);
            }

            return true;
        }

        sender.sendMessage(TextColor.color("&#bbbbbbUsage: &#ff88ff/nametags reload"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("mineaclenametags.admin") && "reload".startsWith(args[0].toLowerCase())) {
            return List.of("reload");
        }

        return List.of();
    }
}
