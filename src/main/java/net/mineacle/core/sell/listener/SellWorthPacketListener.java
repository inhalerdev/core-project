package net.mineacle.core.sell.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import net.kyori.adventure.text.Component;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
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

        DisplayContext context = displayContext(player);

        /*
         * Default-deny before touching packet items. External/plugin GUIs and
         * Mineacle workflow menus should cost essentially nothing here.
         */
        if (context == DisplayContext.DENIED
                || context == DisplayContext.WORTH) {
            return;
        }

        if (event.getPacketType()
                == PacketType.Play.Server.SET_SLOT) {
            handleSetSlot(
                    event,
                    player,
                    context
            );
            return;
        }

        if (event.getPacketType()
                == PacketType.Play.Server.WINDOW_ITEMS) {
            handleWindowItems(
                    event,
                    player,
                    context
            );
        }
    }

    private void handleSetSlot(
            PacketEvent event,
            Player player,
            DisplayContext context
    ) {
        StructureModifier<ItemStack> modifier =
                event.getPacket().getItemModifier();
        int rawSlot = setSlotRawSlot(event);

        for (int index = 0;
             index < modifier.size();
             index++) {
            ItemStack original =
                    modifier.readSafely(index);

            if (original == null
                    || original.getType().isAir()) {
                continue;
            }

            ItemStack displayed =
                    displayItem(
                            player,
                            original,
                            rawSlot,
                            context
                    );

            if (displayed != original) {
                modifier.writeSafely(
                        index,
                        displayed
                );
            }
        }
    }

    private void handleWindowItems(
            PacketEvent event,
            Player player,
            DisplayContext context
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

            List<ItemStack> updated = null;

            for (int rawSlot = 0;
                 rawSlot < original.size();
                 rawSlot++) {
                ItemStack source =
                        original.get(rawSlot);

                if (source == null
                        || source.getType().isAir()) {
                    continue;
                }

                ItemStack displayed =
                        displayItem(
                                player,
                                source,
                                rawSlot,
                                context
                        );

                if (displayed == source) {
                    continue;
                }

                if (updated == null) {
                    updated =
                            new ArrayList<>(original);
                }

                updated.set(
                        rawSlot,
                        displayed
                );
            }

            if (updated != null) {
                listModifier.writeSafely(
                        index,
                        updated
                );
            }
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

            ItemStack[] updated = null;

            for (int rawSlot = 0;
                 rawSlot < original.length;
                 rawSlot++) {
                ItemStack source =
                        original[rawSlot];

                if (source == null
                        || source.getType().isAir()) {
                    continue;
                }

                ItemStack displayed =
                        displayItem(
                                player,
                                source,
                                rawSlot,
                                context
                        );

                if (displayed == source) {
                    continue;
                }

                if (updated == null) {
                    updated = original.clone();
                }

                updated[rawSlot] = displayed;
            }

            if (updated != null) {
                arrayModifier.writeSafely(
                        index,
                        updated
                );
            }
        }
    }

    private ItemStack displayItem(
            Player player,
            ItemStack original,
            int rawSlot,
            DisplayContext context
    ) {
        if (!contextAllowsWorth(
                context,
                rawSlot
        )) {
            return original;
        }

        ItemStack clean = original;
        boolean stripped = false;

        if (sellService.shouldStripWorthLore(
                original
        )) {
            clean =
                    sellService.stripWorthLore(
                            original
                    );
            stripped = clean != original;
        }

        if (clean == null
                || clean.getType().isAir()
                || clean.getType()
                == Material.BLACK_STAINED_GLASS_PANE) {
            return clean == null
                    ? original
                    : clean;
        }

        ItemValuation valuation =
                sellService.appraise(
                        player,
                        clean
                );

        if (!valuation.priced()) {
            return stripped
                    ? clean
                    : original;
        }

        ItemStack item =
                stripped
                        ? clean
                        : original.clone();
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return stripped
                    ? clean
                    : original;
        }

        List<Component> existingLore =
                meta.lore();
        List<Component> lore =
                existingLore == null
                        ? new ArrayList<>()
                        : new ArrayList<>(existingLore);

        lore.addFirst(
                component(
                        valuation.sellable()
                                ? "&#bbbbbbWorth: "
                                + "&#11fc7b"
                                + sellService.format(
                                        valuation.serverSellCents()
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

    private DisplayContext displayContext(
            Player player
    ) {
        InventoryView view =
                player.getOpenInventory();
        Inventory top =
                view.getTopInventory();

        /*
         * /worth authors its own catalog content. Do not clone or rebuild any
         * packet items while that menu is open.
         */
        if (WorthGui.isInventory(top)) {
            return DisplayContext.WORTH;
        }

        if (SellGui.isInventory(top)) {
            return DisplayContext.DENIED;
        }

        if (top.getType()
                == InventoryType.CRAFTING) {
            return DisplayContext.PLAYER_INVENTORY;
        }

        return isRealStorageTop(top)
                ? DisplayContext.REAL_STORAGE
                : DisplayContext.DENIED;
    }

    private boolean contextAllowsWorth(
            DisplayContext context,
            int rawSlot
    ) {
        if (rawSlot < 0) {
            return false;
        }

        return context == DisplayContext.PLAYER_INVENTORY
                || context == DisplayContext.REAL_STORAGE;
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
        return GuiText.component(text);
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

    private enum DisplayContext {
        WORTH,
        PLAYER_INVENTORY,
        REAL_STORAGE,
        DENIED
    }
}
