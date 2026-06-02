package net.mineacle.core.sell.listener;

import net.mineacle.core.Core;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@SuppressWarnings("deprecation")
public final class ItemStackNormalizeListener implements Listener {

    private final Core core;

    public ItemStackNormalizeListener(Core core) {
        this.core = core;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        Item item = event.getItem();
        item.setItemStack(normalize(item.getItemStack()));
        normalizeLater(player, 1L);
        normalizeLater(player, 3L);
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

    private void normalizeLater(Player player, long delay) {
        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            normalizeInventory(player);
        }, delay);
    }

    private void normalizeInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        boolean changed = false;

        for (int index = 0; index < contents.length; index++) {
            ItemStack original = contents[index];
            ItemStack normalized = normalize(original);

            if (!same(original, normalized)) {
                contents[index] = normalized;
                changed = true;
            }
        }

        if (changed) {
            inventory.setStorageContents(contents);
        }
    }

    private boolean same(ItemStack first, ItemStack second) {
        if (first == null && second == null) {
            return true;
        }

        if (first == null || second == null) {
            return false;
        }

        return first.equals(second);
    }

    private ItemStack normalize(ItemStack original) {
        if (original == null || original.getType().isAir()) {
            return original;
        }

        if (!shouldNormalize(original)) {
            return original;
        }

        ItemMeta meta = original.getItemMeta();

        if (meta == null) {
            return original;
        }

        boolean changed = false;
        ItemStack item = original.clone();

        if (meta.hasDisplayName()) {
            meta.setDisplayName(null);
            changed = true;
        }

        if (meta.hasLore()) {
            meta.setLore(null);
            changed = true;
        }

        if (!meta.getItemFlags().isEmpty()) {
            meta.removeItemFlags(ItemFlag.values());
            changed = true;
        }

        if (meta.hasCustomModelData()) {
            meta.setCustomModelData(null);
            changed = true;
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        Set<NamespacedKey> keys = new HashSet<>(container.getKeys());

        for (NamespacedKey key : keys) {
            container.remove(key);
            changed = true;
        }

        if (!changed) {
            return original;
        }

        item.setItemMeta(meta);
        return item;
    }

    private boolean shouldNormalize(ItemStack item) {
        if (item.getMaxStackSize() <= 1) {
            return false;
        }

        Material material = item.getType();
        String name = material.name().toLowerCase(Locale.ROOT);

        /*
         * These stackable items can contain meaningful vanilla data.
         * Do not normalize them by type-only because that would destroy real content.
         */
        return !name.contains("shulker_box")
                && !name.endsWith("_banner")
                && !name.endsWith("_pattern")
                && !name.equals("player_head")
                && !name.equals("filled_map")
                && !name.equals("map")
                && !name.equals("writable_book")
                && !name.equals("written_book")
                && !name.equals("knowledge_book")
                && !name.equals("bundle")
                && !name.equals("suspicious_stew")
                && !name.equals("potion")
                && !name.equals("splash_potion")
                && !name.equals("lingering_potion")
                && !name.equals("tipped_arrow")
                && !name.equals("firework_rocket")
                && !name.equals("firework_star")
                && !name.equals("goat_horn")
                && !name.equals("ominous_bottle")
                && !name.equals("enchanted_book");
    }
}
