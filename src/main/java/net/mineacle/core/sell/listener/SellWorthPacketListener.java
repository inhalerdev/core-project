package net.mineacle.core.sell.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.gui.SellGui;
import net.mineacle.core.sell.gui.WorthGui;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@SuppressWarnings({"rawtypes", "unchecked"})
public final class SellWorthPacketListener
        extends PacketAdapter {

    /**
     * /worth item entries occupy slots 0-44.
     *
     * Toolbar and navigation controls occupy slots 45-53 and must never
     * receive Worth, Value, Unit Price, or Stack Price lore.
     */
    private static final int WORTH_CONTENT_SLOTS = 45;

    private final SellService sellService;

    public SellWorthPacketListener(
            Core core,
            SellService sellService
    ) {
        super(
                core,
                ListenerPriority.NORMAL,
                PacketType.Play.Server.SET_SLOT,
                PacketType.Play.Server.WINDOW_ITEMS
        );
        this.sellService = sellService;
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        Player player = event.getPlayer();

        if (player == null || unsafeMode(player)) {
            return;
        }

        if (event.getPacketType()
                == PacketType.Play.Server.SET_SLOT) {
            handleSetSlot(event, player);
            return;
        }

        if (event.getPacketType()
                == PacketType.Play.Server.WINDOW_ITEMS) {
            handleWindowItems(event, player);
        }
    }

    private void handleSetSlot(
            PacketEvent event,
            Player player
    ) {
        StructureModifier<ItemStack> modifier =
                event.getPacket().getItemModifier();
        int rawSlot = setSlotRawSlot(event);

        for (int index = 0;
             index < modifier.size();
             index++) {
            ItemStack item = modifier.readSafely(index);

            if (item == null || item.getType().isAir()) {
                continue;
            }

            modifier.writeSafely(
                    index,
                    displayItem(
                            player,
                            item,
                            rawSlot
                    )
            );
        }
    }

    private void handleWindowItems(
            PacketEvent event,
            Player player
    ) {
        StructureModifier<List<ItemStack>> listModifier =
                event.getPacket().getItemListModifier();

        for (int index = 0;
             index < listModifier.size();
             index++) {
            List<ItemStack> original =
                    listModifier.readSafely(index);

            if (original == null || original.isEmpty()) {
                continue;
            }

            List<ItemStack> updated =
                    new ArrayList<>(original.size());

            for (int rawSlot = 0;
                 rawSlot < original.size();
                 rawSlot++) {
                ItemStack item = original.get(rawSlot);

                updated.add(
                        item == null || item.getType().isAir()
                                ? item
                                : displayItem(
                                player,
                                item,
                                rawSlot
                        )
                );
            }

            listModifier.writeSafely(index, updated);
        }

        StructureModifier<ItemStack[]> arrayModifier =
                event.getPacket().getItemArrayModifier();

        for (int index = 0;
             index < arrayModifier.size();
             index++) {
            ItemStack[] original =
                    arrayModifier.readSafely(index);

            if (original == null || original.length == 0) {
                continue;
            }

            ItemStack[] updated =
                    new ItemStack[original.length];

            for (int rawSlot = 0;
                 rawSlot < original.length;
                 rawSlot++) {
                ItemStack item = original[rawSlot];

                updated[rawSlot] =
                        item == null || item.getType().isAir()
                                ? item
                                : displayItem(
                                player,
                                item,
                                rawSlot
                        );
            }

            arrayModifier.writeSafely(index, updated);
        }
    }

    private ItemStack displayItem(
            Player player,
            ItemStack original,
            int rawSlot
    ) {
        /*
         * Always strip stale Mineacle price lore first. This ensures toolbar
         * controls remain clean even if the client previously received a
         * priced copy of the same material.
         */
        ItemStack clean =
                sellService.stripWorthLore(original);

        if (!shouldShowWorth(player, clean, rawSlot)) {
            return clean;
        }

        long stackWorth = sellService.visualWorthCents(
                player.getUniqueId(),
                clean
        );

        if (stackWorth <= 0L) {
            return clean;
        }

        long unitWorth =
                clean.getAmount() > 1
                        ? sellService.visualUnitWorthCents(
                        player.getUniqueId(),
                        clean
                )
                        : stackWorth;

        ItemStack item = clean.clone();
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        List<String> lore = meta.hasLore()
                && meta.getLore() != null
                ? new ArrayList<>(meta.getLore())
                : new ArrayList<>();

        if (clean.getAmount() > 1
                && shouldShowStackPrice(player, rawSlot)) {
            lore.add(
                    0,
                    TextColor.color(
                            "&#bbbbbbStack Price: &a"
                                    + sellService.format(
                                    stackWorth
                            )
                    )
            );
        }

        lore.add(
                0,
                TextColor.color(
                        "&#bbbbbbWorth: &a"
                                + sellService.format(unitWorth)
                )
        );

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private boolean shouldShowWorth(
            Player player,
            ItemStack item,
            int rawSlot
    ) {
        if (item == null
                || item.getType().isAir()
                || item.getType()
                == Material.BLACK_STAINED_GLASS_PANE) {
            return false;
        }

        InventoryView view = player.getOpenInventory();

        if (view == null) {
            return true;
        }

        Inventory top = view.getTopInventory();

        if (top == null) {
            return true;
        }

        boolean topSlot = rawSlot >= 0
                && rawSlot < top.getSize();

        /*
         * Player-inventory slots may still display their normal item worth.
         * The restrictions below apply only to the open GUI's top inventory.
         */
        if (!topSlot) {
            return true;
        }

        /*
         * /worth is the only Mineacle GUI allowed to show price lore, and
         * only its actual catalog entries in slots 0-44 may show it.
         *
         * Slots 45-53 are toolbar/navigation controls and are always clean.
         */
        if (WorthGui.isInventory(top)) {
            return rawSlot < WORTH_CONTENT_SLOTS;
        }

        /*
         * The Sell GUI keeps deposited items visually clean. Its summary
         * control is the single source of the pending payout total.
         */
        if (SellGui.isInventory(top)) {
            return false;
        }

        /*
         * Every other MineacleCore GUI is a control/workflow interface.
         * No top-inventory item in those menus may receive automatic value
         * lore, regardless of its material.
         */
        if (isMineacleCoreGui(top)) {
            return false;
        }

        /*
         * PhoenixCrates reward entries intentionally retain unit value
         * display. Stack-price duplication remains disabled there.
         */
        if (isPhoenixCrateRewardsMenu(view)) {
            return true;
        }

        /*
         * Outside Mineacle GUI menus, automatic worth lore is limited to
         * real storage inventories such as chests, barrels and shulkers.
         */
        return isRealStorageTop(top);
    }

    private boolean shouldShowStackPrice(
            Player player,
            int rawSlot
    ) {
        InventoryView view = player.getOpenInventory();

        if (view == null) {
            return true;
        }

        Inventory top = view.getTopInventory();

        return top == null
                || rawSlot < 0
                || rawSlot >= top.getSize()
                || !isPhoenixCrateRewardsMenu(view);
    }

    private boolean isMineacleCoreGui(Inventory inventory) {
        if (inventory == null) {
            return false;
        }

        InventoryHolder holder =
                inventory.getHolder(false);

        if (holder == null) {
            /*
             * Legacy Mineacle menus with null holders already fall through
             * to false because they are not real storage inventories.
             */
            return false;
        }

        Package holderPackage =
                holder.getClass().getPackage();
        String packageName = holderPackage == null
                ? holder.getClass().getName()
                : holderPackage.getName();

        return packageName.equals("net.mineacle.core")
                || packageName.startsWith(
                "net.mineacle.core."
        );
    }

    private boolean isPhoenixCrateRewardsMenu(
            InventoryView view
    ) {
        Inventory top = view.getTopInventory();

        if (top == null || isRealStorageTop(top)) {
            return false;
        }

        String title = ChatColor.stripColor(
                view.getTitle()
        );
        String lowerTitle = title == null
                ? ""
                : title.toLowerCase(Locale.ROOT);
        InventoryHolder holder =
                top.getHolder(false);
        String holderName = holder == null
                ? ""
                : holder.getClass()
                .getName()
                .toLowerCase(Locale.ROOT);

        return holderName.contains("phoenix")
                || holderName.contains("pcrates")
                || holderName.contains("crate")
                || lowerTitle.contains("crate reward")
                || lowerTitle.contains("crate preview")
                || lowerTitle.contains("rewards")
                || lowerTitle.contains("preview");
    }

    private boolean isRealStorageTop(
            Inventory inventory
    ) {
        InventoryType type = inventory.getType();

        if (type == InventoryType.ENDER_CHEST) {
            return true;
        }

        InventoryHolder holder =
                inventory.getHolder(false);

        if (holder instanceof BlockInventoryHolder
                || holder instanceof DoubleChest
                || holder instanceof StorageMinecart) {
            return type == InventoryType.CHEST
                    || type == InventoryType.BARREL
                    || type == InventoryType.SHULKER_BOX
                    || type == InventoryType.HOPPER
                    || type == InventoryType.DROPPER
                    || type == InventoryType.DISPENSER
                    || type == InventoryType.ENDER_CHEST;
        }

        return false;
    }

    private int setSlotRawSlot(PacketEvent event) {
        StructureModifier<Integer> integers =
                event.getPacket().getIntegers();

        for (int index = integers.size() - 1;
             index >= 0;
             index--) {
            Integer value = integers.readSafely(index);

            if (value != null) {
                return value;
            }
        }

        return -1;
    }

    private boolean unsafeMode(Player player) {
        return player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode()
                == GameMode.SPECTATOR;
    }
}
