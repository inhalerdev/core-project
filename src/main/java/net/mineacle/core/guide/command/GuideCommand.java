package net.mineacle.core.guide.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.guide.service.GuideMenuService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GuideCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final GuideMenuService service;
    private final String menuKey;

    public GuideCommand(Core core, GuideMenuService service, String menuKey) {
        this.core = core;
        this.service = service;
        this.menuKey = menuKey;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("mineacleguide.admin")) {
                sender.sendMessage(TextColor.color("&cYou do not have permission"));
                return true;
            }

            service.reload();
            sender.sendMessage(TextColor.color("&#bbbbbbGuide menus reloaded"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        MenuHistory.openRoot(core, player, () -> service.open(player, menuKey));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length != 1 || !sender.hasPermission("mineacleguide.admin")) {
            return completions;
        }

        String partial = args[0].toLowerCase(Locale.ROOT);

        if ("reload".startsWith(partial)) {
            completions.add("reload");
        }

        return completions;
    }
}
