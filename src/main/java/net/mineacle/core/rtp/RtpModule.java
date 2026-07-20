package net.mineacle.core.rtp;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.rtp.command.OriginRtpCommand;
import net.mineacle.core.rtp.listener.OriginRtpMoveListener;
import net.mineacle.core.rtp.listener.RtpMenuListener;
import net.mineacle.core.rtp.service.OriginRtpQueueService;
import net.mineacle.core.rtp.service.RtpMenuService;
import org.bukkit.command.PluginCommand;

public final class RtpModule extends Module {

    private OriginRtpQueueService queueService;
    private RtpMenuService menuService;

    @Override
    public String name() {
        return "RTP";
    }

    @Override
    public void enable(Core core) {
        queueService =
                new OriginRtpQueueService(core);
        menuService =
                new RtpMenuService(core);

        PluginCommand command =
                core.getCommand("originrtp");

        if (command == null) {
            queueService = null;
            menuService = null;
            throw new IllegalStateException(
                    "Missing command in plugin.yml: originrtp"
            );
        }

        OriginRtpCommand executor =
                new OriginRtpCommand(
                        core,
                        queueService,
                        menuService
                );
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        core.getServer().getPluginManager().registerEvents(
                new OriginRtpMoveListener(
                        queueService
                ),
                core
        );
        core.getServer().getPluginManager().registerEvents(
                new RtpMenuListener(
                        core,
                        queueService
                ),
                core
        );

        queueService.start();
    }

    @Override
    public void disable() {
        if (queueService != null) {
            queueService.stop();
        }

        queueService = null;
        menuService = null;
    }
}
