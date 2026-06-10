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

    private final Core core;
    private final SellService sellService;

    public SellWorthPacketListener(Core core, SellService sellService) {
        super(core, ListenerPriority.NORMAL, PacketType.Play.Server.SET_SLOT, PacketType.Play.Server.WINDOW_ITEMS);
        this.core = core;
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

        if (!shouldShowWorth(player, clean, rawSlot)) {
            return clean;
        }

        return withWorthLore(player, clean, shouldShowStackPrice(player, rawSlot));
    }

    private boolean shouldShowStackPrice(Player player, int rawSlot) {
        InventoryView view = player.getOpenInventory();

        if (view == null) {
            return true;
        }

        Inventory top = view.getTopInventory();

        if (top == null) {
            return true;
        }

        if (rawSlot >= 0 && rawSlot < top.getSize() && isPhoenixCrateRewardsMenu(view)) {
            return false;
        }

        return true;
    }

    private boolean shouldShowWorth(Player player, ItemStack item, int rawSlot) {
        if (item == null || item.getType().isAir() || item.getType() == Material.BLACK_STAINED_GLASS_PANE) {
            return false;
        }

        if (isWorthMenu(player) || isSellMenu(player)) {
            return sellService.canSell(item);
        }

        InventoryView view = player.getOpenInventory();

        if (view == null) {
            return sellService.canSell(item);
        }

        Inventory top = view.getTopInventory();

        if (top == null) {
            return sellService.canSell(item);
        }

        if (rawSlot >= 0 && rawSlot < top.getSize()) {
            if (isPhoenixCrateRewardsMenu(view)) {
                return sellService.canSell(item);
            }

            return isRealStorageTop(top) && sellService.canSell(item);
        }

        return sellService.canSell(item);
    }

    private boolean isWorthMenu(Player player) {
        InventoryView view = player.getOpenInventory();

        if (view == null) {
            return false;
        }

        String title = ChatColor.stripColor(view.getTitle());
        return WorthGui.isTitle(title);
    }

    private boolean isSellMenu(Player player) {
        InventoryView view = player.getOpenInventory();

        if (view == null) {
            return false;
        }

        String title = ChatColor.stripColor(view.getTitle());
        String sellTitle = ChatColor.stripColor(SellGui.title(core));

        return title != null && sellTitle != null && title.equals(sellTitle);
    }

    private boolean isPhoenixCrateRewardsMenu(InventoryView view) {
        Inventory top = view.getTopInventory();

        if (top == null || isRealStorageTop(top)) {
            return false;
        }

        String title = ChatColor.stripColor(view.getTitle());

        if (title == null) {
            title = "";
        }

        String lowerTitle = title.toLowerCase(Locale.ROOT);
        String holderName = top.getHolder(false) == null ? "" : top.getHolder(false).getClass().getName().toLowerCase(Locale.ROOT);

        return holderName.contains("phoenix")
                || holderName.contains("pcrates")
                || holderName.contains("crate")
                || lowerTitle.contains("crate reward")
                || lowerTitle.contains("crate preview")
                || lowerTitle.contains("rewards")
                || lowerTitle.contains("preview");
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

    private ItemStack withWorthLore(Player player, ItemStack original, boolean showStackPrice) {
        if (original == null || original.getType().isAir() || original.getType() == Material.BLACK_STAINED_GLASS_PANE) {
            return original;
        }

        long unitWorth = sellService.unitWorthCents(player, original.getType());
        long stackWorth = visualWorthCents(player, original, 0);
        long enchantWorth = sellService.enchantWorthCents(original);
        long displayedWorth = original.getAmount() <= 1 ? stackWorth : unitWorth;

        if (displayedWorth <= 0L || stackWorth <= 0L) {
            return original;
        }

        ItemStack item = original.clone();
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();

        if (showStackPrice) {
            lore.add(0, TextColor.color("&#bbbbbbStack Price: &a" + sellService.format(stackWorth)));
        }

        if (enchantWorth > 0L) {
            lore.add(0, TextColor.color("&#bbbbbbEnchant Value: &a+" + sellService.format(enchantWorth)));
        }

        lore.add(0, TextColor.color("&#bbbbbbWorth: &a" + sellService.format(displayedWorth)));

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
                || lower.startsWith("price:")
                || lower.startsWith("base:")
                || lower.startsWith("stack:")
                || lower.startsWith("stack price:")
                || lower.startsWith("stack worth:")
                || lower.startsWith("enchant value:")
                || lower.startsWith("demand:")
                || lower.startsWith("category:")
                || lower.startsWith("sold this cycle:");
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
