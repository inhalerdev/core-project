package net.mineacle.core.links.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.links.gui.GuideRulesGui;
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

            sendAllLinks(player);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (used.equals("guide") || used.equals("help") || used.equals("mineacle")) {
            MenuHistory.openRoot(core, player, () -> GuideRulesGui.openGuide(player));
            return true;
        }

        if (used.equals("rules")) {
            MenuHistory.openRoot(core, player, () -> GuideRulesGui.openRules(player));
            return true;
        }

        sendConfiguredLink(player, used);
        return true;
    }

    public void sendAllLinks(Player player) {
        player.sendMessage(Component.empty());
        player.sendMessage(legacy("&#ff88ffMineacle Links"));
        sendCompactLine(player, "store");
        sendCompactLine(player, "discord");
        sendCompactLine(player, "x");
        SoundService.guiClick(player, core);
    }

    public void sendConfiguredLink(Player player, String key) {
        key = normalize(key);

        if (!config.getBoolean("enabled", true)) {
            return;
        }

        if (!config.contains("links." + key)) {
            player.sendMessage(TextColor.color("&cThat link is not configured"));
            SoundService.guiError(player, core);
            return;
        }

        String title = config.getString("links." + key + ".title", "&dMineacle Link");
        String url = config.getString("links." + key + ".url", "");
        String buttonLine = config.getString("links." + key + ".button-line", "&dClick here");
        String hover = config.getString("links." + key + ".hover", "Open link");
        List<String> lines = config.getStringList("links." + key + ".lines");

        player.sendMessage(Component.empty());
        player.sendMessage(legacy(title));

        for (String line : lines) {
            player.sendMessage(legacy(line));
        }

        player.sendMessage(Component.empty());

        Component clickLine = legacy(buttonLine)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(legacy(hover)));

        player.sendMessage(clickLine);
        SoundService.guiClick(player, core);
    }

    private void sendCompactLine(Player player, String key) {
        String title = config.getString("links." + key + ".title", key);
        String button = config.getString("links." + key + ".button", "&d[OPEN]");
        String url = config.getString("links." + key + ".url", "");
        String hover = config.getString("links." + key + ".hover", "Open link");

        Component line = legacy("&#bbbbbb- " + title + " ")
                .append(legacy(button)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(legacy(hover))));

        player.sendMessage(line);
    }

    private String normalize(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "buy", "shop" -> "store";
            case "dc" -> "discord";
            case "twitter" -> "x";
            case "link", "social", "socials" -> "links";
            default -> key.toLowerCase(Locale.ROOT);
        };
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
