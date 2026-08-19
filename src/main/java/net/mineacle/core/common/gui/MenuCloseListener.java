package net.mineacle.core.common.gui;

import net.mineacle.core.common.sound.SoundService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

public final class MenuCloseListener implements Listener {

    private final Plugin plugin;

    public MenuCloseListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !MenuHistory.isTracked(player)) {
            return;
        }

        /*
         * ESC is structural GUI navigation, not a player action.
         * MenuHistory may reopen the tracked parent on the next tick, but that
         * automatic restoration is intentionally silent. Explicit clicks,
         * Previous/Next page actions and all other semantic GUI interactions
         * continue to own their sounds in their respective listeners.
         */
        MenuHistory.handleClose(
                plugin,
                player,
                event.getInventory()
        );
    }

    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        MenuHistory.clear(event.getPlayer());
        SoundService.clearPlayer(event.getPlayer());
    }

    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin().equals(plugin)) {
            MenuHistory.clearAll();
            SoundService.clearCache();
        }
    }
}
