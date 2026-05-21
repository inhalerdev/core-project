package net.mineacle.core.sell.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
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

        int packetSlot = packetSlot(event);

        for (int index = 0; index < modifier.size(); index++) {
            ItemStack item = modifier.readSafely(index);

            if (item == null || item.getType() == Material.AIR) {
                continue;
            }

            if (shouldSkipWorthForSlot(player, packetSlot)) {
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

            for (int slot = 0; slot < original.size(); slot++) {
                ItemStack item = original.get(slot);

                if (item == null || item.getType() == Material.AIR) {
                    updated.add(item);
                    continue;
                }

                if (shouldSkipWorthForSlot(player, slot)) {
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

            for (int slot = 0; slot < original.length; slot++) {
                ItemStack item = original[slot];

                if (item == null || item.getType() == Material.AIR) {
                    updated[slot] = item;
                    continue;
                }

                if (shouldSkipWorthForSlot(player, slot)) {
                    updated[slot] = stripWorthLore(item);
                    continue;
                }

                updated[slot] = withWorthLore(player, item);
            }

            arrayModifier.writeSafely(index, updated);
        }
    }

    private boolean shouldSkipWorthForSlot(Player player, int rawSlot) {
        if (!isMineacleCoreGui(player)) {
            return false;
        }

        if (rawSlot < 0) {
            return true;
        }

        InventoryView view = player.getOpenInventory();

        if (view == null || view.getTopInventory() == null) {
            return true;
        }

        /*
         * Only block Worth lore on the custom Core GUI area.
         * The player's own bottom inventory still gets Worth lore.
         */
        return rawSlot < view.getTopInventory().getSize();
    }

    private boolean isMineacleCoreGui(Player player) {
        InventoryView view = player.getOpenInventory();

        if (view == null) {
            return false;
        }

        String title = ChatColor.stripColor(view.getTitle());

        if (title == null || title.isBlank()) {
            return false;
        }

        String lower = title.toLowerCase(Locale.ROOT);

        return lower.equals("homes")
                || lower.equals("team menu")
                || lower.equals("team invites")
                || lower.equals("team bans")
                || lower.equals("team manage")
                || lower.equals("member manager")
                || lower.equals("banner color")
                || lower.equals("name color")
                || lower.equals("spawn")
                || lower.equals("teleport request")
                || lower.equals("confirm request")
                || lower.equals("confirm action")
                || lower.equals("sell")
                || lower.equals("sell multipliers")
                || lower.equals("my orders")
                || lower.equals("create order")
                || lower.equals("confirm delivery")
                || lower.equals("confirm cancel")
                || lower.startsWith("balance top")
                || lower.startsWith("orders")
                || lower.startsWith("bounties")
                || lower.startsWith("sell history")
                || lower.startsWith("item prices")
                || lower.contains(" statistics")
                || lower.endsWith(" stats")
                || lower.contains(" statistics")
                || lower.contains(" member")
                || lower.contains(" (page ");
    }

    private ItemStack withWorthLore(Player player, ItemStack original) {
        ItemStack item = stripWorthLore(original);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        List<String> lore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore())
                : new ArrayList<>();

        long totalWorth = sellService.stackWorthCents(player, item);

        if (totalWorth <= 0L) {
            return item;
        }

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
        meta.setLore(lore);
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
                || stripped.startsWith("Enchant Value:")
                || stripped.startsWith("Stack Worth:")
                || stripped.startsWith("Demand:");
    }

    private int packetSlot(PacketEvent event) {
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
        return player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR;
    }
}
