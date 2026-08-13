package net.mineacle.core.sell.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.gui.SellGui;
import net.mineacle.core.sell.gui.WorthGui;
import net.mineacle.core.sell.model.ItemValuation;
import net.mineacle.core.sell.service.SellService;
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

public final class SellWorthPacketListener
        extends PacketAdapter {

    /**
     * /worth item entries occupy slots 0-44.
     * Toolbar/navigation controls occupy slots 45-53.
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
            ItemStack item =
                    modifier.readSafely(index);

            if (item == null
                    || item.getType().isAir()) {
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
                event.getPacket()
                        .getItemListModifier();

        for (int index = 0;
             index < listModifier.size();
             index++) {
            List<ItemStack> original =
                    listModifier.readSafely(index);

            if (original == null
                    || original.isEmpty()) {
                continue;
            }

            List<ItemStack> updated =
                    new ArrayList<>(
                            original.size()
                    );

            for (int rawSlot = 0;
                 rawSlot < original.size();
                 rawSlot++) {
                ItemStack item =
                        original.get(rawSlot);

                updated.add(
                        item == null
                                || item.getType().isAir()
                                ? item
                                : displayItem(
                                        player,
                                        item,
                                        rawSlot
                                )
                );
            }

            listModifier.writeSafely(
                    index,
                    updated
            );
        }

        StructureModifier<ItemStack[]> arrayModifier =
                event.getPacket()
                        .getItemArrayModifier();

        for (int index = 0;
             index < arrayModifier.size();
             index++) {
            ItemStack[] original =
                    arrayModifier.readSafely(index);

            if (original == null
                    || original.length == 0) {
                continue;
            }

            ItemStack[] updated =
                    new ItemStack[
                            original.length
                    ];

            for (int rawSlot = 0;
                 rawSlot < original.length;
                 rawSlot++) {
                ItemStack item =
                        original[rawSlot];

                updated[rawSlot] =
                        item == null
                                || item.getType().isAir()
                                ? item
                                : displayItem(
                                        player,
                                        item,
                                        rawSlot
                                );
            }

            arrayModifier.writeSafely(
                    index,
                    updated
            );
        }
    }

    private ItemStack displayItem(
            Player player,
            ItemStack original,
            int rawSlot
    ) {
        /*
         * /worth authors its own catalog lore. Never rebuild those entries
         * through the generic packet overlay.
         */
        if (isWorthCatalogSlot(
                player,
                rawSlot
        )) {
            return original.clone();
        }

        /*
         * Always strip a stale Mineacle packet-copy marker first. The server
         * inventory remains authoritative; this operates on display copies.
         */
        ItemStack clean =
                sellService.stripWorthLore(
                        original
                );

        if (!shouldShowWorth(
                player,
                clean,
                rawSlot
        )) {
            return clean;
        }

        ItemValuation valuation =
                sellService.appraise(
                        player,
                        clean
                );

        if (!valuation.priced()) {
            return clean;
        }

        ItemStack item = clean.clone();
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        List<Component> existingLore =
                meta.lore();
        List<Component> lore =
                existingLore == null
                        ? new ArrayList<>()
                        : new ArrayList<>(
                                existingLore
                        );

        lore.addFirst(
                component(
                        valuation.sellable()
                                ? "&#bbbbbbWorth: "
                                + "&#11fc7b"
                                + sellService.format(
                                        valuation
                                                .serverSellCents()
                                )
                                : "&cPlayer Market Only"
                )
        );

        meta.lore(lore);
        sellService.markInjectedWorthLore(
                meta,
                1
        );
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWorthCatalogSlot(
            Player player,
            int rawSlot
    ) {
        InventoryView view =
                player.getOpenInventory();

        Inventory top =
                view.getTopInventory();

        return WorthGui.isInventory(top)
                && rawSlot >= 0
                && rawSlot < WORTH_CONTENT_SLOTS;
    }

    /**
     * Display policy is intentionally allowlist-based.
     *
     * <p>Allowed:</p>
     * <ul>
     *     <li>/worth catalog content</li>
     *     <li>the player's normal inventory screen</li>
     *     <li>real vanilla storage inventories</li>
     * </ul>
     *
     * <p>Everything else is denied, including every Mineacle workflow GUI
     * and every external-plugin GUI. We do not guess safety from a title or
     * another plugin's holder class.</p>
     */
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

        InventoryView view =
                player.getOpenInventory();

        Inventory top =
                view.getTopInventory();

        if (WorthGui.isInventory(top)) {
            return rawSlot >= 0
                    && rawSlot
                    < WORTH_CONTENT_SLOTS;
        }

        /*
         * Deposited Sell items remain visually clean. The Sell GUI summary
         * is the only pending-payout display.
         */
        if (SellGui.isInventory(top)) {
            return false;
        }

        /*
         * Player inventory screen. Packet-only display is allowed here.
         */
        if (top.getType()
                == InventoryType.CRAFTING) {
            return true;
        }

        /*
         * Real physical vanilla storage is explicitly allowed. This also
         * permits the player's bottom inventory while that storage is open.
         */
        /*
         * Default deny:
         * anvils, enchanting, smithing, merchant/trading, furnaces,
         * Mineacle menus, PhoenixCrates, and every other plugin GUI receive
         * no automatic Worth line.
         */
        return isRealStorageTop(top);
    }

    private boolean isRealStorageTop(
            Inventory inventory
    ) {
        InventoryType type =
                inventory.getType();

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
                    || type == InventoryType.DISPENSER;
        }

        return false;
    }

    private Component component(
            String text
    ) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(text)
                );
    }

    private int setSlotRawSlot(
            PacketEvent event
    ) {
        StructureModifier<Integer> integers =
                event.getPacket()
                        .getIntegers();

        for (int index =
             integers.size() - 1;
             index >= 0;
             index--) {
            Integer value =
                    integers.readSafely(index);

            if (value != null) {
                return value;
            }
        }

        return -1;
    }

    private boolean unsafeMode(Player player) {
        return player.getGameMode()
                == GameMode.CREATIVE
                || player.getGameMode()
                == GameMode.SPECTATOR;
    }
}
