package net.mineacle.core.teams.gui;

import net.mineacle.core.common.gui.GuiText;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public final class TeamConfirmGui {

    public static final String TITLE = "Confirm Action";

    public static final int CANCEL_SLOT = 11;
    public static final int ACTION_SLOT = 13;
    public static final int CONFIRM_SLOT = 15;

    private static final String BODY =
            "&#bbbbbb";

    private TeamConfirmGui() {
    }

    public static void open(
            Player player,
            String action,
            UUID targetId,
            String actionName
    ) {
        ConfirmHolder holder =
                new ConfirmHolder(
                        action,
                        targetId
                );
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        27,
                        GuiText.title(TITLE)
                );
        holder.inventory = inventory;

        inventory.setItem(
                CANCEL_SLOT,
                item(
                        Material
                                .RED_STAINED_GLASS_PANE,
                        "&cCancel",
                        List.of(
                                BODY
                                        + "Return without changes"
                        )
                )
        );
        inventory.setItem(
                ACTION_SLOT,
                item(
                        Material.RED_DYE,
                        "&c" + actionName,
                        List.of(
                                BODY
                                        + "Review this action"
                        )
                )
        );
        inventory.setItem(
                CONFIRM_SLOT,
                confirmItem(false)
        );

        player.openInventory(
                inventory
        );
    }

    public static void showUnarmed(
            Inventory inventory
    ) {
        if (inventory == null
                || !(inventory.getHolder(false)
                instanceof ConfirmHolder)) {
            return;
        }

        inventory.setItem(
                CONFIRM_SLOT,
                confirmItem(false)
        );
    }

    public static void showArmed(
            Inventory inventory
    ) {
        if (inventory == null
                || !(inventory.getHolder(false)
                instanceof ConfirmHolder)) {
            return;
        }

        inventory.setItem(
                CONFIRM_SLOT,
                confirmItem(true)
        );
    }

    private static ItemStack confirmItem(
            boolean armed
    ) {
        return item(
                Material
                        .LIME_STAINED_GLASS_PANE,
                armed
                        ? "&aConfirm Again"
                        : "&aConfirm",
                List.of(
                        armed
                                ? BODY
                                + "Click again to continue"
                                : BODY
                                + "Click to ready this action"
                )
        );
    }

    private static ItemStack item(
            Material material,
            String name,
            List<String> lore
    ) {
        ItemStack item =
                new ItemStack(material);
        ItemMeta meta =
                item.getItemMeta();

        if (meta == null) {
            return item;
        }

        GuiText.apply(
                meta,
                name,
                lore
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES
        );
        item.setItemMeta(meta);
        return item;
    }

    public static final class ConfirmHolder
            implements InventoryHolder {

        private final String action;
        private final UUID targetId;
        private Inventory inventory;

        private ConfirmHolder(
                String action,
                UUID targetId
        ) {
            this.action = action;
            this.targetId = targetId;
        }

        public String action() {
            return action;
        }

        public UUID targetId() {
            return targetId;
        }


        public String token() {
            return action
                    + ":"
                    + (
                    targetId == null
                            ? "-"
                            : targetId
                            .toString()
            );
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
