package net.mineacle.core.shulkerpreview;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.gui.AuctionHouseGui;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.gui.MenuHistory;
import org.bukkit.Bukkit;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class ShulkerPreviewListener implements Listener {

    private static final int SHULKER_SIZE = 27;
    private static final String DEFAULT_GUI_TITLE =
            "Shulker Box";

    private final Core core;

    public ShulkerPreviewListener(Core core) {
        this.core = core;
    }

    /**
     * Right-click preview is a global Mineacle interaction.
     * It is intentionally not permission-gated and it accepts already-cancelled
     * inventory clicks so shulkers can be previewed from normal inventories and
     * from virtual/plugin GUIs. Cancelling at LOWEST also gives every later GUI
     * listener a clear signal that this right-click belongs to preview.
     */
    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.LOWEST
    )
    public void onInventoryClick(
            InventoryClickEvent event
    ) {
        if (isPreview(event.getView())) {
            deny(event);
            return;
        }

        if (!(event.getWhoClicked()
                instanceof Player player)
                || !event.isRightClick()
                || hasItem(event.getCursor())
                || event.getClickedInventory()
                == null) {
            return;
        }

        /*
         * Auction transaction-history rows are display snapshots, not the real
         * sold/bought ItemStack. A shulker material used there must not open an
         * empty fake preview. Real shulkers in the player's bottom inventory
         * still preview while this GUI is open.
         */
        if (event.getClickedInventory()
                == event.getView()
                .getTopInventory()
                && event.getView()
                .getTopInventory()
                .getHolder()
                instanceof AuctionHouseGui.HistoryHolder) {
            return;
        }

        ItemStack clicked =
                event.getCurrentItem();

        if (!isShulkerBox(clicked)) {
            return;
        }

        PreviewSnapshot snapshot =
                snapshot(clicked);

        if (snapshot == null) {
            return;
        }

        Inventory sourceTop =
                event.getView()
                        .getTopInventory();

        deny(event);

        Bukkit.getScheduler().runTask(
                core,
                () -> openPreview(
                        player,
                        snapshot,
                        sourceTop
                )
        );
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.HIGHEST
    )
    public void onInventoryDrag(
            InventoryDragEvent event
    ) {
        if (!isPreview(event.getView())) {
            return;
        }

        event.setCancelled(true);
        event.setResult(
                Event.Result.DENY
        );
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPlayerDropItem(
            PlayerDropItemEvent event
    ) {
        if (isPreview(
                event.getPlayer()
                        .getOpenInventory()
        )) {
            event.setCancelled(true);
        }
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPlayerSwapHandItems(
            PlayerSwapHandItemsEvent event
    ) {
        if (isPreview(
                event.getPlayer()
                        .getOpenInventory()
        )) {
            event.setCancelled(true);
        }
    }

    private void openPreview(
            Player player,
            PreviewSnapshot snapshot,
            Inventory sourceTop
    ) {
        if (!player.isOnline()
                || !core.isEnabled()
                || hasItem(
                player.getOpenInventory()
                        .getCursor()
        )) {
            return;
        }

        PreviewHolder holder =
                new PreviewHolder();
        Inventory preview =
                Bukkit.createInventory(
                        holder,
                        SHULKER_SIZE,
                        GuiText.title(
                                guiTitle()
                        )
                );
        holder.attach(preview);

        ItemStack[] contents =
                snapshot.contents();

        for (int slot = 0;
             slot < Math.min(
                     contents.length,
                     SHULKER_SIZE
             );
             slot++) {
            ItemStack item =
                    contents[slot];

            if (hasItem(item)) {
                preview.setItem(
                        slot,
                        item.clone()
                );
            }
        }

        if (shouldRestoreSource(
                sourceTop
        )) {
            MenuHistory.openChild(
                    core,
                    player,
                    () -> player.openInventory(
                            sourceTop
                    ),
                    () -> player.openInventory(
                            preview
                    )
            );
            return;
        }

        player.openInventory(preview);
    }

    private PreviewSnapshot snapshot(
            ItemStack shulkerItem
    ) {
        try {
            ItemMeta meta =
                    shulkerItem.getItemMeta();

            if (!(meta
                    instanceof BlockStateMeta blockStateMeta)
                    || !(blockStateMeta
                    .getBlockState()
                    instanceof ShulkerBox shulkerBox)) {
                return null;
            }

            ItemStack[] source =
                    shulkerBox
                            .getSnapshotInventory()
                            .getContents();
            ItemStack[] copy =
                    new ItemStack[SHULKER_SIZE];

            for (int slot = 0;
                 slot < Math.min(
                         source.length,
                         SHULKER_SIZE
                 );
                 slot++) {
                ItemStack item =
                        source[slot];

                if (hasItem(item)) {
                    copy[slot] =
                            item.clone();
                }
            }

            return new PreviewSnapshot(
                    copy
            );
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Virtual GUIs get an ESC/back path to the exact inventory instance they
     * came from. Normal block/player inventories simply close the preview.
     */
    private boolean shouldRestoreSource(
            Inventory inventory
    ) {
        if (inventory == null) {
            return false;
        }

        InventoryHolder holder =
                inventory.getHolder();

        return holder != null
                && !(holder instanceof Player)
                && !(holder instanceof Container)
                && !(holder instanceof DoubleChest)
                && !(holder instanceof PreviewHolder);
    }

    private boolean isPreview(
            InventoryView view
    ) {
        return view.getTopInventory()
                .getHolder()
                instanceof PreviewHolder;
    }

    private boolean isShulkerBox(
            ItemStack item
    ) {
        return hasItem(item)
                && item.getType()
                .name()
                .endsWith(
                        "SHULKER_BOX"
                )
                && item.getItemMeta()
                instanceof BlockStateMeta;
    }

    private String guiTitle() {
        String configured =
                core.getConfig()
                        .getString(
                                "shulker-preview.gui-title",
                                DEFAULT_GUI_TITLE
                        );

        return configured.isBlank()
                ? DEFAULT_GUI_TITLE
                : configured;
    }

    private static boolean hasItem(
            ItemStack item
    ) {
        return item != null
                && !item.getType()
                .isAir();
    }

    private static void deny(
            InventoryClickEvent event
    ) {
        event.setCancelled(true);
        event.setResult(
                Event.Result.DENY
        );
    }

    private record PreviewSnapshot(
            ItemStack[] contents
    ) {
    }

    private static final class PreviewHolder
            implements InventoryHolder {

        private Inventory inventory;

        private void attach(
                Inventory inventory
        ) {
            this.inventory =
                    Objects.requireNonNull(
                            inventory,
                            "inventory"
                    );
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Objects.requireNonNull(
                    inventory,
                    "Preview inventory is not attached"
            );
        }
    }
}
