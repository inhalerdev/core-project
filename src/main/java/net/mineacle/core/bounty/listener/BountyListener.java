package net.mineacle.core.bounty;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class BountyListener implements Listener {

    private final Core core;
    private final BountyService bountyService;

    public BountyListener(Core core, BountyService bountyService) {
        this.core = core;
        this.bountyService = bountyService;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player target = event.getEntity();
        Player killer = target.getKiller();

        if (killer == null || killer.getUniqueId().equals(target.getUniqueId())) {
            return;
        }

        long beforeTax = bountyService.getAmount(target.getUniqueId());

        if (beforeTax <= 0L) {
            return;
        }

        long payout = bountyService.claim(killer, target);

        if (payout <= 0L) {
            return;
        }

        String killerMessage = "&#bbbbbbYou claimed &a+" + bountyService.format(payout)
                + " &#bbbbbbfrom &#ff88ff" + DisplayNames.displayName(target);

        killer.sendMessage(TextColor.color(killerMessage));
        killer.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(TextColor.color(killerMessage)));
        SoundService.play(killer, core, "bounty.claim");

        String targetMessage = "&#ff88ff" + DisplayNames.displayName(killer)
                + " &#bbbbbbclaimed your bounty";

        target.sendMessage(TextColor.color(targetMessage));
        SoundService.guiError(target, core);
    }
}
