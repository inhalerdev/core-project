package net.mineacle.core.rtp;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.rtp.command.OriginRtpCommand;
import net.mineacle.core.rtp.listener.OriginRtpMoveListener;
import net.mineacle.core.rtp.service.OriginRtpQueueService;
import org.bukkit.command.PluginCommand;

public final class RtpModule extends Module {

    private OriginRtpQueueService queueService;

    @Override
    public String name() {
        return "RTP";
    }

    @Override
    public void enable(Core core) {
        this.queueService = new OriginRtpQueueService(core);
        this.queueService.start();

        OriginRtpCommand command = new OriginRtpCommand(core, queueService);

        PluginCommand originRtp = core.getCommand("originrtp");

        if (originRtp != null) {
            originRtp.setExecutor(command);
            originRtp.setTabCompleter(command);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: originrtp");
        }

        core.getServer().getPluginManager().registerEvents(
                new OriginRtpMoveListener(queueService),
                core
        );
    }

    @Override
    public void disable() {
        if (queueService != null) {
            queueService.stop();
        }

        queueService = null;
    }
}