package net.mineacle.core.bounty.listener;

import net.mineacle.core.Core;
import net.mineacle.core.bounty.service.BountyService;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class BountyListener
        implements Listener {

    private final Core core;
    private final BountyService bountyService;

    public BountyListener(
            Core core,
            BountyService bountyService
    ) {
        this.core = core;
        this.bountyService = bountyService;
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onPlayerDeath(
            PlayerDeathEvent event
    ) {
        Player target =
                event.getEntity();
        Player killer =
                target.getKiller();

        if (killer == null
                || killer.getUniqueId()
                .equals(
                        target.getUniqueId()
                )) {
            return;
        }

        BountyService.ClaimResult result =
                bountyService.claimDetailed(
                        killer,
                        target
                );

        switch (result.status()) {
            case SUCCESS ->
                    notifyClaim(
                            killer,
                            target,
                            result
                    );
            case SAME_TEAM ->
                    actionError(
                            killer,
                            "&cTeam kills cannot claim bounties"
                    );
            case BALANCE_LIMIT ->
                    actionError(
                            killer,
                            "&cYour balance cannot receive that bounty"
                    );
            case ECONOMY_UNAVAILABLE,
                 PAYOUT_FAILED ->
                    actionError(
                            killer,
                            "&cBounty reward could not be paid"
                    );
            case STORAGE_ERROR ->
                    actionError(
                            killer,
                            "&cBounty claim could not be saved"
                    );
            case NO_BOUNTY,
                 BLOCKED_WORLD -> {
            }
        }
    }

    private void notifyClaim(
            Player killer,
            Player target,
            BountyService.ClaimResult result
    ) {
        String targetName =
                bountyService.displayName(
                        target
                );
        String killerMessage =
                "&#bbbbbbClaimed &a+"
                        + bountyService.format(
                        result.payoutCents()
                )
                        + " &#bbbbbbfrom &#B078FF"
                        + targetName;

        killer.sendMessage(
                TextColor.color(
                        killerMessage
                )
        );
        killer.sendActionBar(
                GuiText.component(
                        killerMessage
                )
        );
        SoundService.play(
                killer,
                core,
                "bounty.claim"
        );

        String targetMessage =
                "&#B078FF"
                        + bountyService.displayName(
                        killer
                )
                        + " &#bbbbbbclaimed your &a"
                        + bountyService.format(
                        result.grossCents()
                )
                        + " &#bbbbbounty";

        target.sendMessage(
                TextColor.color(
                        targetMessage
                )
        );
        SoundService.guiError(
                target,
                core
        );
    }

    private void actionError(
            Player player,
            String message
    ) {
        player.sendActionBar(
                GuiText.component(message)
        );
        SoundService.guiError(
                player,
                core
        );
    }
}
