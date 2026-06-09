package net.mineacle.core.nametag;

import net.mineacle.core.Core;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;

public final class NametagListener implements Listener {

    private final Core core;
    private final NametagService service;

    public NametagListener(Core core, NametagService service) {
        this.core = core;
        this.service = service;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        scheduleRefreshBurst();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.removeDisplay(event.getPlayer());
        scheduleRefreshBurst();
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        scheduleRefreshBurst();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage();

        if (command == null || command.isBlank()) {
            return;
        }

        if (shouldRefreshAfter(command.substring(1))) {
            scheduleRefreshBurst();
        }
    }

    @EventHandler
    public void onServerCommand(ServerCommandEvent event) {
        String command = event.getCommand();

        if (command == null || command.isBlank()) {
            return;
        }

        if (shouldRefreshAfter(command)) {
            scheduleRefreshBurst();
        }
    }

    private boolean shouldRefreshAfter(String rawCommand) {
        String command = rawCommand.toLowerCase(Locale.ROOT).trim();

        if (command.isBlank()) {
            return false;
        }

        return command.startsWith("op ")
                || command.startsWith("deop ")
                || command.startsWith("lp ")
                || command.startsWith("luckperms ")
                || command.startsWith("perm ")
                || command.startsWith("permissions ")
                || command.startsWith("pex ")
                || command.startsWith("mineaclenametags ")
                || command.equals("mineaclenametags");
    }

    private void scheduleRefreshBurst() {
        core.getServer().getScheduler().runTaskLater(core, service::refreshAll, 1L);
        core.getServer().getScheduler().runTaskLater(core, service::refreshAll, 5L);
        core.getServer().getScheduler().runTaskLater(core, service::refreshAll, 20L);
        core.getServer().getScheduler().runTaskLater(core, service::refreshAll, 60L);
    }
}
