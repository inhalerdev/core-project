package net.mineacle.core.chat.command;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.NicknameService;
import net.mineacle.core.chat.service.NicknameSettings;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.nametag.NametagModule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class NickCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final NicknameService nicknameService;
    private final NicknameSettings nicknameSettings;

    public NickCommand(Core core, NicknameService nicknameService, NicknameSettings nicknameSettings) {
        this.core = core;
        this.nicknameService = nicknameService;
        this.nicknameSettings = nicknameSettings;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (!nicknameSettings.enabled()) {
            player.sendMessage(TextColor.color("&cNicknames are currently disabled"));
            return true;
        }

        if (!nicknameSettings.canUse(player)) {
            player.sendActionBar(TextColor.color("&cThis is a Mineacle+ feature"));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(core.getMessage("chat.nick-usage"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("clear")) {
            nicknameService.clearNickname(player);
            player.sendMessage(core.getMessage("chat.nick-reset"));
            NametagModule.refreshAll();
            return true;
        }

        if (!nicknameService.setNickname(player, args[0])) {
            player.sendMessage(core.getMessage("chat.nick-invalid")
                    .replace("%max%", String.valueOf(nicknameService.maxLength())));
            return true;
        }

        player.sendMessage(core.getMessage("chat.nick-set")
                .replace("%nickname%", nicknameService.displayName(player)));

        NametagModule.refreshAll();
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }

        if (!nicknameSettings.enabled() || !nicknameSettings.canUse(player)) {
            return List.of();
        }

        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);

            if ("reset".startsWith(partial) || "clear".startsWith(partial) || "off".startsWith(partial)) {
                return List.of("reset");
            }
        }

        return List.of();
    }
}
