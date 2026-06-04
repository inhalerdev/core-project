package net.mineacle.core.duels;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.duels.command.DuelCommand;
import net.mineacle.core.duels.listener.DuelListener;
import net.mineacle.core.duels.service.DuelService;
import org.bukkit.command.PluginCommand;
import org.bukkit.scheduler.BukkitTask;

public final class DuelsModule extends Module {

    private DuelService duelService;
    private BukkitTask tickTask;

    @Override
    public String name() {
        return "Duels";
    }

    @Override
    public void enable(Core core) {
        core.saveResource("duels.yml", false);

        this.duelService = new DuelService(core);

        DuelCommand command = new DuelCommand(core, duelService);
        PluginCommand duel = core.getCommand("duel");

        if (duel != null) {
            duel.setExecutor(command);
            duel.setTabCompleter(command);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: duel");
        }

        core.getServer().getPluginManager().registerEvents(new DuelListener(core, duelService), core);

        this.tickTask = core.getServer().getScheduler().runTaskTimer(
                core,
                duelService::tick,
                20L,
                20L
        );
    }

    @Override
    public void disable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }

        if (duelService != null) {
            duelService.shutdown();
            duelService = null;
        }
    }
}
