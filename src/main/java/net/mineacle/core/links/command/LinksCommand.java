package net.mineacle.core.links.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.links.gui.GuideRulesGui;
import net.mineacle.core.links.service.LinksService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class LinksCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final LinksService linksService;
    private final GuideRulesGui guideRulesGui;

    public LinksCommand(
            Core core,
            LinksService linksService,
            GuideRulesGui guideRulesGui
    ) {
        this.core = core;
        this.linksService = linksService;
        this.guideRulesGui = guideRulesGui;
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

        String root = command.getName()
                .toLowerCase(Locale.ROOT);

        if (args.length != 0) {
            error(
                    player,
                    "&cUsage: /" + root
            );
            return true;
        }

        switch (root) {
            case "guide" -> MenuHistory.openRoot(
                    core,
                    player,
                    () -> guideRulesGui.openGuide(player)
            );
            case "rules" -> MenuHistory.openRoot(
                    core,
                    player,
                    () -> guideRulesGui.openRules(player)
            );
            case "discord", "store", "x" ->
                    linksService.sendLink(player, root);
            default -> error(
                    player,
                    "&cThis command does not exist"
            );
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

    private void error(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        SoundService.guiError(player, core);
    }
}
