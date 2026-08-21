package net.mineacle.core.sell;

import com.comphenix.protocol.ProtocolLibrary;
import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.sell.command.SellCommand;
import net.mineacle.core.sell.listener.ItemStackNormalizeListener;
import net.mineacle.core.sell.listener.SellGuiListener;
import net.mineacle.core.sell.listener.SellWorthPacketListener;
import net.mineacle.core.sell.listener.WorthGuiListener;
import net.mineacle.core.sell.service.SellLearningService;
import net.mineacle.core.sell.service.SellLivePricingService;
import net.mineacle.core.sell.service.SellRuntimeIntegrityAudit;
import net.mineacle.core.sell.service.SellService;
import net.mineacle.core.sell.storage.SellCatalogV10BootstrapService;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class SellModule extends Module {

    private static SellService sellService;
    private SellLearningService learningService;
    private SellLivePricingService livePricingService;

    private SellWorthPacketListener packetListener;
    private WorthGuiListener worthGuiListener;
    private SellRuntimeIntegrityAudit integrityAudit;
    private BukkitTask marketTask;
    private BukkitTask learningTask;

    public static SellService sellService() {
        return sellService;
    }

    @Override
    public String name() {
        return "Sell";
    }

    @Override
    public void enable(Core core) {
        sellService = new SellService(core);
        sellService.start();

        /*
         * The live governor owns evidence-backed publication. It is installed
         * before the learner so the learner can always resolve frozen static
         * reference markets rather than feeding back the current live price.
         */
        livePricingService = new SellLivePricingService(
                core,
                sellService
        );
        livePricingService.start();

        learningService = new SellLearningService(
                core,
                sellService,
                livePricingService
        );
        learningService.start();

        /*
         * Revision 10 is the static reference authority. It compiles only
         * forward from primary references, preserves exact commodity ratios,
         * clamps crafted outputs against their cheapest trusted recipe, and
         * freezes the retired v9 demand/featured multipliers at 1.0x.
         */
        SellCatalogV10BootstrapService catalogBootstrapService =
                new SellCatalogV10BootstrapService(
                        core,
                        sellService,
                        livePricingService
                );
        catalogBootstrapService.start();

        integrityAudit =
                new SellRuntimeIntegrityAudit(
                        core,
                        sellService
                );
        integrityAudit.start();

        SellCommand command =
                new SellCommand(
                        core,
                        sellService
                );

        register(
                core,
                "sell",
                command
        );
        register(
                core,
                "worth",
                command
        );

        core.getServer()
                .getPluginManager()
                .registerEvents(
                        new SellGuiListener(
                                core,
                                sellService
                        ),
                        core
                );

        worthGuiListener =
                new WorthGuiListener(
                        core,
                        sellService
                );
        core.getServer()
                .getPluginManager()
                .registerEvents(
                        worthGuiListener,
                        core
                );

        core.getServer()
                .getPluginManager()
                .registerEvents(
                        new ItemStackNormalizeListener(
                                core,
                                sellService
                        ),
                        core
                );

        marketTask =
                core.getServer()
                        .getScheduler()
                        .runTaskTimer(
                                core,
                                sellService::tick,
                                20L,
                                20L * 20L
                        );

        learningTask =
                core.getServer()
                        .getScheduler()
                        .runTaskTimer(
                                core,
                                () -> {
                                    learningService.tick();
                                    livePricingService.tick();
                                },
                                20L,
                                20L * 20L
                        );

        Plugin protocolLib =
                core.getServer()
                        .getPluginManager()
                        .getPlugin(
                                "ProtocolLib"
                        );

        if (protocolLib != null
                && protocolLib.isEnabled()) {
            packetListener =
                    new SellWorthPacketListener(
                            core,
                            sellService
                    );
            ProtocolLibrary
                    .getProtocolManager()
                    .addPacketListener(
                            packetListener
                    );
            core.getLogger().info(
                    "Sell worth hover display enabled for explicitly allowed inventory contexts"
            );
        } else {
            core.getLogger().warning(
                    "ProtocolLib not found — packet-only Worth display is disabled"
            );
        }
    }

    @Override
    public void disable() {
        if (learningTask != null) {
            learningTask.cancel();
            learningTask = null;
        }

        if (marketTask != null) {
            marketTask.cancel();
            marketTask = null;
        }

        if (packetListener != null) {
            ProtocolLibrary
                    .getProtocolManager()
                    .removePacketListener(
                            packetListener
                    );
            packetListener = null;
        }

        if (worthGuiListener != null) {
            worthGuiListener.shutdown();
            worthGuiListener = null;
        }

        if (integrityAudit != null) {
            integrityAudit.shutdown();
            integrityAudit = null;
        }

        if (learningService != null) {
            learningService.shutdown();
            learningService = null;
        }

        if (livePricingService != null) {
            livePricingService.shutdown();
            livePricingService = null;
        }

        if (sellService != null) {
            sellService.shutdown();
            sellService = null;
        }
    }

    private void register(
            Core core,
            String commandName,
            CommandExecutor executor
    ) {
        PluginCommand command =
                core.getCommand(
                        commandName
                );

        if (command == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: "
                            + commandName
            );
        }

        command.setExecutor(
                executor
        );

        if (executor
                instanceof TabCompleter completer) {
            command.setTabCompleter(
                    completer
            );
        }
    }
}
