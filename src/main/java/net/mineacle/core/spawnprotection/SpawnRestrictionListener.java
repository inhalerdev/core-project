package net.mineacle.core.spawnprotection.listener;

import net.mineacle.core.Core;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

public final class SpawnRestrictionListener implements Listener {

    private final Core core;

    public SpawnRestrictionListener(Core core) {
        this.core = core;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        Block block = event.getClickedBlock();

        if (block == null) {
            return;
        }

        if (!enabled() || !isWorldProtected(player.getWorld().getName())) {
            return;
        }

        if (!isConfiguredMaterial("spawn-restrictions.blocked-interactions", block.getType())) {
            return;
        }

        event.setCancelled(true);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        if (!enabled() || !isWorldProtected(player.getWorld().getName())) {
            return;
        }

        if (!isConfiguredMaterial("spawn-restrictions.blocked-placement", event.getBlockPlaced().getType())) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();

        if (!enabled() || !isWorldProtected(player.getWorld().getName())) {
            return;
        }

        if (!isConfiguredMaterial("spawn-restrictions.blocked-breaking", event.getBlock().getType())) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        Entity entity = event.getEntity();

        if (!(entity instanceof Player player)) {
            return;
        }

        if (!enabled() || !isWorldProtected(player.getWorld().getName())) {
            return;
        }

        PotionEffect newEffect = event.getNewEffect();

        if (newEffect == null || newEffect.getType() != PotionEffectType.WITHER) {
            return;
        }

        if (!core.getConfig().getBoolean("spawn-restrictions.prevent-wither-rose-effects", true)) {
            return;
        }

        event.setCancelled(true);
    }

    private boolean enabled() {
        return core.getConfig().getBoolean("spawn-restrictions.enabled", true);
    }

    private boolean isWorldProtected(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }

        for (String world : core.getConfig().getStringList("spawn-restrictions.worlds")) {
            if (world.equalsIgnoreCase(worldName)) {
                return true;
            }
        }

        return false;
    }

    private boolean isConfiguredMaterial(String path, Material material) {
        for (String raw : core.getConfig().getStringList(path)) {
            if (raw == null || raw.isBlank()) {
                continue;
            }

            try {
                Material configured = Material.valueOf(raw.trim().toUpperCase(Locale.ROOT));

                if (configured == material) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                core.getLogger().warning("Invalid material in " + path + ": " + raw);
            }
        }

        return false;
    }
}
