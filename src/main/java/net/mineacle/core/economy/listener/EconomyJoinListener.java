package net.mineacle.core.economy.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.economy.service.OfflinePaymentNotice;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class EconomyJoinListener implements Listener {

    private final Core core;
    private final EconomyService economyService;

    public EconomyJoinListener(
            Core core,
            EconomyService economyService
    ) {
        this.core = core;
        this.economyService = economyService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (!economyService.enabled()) {
            return;
        }

        economyService.ensureAccount(player.getUniqueId());

        core.getServer().getScheduler().runTaskLater(
                core,
                () -> deliverNotice(player),
                20L
        );
    }

    private void deliverNotice(Player player) {
        if (!player.isOnline()) {
            return;
        }

        OfflinePaymentNotice notice =
                economyService.consumeOfflinePayment(
                        player.getUniqueId()
                );

        if (notice == null || notice.totalCents() <= 0L) {
            return;
        }

        String amount = economyService.format(
                notice.totalCents()
        );
        String chatMessage;
        String actionBar;

        if (notice.singleSender()) {
            String senderName = notice.singleSenderName();

            chatMessage = "&#bbbbbbYou received &a+"
                    + amount
                    + " &#bbbbbbfrom &#bbbbbb"
                    + senderName
                    + " &#bbbbbbwhile you were away";
            actionBar = "&a+" + amount
                    + " &#bbbbbbfrom &#bbbbbb"
                    + senderName;
        } else {
            chatMessage = "&#bbbbbbYou received &a+"
                    + amount
                    + " &#bbbbbbwhile you were away";
            actionBar = "&a+" + amount;
        }

        player.sendMessage(TextColor.color(chatMessage));
        player.sendActionBar(component(actionBar));
        SoundService.economyReceive(player, core);
    }

    private Component component(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(message));
    }
}
