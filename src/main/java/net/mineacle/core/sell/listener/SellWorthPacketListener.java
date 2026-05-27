package net.mineacle.core.sell.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.guide.gui.GuideMenuHolder;
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

            if (shouldStripForRawSlot(player, rawSlot)) {
                modifier.writeSafely(index, stripWorthLore(item));
                continue;
            }

            modifier.writeSafely(index, withWorthLore(player, item));
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

                if (shouldStripForRawSlot(player, rawSlot)) {
                    updated.add(stripWorthLore(item));
                    continue;
                }

                updated.add(withWorthLore(player, item));
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

                if (shouldStripForRawSlot(player, rawSlot)) {
                    updated[rawSlot] = stripWorthLore(item);
                    continue;
                }

                updated[rawSlot] = withWorthLore(player, item);
            }

            arrayModifier.writeSafely(index, updated);
        }
    }

    private boolean shouldStripForRawSlot(Player player, int rawSlot) {
        if (rawSlot < 0) {
            return false;
        }

        InventoryView view = player.getOpenInventory();

        if (view == null || view.getTopInventory() == null) {
            return false;
        }

        Inventory top = view.getTopInventory();
        int topSize = top.getSize();

        if (rawSlot >= topSize) {
            return false;
        }

        /*
         * Hard rule:
         * Worth lore is never shown on the top inventory of Core/custom menus.
         * The only intentional exception is the dedicated Item Prices/Worth GUI,
         * where WorthGui creates its own price encyclopedia lore.
         */
        return isCoreOrCustomTopInventory(view, top);
    }

    private boolean isCoreOrCustomTopInventory(InventoryView view, Inventory top) {
        if (top.getHolder(false) instanceof GuideMenuHolder) {
            return true;
        }

        String title = ChatColor.stripColor(view.getTitle());

        if (title == null) {
            title = "";
        }

        String lowerTitle = title.toLowerCase(Locale.ROOT);

        if (isKnownMineacleTitle(lowerTitle)) {
            return true;
        }

        /*
         * Bukkit.createInventory(null, size, title) menus have no holder.
         * Real containers like chests, shulkers, furnaces, crafting tables,
         * and ender chests usually have a real holder/type and are allowed
         * to show Worth lore.
         */
        boolean noHolder = top.getHolder(false) == null;
        InventoryType type = top.getType();

        if (!noHolder) {
            return false;
        }

        return type == InventoryType.CHEST
                || type == InventoryType.HOPPER
                || type == InventoryType.DROPPER
                || type == InventoryType.DISPENSER;
    }

    private boolean isKnownMineacleTitle(String lowerTitle) {
        if (lowerTitle.isBlank()) {
            return false;
        }

        return lowerTitle.equals("homes")
                || lowerTitle.equals("spawn")
                || lowerTitle.equals("sell")
                || lowerTitle.equals("sell multipliers")
                || lowerTitle.equals("my orders")
                || lowerTitle.equals("create order")
                || lowerTitle.equals("confirm delivery")
                || lowerTitle.equals("confirm cancel")
                || lowerTitle.equals("team menu")
                || lowerTitle.equals("team invites")
                || lowerTitle.equals("team bans")
                || lowerTitle.equals("team manage")
                || lowerTitle.equals("member manager")
                || lowerTitle.equals("banner color")
                || lowerTitle.equals("name color")
                || lowerTitle.equals("teleport request")
                || lowerTitle.equals("confirm request")
                || lowerTitle.equals("confirm action")
                || lowerTitle.equals("mineacle guide")
                || lowerTitle.equals("server rules")
                || lowerTitle.equals("rules")
                || lowerTitle.startsWith("homes ")
                || lowerTitle.startsWith("balance top")
                || lowerTitle.startsWith("orders")
                || lowerTitle.startsWith("bounties")
                || lowerTitle.startsWith("sell history")
                || lowerTitle.startsWith("item prices")
                || lowerTitle.startsWith("delete ")
                || lowerTitle.startsWith("confirm ")
                || lowerTitle.contains(" statistics")
                || lowerTitle.endsWith(" stats")
                || lowerTitle.contains(" member")
                || lowerTitle.contains("page ");
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

        List<String> lore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore())
                : new ArrayList<>();

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

        /*
         * PacketPlayOutSetSlot normally has:
         * containerId, stateId, slot
         * ProtocolLib exposes these as integers, so the last int is the raw slot.
         */
        for (int index = integers.size() - 1; index >= 0; index--) {
            Integer value = integers.readSafely(index);

            if (value != null) {
                return value;
            }
        }

        return -1;
    }

    private boolean isUnsafeInventoryMode(Player player) {
        return player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR;
    }
}
