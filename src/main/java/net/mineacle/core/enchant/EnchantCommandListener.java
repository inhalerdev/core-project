package net.mineacle.core.enchant;

import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.Locale;

public final class EnchantCommandListener implements Listener {

    private final EnchantCommand enchantCommand;
    private final EnchantInfoCommand enchantInfoCommand;

    public EnchantCommandListener(EnchantCommand enchantCommand, EnchantInfoCommand enchantInfoCommand) {
        this.enchantCommand = enchantCommand;
        this.enchantInfoCommand = enchantInfoCommand;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();

        if (raw.isBlank() || !raw.startsWith("/")) {
            return;
        }

        String withoutSlash = raw.substring(1).trim();

        if (withoutSlash.isBlank()) {
            return;
        }

        String[] parts = withoutSlash.split("\\s+");
        String command = parts[0].toLowerCase(Locale.ROOT);

        String[] args = parts.length <= 1
                ? new String[0]
                : Arrays.copyOfRange(parts, 1, parts.length);

        CommandSender sender = event.getPlayer();

        if (command.equals("enchant") || command.equals("mineaclecore:enchant") || command.equals("mineacle:enchant")) {
            event.setCancelled(true);
            enchantCommand.run(sender, args);
            return;
        }

        if (command.equals("enchantinfo") || command.equals("mineaclecore:enchantinfo") || command.equals("mineacle:enchantinfo")) {
            event.setCancelled(true);
            enchantInfoCommand.run(sender, args);
        }
    }
}
