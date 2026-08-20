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
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SellWorthPacketListener
        extends PacketAdapter {

    private static final int MAX_CONTAINER_DEPTH = 3;

    private static final String PHOENIX_CRATES =
            "phoenixcrates";
    private static final String PHOENIX_CRATES_LITE =
            "phoenixcrateslite";

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
         * Default-deny before touching packet items. Mineacle workflow menus
         * and unrelated external/plugin GUIs should cost essentially nothing
         * here. PhoenixCrates reward displays are the deliberate third-party
         * exception and remain packet-only.
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
        long displayedWorth =
                valuation.sellable()
                        ? valuation.serverSellCents()
                        : containerLiquidationReference(
                        player,
                        clean,
                        0
                );

        if (!valuation.priced()
                && displayedWorth <= 0L) {
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
                        displayedWorth > 0L
                                ? "&#bbbbbbWorth: "
                                + "&#11fc7b"
                                + sellService.format(
                                        displayedWorth
                                )
                                : "&cServer sell unavailable"
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

    private long containerLiquidationReference(
            Player player,
            ItemStack item,
            int depth
    ) {
        if (item == null
                || item.getType().isAir()
                || depth > MAX_CONTAINER_DEPTH) {
            return 0L;
        }

        ItemMeta rawMeta = item.getItemMeta();
        List<ItemStack> contents = new ArrayList<>();
        ItemStack shell = item.clone();
        boolean container = false;

        if (rawMeta instanceof BundleMeta bundleMeta
                && bundleMeta.hasItems()) {
            contents.addAll(bundleMeta.getItems());

            ItemMeta shellRaw = shell.getItemMeta();
            if (shellRaw instanceof BundleMeta shellMeta) {
                shellMeta.setItems(null);
                shell.setItemMeta(shellMeta);
                container = true;
            }
        } else if (rawMeta instanceof BlockStateMeta stateMeta
                && stateMeta.getBlockState()
                instanceof ShulkerBox shulker) {
            boolean hasContents = false;

            for (ItemStack content
                    : shulker.getSnapshotInventory()
                    .getContents()) {
                if (content == null
                        || content.getType().isAir()) {
                    continue;
                }

                hasContents = true;
                contents.add(content.clone());
            }

            if (hasContents) {
                ItemMeta shellRaw = shell.getItemMeta();

                if (shellRaw instanceof BlockStateMeta shellState
                        && shellState.getBlockState()
                        instanceof ShulkerBox emptyShulker) {
                    emptyShulker.getSnapshotInventory().clear();
                    shellState.setBlockState(emptyShulker);
                    shell.setItemMeta(shellState);
                    container = true;
                }
            }
        }

        if (!container) {
            return 0L;
        }

        long total = 0L;
        ItemValuation shellValuation =
                sellService.appraise(
                        player,
                        shell
                );

        if (shellValuation.sellable()) {
            total = safeAdd(
                    total,
                    shellValuation.serverSellCents()
            );
        }

        for (ItemStack content : contents) {
            ItemStack cleanContent =
                    sellService.stripWorthLore(
                            content
                    );
            ItemValuation contentValuation =
                    sellService.appraise(
                            player,
                            cleanContent
                    );

            long value = contentValuation.sellable()
                    ? contentValuation.serverSellCents()
                    : containerLiquidationReference(
                    player,
                    cleanContent,
                    depth + 1
            );

            total = safeAdd(total, value);
        }

        return total;
    }

    private long safeAdd(
            long first,
            long second
    ) {
        try {
            return Math.addExact(
                    Math.max(0L, first),
                    Math.max(0L, second)
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
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

        if (isRealStorageTop(top)) {
            return DisplayContext.REAL_STORAGE;
        }

        /*
         * PhoenixCrates preview/reward menus may use fully customized titles,
         * so title matching is intentionally not used. Resolve the JavaPlugin
         * that provided the inventory holder class instead. This keeps all
         * unrelated third-party GUIs default-denied.
         */
        if (isPhoenixCratesTop(top)) {
            return DisplayContext.PHOENIX_CRATES;
        }

        return DisplayContext.DENIED;
    }

    private boolean contextAllowsWorth(
            DisplayContext context,
            int rawSlot
    ) {
        if (rawSlot < 0) {
            return false;
        }

        return context == DisplayContext.PLAYER_INVENTORY
                || context == DisplayContext.REAL_STORAGE
                || context == DisplayContext.PHOENIX_CRATES;
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

    private boolean isPhoenixCratesTop(
            Inventory inventory
    ) {
        if (inventory == null) {
            return false;
        }

        InventoryHolder holder =
                inventory.getHolder(false);

        if (holder == null) {
            return false;
        }

        Class<?> holderClass =
                holder.getClass();

        try {
            Plugin provider =
                    JavaPlugin.getProvidingPlugin(
                            holderClass
                    );
            String pluginName =
                    provider.getName()
                            .toLowerCase(
                                    Locale.ROOT
                            );

            if (pluginName.equals(
                    PHOENIX_CRATES
            )
                    || pluginName.equals(
                    PHOENIX_CRATES_LITE
            )) {
                return true;
            }
        } catch (IllegalArgumentException ignored) {
            /*
             * Some menu frameworks can expose an InventoryHolder class that
             * JavaPlugin cannot resolve directly. Fall through to the narrow
             * class-name check below rather than widening external GUI access.
             */
        }

        String className =
                holderClass.getName()
                        .toLowerCase(
                                Locale.ROOT
                        );

        return className.contains(
                "phoenix"
        )
                && className.contains(
                "crate"
        );
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
        PHOENIX_CRATES,
        DENIED
    }
}
