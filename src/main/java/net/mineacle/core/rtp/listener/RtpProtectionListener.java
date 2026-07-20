package net.mineacle.core.rtp.listener;

import net.mineacle.core.rtp.service.OriginRtpQueueService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.EnumSet;
import java.util.Set;

public final class RtpProtectionListener
        implements Listener {

    private static final Set<EntityDamageEvent.DamageCause>
            PROTECTED_CAUSES = EnumSet.of(
            EntityDamageEvent.DamageCause.FALL,
            EntityDamageEvent.DamageCause.SUFFOCATION,
            EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.LAVA,
            EntityDamageEvent.DamageCause.HOT_FLOOR,
            EntityDamageEvent.DamageCause.FREEZE
    );

    private final OriginRtpQueueService queueService;

    public RtpProtectionListener(
            OriginRtpQueueService queueService
    ) {
        this.queueService = queueService;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !PROTECTED_CAUSES.contains(
                event.getCause()
        )
                || !queueService.hasLandingProtection(
                player
        )) {
            return;
        }

        event.setCancelled(true);
    }
}
