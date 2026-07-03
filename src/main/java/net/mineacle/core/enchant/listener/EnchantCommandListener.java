package net.mineacle.core.enchant.listener;

import net.mineacle.core.enchant.command.EnchantCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.Locale;

public final class EnchantCommandListener implements Listener {

    private final EnchantCommand command;

    public EnchantCommandListener(EnchantCommand command) {
        this.command = command;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();

        if (message.length() <= 1) {
            return;
        }

        String[] parts = message.substring(1).trim().split("\\s+");

        if (parts.length == 0 || parts[0].isBlank()) {
            return;
        }

        String label = parts[0].toLowerCase(Locale.ROOT);

        if (!label.equals("enchant") && !label.equals("mineaclecore:enchant") && !label.equals("mineacle:enchant")) {
            return;
        }

        event.setCancelled(true);

        String[] args = parts.length <= 1
                ? new String[0]
                : Arrays.copyOfRange(parts, 1, parts.length);

        command.execute(event.getPlayer(), args);
    }
}
