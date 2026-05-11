package net.mineacle.core.economy.listener;

import net.kyori.adventure.text.Component;
import net.mineacle.core.Core;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.economy.service.OfflinePaymentNotice;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class EconomyJoinListener implements Listener {

    private final Core core;
    private final EconomyService economyService;

    public EconomyJoinListener(Core core, EconomyService economyService) {
        this.core = core;
        this.economyService = economyService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(core, () -> {
            OfflinePaymentNotice notice = economyService.consumeOfflinePayment(player.getUniqueId());

            if (notice == null || notice.totalCents() <= 0L) {
                return;
            }

            String amount = economyService.format(notice.totalCents());

            if (notice.singleSender()) {
                String message = core.getMessage("economy.paid-offline-single")
                        .replace("%amount%", amount)
                        .replace("%player%", notice.singleSenderName());

                player.sendMessage(message);
                player.sendActionBar(Component.text(message));
                return;
            }

            String message = core.getMessage("economy.paid-offline-multiple")
                    .replace("%amount%", amount);

            player.sendMessage(message);
        }, 20L);
    }
}