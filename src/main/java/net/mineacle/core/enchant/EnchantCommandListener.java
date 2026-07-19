package net.mineacle.core.enchant;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class EnchantCommandListener
        implements Listener {

    private final EnchantCommand enchantCommand;
    private final EnchantInfoCommand enchantInfoCommand;

    public EnchantCommandListener(
            EnchantCommand enchantCommand,
            EnchantInfoCommand enchantInfoCommand
    ) {
        this.enchantCommand = enchantCommand;
        this.enchantInfoCommand = enchantInfoCommand;
    }

    /**
     * Minecraft owns a built-in /enchant command. This compatibility route
     * guarantees that Mineacle's administrative implementation consistently
     * handles the plain command while leaving /minecraft:enchant untouched.
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onCommand(
            PlayerCommandPreprocessEvent event
    ) {
        ParsedCommand parsed = parse(event.getMessage());

        if (parsed == null) {
            return;
        }

        if (isEnchantRoot(parsed.root())) {
            event.setCancelled(true);
            enchantCommand.run(
                    event.getPlayer(),
                    parsed.args()
            );
            return;
        }

        if (isEnchantInfoRoot(parsed.root())) {
            event.setCancelled(true);
            enchantInfoCommand.run(
                    event.getPlayer(),
                    parsed.args()
            );
        }
    }

    /**
     * TabCompleteEvent starts after a player has entered command arguments.
     * Replacing only these argument completions keeps root-command visibility
     * under Mineacle Security while preventing vanilla /enchant suggestions.
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onTabComplete(TabCompleteEvent event) {
        ParsedCommand parsed = parse(event.getBuffer());

        if (parsed == null) {
            return;
        }

        CommandSender sender = event.getSender();
        List<String> completions;

        if (isEnchantRoot(parsed.root())) {
            completions = enchantCommand.complete(
                    sender,
                    parsed.args()
            );
        } else if (isEnchantInfoRoot(parsed.root())) {
            completions = enchantInfoCommand.complete(
                    sender,
                    parsed.args()
            );
        } else {
            return;
        }

        event.setCompletions(completions);
    }

    private ParsedCommand parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String commandLine = raw.startsWith("/")
                ? raw.substring(1)
                : raw;

        /*
         * Do not trim the end: the trailing empty argument is required for
         * immediate completions after "/enchant ".
         */
        commandLine = commandLine.stripLeading();

        if (commandLine.isBlank()) {
            return null;
        }

        String[] parts = commandLine.split("\\s+", -1);
        String root = parts[0].toLowerCase(Locale.ROOT);
        String[] args = parts.length <= 1
                ? new String[0]
                : Arrays.copyOfRange(
                        parts,
                        1,
                        parts.length
                );

        return new ParsedCommand(root, args);
    }

    private boolean isEnchantRoot(String root) {
        return root.equals("enchant")
                || root.equals("mineaclecore:enchant")
                || root.equals("mineacle:enchant");
    }

    private boolean isEnchantInfoRoot(String root) {
        return root.equals("enchantinfo")
                || root.equals("mineaclecore:enchantinfo")
                || root.equals("mineacle:enchantinfo");
    }

    private record ParsedCommand(
            String root,
            String[] args
    ) {
    }
}
