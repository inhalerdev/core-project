package net.mineacle.core.rtp.gui;

import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.rtp.service.RtpMenuItem;
import net.mineacle.core.rtp.service.RtpMenuService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

public final class RtpMenuGui {

    public static final String MAIN =
            RtpMenuService.MAIN_MENU;

    private RtpMenuGui() {
    }

    public static void open(
            Player player,
            RtpMenuService service
    ) {
        open(player, service, MAIN);
    }

    public static void open(
            Player player,
            RtpMenuService service,
            String menuKey
    ) {
        RtpMenuService.MenuDefinition definition =
                service.menu(menuKey);
        Holder holder = new Holder(definition);
        Inventory inventory = Bukkit.createInventory(
                holder,
                definition.size(),
                GuiText.title(definition.title())
        );
        holder.inventory = inventory;

        for (RtpMenuItem item
                : definition.items().values()) {
            inventory.setItem(
                    item.slot(),
                    item(
                            player,
                            service,
                            item
                    )
            );
        }

        player.openInventory(inventory);
    }

    public static Holder holder(Inventory inventory) {
        if (inventory == null
                || !(inventory.getHolder(false)
                instanceof Holder holder)) {
            return null;
        }

        return holder;
    }

    private static ItemStack item(
            Player player,
            RtpMenuService service,
            RtpMenuItem definition
    ) {
        ItemStack item =
                new ItemStack(definition.material());
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.displayName(
                GuiText.component(
                        service.parse(
                                player,
                                definition.name()
                        )
                )
        );
        meta.lore(
                service.parseLore(
                        player,
                        definition
                ).stream()
                        .map(GuiText::component)
                        .toList()
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_UNBREAKABLE,
                ItemFlag.HIDE_DYE
        );
        item.setItemMeta(meta);
        return item;
    }

    public static final class Holder
            implements InventoryHolder {

        private final RtpMenuService.MenuDefinition definition;
        private Inventory inventory;

        private Holder(
                RtpMenuService.MenuDefinition definition
        ) {
            this.definition = definition;
        }

        public RtpMenuItem itemAt(int slot) {
            return definition.itemAt(slot);
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
