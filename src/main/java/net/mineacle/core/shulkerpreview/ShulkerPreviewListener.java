package net.mineacle.core.shulkerpreview;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;

public final class ShulkerPreviewListener implements Listener {

    private final Core core;

    public ShulkerPreviewListener(Core core) {
        this.core = core;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PreviewHolder) {
            event.setCancelled(true);
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!enabled()) {
            return;
        }

        if (isBlockedView(event.getView())) {
            return;
        }

        if (event.getClick() != ClickType.RIGHT && event.getClick() != ClickType.SHIFT_RIGHT) {
            return;
        }

        Inventory clickedInventory = event.getClickedInventory();

        if (clickedInventory == null || !isAllowedStorage(clickedInventory)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();

        if (!isShulkerBox(clicked)) {
            return;
        }

        if (!canUse(player)) {
            player.sendActionBar(legacy(message("no-permission", "&cThis is a Mineacle+ feature")));
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        openPreview(player, clicked);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PreviewHolder) {
            event.setCancelled(true);
        }
    }

    private void openPreview(Player player, ItemStack shulkerItem) {
        ItemMeta meta = shulkerItem.getItemMeta();

        if (!(meta instanceof BlockStateMeta blockStateMeta)) {
            return;
        }

        if (!(blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return;
        }

        PreviewHolder holder = new PreviewHolder();
        Inventory preview = Bukkit.createInventory(holder, 27, legacy(title(shulkerItem)));
        holder.inventory = preview;

        ItemStack[] contents = shulkerBox.getInventory().getContents();

        for (int slot = 0; slot < Math.min(contents.length, preview.getSize()); slot++) {
            ItemStack item = contents[slot];

            if (item == null || item.getType().isAir()) {
                continue;
            }

            preview.setItem(slot, item.clone());
        }

        player.openInventory(preview);
    }

    private String title(ItemStack shulkerItem) {
        String configured = core.getConfig().getString("shulker-preview.gui-title", "Shulker Box");

        if (configured == null || configured.isBlank()) {
            configured = "Shulker Box";
        }

        return configured;
    }

    private boolean enabled() {
        return core.getConfig().getBoolean("shulker-preview.enabled", true);
    }

    private boolean canUse(Player player) {
        FileConfiguration config = core.getConfig();

        if (config.getBoolean("shulker-preview.allow-default", false)) {
            return true;
        }

        String plusPermission = config.getString("shulker-preview.plus-permission", "mineacle.plus");
        String permission = config.getString("shulker-preview.permission", "mineacleshulkerpreview.use");

        return player.hasPermission(plusPermission) || player.hasPermission(permission);
    }

    private boolean isAllowedStorage(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();

        if (holder instanceof Player) {
            return true;
        }

        if (holder instanceof BlockState) {
            return true;
        }

        if (holder instanceof DoubleChest) {
            return true;
        }

        InventoryType type = inventory.getType();

        return type == InventoryType.PLAYER
                || type == InventoryType.CHEST
                || type == InventoryType.ENDER_CHEST
                || type == InventoryType.SHULKER_BOX
                || type == InventoryType.BARREL
                || type == InventoryType.HOPPER
                || type == InventoryType.DISPENSER
                || type == InventoryType.DROPPER
                || type == InventoryType.FURNACE
                || type == InventoryType.BLAST_FURNACE
                || type == InventoryType.SMOKER;
    }

    private boolean isBlockedView(InventoryView view) {
        String title = ChatColor.stripColor(view.getTitle());

        if (title == null) {
            return false;
        }

        String lowerTitle = title.toLowerCase(Locale.ROOT);
        List<String> blocked = core.getConfig().getStringList("shulker-preview.blocked-title-contains");

        for (String entry : blocked) {
            if (entry == null || entry.isBlank()) {
                continue;
            }

            if (lowerTitle.contains(entry.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    private boolean isShulkerBox(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }

        return item.getType().name().endsWith("SHULKER_BOX") && item.getItemMeta() instanceof BlockStateMeta;
    }

    private String message(String key, String fallback) {
        return core.getConfig().getString("shulker-preview.messages." + key, fallback);
    }

    private Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(text == null ? "" : text));
    }

    private static final class PreviewHolder implements InventoryHolder {

        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
