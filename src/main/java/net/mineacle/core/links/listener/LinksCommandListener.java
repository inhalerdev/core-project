package net.mineacle.core.links.listener;

import net.mineacle.core.links.command.LinksCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.Set;

public final class LinksCommandListener implements Listener {

    private static final Set<String> COMMANDS = Set.of(
            "store",
            "buy",
            "shop",
            "discord",
            "dc",
            "x",
            "twitter",
            "links",
            "link",
            "socials",
            "social"
    );

    private final LinksCommand command;

    public LinksCommandListener(LinksCommand command) {
        this.command = command;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();

        if (message == null || message.length() <= 1) {
            return;
        }

        String raw = message.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);

        if (!COMMANDS.contains(raw)) {
            return;
        }

        event.setCancelled(true);

        Player player = event.getPlayer();

        switch (raw) {
            case "store", "buy", "shop" -> command.sendConfiguredLink(player, "store");
            case "discord", "dc" -> command.sendConfiguredLink(player, "discord");
            case "x", "twitter" -> command.sendConfiguredLink(player, "x");
            case "links", "link", "socials", "social" -> command.sendAllLinks(player);
            default -> {
            }
        }
    }
}
