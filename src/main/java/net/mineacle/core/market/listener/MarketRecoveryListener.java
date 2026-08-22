package net.mineacle.core.market.listener;

import net.mineacle.core.market.service.MarketSettlementService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class MarketRecoveryListener
        implements Listener {

    private final MarketSettlementService settlementService;

    public MarketRecoveryListener(
            MarketSettlementService settlementService
    ) {
        this.settlementService = settlementService;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        settlementService.recoverPlayer(
                event.getPlayer()
        );
    }
}
