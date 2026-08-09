package net.mineacle.core.economy;

import net.milkbowl.vault.economy.Economy;
import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.economy.command.BalanceCommand;
import net.mineacle.core.economy.command.EcoCommand;
import net.mineacle.core.economy.command.PayCommand;
import net.mineacle.core.economy.listener.EconomyJoinListener;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.economy.storage.YamlEconomyRepository;
import net.mineacle.core.economy.vault.MineacleVaultEconomy;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.scheduler.BukkitTask;

public final class EconomyModule extends Module {

    private static EconomyService economyService;

    private MineacleVaultEconomy vaultEconomy;
    private BukkitTask retrySaveTask;

    public static EconomyService economyService() {
        return economyService;
    }

    @Override
    public String name() {
        return "Economy";
    }

    @Override
    public void enable(Core core) {
        economyService = new EconomyService(
                core,
                new YamlEconomyRepository(core)
        );

        register(
                core,
                "balance",
                new BalanceCommand(core, economyService)
        );
        register(
                core,
                "pay",
                new PayCommand(core, economyService)
        );
        register(
                core,
                "eco",
                new EcoCommand(core, economyService)
        );

        core.getServer().getPluginManager().registerEvents(
                new EconomyJoinListener(core, economyService),
                core
        );

        long retrySeconds = Math.max(
                5L,
                Math.min(
                        3_600L,
                        core.getConfig().getLong(
                                "economy.save-retry-seconds",
                                30L
                        )
                )
        );

        retrySaveTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        economyService::flushIfDirty,
                        retrySeconds * 20L,
                        retrySeconds * 20L
                );

        registerVaultProvider(core);
    }

    @Override
    public void disable() {
        if (retrySaveTask != null) {
            retrySaveTask.cancel();
            retrySaveTask = null;
        }

        if (vaultEconomy != null) {
            Bukkit.getServicesManager().unregister(
                    Economy.class,
                    vaultEconomy
            );
            vaultEconomy = null;
        }

        if (economyService != null) {
            economyService.shutdown();
            economyService = null;
        }
    }

    private void registerVaultProvider(Core core) {
        if (!economyService.enabled()) {
            core.getLogger().info(
                    "Mineacle economy is disabled"
            );
            return;
        }

        if (Bukkit.getPluginManager()
                .getPlugin("Vault") == null) {
            core.getLogger().warning(
                    "Vault is not installed — Mineacle economy "
                            + "will not be registered with Vault"
            );
            return;
        }

        vaultEconomy = new MineacleVaultEconomy(economyService);

        Bukkit.getServicesManager().register(
                Economy.class,
                vaultEconomy,
                core,
                ServicePriority.Highest
        );

        core.getLogger().info(
                "Registered MineacleCore as the Vault economy provider"
        );
    }

    private void register(
            Core core,
            String commandName,
            CommandExecutor executor
    ) {
        PluginCommand command = core.getCommand(commandName);

        if (command == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: " + commandName
            );
        }

        command.setExecutor(executor);

        if (executor instanceof TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }
}
