package net.mineacle.core.auctionhouse.listener;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Recovers durable Auction House v2 transactions independently of GUI state.
 */
public final class AuctionTransactionRecoveryListener implements Listener {

    private static final long RETRY_TICKS = 20L;

    private final AuctionHouseService service;
    private BukkitTask retryTask;

    public AuctionTransactionRecoveryListener(
            Core core,
            AuctionHouseService service
    ) {
        this.service = service;
        this.retryTask = core.getServer().getScheduler().runTaskTimer(
                core,
                service::retryPendingTransactions,
                RETRY_TICKS,
                RETRY_TICKS
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        service.recoverPlayerTransactions(event.getPlayer());
    }

    public void shutdown() {
        if (retryTask != null) {
            retryTask.cancel();
            retryTask = null;
        }
    }
}
