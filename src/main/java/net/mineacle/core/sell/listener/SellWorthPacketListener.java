package net.mineacle.core.sell.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.gui.WorthGui;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SellWorthPacketListener extends PacketAdapter {

    private final SellService sellService;

    public SellWorthPacketListener(Core core, SellService sellService) {
        super(core, ListenerPriority.NORMAL, PacketType.Play.Server.SET_SLOT, PacketType.Play.Server.WINDOW_ITEMS);
        this.sellService = sellService;
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        Player player = event.getPlayer();

        if (player == null || isUnsafeInventoryMode(player)) {
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
            handleSetSlot(event, player);
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
            handleWindowItems(event, player);
        }
    }

    private void handleSetSlot(PacketEvent event, Player player) {
        StructureModifier<ItemStack> modifier = event.getPacket().getItemModifier();
        int rawSlot = setSlotRawSlot(event);

        for (int index = 0; index < modifier.size(); index++) {
            ItemStack item = modifier.readSafely(index);

            if (item == null || item.getType() == Material.AIR) {
                continue;
            }

            if (isWorthMenu(player)) {
                modifier.writeSafely(index, item);
                continue;
            }

            if (shouldStripForRawSlot(player, rawSlot)) {
                modifier.writeSafely(index, stripWorthLore(item));
                continue;
            }

            if (shouldAddWorthForRawSlot(player, rawSlot)) {
                modifier.writeSafely(index, withWorthLore(player, item));
            } else {
                modifier.writeSafely(index, stripWorthLore(item));
            }
        }
    }

    private void handleWindowItems(PacketEvent event, Player player) {
        StructureModifier<List<ItemStack>> listModifier = event.getPacket().getItemListModifier();

        for (int index = 0; index < listModifier.size(); index++) {
            List<ItemStack> original = listModifier.readSafely(index);

            if (original == null || original.isEmpty()) {
                continue;
            }

            List<ItemStack> updated = new ArrayList<>(original.size());

            for (int rawSlot = 0; rawSlot < original.size(); rawSlot++) {
                ItemStack item = original.get(rawSlot);

                if (item == null || item.getType() == Material.AIR) {
                    updated.add(item);
                    continue;
                }

                if (isWorthMenu(player)) {
                    updated.add(item);
                    continue;
                }

                if (shouldStripForRawSlot(player, rawSlot)) {
                    updated.add(stripWorthLore(item));
                    continue;
                }

                if (shouldAddWorthForRawSlot(player, rawSlot)) {
                    updated.add(withWorthLore(player, item));
                } else {
                    updated.add(stripWorthLore(item));
                }
            }

            listModifier.writeSafely(index, updated);
        }

        StructureModifier<ItemStack[]> arrayModifier = event.getPacket().getItemArrayModifier();

        for (int index = 0; index < arrayModifier.size(); index++) {
            ItemStack[] original = arrayModifier.readSafely(index);

            if (original == null || original.length == 0) {
                continue;
            }

            ItemStack[] updated = new ItemStack[original.length];

            for (int rawSlot = 0; rawSlot < original.length; rawSlot++) {
                ItemStack item = original[rawSlot];

                if (item == null || item.getType() == Material.AIR) {
                    updated[rawSlot] = item;
                    continue;
                }

                if (isWorthMenu(player)) {
                    updated[rawSlot] = item;
                    continue;
                }

                if (shouldStripForRawSlot(player, rawSlot)) {
                    updated[rawSlot] = stripWorthLore(item);
                    continue;
                }

                if (shouldAddWorthForRawSlot(player, rawSlot)) {
                    updated[rawSlot] = withWorthLore(player, item);
                } else {
                    updated[rawSlot] = stripWorthLore(item);
                }
            }

            arrayModifier.writeSafely(index, updated);
        }
    }

    private boolean shouldStripForRawSlot(Player player, int rawSlot) {
        if (rawSlot < 0) {
            return false;
        }

        InventoryView view = player.getOpenInventory();

        if (view == null) {
            return false;
        }

        Inventory top = view.getTopInventory();

        if (top == null) {
            return false;
        }

        /*
         * Long-term safe rule:
         * Never show Mineacle worth lore inside top inventories from menus/crates/previews.
         * This protects PhoenixCrates and any future crate/shop/menu plugin without needing
         * every plugin title hardcoded.
         */
        return rawSlot < top.getSize() && isProtectedTopInventory(view, top);
    }

    private boolean shouldAddWorthForRawSlot(Player player, int rawSlot) {
        InventoryView view = player.getOpenInventory();

        if (view == null) {
            return true;
        }

        Inventory top = view.getTopInventory();

        if (top == null) {
            return true;
        }

        if (rawSlot >= 0 && rawSlot < top.getSize()) {
            return false;
        }

        return true;
    }

    private boolean isWorthMenu(Player player) {
        InventoryView view = player.getOpenInventory();

        if (view == null) {
            return false;
        }

        String title = ChatColor.stripColor(view.getTitle());
        return WorthGui.isTitle(title);
    }

    private boolean isProtectedTopInventory(InventoryView view, Inventory top) {
        String title = ChatColor.stripColor(view.getTitle());

        if (title == null) {
            title = "";
        }

        String lowerTitle = title.toLowerCase(Locale.ROOT);
        String holderName = top.getHolder(false) == null ? "" : top.getHolder(false).getClass().getName().toLowerCase(Locale.ROOT);

        if (lowerTitle.contains("crate")
                || lowerTitle.contains("key")
                || lowerTitle.contains("reward")
                || holderName.contains("phoenix")
                || holderName.contains("crate")
                || holderName.contains("reward")) {
            return true;
        }

        InventoryType type = top.getType();

        return type == InventoryType.CHEST
                || type == InventoryType.HOPPER
                || type == InventoryType.DROPPER
                || type == InventoryType.DISPENSER
                || type == InventoryType.BARREL
                || type == InventoryType.SHULKER_BOX
                || type == InventoryType.ENDER_CHEST;
    }

    private ItemStack withWorthLore(Player player, ItemStack original) {
        ItemStack item = stripWorthLore(original);

        if (item.getType() == Material.AIR) {
            return item;
        }

        long totalWorth = sellService.stackWorthCents(player, item);

        if (totalWorth <= 0L) {
            return item;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(0, TextColor.color("&#bbbbbbWorth: &a" + sellService.format(totalWorth)));
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack stripWorthLore(ItemStack original) {
        ItemStack item = original.clone();
        ItemMeta meta = item.getItemMeta();

        if (meta == null || !meta.hasLore() || meta.getLore() == null) {
            return item;
        }

        List<String> lore = new ArrayList<>(meta.getLore());
        lore.removeIf(this::isWorthLine);

        if (lore.isEmpty()) {
            meta.setLore(null);
        } else {
            meta.setLore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    private boolean isWorthLine(String line) {
        if (line == null) {
            return false;
        }

        String stripped = ChatColor.stripColor(line);

        if (stripped == null) {
            return false;
        }

        return stripped.startsWith("Worth:")
                || stripped.startsWith("Price:")
                || stripped.startsWith("Stack:")
                || stripped.startsWith("Stack Worth:")
                || stripped.startsWith("Enchant Value:")
                || stripped.startsWith("Demand:")
                || stripped.startsWith("Category:");
    }

    private int setSlotRawSlot(PacketEvent event) {
        StructureModifier<Integer> integers = event.getPacket().getIntegers();

        for (int index = integers.size() - 1; index >= 0; index--) {
            Integer value = integers.readSafely(index);

            if (value != null) {
                return value;
            }
        }

        return -1;
    }

    private boolean isUnsafeInventoryMode(Player player) {
        return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
    }
}
