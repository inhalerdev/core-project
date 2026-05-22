package net.mineacle.core.links.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.List;
import java.util.Locale;

public final class LinksCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final File file;
    private FileConfiguration config;

    public LinksCommand(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "links.yml");
        reload();
    }

    public void reload() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        if (!file.exists()) {
            core.saveResource("links.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(file);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String used = command.getName().toLowerCase(Locale.ROOT);

        if (used.equals("links")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("mineaclelinks.admin")) {
                    sender.sendMessage(TextColor.color("&cYou do not have permission"));
                    return true;
                }

                reload();
                sender.sendMessage(TextColor.color("&#bbbbbbLinks reloaded"));
                return true;
            }

            if (!(sender instanceof Player player)) {
                sender.sendMessage("Players only");
                return true;
            }

            sendAll(player);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        sendLink(player, used);
        return true;
    }

    private void sendAll(Player player) {
        player.sendMessage(Component.empty());
        player.sendMessage(legacy("&#ff88ffMineacle Links"));
        sendCompactLine(player, "store");
        sendCompactLine(player, "discord");
        sendCompactLine(player, "x");
        SoundService.guiClick(player, core);
    }

    private void sendCompactLine(Player player, String key) {
        String label = config.getString("links." + key + ".label", key);
        String commandLine = config.getString("links." + key + ".command-line", "&d/" + key);
        String url = config.getString("links." + key + ".url", "");
        String hover = config.getString("links." + key + ".hover", "Open link");

        Component line = legacy("&#bbbbbb- " + commandLine + " &#bbbbbb" + label)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(legacy(hover)));

        player.sendMessage(line);
    }

    private void sendLink(Player player, String key) {
        if (!config.getBoolean("enabled", true)) {
            return;
        }

        if (!config.contains("links." + key)) {
            player.sendMessage(TextColor.color("&cThat link is not configured"));
            SoundService.guiError(player, core);
            return;
        }

        String label = config.getString("links." + key + ".label", key);
        String url = config.getString("links." + key + ".url", "");
        String action = config.getString("links." + key + ".action", "&dClick here");
        String hover = config.getString("links." + key + ".hover", "Open link");
        List<String> lines = config.getStringList("links." + key + ".lines");

        player.sendMessage(Component.empty());
        player.sendMessage(legacy(config.getString("links." + key + ".command-line", "&d/" + key) + " &#bbbbbb" + label));

        for (String line : lines) {
            player.sendMessage(legacy(line));
        }

        player.sendMessage(Component.empty());

        Component clickLine = legacy(action)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(legacy(hover)));

        player.sendMessage(clickLine);
        SoundService.guiClick(player, core);
    }

    private Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(text));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("links")) {
            return List.of();
        }

        if (args.length == 1 && sender.hasPermission("mineaclelinks.admin")) {
            String partial = args[0].toLowerCase(Locale.ROOT);

            if ("reload".startsWith(partial)) {
                return List.of("reload");
            }
        }

        return List.of();
    }
}
