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
        this.queueService = new OriginRtpQueueService(core);
        this.menuService = new RtpMenuService(core);
        this.queueService.start();

        core.getServer().getMessenger().registerOutgoingPluginChannel(core, "BungeeCord");

        OriginRtpCommand command = new OriginRtpCommand(core, queueService, menuService);

        PluginCommand originRtp = core.getCommand("originrtp");

        if (originRtp != null) {
            originRtp.setExecutor(command);
            originRtp.setTabCompleter(command);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: originrtp");
        }

        core.getServer().getPluginManager().registerEvents(new OriginRtpMoveListener(queueService), core);
        core.getServer().getPluginManager().registerEvents(new RtpMenuListener(core, menuService, queueService), core);
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
