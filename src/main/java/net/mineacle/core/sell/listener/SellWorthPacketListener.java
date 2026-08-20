package net.mineacle.core.sell.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import net.kyori.adventure.text.Component;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.orders.gui.OrdersGuiHolder;
import net.mineacle.core.sell.gui.SellGui;
import net.mineacle.core.sell.gui.WorthGui;
import net.mineacle.core.sell.model.ItemValuation;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class SellWorthPacketListener
        extends PacketAdapter {


    private static final String MINEACLE_PACKAGE_PREFIX =
            "net.mineacle.core.";
    private static final String SHULKER_PREVIEW_HOLDER =
            "net.mineacle.core.shulkerpreview.ShulkerPreviewListener$PreviewHolder";

    private final Core core;
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
        this.core = core;
        this.sellService = sellService;
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        Player player = event.getPlayer();

        if (player == null || unsafeMode(player)) {
            return;
        }

        DisplayPolicy policy = displayPolicy(player);

        if (event.getPacketType()
                == PacketType.Play.Server.SET_SLOT) {
            handleSetSlot(
                    event,
                    player,
                    policy
            );
            return;
        }

        if (event.getPacketType()
                == PacketType.Play.Server.WINDOW_ITEMS) {
            handleWindowItems(
                    event,
                    player,
                    policy
            );
        }
    }

    private void handleSetSlot(
            PacketEvent event,
            Player player,
            DisplayPolicy policy
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
                            policy
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
            DisplayPolicy policy
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
                                policy
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
                                policy
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
            DisplayPolicy policy
    ) {
        if (!policyAllowsWorth(
                policy,
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

        /*
         * Worth is transactional, not an appraisal hint. If the exact stack
         * cannot be submitted to /sell for a positive payout right now, do not
         * show Mineacle Worth at all. This keeps custom items and filled
         * containers from displaying a value the server will not actually pay.
         */
        if (!valuation.sellable()
                || valuation.serverSellCents() <= 0L) {
            return stripped
                    ? clean
                    : original;
        }

        long displayedWorth =
                valuation.serverSellCents();

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
                        "&#bbbbbbWorth: "
                                + "&#11fc7b"
                                + sellService.format(
                                        displayedWorth
                                )
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

    private DisplayPolicy displayPolicy(
            Player player
    ) {
        InventoryView view =
                player.getOpenInventory();
        Inventory top =
                view.getTopInventory();
        InventoryHolder holder =
                top.getHolder(false);
        int topSize =
                Math.max(
                        0,
                        top.getSize()
                );

        /*
         * Mineacle owns the top inventory policy only. The player inventory
         * beneath every GUI remains eligible for packet-only Worth display.
         */
        if (WorthGui.isInventory(top)) {
            return new DisplayPolicy(
                    DisplayContext.MINEACLE_WORTH,
                    topSize,
                    null
            );
        }

        if (SellGui.isInventory(top)) {
            return new DisplayPolicy(
                    DisplayContext.MINEACLE_SELL,
                    topSize,
                    null
            );
        }

        if (holder instanceof OrdersGuiHolder ordersHolder) {
            return new DisplayPolicy(
                    DisplayContext.MINEACLE_ORDERS,
                    topSize,
                    ordersHolder
            );
        }

        if (isMineacleShulkerPreview(holder)) {
            return new DisplayPolicy(
                    DisplayContext.MINEACLE_SHULKER_PREVIEW,
                    topSize,
                    null
            );
        }

        if (isMineacleHolder(holder)) {
            return new DisplayPolicy(
                    DisplayContext.MINEACLE_BLOCKED,
                    topSize,
                    null
            );
        }

        /*
         * Vanilla containers/workstations and every third-party plugin GUI are
         * allowed by default. This is deliberately future-proof: installing a
         * new external plugin does not require another Mineacle whitelist.
         */
        return new DisplayPolicy(
                DisplayContext.EXTERNAL_OR_VANILLA,
                topSize,
                null
        );
    }

    private boolean policyAllowsWorth(
            DisplayPolicy policy,
            int rawSlot
    ) {
        if (policy == null
                || rawSlot < 0) {
            return false;
        }

        /*
         * Raw slots at/after the top size are the player's own inventory.
         * Mineacle top-menu restrictions must never suppress those items.
         */
        if (rawSlot >= policy.topSize()) {
            return true;
        }

        return switch (policy.context()) {
            case EXTERNAL_OR_VANILLA,
                 MINEACLE_SHULKER_PREVIEW -> true;
            case MINEACLE_SELL ->
                    rawSlot != SellGui.SUMMARY_SLOT;
            case MINEACLE_ORDERS ->
                    ordersContentSlot(
                            policy.ordersHolder(),
                            rawSlot
                    );
            case MINEACLE_WORTH,
                 MINEACLE_BLOCKED -> false;
        };
    }

    private boolean ordersContentSlot(
            OrdersGuiHolder holder,
            int rawSlot
    ) {
        if (holder == null
                || rawSlot < 0
                || rawSlot >= 45) {
            return false;
        }

        return switch (holder.view()) {
            case MAIN, YOUR_ORDERS ->
                    holder.orderIdAt(rawSlot) != null;
            case CREATE ->
                    holder.materialAt(rawSlot) != null;
            case CONFIRM -> false;
        };
    }

    private boolean isMineacleShulkerPreview(
            InventoryHolder holder
    ) {
        return holder != null
                && holder.getClass()
                .getName()
                .equals(
                        SHULKER_PREVIEW_HOLDER
                );
    }

    private boolean isMineacleHolder(
            InventoryHolder holder
    ) {
        if (holder == null) {
            return false;
        }

        Class<?> holderClass =
                holder.getClass();
        String className =
                holderClass.getName();

        if (className.startsWith(
                MINEACLE_PACKAGE_PREFIX
        )) {
            return true;
        }

        try {
            JavaPlugin provider =
                    JavaPlugin.getProvidingPlugin(
                            holderClass
                    );
            return provider == core
                    || provider.getName()
                    .equalsIgnoreCase(
                            core.getName()
                    );
        } catch (IllegalArgumentException ignored) {
            return false;
        }
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
        EXTERNAL_OR_VANILLA,
        MINEACLE_WORTH,
        MINEACLE_SELL,
        MINEACLE_ORDERS,
        MINEACLE_SHULKER_PREVIEW,
        MINEACLE_BLOCKED
    }

    private record DisplayPolicy(
            DisplayContext context,
            int topSize,
            OrdersGuiHolder ordersHolder
    ) {
    }
}
