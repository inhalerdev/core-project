package net.mineacle.core.bounty;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class BountyListener implements Listener {

    private final Core core;
    private final BountyService bountyService;

    public BountyListener(
            Core core,
            BountyService bountyService
    ) {
        this.core = core;
        this.bountyService = bountyService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player target = event.getEntity();
        Player killer = target.getKiller();

        if (killer == null
                || killer.getUniqueId()
                .equals(target.getUniqueId())) {
            return;
        }

        BountyService.ClaimResult result =
                bountyService.claimDetailed(killer, target);

        switch (result.status()) {
            case SUCCESS -> notifyClaim(
                    killer,
                    target,
                    result
            );
            case ECONOMY_UNAVAILABLE -> {
                killer.sendMessage(TextColor.color(
                        "&cBounty reward could not be paid"
                ));
                SoundService.guiError(killer, core);
            }
            case STORAGE_ERROR -> {
                killer.sendMessage(TextColor.color(
                        "&cBounty claim could not be saved"
                ));
                SoundService.guiError(killer, core);
            }
            case NO_BOUNTY, BLOCKED_WORLD -> {
            }
        }
    }

    private void notifyClaim(
            Player killer,
            Player target,
            BountyService.ClaimResult result
    ) {
        String killerMessage =
                "&#bbbbbbYou claimed &a+"
                        + bountyService.format(
                        result.payoutCents()
                )
                        + " &#bbbbbbfrom &#bbbbbb"
                        + bountyService.displayName(target);

        killer.sendMessage(TextColor.color(killerMessage));
        killer.sendActionBar(
                LegacyComponentSerializer.legacySection()
                        .deserialize(
                                TextColor.color(killerMessage)
                        )
        );
        SoundService.play(
                killer,
                core,
                "bounty.claim"
        );

        String targetMessage =
                "&#bbbbbb"
                        + bountyService.displayName(killer)
                        + " claimed your bounty";

        target.sendMessage(TextColor.color(targetMessage));
        SoundService.guiError(target, core);
    }
}
