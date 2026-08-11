package net.mineacle.core.shulkerpreview;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.sound.SoundService;
import org.bukkit.Bukkit;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.file.FileConfiguration;
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

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ShulkerPreviewListener implements Listener {

    private static final int SHULKER_SIZE = 27;
    private static final String DEFAULT_GUI_TITLE =
            "Shulker Box";
    private static final String DEFAULT_NO_PERMISSION =
            "&cThis is a Mineacle+ feature";

    private final Core core;

    public ShulkerPreviewListener(Core core) {
        this.core = core;
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.HIGHEST
    )
    public void onInventoryClick(
            InventoryClickEvent event
    ) {
        /*
         * A preview is a completely frozen view. Cancelling every inventory
         * click also freezes the player's bottom inventory while the preview
         * is open, covering shift-click, number keys, Q/Ctrl-Q, offhand swap,
         * double-click collection and creative inventory click subclasses.
         */
        if (isPreview(event.getView())) {
            deny(event);
            return;
        }

        if (!(event.getWhoClicked()
                instanceof Player player)
                || !enabled()
                || event.isCancelled()
                || !event.isRightClick()
                || !empty(event.getCursor())
                || !isAllowedView(
                player,
                event.getView()
        )) {
            return;
        }

        Inventory clickedInventory =
                event.getClickedInventory();

        if (clickedInventory == null
                || !isAllowedInventory(
                player,
                clickedInventory
        )
                || isBlockedView(
                event.getView()
        )) {
            return;
        }

        ItemStack clicked =
                event.getCurrentItem();

        if (!isShulkerBox(clicked)) {
            return;
        }

        if (!canUse(player)) {
            deny(event);
            player.sendActionBar(
                    GuiText.component(
                            noPermissionMessage()
                    )
            );
            SoundService.mineaclePlus(
                    player,
                    core
            );
            return;
        }

        PreviewSnapshot snapshot =
                snapshot(clicked);

        if (snapshot == null) {
            return;
        }

        /*
         * Capture the exact real source view before leaving this event. The
         * next-tick task will only open if the player is still looking at this
         * exact inventory and still has an empty cursor. This prevents rapid
         * repeated right-clicks or another plugin changing the view between
         * the cancelled click and the scheduled preview open.
         */
        Inventory expectedTopInventory =
                event.getView()
                        .getTopInventory();

        deny(event);

        Bukkit.getScheduler().runTask(
                core,
                () -> openPreview(
                        player,
                        snapshot,
                        expectedTopInventory
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

    /**
     * Defensive fallbacks for clients/plugins that surface these actions as
     * player events in addition to inventory click semantics. Normal Paper
     * inventory handling is already frozen by onInventoryClick.
     */
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
            Inventory expectedTopInventory
    ) {
        if (!player.isOnline()
                || !core.isEnabled()) {
            return;
        }

        InventoryView currentView =
                player.getOpenInventory();

        if (currentView.getTopInventory()
                != expectedTopInventory
                || !empty(
                currentView.getCursor()
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

            if (empty(item)) {
                continue;
            }

            /*
             * Snapshot data is already detached from the real shulker. Clone
             * once more at the GUI boundary so the displayed inventory never
             * shares an ItemStack object with the captured snapshot.
             */
            preview.setItem(
                    slot,
                    item.clone()
            );
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

                if (!empty(item)) {
                    copy[slot] =
                            item.clone();
                }
            }

            return new PreviewSnapshot(
                    copy
            );
        } catch (RuntimeException ignored) {
            /*
             * Malformed or incompatible block-state item data must never
             * escape the inventory event or produce a partial preview. The
             * original click remains untouched because cancellation happens
             * only after a valid snapshot has been created.
             */
            return null;
        }
    }

    /**
     * The current view must itself be a real player/container view.
     * <p>
     * This blocks right-click preview hijacking while Auction House, Orders,
     * Bounty, crates, or any other virtual plugin inventory is open, even
     * when the player clicks a shulker in their own bottom inventory.
     */
    private boolean isAllowedView(
            Player player,
            InventoryView view
    ) {
        return isAllowedInventory(
                player,
                view.getTopInventory()
        );
    }

    /**
     * Only inventories with a real Minecraft owner are preview sources.
     * Custom/virtual Bukkit inventories generally have a null or custom
     * holder and are rejected without relying only on a title blacklist.
     */
    private boolean isAllowedInventory(
            Player player,
            Inventory inventory
    ) {
        if (inventory == player.getInventory()
                || inventory
                == player.getEnderChest()) {
            return true;
        }

        InventoryHolder holder =
                inventory.getHolder();

        if (holder
                instanceof Player owner) {
            return owner.getUniqueId()
                    .equals(
                            player.getUniqueId()
                    );
        }

        return holder instanceof Container
                || holder instanceof DoubleChest;
    }

    private boolean isBlockedView(
            InventoryView view
    ) {
        String title =
                GuiText.plain(
                                view.title()
                        )
                        .toLowerCase(
                                Locale.ROOT
                        );
        List<String> blocked =
                core.getConfig()
                        .getStringList(
                                "shulker-preview."
                                        + "blocked-title-contains"
                        );

        for (String entry : blocked) {
            if (entry == null
                    || entry.isBlank()) {
                continue;
            }

            if (title.contains(
                    entry.trim()
                            .toLowerCase(
                                    Locale.ROOT
                            )
            )) {
                return true;
            }
        }

        return false;
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
        return !empty(item)
                && item.getType()
                .name()
                .endsWith(
                        "SHULKER_BOX"
                )
                && item.getItemMeta()
                instanceof BlockStateMeta;
    }

    private boolean enabled() {
        return core.getConfig()
                .getBoolean(
                        "shulker-preview.enabled",
                        true
                );
    }

    private boolean canUse(
            Player player
    ) {
        FileConfiguration config =
                core.getConfig();

        if (config.getBoolean(
                "shulker-preview.allow-default",
                false
        )) {
            return true;
        }

        String plusPermission =
                config.getString(
                        "shulker-preview.plus-permission",
                        "mineacle.plus"
                );
        String permission =
                config.getString(
                        "shulker-preview.permission",
                        "mineacleshulkerpreview.use"
                );

        return hasPermission(
                player,
                plusPermission
        )
                || hasPermission(
                player,
                permission
        );
    }

    private boolean hasPermission(
            Player player,
            String permission
    ) {
        return permission != null
                && !permission.isBlank()
                && player.hasPermission(
                permission
        );
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

    private String noPermissionMessage() {
        String configured =
                core.getConfig()
                        .getString(
                                "shulker-preview.messages.no-permission",
                                DEFAULT_NO_PERMISSION
                        );

        return configured.isBlank()
                ? DEFAULT_NO_PERMISSION
                : stripTrailingPeriod(
                        configured
                );
    }

    private String stripTrailingPeriod(
            String input
    ) {
        String output = input;

        while (output.endsWith(".")) {
            output = output.substring(
                    0,
                    output.length() - 1
            );
        }

        return output;
    }

    private boolean empty(
            ItemStack item
    ) {
        return item == null
                || item.getType()
                .isAir();
    }

    private void deny(
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
