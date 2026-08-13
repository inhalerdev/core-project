package net.mineacle.core.sell.listener;

import net.mineacle.core.Core;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

@SuppressWarnings("unused")
public final class ItemStackNormalizeListener
        implements Listener {

    private final Core core;
    private final SellService sellService;

    public ItemStackNormalizeListener(
            Core core,
            SellService sellService
    ) {
        this.core = core;
        this.sellService = sellService;
    }

    /**
     * Old Sell revisions could leave their temporary Worth marker on an item.
     * Clean only the picked-up entity before Bukkit transfers it into a player
     * inventory. This path is O(1) and never scans the recipient inventory.
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPickup(
            EntityPickupItemEvent event
    ) {
        if (!(event.getEntity()
                instanceof Player)) {
            return;
        }

        Item entity =
                event.getItem();
        ItemStack current =
                entity.getItemStack();

        if (sellService.shouldStripWorthLore(
                current
        )) {
            entity.setItemStack(
                    sellService.stripWorthLore(
                            current
                    )
            );
        }
    }

    /**
     * One bounded migration scan per login is enough to clean legacy items
     * saved by versions that predate packet-only Worth rendering. Normal
     * inventory clicks/drags/closes never trigger a full inventory scan.
     */
    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onJoin(
            PlayerJoinEvent event
    ) {
        Player player =
                event.getPlayer();

        core.getServer()
                .getScheduler()
                .runTaskLater(
                        core,
                        () -> {
                            if (player.isOnline()) {
                                normalizeInventory(
                                        player
                                );
                            }
                        },
                        20L
                );
    }

    private void normalizeInventory(
            Player player
    ) {
        PlayerInventory inventory =
                player.getInventory();
        ItemStack[] contents =
                inventory.getContents();
        boolean changed =
                false;

        for (int index = 0;
             index < contents.length;
             index++) {
            ItemStack original =
                    contents[index];

            if (sellService.shouldStripWorthLore(
                    original
            )) {
                contents[index] =
                        sellService.stripWorthLore(
                                original
                        );
                changed = true;
            }
        }

        if (changed) {
            inventory.setContents(
                    contents
            );
        }
    }
}
