package net.mineacle.core.sell.listener;

import net.mineacle.core.Core;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

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

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        Item entity = event.getItem();
        ItemStack clean = sellService.stripWorthLore(
                entity.getItemStack()
        );

        entity.setItemStack(clean);
        normalizeLater(player, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            normalizeLater(player, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            normalizeLater(player, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            normalizeLater(player, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        normalizeLater(event.getPlayer(), 20L);
    }

    private void normalizeLater(
            Player player,
            long delay
    ) {
        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    if (player.isOnline()) {
                        normalizeInventory(player);
                    }
                },
                delay
        );
    }

    private void normalizeInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        boolean changed = false;

        for (int index = 0; index < contents.length; index++) {
            ItemStack original = contents[index];

            if (!sellService.hasInjectedWorthLore(original)) {
                continue;
            }

            contents[index] =
                    sellService.stripWorthLore(original);
            changed = true;
        }

        if (changed) {
            inventory.setStorageContents(contents);
        }
    }
}
