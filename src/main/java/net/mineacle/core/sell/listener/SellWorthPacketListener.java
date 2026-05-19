package net.mineacle.core.sell.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.reflect.StructureModifier;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.gui.SellGui;
import net.mineacle.core.sell.gui.SellHistoryGui;
import net.mineacle.core.sell.gui.SellMultiGui;
import net.mineacle.core.sell.gui.WorthGui;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class SellWorthPacketListener extends PacketAdapter {

    private final Core core;
    private final SellService sellService;

    public SellWorthPacketListener(Core core, SellService sellService) {
        super(
                core,
                ListenerPriority.NORMAL,
                PacketType.Play.Server.SET_SLOT,
                PacketType.Play.Server.WINDOW_ITEMS
        );

        this.core = core;
        this.sellService = sellService;
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        Player player = event.getPlayer();

        if (player == null || shouldSkip(player)) {
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

        for (int index = 0; index < modifier.size(); index++) {
            ItemStack item = modifier.readSafely(index);

            if (item == null || item.getType() == Material.AIR) {
                continue;
            }

            modifier.writeSafely(index, withWorthLore(player, item));
        }
    }

    private void handleWindowItems(PacketEvent event, Player player) {
        StructureModifier<List<ItemStack>> modifier = event.getPacket().getItemListModifier();

        for (int index = 0; index < modifier.size(); index++) {
            List<ItemStack> original = modifier.readSafely(index);

            if (original == null || original.isEmpty()) {
                continue;
            }

            List<ItemStack> updated = new ArrayList<>(original.size());

            for (ItemStack item : original) {
                if (item == null || item.getType() == Material.AIR) {
                    updated.add(item);
                    continue;
                }

                updated.add(withWorthLore(player, item));
            }

            modifier.writeSafely(index, updated);
        }
    }

    private ItemStack withWorthLore(Player player, ItemStack original) {
        ItemStack item = original.clone();
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        List<String> lore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore())
                : new ArrayList<>();

        lore.removeIf(this::isWorthLine);

        long totalWorth = sellService.unitWorthCents(player, item.getType()) * Math.max(1, item.getAmount());
        lore.add(0, TextColor.color("&#bbbbbbWorth: &a" + sellService.format(totalWorth)));

        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
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
                || stripped.startsWith("Stack Worth:")
                || stripped.startsWith("Demand:");
    }

    private boolean shouldSkip(Player player) {
        InventoryView view = player.getOpenInventory();

        if (view == null) {
            return false;
        }

        String title = ChatColor.stripColor(view.getTitle());

        if (title == null) {
            return false;
        }

        String sellTitle = ChatColor.stripColor(SellGui.title(core));

        if (sellTitle != null && title.equals(sellTitle)) {
            return false;
        }

        return title.startsWith(WorthGui.TITLE_PREFIX)
                || title.startsWith(SellHistoryGui.TITLE_PREFIX)
                || title.equalsIgnoreCase(SellMultiGui.TITLE)
                || title.equalsIgnoreCase("Teleport Request")
                || title.equalsIgnoreCase("Confirm Request")
                || title.equalsIgnoreCase("Confirm Action")
                || title.equalsIgnoreCase("Team Invites")
                || title.equalsIgnoreCase("Homes")
                || title.startsWith("Balance Top")
                || title.startsWith("Orders")
                || title.startsWith("Item Prices")
                || title.startsWith("Sell History")
                || title.contains("Statistics")
                || title.endsWith("Stats")
                || title.contains("(") && title.endsWith(")");
    }
}
