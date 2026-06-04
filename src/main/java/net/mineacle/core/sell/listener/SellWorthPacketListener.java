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
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@SuppressWarnings({"rawtypes", "unchecked"})
public final class SellWorthPacketListener extends PacketAdapter {

    private static final int MAX_CONTAINER_DEPTH = 3;

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

        if (isWorthMenu(player)) {
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

            if (item == null || item.getType().isAir()) {
                continue;
            }

            modifier.writeSafely(index, displayItem(player, item, rawSlot));
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

                if (item == null || item.getType().isAir()) {
                    updated.add(item);
                    continue;
                }

                updated.add(displayItem(player, item, rawSlot));
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

                if (item == null || item.getType().isAir()) {
                    updated[rawSlot] = item;
                    continue;
                }

                updated[rawSlot] = displayItem(player, item, rawSlot);
            }

            arrayModifier.writeSafely(index, updated);
        }
    }

    private ItemStack displayItem(Player player, ItemStack original, int rawSlot) {
        ItemStack clean = stripWorthLore(original);

        if (!shouldShowWorth(player, rawSlot)) {
            return clean;
        }

        return withWorthLore(player, clean);
    }

    private boolean shouldShowWorth(Player player, int rawSlot) {
        InventoryView view = player.getOpenInventory();

        if (view == null) {
            return true;
        }

        Inventory top = view.getTopInventory();

        if (top == null) {
            return true;
        }

        if (rawSlot >= 0 && rawSlot < top.getSize()) {
            return isRealStorageTop(top);
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

    private boolean isRealStorageTop(Inventory inventory) {
        InventoryType type = inventory.getType();

        if (type == InventoryType.ENDER_CHEST) {
            return true;
        }

        InventoryHolder holder = inventory.getHolder(false);

        if (holder instanceof BlockInventoryHolder || holder instanceof DoubleChest || holder instanceof StorageMinecart) {
            return isStorageType(type);
        }

        return false;
    }

    private boolean isStorageType(InventoryType type) {
        return type == InventoryType.CHEST
                || type == InventoryType.BARREL
                || type == InventoryType.SHULKER_BOX
                || type == InventoryType.HOPPER
                || type == InventoryType.DROPPER
                || type == InventoryType.DISPENSER
                || type == InventoryType.ENDER_CHEST;
    }

    private ItemStack withWorthLore(Player player, ItemStack original) {
        if (original == null || original.getType().isAir()) {
            return original;
        }

        long totalWorth = visualWorthCents(player, original, 0);

        if (totalWorth <= 0L) {
            return original;
        }

        ItemStack item = original.clone();
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

    private long visualWorthCents(Player player, ItemStack item, int depth) {
        if (item == null || item.getType().isAir() || depth > MAX_CONTAINER_DEPTH) {
            return 0L;
        }

        ItemStack clean = stripWorthLore(item);
        long total = sellService.stackWorthCents(player, clean);
        ItemMeta meta = clean.getItemMeta();

        if (meta instanceof BundleMeta bundleMeta) {
            for (ItemStack content : bundleMeta.getItems()) {
                total += visualWorthCents(player, content, depth + 1);
            }
        }

        if (meta instanceof BlockStateMeta blockStateMeta && blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox) {
            for (ItemStack content : shulkerBox.getInventory().getContents()) {
                total += visualWorthCents(player, content, depth + 1);
            }
        }

        return Math.max(0L, total);
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

        String lower = stripped.toLowerCase(Locale.ROOT);

        return lower.startsWith("worth:")
                || lower.startsWith("stack worth:")
                || lower.startsWith("enchant value:")
                || lower.startsWith("demand:");
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
